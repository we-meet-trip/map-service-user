package map.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import map.service.user.ServiceUserApplication;
import map.service.user.recommend.TrainingExportRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class TrainingExportIsolationTest {
    @Test
    void actualExportContextReadsIsolatedFixtureWithoutServingOrMigration(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:mem:export_isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var conn = DriverManager.getConnection(url, "sa", ""); var sql = conn.createStatement()) {
            sql.execute("CREATE SCHEMA user_service");
            sql.execute("""
                    CREATE TABLE user_service.schedules (
                    schedule_id BIGINT, user_id BIGINT, job_id UUID, title VARCHAR,
                    date_start DATE, date_end DATE, payload JSON, transport VARCHAR,
                    active_start_hour INT, active_end_hour INT, created_at TIMESTAMP WITH TIME ZONE,
                    started_at TIMESTAMP WITH TIME ZONE, province VARCHAR, city VARCHAR,
                    weather_baseline JSON, weather_alert JSON, weather_checked_at TIMESTAMP WITH TIME ZONE,
                    deleted_at TIMESTAMP WITH TIME ZONE)
                    """);
        }
        try (var context = TrainingExportApplication.application().run(
                "--spring.datasource.url=" + url, "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "--location.crypto.enabled=false",
                "--training.capture.enabled=false", "--tester.seed.enabled=false",
                "--training.export.enabled=true", "--training.export.approved=true",
                "--training.export.user-ref-salt=synthetic-only", "--training.export.output-dir=" + dir)) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
            assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.matches("(?i).*(streamsConsumer|recommendJobsConsumer|sweeper|testerAccountSeed|weatherWatcher|controller|appleAuthorization).*"));
            assertThat(context.getBean(TrainingExportRunner.class).getExitCode()).isZero();
            var files = Files.list(dir).toList();
            Path manifest = files.stream().filter(p -> p.toString().endsWith(".manifest.json")).findFirst().orElseThrow();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(manifest));
            Path data = dir.resolve(json.get("artifact").asText());
            assertThat(json.get("rows").asInt()).isZero();
            assertThat(json.get("sha256").asText()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data))));
            assertThat(Files.getPosixFilePermissions(data)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(manifest)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        try (var conn = DriverManager.getConnection(url, "sa", "")) {
            assertThat(conn.createStatement().executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='USER_SERVICE'").next()).isTrue();
            var tables = conn.createStatement().executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='USER_SERVICE'");
            tables.next();
            assertThat(tables.getInt(1)).isOne(); // no Flyway/seed/generated schemas
        }
    }

    @Test
    void servingRejectsExportBeforeDataSourcesStart() {
        SpringApplication serving = new SpringApplication(ServiceUserApplication.class);
        serving.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> serving.run("--training.export.enabled=true",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never_connect"))
                .hasStackTraceContaining("training export is forbidden in serving");
    }

    @Test
    void exporterRejectsServingFlagsBeforeStartup() {
        for (String flag : new String[]{"--training.capture.enabled=true", "--tester.seed.enabled=true",
                "--spring.main.web-application-type=servlet", "--spring.jpa.hibernate.ddl-auto=create"}) {
            assertThatThrownBy(() -> TrainingExportApplication.application().run(flag))
                    .hasStackTraceContaining("exporter forbids serving/capture/seed/schema mutations");
        }
    }
}
