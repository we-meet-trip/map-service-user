# Native authentication and required birth date integration

Prepared 2026-09-07 from User `2f977e57b734a055114f47f792e89b34154bb190`. The work-start filename remains 2026-09-06. This document describes a candidate, not a GCP deployment or provider-login PASS.

## Fixed Kakao environment

When Kakao is configured, deployments must set all of the following. The provider HTTPS callback is distinct from the app's fixed custom callback. Authorization and code exchange use the exact same redirect property.

| Variable | GCP test | production |
| --- | --- | --- |
| `APP_ENV` | `test` | `prod` |
| `KAKAO_PUBLIC_ORIGIN` | `https://mapapptest.duckdns.org` | explicit production HTTPS DNS origin |
| `KAKAO_OAUTH_REDIRECT_URI` | `https://mapapptest.duckdns.org/api/v1/auth/kakao/callback` | origin + `/api/v1/auth/kakao/callback` |
| `KAKAO_APP_CALLBACK_SCHEME` | `mapauth-test://kakao` | `mapauth://kakao` |

The Kakao console redirect must be byte-identical. Startup rejects unknown environments, non-origin public URLs, mixed callbacks, known test production hosts, and overridden provider API endpoints. A provider with no client ID remains disabled without breaking email login. Local non-enforced development permits only an HTTP loopback callback and the test app scheme. No credentials or raw invalid values are included in validation errors.

`GET /api/v1/auth/kakao?state=...` and the HTTPS GET callback reject missing/blank/duplicate state. State is bounded to 512 characters without control characters; the app's random generation and exact session/state matching remain required. The GET callback carries exactly one code or error and preserves encoded state for both, including cancellation. Duplicate callback code/error/description is rejected; the target cannot be selected by request input. Invalid callbacks return HTTP 400 / `KAKAO_005` without redirecting.

`POST /api/v1/auth/kakao/callback` keeps its existing code-only JSON contract. Existing `(KAKAO, providerUserId)` links are the login identity. An unlinked provider with an existing email receives HTTP 409 / `KAKAO_004`; no account is selected or linked by matching email. Concurrent user/link constraint failures roll back and return the same conflict. A fresh login can find the winning provider link. Querying an aborted transaction or recovering by email is prohibited. Existing users/links are not reassigned or deleted. Provider failure logs contain only exception class, never provider response bodies.

## Birth date and service eligibility

MAP remains an 18+ service. Birth date is self-reported information, not independently verified identity. Email/social account authentication can return a session before a birth date exists so that the client can complete the profile, read policies, report safety issues, or delete its account. Protected service actions require both an eligible stored birthday and current service consent.

- Existing `PATCH /api/v1/users/me` accepts `birthDate: YYYY-MM-DD`. Initial input and later corrections remain supported; `null`/omitted fields preserve the existing value. The account row lock serializes profile corrections and consent acceptance. There is no new DOB lock or support-only correction requirement.
- Future dates are rejected with HTTP 400 / `BIRTH_DATE_INVALID` before any profile fields change. Today is a valid date but is under 18. Date comparisons use Asia/Seoul consistently, including while UTC is on the preceding date.
- `GET /api/v1/consents` keeps `age_eligible` nullable: missing birthday means `null` and `accepted:false`, even if an older acceptance record exists. A known minor has `age_eligible:false`; an eligible adult has `true`.
- `POST /api/v1/consents` keeps its existing JSON fields. A checkbox cannot replace missing birthday: HTTP 403 / `AGE_INFORMATION_REQUIRED`. A known minor receives HTTP 403 / `AGE_RESTRICTED`. Other existing policy version/confirmation errors are unchanged.
- Every subsequent protected REST request, recommendation admission/result retrieval, Vision permit, STOMP connect/send/subscribe and outbound delivery reuses the existing server policy checks. Correcting an accepted adult to a minor blocks further serving; stored acceptance history is retained. Returning to an eligible birthday reuses that history if its versions remain current.
- The established `LocalDate.plusYears(18)` anniversary rule remains: Feb 29 reaches the 18-year threshold on Feb 28 in a non-leap target year. Tests cover the day before and the threshold itself.
- No raw DOB is added to Gemini, recommendation, or Vision payloads or to logs. A request already admitted to an external provider cannot be recalled by a later profile correction. The Client must invalidate pending responses when profile/consent state changes; new server requests re-evaluate eligibility.

The Client must finish required DOB and consent before enabling recommendations. Existing null-DOB accepted clients now fail closed and need the updated UI; no invented birthday or automatic acceptance is backfilled. The root controls final rollout and compatibility/rollback. Do not deploy an earlier User image that restores the missing-DOB bypass as an ordinary rollback.

## Validation boundaries

Regression coverage executes the real Kakao service via `MockRestServiceServer`, including form redirect equality, linked re-login, new users with/without email, cross-provider email conflicts, malformed identity and concurrent failures. A real H2/JPA transaction fixture verifies an inserted new user rolls back on link failure; it does not claim a real provider login or a PostgreSQL concurrency stress result. Spring context tests exercise configuration startup. HTTP/JPA/STOMP and birthday tests cover missing/old consent, first input, correction, future dates, KST boundary and leap day. The old test helper that copied login logic without invoking the service was removed.

Local heavy JVM/Docker builds are prohibited by the memory constraint. Exact remote CI and hosted PostgreSQL outcomes are recorded in root `evidence/integration/user-auth-20260906/`; prepared test files are not execution evidence. Real Kakao console registration/device return, Apple credentials/signing/nonce/revoke, test Maps keys and hosting remain separate integration gates. No provider calls, live GCP changes, applied migrations, develop/master merges or user-data mutations are performed by this candidate.
