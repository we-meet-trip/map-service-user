# Dedicated source exporter (learning remains HOLD)

`./gradlew bootJar trainingExportJar` produces separate serving and exporter entrypoints.
`docker build -f Dockerfile.training-export .` packages only that dedicated exporter
JAR in a separate nonroot image with no HTTP healthcheck. Both approval flags are
checked before the source connection starts. The default serving JAR rejects `training.export.enabled=true` before any application
beans, database pools or consumers start. The exporter is outside serving's component
scan and explicitly imports only export assembly, the existing cipher and repositories.
It excludes HTTP serving, Redis auto-configuration, Flyway, SQL initialization and
scheduling; it never registers Streams consumers, seeds or sweepers. Its exit code
belongs only to the one-shot exporter process.

Run the exporter **on the source environment's approved isolated snapshot boundary**,
not on the learning worker. Supply only that snapshot's read-only DB credential and
required source payload decryption key. The learning worker receives an approved
artifact and never receives these credentials, serving Redis, SSH or Docker socket.
Use a unique private 0700 output directory, a 0600 private environment file, container
memory/CPU/time limits and a role-specific deployment identity. Do not mount a complete
serving `.env`. Do not place secrets in command-line arguments.

Required configuration for an approved synthetic validation:

- `SPRING_MAIN_WEB_APPLICATION_TYPE=none`
- `SPRING_JPA_HIBERNATE_DDL_AUTO=none`
- `TRAINING_CAPTURE_ENABLED=false`, `TESTER_SEED_ENABLED=false`
- `TRAINING_EXPORT_ENABLED=true`, `TRAINING_EXPORT_APPROVED=true`
- `TRAINING_EXPORT_OUTPUT_DIR` and `TRAINING_EXPORT_USER_REF_SALT`
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` for the dedicated read-only snapshot role
- `LOCATION_CRYPTO_*` only if that synthetic snapshot uses encrypted payloads

Approval is a separate export gate. It does not enable source capture, authorize any
real-user dataset, or prove consent/provider rights/deletion propagation. All deployed
capture/export flags remain false until those gates are implemented and approved.
Do not reuse the legacy command that changes only a consumer name.

The batch uses a read-only repeatable-read transaction with a five-minute timeout,
a configurable `training.export.max-sessions` bound (default 10000), a `training.export.max-bytes` limit (default 64 MiB), and a per-output
file lock. It writes unique private files, flushes them, then publishes the JSONL and
checksum manifest atomically. A consumer must require the completed manifest and
verify its SHA-256 before use. A failed/cancelled batch can leave `.pending-*` files or
an unmanifested JSONL, which are not approved artifacts. Existing exports are never
pruned automatically. An explicit dataset registry, consent and deletion index,
time/source metadata, retention enforcement, distributed job leases, cancellation,
budget, evaluation and promotion/rollback remain separate implementation gates.

Validation in `TrainingExportIsolationTest` starts the real exporter context against
an isolated synthetic H2 database, checks absence of HTTP/Redis/Flyway/scheduler/seed
beans, and verifies output hashes/permissions and no created application tables.
Encryption/label and nonempty synthetic exports retain separate regression coverage.
This local verification is not a GCP or real-user export approval.
