package map.service.user.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/** Explicitly imported only by map.export.TrainingExportApplication; no serving scan. */
@Slf4j
public class TrainingExportRunner implements ApplicationRunner, ExitCodeGenerator {
    private final TrainingExportService service;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String salt;
    private final boolean excludeTestAccounts;
    private final List<String> testEmailDomains;
    private final Path outputDir;
    @Value("${training.export.approved:false}")
    private boolean exportApproved;
    @Value("${training.export.max-bytes:67108864}")
    private long maxBytes = 67108864;
    private int exitCode;

    public TrainingExportRunner(TrainingExportService service, ObjectMapper objectMapper,
            @Value("${training.export.enabled:false}") boolean enabled,
            @Value("${training.export.user-ref-salt:}") String salt,
            @Value("${training.export.exclude-test-accounts:true}") boolean excludeTestAccounts,
            @Value("${training.export.test-email-domains:admin.map,test.com,map.test}") String testEmailDomains,
            @Value("${training.export.output-dir:}") String outputDir) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.salt = salt;
        this.excludeTestAccounts = excludeTestAccounts;
        this.testEmailDomains = Arrays.stream(testEmailDomains.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        this.outputDir = outputDir == null || outputDir.isBlank() ? null : Path.of(outputDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !exportApproved) {
            exitCode = 1;
            log.warn("training export blocked reason=approval_hold");
            return;
        }
        if (salt == null || salt.isBlank() || outputDir == null) {
            exitCode = 1;
            log.error("training export blocked reason=missing_private_configuration");
            return;
        }
        try {
            writeExport();
        } catch (RuntimeException | IOException e) {
            exitCode = 1;
            // Provider/SQL/codec exceptions can contain source data or secrets.
            log.error("training export failed reason={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public int getExitCode() { return exitCode; }

    private void writeExport() throws IOException {
        Files.createDirectories(outputDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(outputDir) || !Files.getPosixFilePermissions(outputDir).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("private export directory required");
        }
        Path lockPath = outputDir.resolve(".export.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("invalid export lock");
        try (FileChannel channel = FileChannel.open(lockPath, java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("export already running");
            TrainingExportService.Result result = service.export(excludeTestAccounts, testEmailDomains, salt);
            String id = UUID.randomUUID().toString();
            String name = "sessions-" + id + "-" + TrainingExportService.sha256Hex(salt).substring(0, 8) + ".jsonl";
            Path pending = Files.createTempFile(outputDir, ".pending-", ".jsonl",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (var out = Files.newBufferedWriter(pending)) {
                    long bytes = 0;
                    for (var row : result.rows()) {
                        String line = objectMapper.writeValueAsString(row);
                        bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                        if (maxBytes < 1 || bytes > maxBytes) throw new IOException("export byte budget exceeded");
                        out.write(line);
                        out.newLine();
                    }
                }
                try (var data = FileChannel.open(pending, StandardOpenOption.WRITE)) { data.force(true); }
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = new java.security.DigestInputStream(Files.newInputStream(pending), digest)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                String checksum = java.util.HexFormat.of().formatHex(digest.digest());
                Files.move(pending, outputDir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                Path manifest = Files.createTempFile(outputDir, ".pending-", ".manifest",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(manifest, objectMapper.writeValueAsString(Map.of(
                        "schema_version", TrainingExportService.EXPORT_SCHEMA_VERSION,
                        "artifact", name, "sha256", checksum, "rows", result.rows().size(),
                        "exported_at", Instant.now().toString(), "exclude_test_accounts", excludeTestAccounts,
                        "approval_required_for_training", true)));
                try (var data = FileChannel.open(manifest, StandardOpenOption.WRITE)) { data.force(true); }
                Files.move(manifest, outputDir.resolve(name + ".manifest.json"), StandardCopyOption.ATOMIC_MOVE);
                log.info("training export completed rows={} manifest_published=true", result.rows().size());
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable");
            }
            // No automated pruning: retention/deletion propagation is a separate approval gate.
        }
    }
}
