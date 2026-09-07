# Explicit review summary generation

`GET /api/v1/reviews/summary?query=...` reads the existing summary cache only. A miss, expired/corrupt entry or Redis read failure returns HTTP 200 with `{query, bullets: [], sourceCount: 0}`. It never fetches source reviews, invokes Agent, or fills the cache. The empty response means no usable stored summary; it must not be rendered as a generated answer.

`POST /api/v1/reviews/summary` accepts JSON `{query, consent: true, client_request_id: UUID}`. Query is required, 1–60 characters. The caller must be authenticated and pass the existing service policy/18+ gate. The app must obtain separate optional review-summary AI consent before constructing the request, retain its permission/session guard during transmission and token refresh, and discard a response after withdrawal/session change. The boolean records explicit intent for this request; it does not persist consent or revoke prior provider processing.

The response shape remains `{query, bullets, sourceCount}`. No force-refresh exists: a valid existing summary is reused. Source absence or a failed model response yields no fabricated summary. Normal generated-result cache TTL remains 24 hours.

Explicit-request safeguards use atomic Redis Lua:

- The same authenticated user and request UUID receive the same completed result for 30 minutes, including an empty result. Query reuse with a different normalized value is HTTP 409.
- Pending requests return 409; concurrent explicit requests for one normalized place are excluded for up to five minutes. An ambiguous provider/completion failure leaves the request pending for 30 minutes to prevent another call on replay.
- At most six newly claimed requests per user in a 60-second window; further claims return 429. Cache protection failures return 503 before a new provider request. Redis response loss after provider completion cannot guarantee a delivered answer; replay does not call again during the receipt TTL.
- Receipts contain a query fingerprint, generated public-summary lines and source count, never raw query, identity or location. Request/rate keys hash the user/request identifiers. These transient receipts expire after 30 minutes, place claims after five minutes and rate keys after one minute. This is not a durable consent/audit record.
- Automatic recommendation/research prewarming remains under the trip AI consent flow. Its existing batch path is not merged into the explicit per-place lease; Agent's existing provider budget still applies. Manual `route` and `optimize=true` never prewarm.

User sends Agent only place_name and up to seven source title/description pairs (200/500 character limits), with category null for a single request. Agent's actual Gemini prompt uses sanitized place name, category, internal array index and description snippets. It excludes title, URL, author, date and coordinates. Provider/cache errors are logged only by exception class, without exception response bodies.

Validation: controller request/consent/auth contract; cache-only hit/miss/corrupt/outage provider count zero; replay/conflict/Redis failure/ambiguous completion; separate synthetic Redis concurrency/TTL/quota evidence. No paid model call is needed for these checks. In-app reporting coverage for generated review summaries remains coordinated with the release owner.
