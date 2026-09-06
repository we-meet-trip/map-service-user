package map.service.user.domain.auth.apple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppleAuthorizationMonitor {
    private final AppleSettings settings;
    private final AppleAccountRepository links;
    private final AppleAccountService accounts;
    @Bean("appleAuthorizationScheduler")
    public static ThreadPoolTaskScheduler scheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("apple-authorization-");return scheduler;
    }
    @Scheduled(scheduler="appleAuthorizationScheduler", fixedDelayString="${APPLE_VALIDATION_DELAY_MS:3600000}", initialDelayString="${APPLE_VALIDATION_DELAY_MS:3600000}")
    public void validate() {
        if (!settings.enabled()) return;
        for (var link:links.findTop100ByRefreshTokenCiphertextIsNotNullAndCheckedAtBeforeOrderByCheckedAtAsc(OffsetDateTime.now().minusDays(1))) {
            try { accounts.checkAuthorization(link.getUserId()); }
            catch (Exception e) { log.warn("Apple authorization check deferred: {}",e.getClass().getSimpleName()); }
        }
    }
}
