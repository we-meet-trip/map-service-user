package map.service.user.policy;

import java.time.LocalDate;
import java.time.ZoneId;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;

/** Self-reported age information; this is not identity verification. */
public final class BirthDatePolicy {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private BirthDatePolicy() {}

    public static void validate(LocalDate birthDate, LocalDate today) {
        if (birthDate != null && birthDate.isAfter(today))
            throw new CustomException(ErrorCode.BIRTH_DATE_INVALID);
    }

    public static Boolean adult(LocalDate birthDate, LocalDate today) {
        if (birthDate == null) return null;
        if (birthDate.isAfter(today)) return false;
        return !birthDate.plusYears(18).isAfter(today);
    }
}
