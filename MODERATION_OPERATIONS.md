# Content moderation — release work started 2026-09-06

This candidate adds V026; previously applied migrations are unchanged. It is separate
from the first OSRM/KMA release pinned to User 46fbd0d9. No GCP or production mutation
was performed for this implementation.

- Authenticated `/api/v1/moderation/reports` accepts CHAT_MESSAGE with room+seq,
  TRIP with exactly one owned schedule ID or recommendation UUID, or VISION with
  a description only. There is no verifiable durable Vision inference reference.
  Images/base64 and unknown fields are rejected. Descriptions are at most 1000
  characters and require configured PayloadCipher encryption; plaintext fallback
  is rejected. Existing chat/image content is not copied into reports.
- Request UUID is unique per reporter. A user-row lock serializes idempotency and
  the three-per-minute / ten-per-rolling-day quota across instances without Redis.
  An exact retry returns its receipt; different content with that key returns 409.
- Personal blocks are idempotent, reject self/unknown/unshared participant IDs,
  and require overlapping membership intervals. At most 1000 blocks per account.
  Lists return only the caller's IDs, public chat nicknames and creation dates.
- Blocks apply in both directions to TEXT/READ/TYPING/PRESENCE, including existing
  subscriptions. Other group recipients still receive messages. REST history,
  pagination, unread and previews exclude hidden/blocked messages. Participant
  read pointers and REST presence also mask blocked counterparts. System notices
  remain shared. Outbound delivery checks the session token and active membership,
  then re-reads the persisted message to prevent queued hidden/erased text replay.
- REST and STOMP send use the same finite literal content filter. NFKC/case/spacing/
  punctuation/format-character normalization handles simple evasion. Severe abuse,
  threats, sexual exploitation and selected illegal-sales phrases are screened;
  this is bounded heuristic screening, not complete content classification.
  Additional literal phrases may be configured with
  `moderation.chat.additional-blocked-phrases` (comma-separated, <=8192 characters).
  Legacy matching text is masked in REST/previews and withheld on STOMP. Users can
  report other prohibited or unsafe content for human review.

The guarded `/internal/admin/moderation/reports` list/detail/action API requires the
existing private-CIDR and internal-token guard. Actions additionally require an
opaque `X-Admin-Actor`, UUID `action_id`, and one of REVIEW, DISMISS, RESOLVE,
HIDE_CHAT_MESSAGE, RESTRICT_CHAT, LIFT_CHAT_RESTRICTION. Restriction is 1..720 hours;
only RESTRICT_CHAT accepts `restriction_hours`. The matching User row lock serializes
restrictions from different reports and follows withdrawal lock ordering. Hiding
retains stored messages for guarded review and sends MESSAGE_REMOVED only after
commit. Restriction prevents new REST/STOMP sends; lift permits new sends again.
The central Admin service must independently authenticate the operator, enforce
selected-environment and read/review/enforcement roles, and audit access/actions.
User does not trust a user-supplied actor or expose this API through ordinary JWT.

Account withdrawal clears descriptions, fingerprints and target references in
reports made by/about that account, detaches reporter/accused IDs, and removes the
account's blocks/restrictions. It preserves other participants' messages and
non-identifying moderation outcomes. The new retention job runs hourly, in batches
of at most 500 per operation: completed reports' descriptions/fingerprints/target
references are scrubbed after 90 days; action rows expire 365 days after creation;
completed receipt metadata expires 365 days after completion once actions have
expired. Open/in-review reports are retained for handling. This job does not alter
existing chat messages or the central Admin audit table. The default initial delay
is five minutes. Polling delay/backlog can defer physical deletion past the threshold.

## Validation

`./gradlew test bootJar` includes repository enforcement, real filter-chain auth,
operator guards, membership intervals, payload validation, retention boundaries,
message masking, idempotency, quotas, and restriction/release tests. The exact
executed totals and JAR checksum are recorded in root runtime evidence.

`scripts/verify-moderation-launch.py` runs only with explicit isolated flag,
loopback URL and a `map.synthetic=true` PostgreSQL container, empty users/memberships,
and V026. It creates three local synthetic accounts, uses real HTTP and three
STOMP sockets, performs restricted deletions of two accounts created by that run,
and leaves the remaining synthetic account/messages as evidence. No external
provider is invoked. Its companion launch harness uses a new dedicated PostgreSQL,
Redis and signed User JAR, and stops only its own new resources.

## Directions request budget

`HubDirectionsClient` uses a dedicated `hubDirectionsRestClient` with
`HUB_DIRECTIONS_TIMEOUT_SECONDS=20`. Other Hub reads keep `HUB_TIMEOUT_SECONDS=5`.
Each batch contains at most 20 legs and is issued once with one request timeout;
there is no application retry. Multiple batches are sequential, so their total
budget can exceed 20 seconds. Cold OSRM must be budgeted inside Hub below the User
batch timeout. An actual local delayed HTTP endpoint verifies a 20-leg batch gets
one timeout and one HTTP request; timeouts still yield unavailable route metadata.

## Dedicated management credential
`USER_ADMIN_INTERNAL_TOKEN` is required for every `/internal/**` request, in addition to the trusted network check. It must differ from `INTERNAL_SERVICE_TOKEN`; absent, blank, or reused credentials deny all internal admin requests while ordinary serving remains available. Provision an independent random value (at least 32 characters) only to User and the selected Admin target. Never forward it to Agent, Hub, YOLO, Client, or a learning worker. Rotate User and the matching Admin target together; keep rollback images/config paired. The header remains `X-Internal-Token` for wire compatibility.
