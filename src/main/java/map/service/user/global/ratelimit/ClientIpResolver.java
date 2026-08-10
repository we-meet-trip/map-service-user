package map.service.user.global.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * ClientIpResolver — 한도 카운터의 키가 될 클라이언트 주소 판정
 *
 * 사용자를 특정할 수 없는 요청(로그인 전 호출)의 한도를 세려면 요청자를 가리킬
 * 무언가가 필요하고, 남는 것이 발신 주소뿐이다. 그 주소를 어디서 읽을지를 한
 * 곳에서 정해, 무인증 경로가 늘어날 때마다 판단이 갈라지지 않게 한다.
 */
@Component
public class ClientIpResolver {

    /**
     * 주소 헤더를 믿어도 되는 직전 발신자 대역.
     *
     * 엣지 프록시는 컨테이너 네트워크 안에서 오므로 사설 대역이 기본이다.
     * 다른 망 구성으로 바꾸면 이 값도 함께 좁혀야 헤더 신뢰 범위가 어긋나지 않는다.
     */
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(
            @Value("${ratelimit.trusted-proxies:172.16.0.0/12,10.0.0.0/8,192.168.0.0/16,127.0.0.0/8}")
            List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .map(IpAddressMatcher::new)
                .toList();
    }

    /**
     * 요청의 클라이언트 주소를 고른다.
     *
     * 프록시가 붙인 주소 헤더는 <b>직전 발신자가 신뢰 대역일 때만</b> 읽는다.
     * 헤더는 누구나 아무 값이나 넣어 보낼 수 있어서, 발신자를 보지 않고 헤더를
     * 믿으면 요청자가 카운터 키를 직접 정하게 된다. 그러면 값을 매 요청 바꿔가며
     * 한도를 무한히 우회할 수 있다.
     *
     * 헤더는 X-Real-IP 만 본다. 엣지가 이 헤더를 자기 판단값으로 항상 덮어쓰므로
     * 요청자가 보낸 값이 남지 않는다. X-Forwarded-For 는 값이 여러 개 이어질 수
     * 있어 어느 원소가 프록시가 쓴 것인지 헤더만으로는 가릴 수 없다.
     *
     * 신뢰 대역 밖에서 직접 들어온 요청은 헤더를 무시하고 실제 발신 주소를 쓴다.
     */
    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return remote;
    }

    /** 발신 주소가 주소 헤더를 믿어도 되는 프록시 대역에 속하는지. */
    private boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(remote)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // IP 형태가 아닌 발신 주소는 불일치로 본다.
            }
        }
        return false;
    }
}
