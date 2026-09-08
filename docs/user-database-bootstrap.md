# User empty-host bootstrap contract (R6 / R7)

This is a **new database only** entrypoint based on User S8
`18825cbb729d60e2eab21831b5d95283c3fae30d`. Never invoke it against existing GCP.
V001–V028, the normal migration main and both normal privilege guards are unchanged.
Implementation/test harness presence is not a PostgreSQL PASS; consume the exact
commit CI `user-bootstrap-postgres-verification` artifact before installation.

The selected design uses a distinct one-use role, instead of temporarily widening
the normal owner. Bootstrap really executes classpath V001–V004 with Flyway target
4. Four SHA-256 pins and history checksum checks retain the original SQL. No
baseline, repair, history INSERT, clean, retry or alternate target is exposed.

1. R6 creates a dedicated PostgreSQL 17+ database from `template0` using an operator
   that owns the database. Before any service schema, PostGIS or exporter/default
   ACL is installed, generate an independent 32-byte random marker and set the
   database comment to `map-user-bootstrap:v1:<64 lowercase hex>`. Record only a
   hash of that marker. The database name and marker are separately supplied to
   the deployment controller and process. This marker is a wrong-target guard;
   it does not replace endpoint/network verification or the host deployment lock.
2. Run `user-database-bootstrap-prepare.sql` as the separate operator via private
   `psql -X` stdin, supplying `expected_database`, `marker`, `bootstrap_password`.
   Never put credentials in command arguments, transcript or shared environment.
   It requires a pristine database, independent marker, exclusive connection and
   previously absent dedicated roles. It creates a 55-minute bootstrap credential
   with only CONNECT/database CREATE, an owned empty `user_service` schema and
   NOLOGIN final roles. PUBLIC database and public-schema rights are revoked only
   on this verified brand-new database. No existing deployment ACL is changed.
3. Run the exact User bootJar using the plain main:

   ```text
   java -Xmx192m -Dloader.main=map.bootstrap.UserBootstrapApplication \
     -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher bootstrap
   ```

   Supply only `USER_BOOTSTRAP_URL`, `USER_BOOTSTRAP_USERNAME=map_user_bootstrap`,
   `USER_BOOTSTRAP_PASSWORD`, `USER_BOOTSTRAP_EXPECTED_DATABASE`,
   `USER_BOOTSTRAP_MARKER`. Optional process environment is limited to PATH,
   JAVA_HOME, HOME, LANG, LC_ALL, TZ, TMPDIR. Strip all other environment keys,
   including dotted/lowercase Spring aliases, JVM injected options, Redis,
   migration/runtime, JWT and provider credentials. `check-config` checks config
   and resource pins without connecting. No target argument is accepted.
4. R7 owns an outer 120-second timeout, exact process identity, deployment lock and
   orphan cleanup. The process takes advisory lock `(736281904,104)`, checks
   database/schema marker, role flags/membership, emptiness and effective rights.
   Before Flyway, it durably changes the schema marker from `:ready` to `:started`.
   Duplicate or timed-out attempts cannot reuse a consumed marker. The main does
   not start Spring, Redis, listeners, provider clients or learning services.
5. On zero exit, `migrations_executed:4` and `operator_finalization_required:true`
   still require the separate operator. After the process exits and no bootstrap
   sessions remain, run `user-database-bootstrap-finalize.sql` with the same target
   and marker. It verifies exact real history, empty application tables and known
   object ownership, then atomically transfers User objects/default grants to
   `map_user_owner`, restricts history to runtime SELECT, revokes bootstrap
   CONNECT/CREATE/TEMP, disables LOGIN, and clears its password. It never grants
   database CREATE/TEMP to the final owner. Final runtime/migrator remain NOLOGIN.
6. Remove the bootstrap secret from the job/controller and verify no credential
   exists in serving. Provision independent runtime/migrator secrets privately,
   enable their LOGIN, then use the unchanged normal `UserMigrationApplication`
   for `migrate` and `validate`. Runtime uses its unchanged read-only privilege
   guard. R6 must capture the exact artifact/source, four legacy checksums,
   final privilege catalog checks and migration result before serving admission.

Any failure/timeout means serving HOLD, no automatic retry and no fallback to a
shared superuser. A timeout before marker consumption still requires operator
quarantine; a ready marker is not permission to retry an uncertain attempt.
Stop the exact process first and use `user-database-bootstrap-quarantine.sql` on
the independently checked target. NOLOGIN alone does not stop live sessions;
the controller separately terminates only inventoried bootstrap-role sessions on
that database and verifies zero remain. Keep real partial history/objects for
diagnosis. Owner-transfer failure rolls back that transaction; it does not undo
Flyway's already committed migrations. Do not reset comments, edit migrations,
repair history or delete the database as an operational recovery strategy.

The hosted CI fixture runs before the existing User isolation fixture, using only
new `user_bootstrap_fixture_*` databases and new dedicated roles. It checks real
bootstrap/finalization/normal migrate/validate/runtime guard/42501, target/marker,
existing objects, privilege errors, duplicate lock, timeout and genuine partial
V001–V003 preservation on injected V004 failure. It removes only its own marked
synthetic databases/roles after evidence capture; failure resources stay on the
disposable runner. This cleanup permission does not apply to GCP/NCP data.

R1 API impact: none. R3 image/build impact: no Dockerfile/dependency change; the
main is packaged in the existing bootJar. R7 deployment impact: add a dedicated
one-shot empty-host flow and two secret domains (one-use bootstrap and normal
runtime/migrator). Existing GCP remains on normal migration only. R6 installs
PostGIS and other application schemas after User bootstrap finalization, applying
the reviewed service ACL contracts before runtime admission.
