package map.service.user.moderation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import map.service.user.global.exception.CustomException;
import org.junit.jupiter.api.Test;

class ModerationContractTest {
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    ReportRequest vision(String description) { return new ReportRequest(UUID.randomUUID(),ModerationReport.ContentType.VISION,
            ModerationReport.Reason.INACCURATE,description,null,null,null,null); }
    @Test void unknownImageLocationFieldsAndRawImageDataAreRejected() throws Exception {
        String accepted=mapper.writeValueAsString(vision("synthetic description"));
        assertThat(mapper.readValue(accepted,ReportRequest.class).description()).isEqualTo("synthetic description");
        for (String field:new String[]{"image","base64","latitude","user_id","unknown"}) {
            var body=mapper.readTree(accepted); ((com.fasterxml.jackson.databind.node.ObjectNode)body).put(field,"synthetic");
            assertThatThrownBy(()->mapper.readValue(body.toString(),ReportRequest.class)).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(()->ModerationService.validate(vision("data:image/jpeg;base64,synthetic"))).isInstanceOf(CustomException.class);
        assertThatThrownBy(()->ModerationService.validate(vision("A".repeat(300)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(()->ModerationService.validate(vision(""))).isInstanceOf(CustomException.class);
        assertThatThrownBy(()->ModerationService.validate(vision("description ".repeat(100)))).isInstanceOf(CustomException.class);
    }
    @Test void targetShapesAreExclusiveAndPositive() {
        UUID key=UUID.randomUUID();
        var reason=ModerationReport.Reason.OTHER;
        assertThatCode(()->ModerationService.validate(new ReportRequest(key,ModerationReport.ContentType.TRIP,reason,null,null,null,1L,null))).doesNotThrowAnyException();
        assertThatCode(()->ModerationService.validate(new ReportRequest(key,ModerationReport.ContentType.TRIP,reason,null,null,null,null,UUID.randomUUID()))).doesNotThrowAnyException();
        for (ReportRequest invalid:new ReportRequest[]{
                new ReportRequest(key,ModerationReport.ContentType.TRIP,reason,null,null,null,1L,UUID.randomUUID()),
                new ReportRequest(key,ModerationReport.ContentType.TRIP,reason,null,null,null,null,null),
                new ReportRequest(key,ModerationReport.ContentType.CHAT_MESSAGE,reason,null,1L,null,null,null),
                new ReportRequest(key,ModerationReport.ContentType.CHAT_MESSAGE,reason,null,1L,0L,null,null),
                new ReportRequest(key,ModerationReport.ContentType.CHAT_MESSAGE,reason,null,1L,2L,3L,null),
                new ReportRequest(key,ModerationReport.ContentType.VISION,reason,"description",1L,null,null,null)}) {
            assertThatThrownBy(()->ModerationService.validate(invalid)).isInstanceOf(CustomException.class);
        }
    }
    @Test void literalFilterHandlesCaseWidthSeparatorsAndZeroWidthWithoutRegexInjection() {
        ChatContentPolicy policy=new ChatContentPolicy("synthetic banned");
        for (String text:new String[]{"I WILL KILL YOU","ｉ ｗｉｌｌ ｋｉｌｌ ｙｏｕ","i.will.kill.you","i\u200bwill\u200ckill\u200dyou","synthetic--banned"})
            assertThat(policy.prohibited(text)).isTrue();
        assertThat(policy.prohibited("즐거운 여행이에요. 위험할 때 신고해주세요.")).isFalse();
        assertThat(policy.prohibited(null)).isFalse();
        assertThat(new ChatContentPolicy(".*").prohibited("ordinary")).isFalse();
    }
    @Test void reviewSummaryIsDescriptionOnlyAndCannotForgeAnyTarget() {
        UUID key=UUID.randomUUID();
        var type=ModerationReport.ContentType.REVIEW_SUMMARY;
        var reason=ModerationReport.Reason.INACCURATE;
        assertThatCode(()->ModerationService.validate(new ReportRequest(key,type,reason,"synthetic explanation",null,null,null,null)))
                .doesNotThrowAnyException();
        for (ReportRequest invalid:new ReportRequest[]{
                new ReportRequest(key,type,reason,null,null,null,null,null),
                new ReportRequest(key,type,reason," ",null,null,null,null),
                new ReportRequest(key,type,reason,"synthetic",1L,2L,null,null),
                new ReportRequest(key,type,reason,"synthetic",null,null,1L,null),
                new ReportRequest(key,type,reason,"synthetic",null,null,null,UUID.randomUUID()),
                new ReportRequest(key,type,reason,"data:image/png;base64,synthetic",null,null,null,null)}) {
            assertThatThrownBy(()->ModerationService.validate(invalid)).isInstanceOf(CustomException.class);
        }
    }
}
