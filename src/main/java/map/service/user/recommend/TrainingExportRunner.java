package map.service.user.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import map.service.user.recommend.dto.TrainingExportRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 쌓인 세션을 파일 하나로 뽑아 준다. 사람이 필요할 때 켜서 한 번 돌린다.
 *
 * <p>주기 작업으로 두지 않는 이유: 이 파일은 다시 만들 수 있는 파생물이라
 * 오래 두지 않기로 했는데, 정기적으로 디스크에 뿌리면 그 약속이 거짓이 된다.
 * 필요할 때만 만들고 쓰고 버린다.
 *
 * <p>기본은 꺼짐이다. 평소 기동에는 아무 영향이 없다.
 *
 * <p>어떤 실패도 밖으로 내보내지 않는다. 러너에서 빠져나간 예외는 컨텍스트를
 * 닫아 서비스 기동을 막는데, 자료를 뽑는 일이 서비스를 못 뜨게 해서는 안 된다.
 *
 * <h2>돌리는 법</h2>
 * <pre>
 * java -jar app.jar \
 *   --training.export.enabled=true \
 *   --training.export.user-ref-salt="$TRAINING_EXPORT_USER_REF_SALT" \
 *   --streams.recommend-consumer=export-oneshot
 * </pre>
 * 마지막 줄이 중요하다. 이름을 바꾸지 않으면 돌아가는 서버와 같은 이름으로
 * 결과 대기열에 붙어 완료 알림을 가로챈다.
 */
@Slf4j
@Component
public class TrainingExportRunner implements ApplicationRunner {

    /** 뽑아 둔 파일을 며칠까지 두는가. 파생물이라 오래 두지 않는다. */
    private static final Duration KEEP = Duration.ofDays(7);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final TrainingExportService service;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;
    @Value("${training.capture.enabled:false}")
    private boolean trainingCaptureEnabled;
    private final boolean enabled;
    private final String salt;
    private final boolean excludeTestAccounts;
    private final List<String> testEmailDomains;
    private final String outputDir;

    public TrainingExportRunner(
            TrainingExportService service,
            ObjectMapper objectMapper,
            ApplicationContext context,
            @Value("${training.export.enabled:false}") boolean enabled,
            @Value("${training.export.user-ref-salt:}") String salt,
            @Value("${training.export.exclude-test-accounts:true}") boolean excludeTestAccounts,
            @Value("${training.export.test-email-domains:admin.map,test.com,map.test}")
            String testEmailDomains,
            @Value("${training.export.output-dir:}") String outputDir) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.context = context;
        this.enabled = enabled;
        this.salt = salt;
        this.excludeTestAccounts = excludeTestAccounts;
        this.testEmailDomains = Arrays.stream(testEmailDomains.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.outputDir = outputDir == null || outputDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/map-training-export"
                : outputDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!trainingCaptureEnabled || !enabled) {
            return;
        }
        int exitCode = 0;
        if (salt == null || salt.isBlank()) {
            // 임의 소금으로 넘어가면 두 번 뽑은 파일이 조용히 서로 안 이어진다.
            // 그 사실은 한참 뒤 학습에서야 드러나므로 여기서 멈춘다.
            log.error("training export skipped reason=salt_not_set"
                    + " — TRAINING_EXPORT_USER_REF_SALT 를 채워야 한다");
            exitCode = 1;
        } else {
            try {
                writeExport();
            } catch (RuntimeException | IOException e) {
                log.error("training export failed reason={}", e.toString());
                exitCode = 1;
            }
        }
        shutdown(exitCode);
    }

    /**
     * 뽑기가 끝나면 스스로 내려간다.
     *
     * <p>이것을 켜고 뜬 것은 한 번 뽑으려고 띄운 것이지 서비스를 하려는 것이
     * 아니다. 그런데 대기열을 듣는 쪽과 주기 작업이 스레드를 붙잡고 있어
     * 가만두면 영영 살아 있는다 — 배치를 돌렸는데 끝나지 않는다.
     *
     * <p>{@code context} 가 없는 자리(시험에서 직접 만들어 부를 때)에서는
     * 내려갈 것이 없으므로 그냥 돌아간다.
     */
    private void shutdown(int exitCode) {
        if (context == null) {
            return;
        }
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private void writeExport() throws IOException {
        if (!excludeTestAccounts) {
            log.warn("training export test accounts INCLUDED"
                    + " — 이 파일은 학습에 그대로 쓰면 안 된다");
        }

        TrainingExportService.Result result =
                service.export(excludeTestAccounts, testEmailDomains, salt);
        TrainingExportService.Summary s = result.summary();

        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);
        prune(dir);

        Path file = dir.resolve("sessions-%s-%s.jsonl".formatted(
                OffsetDateTime.now(ZoneOffset.UTC).format(STAMP),
                TrainingExportService.sha256Hex(salt).substring(0, 8)));

        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (TrainingExportRow row : result.rows()) {
                // 한 줄에 하나. 값 안의 개행은 Jackson 이 이스케이프하므로 줄이 깨지지 않는다.
                out.write(objectMapper.writeValueAsString(row));
                out.write('\n');
            }
        }

        log.info("training export summary scanned={} job_linked={} chained={} deduped={}"
                        + " excluded_test={} excluded_unknown_user={} written={}"
                        + " split_key_fallback={} file={}",
                s.scanned(), s.jobLinked(), s.chained(), s.deduped(),
                s.excludedTest(), s.excludedUnknownUser(), s.written(),
                s.splitKeyFallback(), file);

        if (s.splitKeyFallback() > 0) {
            log.warn("training export split key fell back to job for {} of {} rows"
                            + " — 같은 조건의 세션이 배우는 쪽과 재는 쪽으로 갈릴 수 있다",
                    s.splitKeyFallback(), s.written());
        }

        if (s.written() == 0) {
            // 빈 파일만 나오면 사람이 고장으로 오해한다. 걸러진 내역을 함께 남긴다.
            log.warn("training export produced 0 rows: chained={} deduped={} excluded_test={}"
                            + " — 걸러 낼 것을 다 걸러 내면 남는 것이 없다는 뜻이다",
                    s.chained(), s.deduped(), s.excludedTest());
        }
    }

    /** 오래된 산출물을 지운다. 다시 만들 수 있는 것이라 쌓아 둘 이유가 없다. */
    private void prune(Path dir) {
        Instant cutoff = Instant.now().minus(KEEP);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "sessions-*.jsonl")) {
            for (Path f : files) {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // 정리에 실패해도 이번 뽑기는 계속한다.
            log.warn("training export prune failed reason={}", e.getMessage());
        }
    }
}
