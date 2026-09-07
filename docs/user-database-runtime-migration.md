# User runtime / migration separation — 2026-09-06

R4 work branch only, based on R3 `1d4f394b51f471319eb9403f6e926a8d0a511c72`.
This is a **deployment-blocking credential/ownership contract change**. Do not put
this image into the R3 release or start it with the old shared PostgreSQL account.
No GCP privileges, credentials, existing rows or applied migrations were changed
while preparing this code. develop/master integration remains user-confirmation HOLD.

## Execution boundary

The normal bootJar still starts `ServiceUserApplication`. It excludes Flyway
auto-configuration and rejects any attempt to re-enable it. A static
BeanFactoryPostProcessor verifies the serving role before singleton creation, so
HTTP serving, Redis consumers, schedulers and seed jobs cannot start first. The
guard reads PostgreSQL catalogs/ACLs only and emits bounded failure codes. It does
not repair privileges or query user rows. Only `test` profile plus an in-memory H2
URL and the test-only H2 driver skips the PostgreSQL check; H2 is absent from the
release bootJar. Hibernate remains `validate` in serving.

The migration entrypoint is a **plain Java main**, outside Spring scanning:

```text
java -Dloader.main=map.migration.UserMigrationApplication -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher migrate
```

Use the exact same registry digest as the candidate User image, overriding the
image ENTRYPOINT. The ordinary image entrypoint remains unchanged. There is no
second fat JAR, migration image, daemon or listening port. Allowed commands are
`check-config`, `validate`, `migrate`; clean/repair/baseline/undo are unavailable.
`check-config` performs **no database connection** and cannot certify privileges.
`validate` never changes ACLs or applies SQL. `migrate` runs Flyway then limits
runtime Flyway history access to SELECT. A failed final grant step is a failed
migration job and prevents starting serving until repaired forward.

An alternate Spring profile was rejected: component scans, automatic data sources
and negative scheduler/consumer exclusions would retain more accidental startup
paths. The plain main has no SpringApplication or application context, so it never
starts serving, Redis, Flyway auto-config, tester seed, schedulers or learning
capture/export. Existing learning capture/export approval HOLD is unchanged.

## Credentials and effective privileges

| Role | Credential contract | Effective permissions |
| --- | --- | --- |
| `map_user_runtime` | `USER_DATABASE_USER=map_user_runtime`, `USER_DATABASE_PASSWORD` | CONNECT, schema USAGE, application table SELECT/INSERT/UPDATE/DELETE, sequence USAGE/SELECT; history SELECT only |
| `map_user_migrator` | `USER_MIGRATION_URL`, `USER_MIGRATION_USERNAME=map_user_migrator`, `USER_MIGRATION_PASSWORD` | NOINHERIT login, only non-admin SET ROLE membership in `map_user_owner` |
| `map_user_owner` | NOLOGIN, no credential | Owns only User schema/objects and migrates under SET ROLE; no shared DB ownership or other service schema CREATE |

Serving no longer falls back to POSTGRES_USER/POSTGRES_PASSWORD. The host/port/name
settings still use POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB. Neither a migration password nor the shared POSTGRES_PASSWORD
may be present in serving. A migration job must use a dedicated environment
allowlist: the three USER_MIGRATION variables and minimal OS variables only.
It rejects Spring/serving/Redis/provider/location/training/tester credentials and
flags, even false flags. Do not use `docker compose run user` with the serving
environment. Deployment should provide secrets privately, without shell tracing,
command-line passwords or printing environment/datasource exception bodies.

Both login roles must be non-superuser, NOINHERIT, NOCREATEDB, NOCREATEROLE,
NOREPLICATION and NOBYPASSRLS. Runtime has no memberships and owns no relation,
schema, type or routine. Runtime rejects CREATE anywhere, database CREATE/TEMP,
TRUNCATE/REFERENCES/TRIGGER/MAINTAIN, grant options, sequence UPDATE (`setval`) and history DML. Owner
membership in other roles is forbidden. Migrator checks PostgreSQL 17 role SET
membership explicitly and verifies actual session/current role before Flyway.

Schema USAGE alone is permitted; it is not table access. Cross-schema effective
table/view/column SELECT and write grants, sequence privileges, and executable
non-system SECURITY DEFINER routines are rejected. PostgreSQL system catalogs
remain readable for the driver/ORM and ACL inspection. The sole shared reference
table exception is SELECT on `public.spatial_ref_sys` **only if PostgreSQL's
extension catalog proves it belongs to PostGIS**; writes are still rejected. This
is coordinate-system reference metadata, not Hub/Admin/User application data.
No allowance is made merely because an arbitrary table has that name.

Checks include inherited/PUBLIC ACLs. A role-specific REVOKE cannot deny a PUBLIC
grant. PostgreSQL commonly grants database TEMP and older installations may grant
public-schema CREATE. Inventory all affected service roles before changing these
shared grants; the preparation SQL deliberately performs **no global PUBLIC
REVOKE**. Grant required rights explicitly to each existing service before an
approved shared grant change. Do not silently break Hub's PostGIS operations.

JDBC URLs permit only the fixed schema and explicit TLS settings; embedded
credentials, role/options, duplicate options and custom socket factories are
rejected. Cross-host operation requires verified TLS/private networking configured
by Infra; default local Compose does not claim TLS deployment validation.

## Forward provisioning and safe order (Infra proposal, not applied)

