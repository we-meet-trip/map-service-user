package map.export;

import map.service.user.global.config.LocationCryptoProperties;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.recommend.RecommendCacheKey;
import map.service.user.recommend.TrainingExportRunner;
import map.service.user.recommend.TrainingExportService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Source-host batch: only approved snapshot DB + source decryption key; never a learning worker. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class, SqlInitializationAutoConfiguration.class, TaskSchedulingAutoConfiguration.class})
@EntityScan("map.service.user")
@EnableJpaRepositories(basePackages = {"map.service.user.schedule", "map.service.user.recommend",
        "map.service.user.nearby", "map.service.user.domain.user.repository"})
@EnableConfigurationProperties(LocationCryptoProperties.class)
@Import({PayloadCipher.class, RecommendCacheKey.class, TrainingExportService.class, TrainingExportRunner.class})
public class TrainingExportApplication {
    public static SpringApplication application() {
        SpringApplication app = new SpringApplication(TrainingExportApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> {
            var env = context.getEnvironment();
            if (env.getProperty("spring.main.web-application-type", "none").equalsIgnoreCase("servlet")
                    || env.getProperty("spring.main.web-application-type", "none").equalsIgnoreCase("reactive")
                    || env.getProperty("training.capture.enabled", Boolean.class, false)
                    || env.getProperty("tester.seed.enabled", Boolean.class, false)
                    || !env.getProperty("spring.jpa.hibernate.ddl-auto", "none").equals("none")) {
                throw new IllegalStateException("exporter forbids serving/capture/seed/schema mutations");
            }
            if (!env.getProperty("training.export.enabled", Boolean.class, false)
                    || !env.getProperty("training.export.approved", Boolean.class, false)) {
                throw new IllegalStateException("training export approval HOLD; no source connection started");
            }
            // A server-side read-only DB role is mandatory in addition to this connection setting.
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "exporterIsolation", java.util.Map.of("spring.datasource.hikari.read-only", "true",
                    "spring.datasource.hikari.maximum-pool-size", "2", "spring.jpa.open-in-view", "false")));
        });
        return app;
    }

    public static void main(String[] args) {
        try (var context = application().run(args)) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
