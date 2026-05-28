# 인증 도메인 통합 문서

> map-service-user · SW Architecture v5.0  
> 대상: client/agent/info 레포 담당자  
> API 요청/응답 상세 → `docs/api/index.html` (REST Docs) 참조  
> DB 스키마 상세 → `src/main/resources/db/migration/V1__init_user_service.sql` 참조

---

## 1. JWT 구조 (타 서비스 연동 기준)

### Access Token Payload

| Claim | 키 | 타입 | 설명 |
|---|---|---|---|
| Subject | `sub` | String (**Long**) | 서비스 사용자 ID (BIGSERIAL) |
| JWT ID | `jti` | String (UUID) | 토큰 고유 ID — Redis blacklist 키로 사용 |
| Email | `email` | String \| null | Kakao 동의 거부 시 null |
| Provider | `provider` | String | `EMAIL` \| `KAKAO` \| `APPLE` |
| Issued At | `iat` | Number (epoch) | |
| Expiration | `exp` | Number (epoch) | iat + 3600초 (1시간) |

```json
{
  "sub": "1",
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "email": "user@example.com",
  "provider": "EMAIL",
  "iat": 1748218800,
  "exp": 1748222400
}
```

- 알고리즘: **RS256** (2048-bit RSA)
- `sub` 파싱 시 **Long** 타입으로 처리 필요 (UUID 아님)
- Public Key 공유 방법 → §5 참조

### Refresh Token

JWT 아님. 랜덤 UUID 문자열 (36자). 서버 DB에 SHA-256 해시만 저장. 유효 기간 30일.

---

## 2. Redis 키 구조

| 키 | 값 | TTL | 설명 |
|---|---|---|---|
| `jwt:bl:{jti}` | `"1"` | 토큰 남은 유효 시간 | 로그아웃된 access token |

- Redis DB1 (user-Redis)
- 타 서비스가 토큰 검증 시 blacklist 직접 확인할 경우 이 키 패턴 사용

---

## 3. 인증 흐름

### 이메일 로그인

```
Client                          Server
  │  POST /api/v1/auth/login      │
  │  {email, password}            │──► BCrypt 검증 → JWT 발급
  │◄──────────────────────────────│
  │  {accessToken, refreshToken}  │
```

### 카카오 OAuth

```
Client          Server                    Kakao API
  │  카카오 SDK로 인가 코드 획득
  │  POST /api/v1/auth/kakao/callback {code}
  │──────────────────────────────────────►│
  │              │── code → access_token ─►│
  │              │◄── 사용자 정보 ──────────│
  │              │  oauth_accounts 조회/생성
  │              │  JWT 발급 (카카오 토큰 미보관)
  │◄─────────────│
  │  {accessToken, refreshToken}
```

> **계정 연동**: 카카오 이메일과 동일한 이메일로 이미 가입된 사용자 존재 시 → 기존 계정에 oauth_accounts만 추가. `authProvider`는 EMAIL 유지.

### 토큰 갱신 (Refresh Token Rotation)

```
Client                          Server
  │  POST /api/v1/auth/token/refresh
  │  {refreshToken}               │──► SHA-256 조회 → 유효성 검증
  │                               │    기존 token revoke → 신규 발급
  │◄──────────────────────────────│
  │  {새 accessToken, 새 refreshToken}
```

### 로그아웃

```
Client                          Server
  │  POST /api/v1/auth/logout
  │  Authorization: Bearer {AT}   │──► Redis SET jwt:bl:{jti} TTL=남은유효시간
  │  {refreshToken} (선택)        │    DB refresh_token revoked_at = now()
  │◄──────────────────────────────│
  │  204 No Content
```

---

## 4. 재사용 탐지

이미 폐기된 refresh token으로 갱신 요청 → **계정 탈취 시도로 간주**.

```
revoked_at IS NOT NULL 확인
→ 해당 user의 모든 refresh_tokens SET revoked_at = now()
→ 401 REFRESH_TOKEN_REVOKED
→ 클라이언트: 재로그인 필요
```

---

## 5. 에러 코드 (클라이언트 처리 기준)

| code | HTTP | 클라이언트 처리 |
|---|---|---|
| `AUTH_001` | 409 | 이메일 중복 안내 |
| `AUTH_002` | 401 | 이메일/비밀번호 오류 안내 |
| `JWT_002` | 401 | `/token/refresh` 호출 후 원래 요청 재시도 |
| `JWT_003` | 401 | 로그인 화면 이동 |
| `JWT_005` | 401 | 재사용 탐지 — 로그인 화면 이동 (보안 경고 안내 권장) |
| `JWT_006` | 401 | refresh token 만료 — 로그인 화면 이동 |
| `KAKAO_001` | 502 | 카카오 서버 오류 — 재시도 안내 |
| `KAKAO_002` | 502 | 카카오 서버 오류 — 재시도 안내 |
| `VALIDATION_ERROR` | 400 | 필드 오류 — `message` 필드 표시 |

에러 응답 형식:
```json
{
  "timestamp": "2026-05-29T12:00:00",
  "status": 401,
  "code": "JWT_002",
  "message": "만료된 토큰입니다."
}
```

---

## 6. 토큰 갱신 전략 (Flutter 클라이언트)

```
API 호출 → 401 수신 시:
  code == "JWT_002" (만료)
    → POST /api/v1/auth/token/refresh
    → 성공: 새 토큰 저장 후 원래 요청 재시도
    → 실패 (JWT_005 / JWT_006): 로그인 화면 이동

  code == "JWT_003" (blacklist)
    → 로그인 화면 이동
```

**토큰 저장소:**

| 토큰 | 저장소 |
|---|---|
| `accessToken` | `flutter_secure_storage` |
| `refreshToken` | `flutter_secure_storage` (**필수** — 유출 시 계정 탈취) |

SharedPreferences / 일반 파일 저장 금지.

---

## 7. 운영 RSA 키 설정

```bash
# 키 쌍 생성
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private_key.pem
openssl rsa -pubout -in private_key.pem -out public_key.pem

# Base64 인코딩
base64 -w 0 private_key.pem   # → JWT_PRIVATE_KEY
base64 -w 0 public_key.pem    # → JWT_PUBLIC_KEY
```

- **Private Key**: map-service-user 서버에만 보관
- **Public Key**: 토큰 검증이 필요한 타 서비스와 공유 가능
- 키 미설정 시: 서버 시작 시 임시 키 자동 생성. 재시작 시 기존 토큰 전부 무효화됨 → **운영 환경 필수 설정**
