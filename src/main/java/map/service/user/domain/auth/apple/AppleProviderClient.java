package map.service.user.domain.auth.apple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class AppleProviderClient {
    private static final String ISSUER = "https://appleid.apple.com";
    private final AppleSettings settings;
    private final RestClient http;
    private final ObjectMapper json;
    private Map<String, PublicKey> keys = Map.of();
    private Instant keysExpire = Instant.EPOCH;

    @Autowired
    public AppleProviderClient(AppleSettings settings, ObjectMapper json) {
        this(settings, json, client());
    }
    AppleProviderClient(AppleSettings settings, ObjectMapper json, RestClient http) {
        this.settings = settings; this.json = json; this.http = http;
    }
    private static RestClient client() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }
    public record Identity(String subject, String email, boolean emailVerified) {}
    public record Tokens(String idToken, String refreshToken) {}

    public Identity verify(String token, String nonce) {
        settings.requireConfigured();
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || token.length() > 16384) throw new IllegalArgumentException();
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) throw new IllegalArgumentException();
            PublicKey key = publicKey(header.path("kid").asText());
            if (key == null) throw new IllegalArgumentException();
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                    .requireAudience(settings.clientId()).clockSkewSeconds(30).build()
                    .parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null ||
                    claims.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(30)) ||
                    claims.getSubject() == null || claims.getSubject().isBlank() || claims.getSubject().length() > 255 ||
                    (nonce != null && !nonce.equals(claims.get("nonce", String.class)))) throw new IllegalArgumentException();
            return new Identity(claims.getSubject(), claims.get("email", String.class),
                    "true".equals(String.valueOf(claims.get("email_verified"))));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Apple identity"); }
    }
    private synchronized PublicKey publicKey(String kid) {
        if (!keysExpire.isAfter(Instant.now())) {
            try {
                JsonNode jwks = http.get().uri(ISSUER + "/auth/keys").retrieve().body(JsonNode.class);
                Map<String, PublicKey> updated = new HashMap<>();
                if (jwks == null || !jwks.path("keys").isArray()) throw new IllegalStateException();
                for (JsonNode entry : jwks.path("keys")) {
                    if (!"RSA".equals(entry.path("kty").asText()) || !"RS256".equals(entry.path("alg").asText()) ||
                            !"sig".equals(entry.path("use").asText())) continue;
                    var n = new BigInteger(1, Base64.getUrlDecoder().decode(entry.path("n").asText()));
                    var e = new BigInteger(1, Base64.getUrlDecoder().decode(entry.path("e").asText()));
                    if (n.bitLength() < 2048) continue;
                    updated.put(entry.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
                }
                if (updated.isEmpty()) throw new IllegalStateException();
                keys = Map.copyOf(updated); keysExpire = Instant.now().plusSeconds(300);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple keys unavailable");
            }
        }
        return keys.get(kid);
    }
    private String clientSecret() {
        settings.requireConfigured();
        try {
            String pem = new String(Base64.getDecoder().decode(settings.privateKeyB64()), StandardCharsets.UTF_8);
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            Instant now = Instant.now();
            return Jwts.builder().header().keyId(settings.keyId()).and().issuer(settings.teamId())
                    .subject(settings.clientId()).audience().add(ISSUER).and()
                    .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(300)))
                    .signWith(key, Jwts.SIG.ES256).compact();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple signing unavailable"); }
    }
    public Tokens exchange(String code) {
        JsonNode result = tokenRequest("authorization_code", "code", code);
        String id = result.path("id_token").asText(), refresh = result.path("refresh_token").asText();
        if (id.isBlank() || refresh.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Incomplete Apple token response");
        return new Tokens(id, refresh);
    }
    public boolean remainsAuthorized(String refresh, String subject) {
        try {
            JsonNode result = tokenRequest("refresh_token", "refresh_token", refresh);
            if (!subject.equals(verify(result.path("id_token").asText(),null).subject())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Apple identity mismatch");
            }
            return true;
        }
        catch (AppleRevoked e) { return false; }
    }
    private JsonNode tokenRequest(String grant, String field, String value) {
        var form = form(); form.add("grant_type", grant); form.add(field, value);
        try {
            JsonNode result = http.post().uri(ISSUER + "/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            if (result == null || result.has("error")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Apple token exchange unavailable");
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                try {
                    if ("invalid_grant".equals(json.readTree(e.getResponseBodyAsString()).path("error").asText())) throw new AppleRevoked();
                } catch (AppleRevoked revoked) { throw revoked; }
                catch (Exception ignored) { /* Return a generic error; never log tokens. */ }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Apple token exchange unavailable");
        } catch (ResponseStatusException | AppleRevoked e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple connection unavailable"); }
    }
    public void revoke(String refresh) {
        var form = form(); form.add("token", refresh); form.add("token_type_hint", "refresh_token");
        try {
            http.post().uri(ISSUER + "/auth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple revocation unavailable; retry account deletion"); }
    }
    private LinkedMultiValueMap<String, String> form() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", settings.clientId()); form.add("client_secret", clientSecret()); return form;
    }
    static class AppleRevoked extends RuntimeException {}
}
