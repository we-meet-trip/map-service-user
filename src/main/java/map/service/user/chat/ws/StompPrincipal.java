package map.service.user.chat.ws;

import java.security.Principal;

/**
 * StompPrincipal — WebSocket 세션의 사용자 신원
 *
 * CONNECT 프레임에서 JWT 로 확인한 사용자 식별자를 이름으로 담는 Principal 이다. 이 값이
 * 세션에 저장되어 이후 SUBSCRIBE/SEND 프레임에서 사용자 식별에 쓰인다. getName() 은
 * userId 를 문자열로 반환하며, 소비 측에서 Long 으로 파싱한다.
 */
public class StompPrincipal implements Principal {

    private final String name;

    public StompPrincipal(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }
}
