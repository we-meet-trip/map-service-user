package map.service.user.domain.auth.apple;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AppleProviderClientTest {
    private KeyPair pair;
    private AppleProviderClient client;
    private MockRestServiceServer server;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair=generator.generateKeyPair();
        var builder = RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
        client=new AppleProviderClient(new AppleSettings(true,"kr.mapservice.client","team","key","configured-test"),json,builder.build());
    }
    private void keys() throws Exception {
        var key=(RSAPublicKey)pair.getPublic();
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String jwks=json.writeValueAsString(Map.of("keys",List.of(Map.of("kid","test-key","alg","RS256","kty","RSA","use","sig",
                "n",encoder.encodeToString(key.getModulus().toByteArray()),"e",encoder.encodeToString(key.getPublicExponent().toByteArray())))));
        server.expect(requestTo("https://appleid.apple.com/auth/keys")).andRespond(withSuccess(jwks,MediaType.APPLICATION_JSON));
    }
    private String token(String issuer, String audience, String nonce, Instant expiry) {
        return Jwts.builder().header().keyId("test-key").and().issuer(issuer).subject("apple-subject")
                .audience().add(audience).and().issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(expiry)).claim("nonce",nonce).claim("email_verified",true)
                .signWith(pair.getPrivate(),Jwts.SIG.RS256).compact();
    }
    @Test void verifiesSignatureAudienceIssuerNonceAndUsesBoundedKeyCache() throws Exception {
        keys();
        String token=token("https://appleid.apple.com","kr.mapservice.client","nonce",Instant.now().plusSeconds(60));
        assertThat(client.verify(token,"nonce").subject()).isEqualTo("apple-subject");
        assertThat(client.verify(token,"nonce").emailVerified()).isTrue();
        server.verify(); // only one JWKS request for both validations
    }
    @Test void rejectsWrongNonceIssuerAudienceAndExpiredToken() throws Exception {
        keys();
        for (String token:List.of(
                token("https://appleid.apple.com","kr.mapservice.client","other",Instant.now().plusSeconds(60)),
                token("https://attacker.invalid","kr.mapservice.client","nonce",Instant.now().plusSeconds(60)),
                token("https://appleid.apple.com","another-app","nonce",Instant.now().plusSeconds(60)),
                token("https://appleid.apple.com","kr.mapservice.client","nonce",Instant.now().minusSeconds(120)))) {
            assertThatThrownBy(() -> client.verify(token,"nonce")).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        }
        server.verify();
    }
    @Test void rejectsTamperedSignature() throws Exception {
        keys();
        String token=token("https://appleid.apple.com","kr.mapservice.client","nonce",Instant.now().plusSeconds(60));
        String[] parts=token.split("\\.");
        parts[2]=(parts[2].startsWith("A") ? "B" : "A")+parts[2].substring(1);
        assertThatThrownBy(() -> client.verify(String.join(".",parts),"nonce")).isInstanceOf(ResponseStatusException.class);
    }
    /** 철회 요청은 client_secret 서명을 먼저 통과해야 하므로 실제 P-256 키가 필요하다. */
    private AppleProviderClient signingClient() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new AppleProviderClient(new AppleSettings(true, "kr.mapservice.client", "team", "key",
                Base64.getEncoder().encodeToString(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                json, builder.build());
    }

    @Test void 이미_무효인_토큰의_철회는_탈퇴를_막지_않는다() throws Exception {
        var signing = signingClient();
        server.expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatCode(() -> signing.revoke("already-revoked")).doesNotThrowAnyException();
        server.verify();
    }

    @Test void 철회가_닿지_않으면_완료로_표시하지_않는다() throws Exception {
        var signing = signingClient();
        server.expect(requestTo("https://appleid.apple.com/auth/revoke")).andRespond(withServerError());
        assertThatThrownBy(() -> signing.revoke("live-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        server.verify();
    }

    @Test void disabledConfigurationDoesNotContactApple() {
        var disabled=new AppleProviderClient(new AppleSettings(false,"","","",""),json,RestClient.create());
        assertThatThrownBy(() -> disabled.verify("invalid","nonce")).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
}
