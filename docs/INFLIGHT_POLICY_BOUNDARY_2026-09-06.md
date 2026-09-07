# In-flight response policy boundary

2026-09-07 follow-up to User `1def6c41c8a59163393eaf9eaf80737d44bce741`. Candidate source/tests are not GCP/mobile/provider execution proof. No database migration is introduced.

## Confirmed problem and implementation

The service policy interceptor previously checked only when a protected HTTP handler started. A synchronous trip generation/research/replan, review generation, recommendation poll or Vision permit could finish after the account's birthday was corrected, the session was revoked or the account was deleted. Repeating an entity `findById` is insufficient when a request EntityManager retains the earlier User or consent entity.

A successful interceptor check now marks the request with its server-authenticated user ID. The HTTP response advice checks this marker immediately before serializing a successful body, revalidates the original JWT and session, and reads current policy data with a scalar database query in a new read-committed transaction. The scalar projection deliberately bypasses cached User/acceptance entities. Wrong/missing identity, expired/revoked token, missing DOB, under-18 DOB, missing/stale policy or an unavailable permission store withholds the original result.

Existing age/token errors retain their wire codes. Permission-store failure adds HTTP 503 `SERVICE_POLICY_UNAVAILABLE`; no prior generated body or underlying provider/database error body is exposed. Non-success error responses retain their original error contract and do not recursively enter the response guard. Authentication, profile correction, consent, account deletion, safety reports and public invite previews retain their existing interceptor exemptions.

This is a check before serialization, not a claim that bytes already delivered can be recalled. Current User result controllers use ordinary HTTP message converters; no User SSE/streaming result endpoint was found. Existing STOMP revalidates token and policy for each SEND/SUBSCRIBE/outbound delivery. YOLO's separate WebSocket requires its own post-inference permit recheck and is not covered by HTTP response advice.

## External AI consent boundary

Server-persisted service policy is distinct from optional per-feature AI sharing consent. Current feature-specific consent/revocation epochs are Client state; review generation also checks its explicit request consent. User has no durable API for observing a Client-only AI revocation moment. The response guard enforces server-observable DOB/service-policy/session changes; it does not invent such an AI revocation signal. The Client must continue discarding pending results after feature consent changes. Work already sent to an external provider cannot be recalled. Existing capture/export HOLD remains unchanged, and new paid-provider calls are not required for these checks.

## Regression scope

- Delayed MockMvc handlers use latches so that DOB/policy/token changes happen after admission but before the body is returned. Cases cover synchronous generation/research/replan, explicit review, recommendation research/polling, Vision permit, expired/logged-out session and a failed permission lookup; protected sentinel content must not appear.
- Unchanged eligibility still returns the original successful body. Exempt profile correction and original upstream error bodies keep their behavior.
- A real JPA fixture loads a cached adult User, commits a DOB correction in a separate transaction and proves the scalar current-policy check sees the new minor value. Current/missing policy and deleted-account cases are separate assertions.
- Whole User Java and hosted PostgreSQL checks must pass on the exact feature SHA. Local heavy JVM/Docker builds, production/GCP changes, real-user mutation and paid provider requests remain prohibited for this worktree.

## Privacy revision for required DOB

Terms remain `2026-09-07`; privacy is `2026-09-07.1` because DOB changed from optional profile data to required service age checking. Existing adult accounts with the old receipt must explicitly accept the revised notice too. The original receipt remains unchanged until that action; old-version POST returns `409 POLICY_VERSION_MISMATCH`, and protected use returns `403 SERVICE_POLICY_REQUIRED`. Existing DB varchar(32), entity length 32 and string DTO contracts accept the revision without a migration. Hosted PostgreSQL begins with the unchanged R3 receipt, verifies denial and explicit revision, then continues encrypted serving CRUD.
