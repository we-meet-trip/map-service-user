package map.service.user.policy;

import java.time.*;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BirthDatePolicyTest {
    @Test void missingAndFutureAndTodayRemainDifferentStates() {
        LocalDate today = LocalDate.of(2026, 9, 7);
        assertThat(BirthDatePolicy.adult(null, today)).isNull();
        assertThat(BirthDatePolicy.adult(today, today)).isFalse();
        assertThatCode(() -> BirthDatePolicy.validate(today, today)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BirthDatePolicy.validate(today.plusDays(1), today))
                .isInstanceOf(CustomException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.BIRTH_DATE_INVALID);
        assertThat(BirthDatePolicy.adult(today.plusDays(1), today)).isFalse();
    }
    @Test void kstBirthdayBoundaryDoesNotDependOnServerUtcDate() {
        LocalDate birth = LocalDate.of(2008, 9, 7);
        var before = Instant.parse("2026-09-06T14:59:59Z").atZone(BirthDatePolicy.KST).toLocalDate();
        var after = Instant.parse("2026-09-06T15:00:00Z").atZone(BirthDatePolicy.KST).toLocalDate();
        assertThat(BirthDatePolicy.adult(birth, before)).isFalse();
        assertThat(BirthDatePolicy.adult(birth, after)).isTrue();
    }
    @Test void leapDayUsesLocalDateAnniversaryAtTheEndOfFebruary() {
        LocalDate leapBirth = LocalDate.of(2008, 2, 29);
        assertThat(BirthDatePolicy.adult(leapBirth, LocalDate.of(2026, 2, 27))).isFalse();
        assertThat(BirthDatePolicy.adult(leapBirth, LocalDate.of(2026, 2, 28))).isTrue();
    }
}