1. Take and verify a backup, inventory owners/ACLs/default ACLs/schema definitions
   and existing role memberships; retain private before/after evidence. Stop User
   traffic/consumers during ownership transfer. Preserve PG/Redis/OSRM/observability
   and all data volumes. Record current image tuple and security rollback policy.
2. Review `user-database-role-provisioning.sql` against the actual catalog and
   expected old schema owner. It targets `user_service` only, takes a transaction
   advisory lock, rejects unsupported extension/foreign/routine/type ownership and
   transfers known tables/sequences/views. It never uses REASSIGN OWNED, DROP,
   TRUNCATE or copies rows. The script is a **proposal pending PG17 execution**.
3. Resolve shared PUBLIC privilege findings explicitly after impact review. Create
   new private login credentials and enable only the runtime/migrator logins. Do
   not change the existing shared account/password or grant runtime owner access.
4. Run the candidate exact image's `check-config`, then `validate` on an already
   migrated existing DB, and `migrate`. The normal runner requires a successful
   V004 history row and rejects an empty/unbootstrapped schema before DDL. Flyway uses the
   owner role, fixed schema, no schema creation, baseline disabled, no future-ignore,
   validation enabled and bounded DB lock retries. V001–V028 are unmodified.
5. Start User with runtime-only credentials. The startup guard checks effective
   ACLs, history and owners. Authenticate a synthetic account and exercise profile,
   schedule/chat encrypted CRUD, then verify original-row fingerprints. Prove DDL,
   CREATE TEMP, TRUNCATE, setval, cross-schema table/column access and role elevation
   fail. Retry a no-op migrate and an intentional migration failure safely.
6. Compare owners/ACLs/schema and exact prior user-row fingerprints; independently
   verify Hub/Admin data/permissions remain unchanged. Remove the migration job's
   short-lived secret/material from its process environment after exit, retaining
   a redacted job record and exact image/source/checksum. Keep public serving closed
   if any migration, permission check or semantic smoke fails.

## Infra Compose/receiver contract to implement separately

- User environment receives only dedicated runtime credentials (remove shared
  POSTGRES_USER/POSTGRES_PASSWORD from User, keep unrelated service credentials).
- A one-shot migration service uses the candidate USER_IMAGE@sha256 with the exact
  entrypoint above, `restart: no`, no ports, cap_drop ALL, no-new-privileges, and
  only database network access. No Redis, JWT, provider, location master keys,
  SSH keys, Docker socket or application volumes. Owner credentials never enter
  serving. Same-host Compose network names are not cross-host service contracts.
- Receiver runs the migration job to successful completion before User serving,
  handles nonzero/timeout as a release failure, and keeps the root-owned security
  cutover policy. It must not run old app auto-migration as a fallback.
- Fixed statement/connect/socket and Flyway lock budgets are separate from the
  receiver's whole-job timeout; that timeout must terminate the one-shot job too.
- CI launcher results are not sufficient to enable this role boundary in GCP.

## Rollback limits

An empty-host bootstrap is a separate **unimplemented/unverified gate**. Applied
V004 contains `CREATE SCHEMA IF NOT EXISTS user_service`; PostgreSQL checks database
CREATE **before** it checks whether that schema exists ([official PostgreSQL 17
source](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/commands/schemacmds.c)).
Changing V004 or silently granting the regular owner database CREATE is forbidden.
The production-transition path assumes the genuine existing V004 history from the
old release, then transfers owners/ACLs. A fresh-host path needs a separately
reviewed, isolated, one-time bootstrap of the unchanged early migrations and
removal of bootstrap privileges before this regular runner can be used. Do not
substitute Flyway baseline/repair or synthetic history entries. The proposal SQL
may precreate an empty schema, but that alone does not bootstrap V004.

Role/owner transfer is a forward security transition, not a volume replacement.
No down migration, old SQL rewrite or schema-history deletion is permitted. An R3
image still expects shared credentials and auto-runs Flyway; do **not** mark it
compatible with this least-privilege deployment just because additive columns
remain readable. Restoring its old environment would restore shared-superuser
serving. Running it with runtime credentials may fail its Flyway ownership/history
writes. Neither path is an approved automatic rollback. First establish a verified
fallback image with the same runtime guard, isolated migration path and R3 security
semantics; otherwise fail closed and repair/roll forward with an allowed exact
image. Reverting ownership is a separate reviewed catalog transaction, not a normal
application rollback and must preserve security/schema/data invariants.

## Verification status

This document is a source implementation record, not a PG/GCP completion claim.
New unit tests exercise configuration rejection, safe diagnostics, guard failures
and Flyway configuration. CI launches the exact bootJar for no-DB configuration and
forbidden-secret/command checks; it also runs the existing full test suite. No local
Docker, Gradle, Java test process, fixture or build is started under the current
memory constraint. **Actual PG17 owner transfer, SQL catalog compatibility, runtime
login/encrypted CRUD, deny-privilege probes, fingerprints and rollback execution
remain required, unexecuted, and must be scheduled with root before deployment.**

Primary specification checks (2026-09-07): PostgreSQL 17 [role membership](https://www.postgresql.org/docs/17/role-membership.html), [GRANT](https://www.postgresql.org/docs/17/sql-grant.html), and [default privileges](https://www.postgresql.org/docs/17/sql-alterdefaultprivileges.html). SET ROLE determines the new object owner; role-scoped defaults apply to that creating role, and per-schema REVOKE does not cancel global defaults. Flyway 11.7.2 configuration APIs were checked against the resolved official source JAR in the Gradle cache.
