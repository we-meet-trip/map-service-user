package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import map.service.user.recommend.dto.TrainingExportRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 파일로 뽑아 주는 쪽의 규칙.
 *
 * <p>여기서 지키는 것은 셋이다. 꺼져 있으면 아무 것도 만들지 않는다. 소금이
 * 없으면 만들지 않되 기동을 막지도 않는다. 그리고 만들어진 파일에는 사람을
 * 곧장 가리키는 것이 없어야 한다 — 이 파일은 저장소 밖으로 나가는 것이라
 * 마지막 방어선이 여기다.
 */
@DisplayName("학습 자료 파일 쓰기")
class TrainingExportRunnerTest {

    private static final String SALT = "소금";

    private TrainingExportService service(List<TrainingExportRow> rows) {
        TrainingExportService s = mock(TrainingExportService.class);
        when(s.export(anyBoolean(), any(), anyString())).thenReturn(
                new TrainingExportService.Result(rows,
                        new TrainingExportService.Summary(
                                rows.size(), rows.size(), rows.size(), rows.size(),
                                0, 0, rows.size(), 0)));
        return s;
    }

    private TrainingExportRunner runner(TrainingExportService service, Path dir,
                                        boolean enabled, String salt) {
        // 문맥을 주지 않는다 — 주면 뽑고 나서 이 시험 프로세스를 내려 버린다.
        TrainingExportRunner runner = new TrainingExportRunner(service, new ObjectMapper(), enabled, salt,
                true, "admin.map,test.com", dir.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "exportApproved", true);
        return runner;
    }

    private TrainingExportRow row(Long sessionId, String userRef) {
        return new TrainingExportRow(1, "2026-08-23T00:00:00Z", sessionId, userRef, true,
                "job-1", "job-0", "parent", "init", "cache_hit", "refresh", "agent", 2,
                "splitkey", "request", null, null, null,
                new TrainingExportRow.Schedule("2026-09-05", "2026-09-05", "walk",
                        10, 18, null, null, 1, false),
                List.of(), new TrainingExportRow.Labels(
                        List.of(), List.of(), List.of(), "unavailable", List.of(), false),
                List.of(),
                new TrainingExportRow.Counts(0, 0, 0, 0, 0, 0), true, null);
    }

    private Stream<Path> files(Path dir) throws IOException {
        return Files.exists(dir) ? Files.list(dir).filter(p -> p.toString().endsWith(".jsonl")) : Stream.empty();
    }

    @Test
    void captureHoldBlocksExportEvenWhenExportFlagIsEnabled(@TempDir Path dir) throws IOException {
        TrainingExportService service = service(List.of(row(1L, "u_a")));
        TrainingExportRunner runner = new TrainingExportRunner(service, new ObjectMapper(),
                true, SALT, true, "test.com", dir.toString());
        runner.run(new DefaultApplicationArguments());
        verify(service, never()).export(anyBoolean(), any(), anyString());
        assertThat(files(dir)).isEmpty();
    }

    @Test
    @DisplayName("꺼져 있으면 조회조차 하지 않는다")
    void disabledDoesNothing(@TempDir Path dir) throws IOException {
        TrainingExportService service = service(List.of(row(1L, "u_a")));

        runner(service, dir, false, SALT).run(new DefaultApplicationArguments());

        verify(service, never()).export(anyBoolean(), any(), anyString());
        assertThat(files(dir)).isEmpty();
    }

    @Test
    @DisplayName("소금이 없으면 만들지 않되 기동을 막지도 않는다")
    void missingSaltSkipsWithoutThrowing(@TempDir Path dir) throws IOException {
        // 임의 소금으로 넘어가면 두 번 뽑은 파일이 조용히 서로 안 이어진다.
        TrainingExportService service = service(List.of(row(1L, "u_a")));

        runner(service, dir, true, "  ").run(new DefaultApplicationArguments());

        verify(service, never()).export(anyBoolean(), any(), anyString());
        assertThat(files(dir)).isEmpty();
    }

    @Test
    @DisplayName("한 줄에 한 세션이고 각 줄이 그 자체로 읽힌다")
    void writesOneParsableLinePerSession(@TempDir Path dir) throws IOException {
        runner(service(List.of(row(1L, "u_a"), row(2L, "u_b"))), dir, true, SALT)
                .run(new DefaultApplicationArguments());

        Path file = files(dir).findFirst().orElseThrow();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        ObjectMapper mapper = new ObjectMapper();
        for (String line : lines) {
            assertThat(mapper.readTree(line).get("session_id")).isNotNull();
        }
    }

    @Test
    @DisplayName("사람을 곧장 가리키는 것은 파일에 없다")
    void fileCarriesNoDirectIdentifiers(@TempDir Path dir) throws IOException {
        // 이 파일은 저장소 밖으로 나간다. 메일 주소나 원 식별자가 섞이면
        // 소금을 섞어 가린 뜻이 사라진다.
        runner(service(List.of(row(42L, "u_fingerprint"))), dir, true, SALT)
                .run(new DefaultApplicationArguments());

        String content = Files.readString(files(dir).findFirst().orElseThrow());
        assertThat(content).doesNotContain("@").doesNotContain("\"user_id\"");
        assertThat(content).contains("u_fingerprint");
    }

    @Test
    @DisplayName("파일 이름이 어느 소금으로 뽑은 것인지 밝힌다")
    void fileNameCarriesTheSaltFingerprint(@TempDir Path dir) throws IOException {
        // 소금이 다르면 두 파일의 사람이 이어지지 않는다. 이름만 보고 가릴 수 있어야
        // 섞어 쓰는 실수를 막는다. 소금 자체는 어디에도 남기지 않는다.
        runner(service(List.of(row(1L, "u_a"))), dir, true, SALT)
                .run(new DefaultApplicationArguments());

        String name = files(dir).findFirst().orElseThrow().getFileName().toString();
        assertThat(name).startsWith("sessions-")
                .endsWith(TrainingExportService.sha256Hex(SALT).substring(0, 8) + ".jsonl")
                .doesNotContain(SALT);
    }

    @Test
    void byteBudgetFailureNeverPublishesManifest(@TempDir Path dir) throws IOException {
        var runner = runner(service(List.of(row(1L, "u_synthetic"))), dir, true, SALT);
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "maxBytes", 1L);
        runner.run(new DefaultApplicationArguments());
        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(Files.list(dir)).noneMatch(p -> p.toString().endsWith(".manifest.json"));
    }

    @Test
    void parallelExportIsRejectedBeforeReadingSource(@TempDir Path dir) throws IOException {
        TrainingExportService service = service(List.of(row(1L, "u_synthetic")));
        try (var channel = java.nio.channels.FileChannel.open(dir.resolve(".export.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var runner = runner(service, dir, true, SALT);
            runner.run(new DefaultApplicationArguments());
            assertThat(runner.getExitCode()).isEqualTo(1);
            verify(service, never()).export(anyBoolean(), any(), anyString());
        }
    }

    @Test
    void publicDirectoryIsRejectedBeforeReadingSource(@TempDir Path dir) throws IOException {
        Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        TrainingExportService service = service(List.of(row(1L, "u_synthetic")));
        var runner = runner(service, dir, true, SALT);
        runner.run(new DefaultApplicationArguments());
        assertThat(runner.getExitCode()).isEqualTo(1);
        verify(service, never()).export(anyBoolean(), any(), anyString());
    }
}
