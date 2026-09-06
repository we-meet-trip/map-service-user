package map.service.user.moderation;

import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded literal screening, supplemented by authenticated reporting and human review. */
@Component
public class ChatContentPolicy {
    public static final String REMOVED_TEXT="운영 정책에 따라 표시되지 않는 메시지입니다.";
    private final List<String> phrases;
    public ChatContentPolicy(@Value("${moderation.chat.additional-blocked-phrases:}") String additional) {
        List<String> configured=new ArrayList<>(List.of(
                "죽여버리", "죽여버릴", "강간해", "아동성착취", "아동포르노", "몰카판매",
                "마약판매", "필로폰판매", "대마판매", "니거", "nigger", "kill yourself",
                "i will kill you", "child porn", "rape you"));
        if (additional.length()>8192) throw new IllegalArgumentException("Too many moderation phrases");
        configured.addAll(Arrays.asList(additional.split(",")));
        phrases=configured.stream().map(ChatContentPolicy::normalize).filter(s->!s.isEmpty()).distinct().toList();
    }
    static String normalize(String value) {
        StringBuilder out=new StringBuilder();
        Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }
    public boolean prohibited(String content) {
        if (content==null) return false;
        if (content.length()>10000) return true;
        String normalized=normalize(content);
        return phrases.stream().anyMatch(normalized::contains);
    }
    public String visibleText(String content) { return prohibited(content) ? REMOVED_TEXT : content; }
}
