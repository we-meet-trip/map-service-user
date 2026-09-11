-- R4 INFRA PROPOSAL, NOT EXECUTED. PostgreSQL 17, psql -X -v ON_ERROR_STOP=1.
-- Requires reviewed backup/owner/ACL inventories and stopped User serving.
-- Set expected_old_owner explicitly; never place passwords in command arguments.
-- Does not globally revoke PUBLIC or change existing shared credentials.
\set ON_ERROR_STOP on
\if :{?expected_old_owner}
\else
  \echo 'required: reviewed expected_old_owner'
  \quit 2
\endif

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SELECT pg_advisory_xact_lock(736281904, 4);
SELECT set_config('map.expected_old_owner', :'expected_old_owner', true);

DO $guard$
DECLARE item record; expected text := current_setting('map.expected_old_owner');
BEGIN
  IF current_setting('server_version_num')::integer < 170000 THEN
    RAISE EXCEPTION 'PostgreSQL 17 or later required';
  END IF;
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) THEN
    RAISE EXCEPTION 'separate reviewed provisioning operator required';
  END IF;
  IF expected !~ '^[a-z_][a-z0-9_]{0,62}$' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=expected) THEN
    RAISE EXCEPTION 'expected schema owner invalid';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='user_service'
             AND pg_get_userbyid(nspowner) NOT IN (expected,'map_user_owner')) THEN
    RAISE EXCEPTION 'unexpected User schema owner';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','S','f','c')
             AND (pg_get_userbyid(c.relowner) NOT IN (expected,'map_user_owner') OR c.relkind IN ('f','c'))) THEN
    RAISE EXCEPTION 'unexpected User relation owner or unsupported relation type';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='user_service')
     OR EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                WHERE n.nspname='user_service' AND t.typtype IN ('d','e'))
     OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_depend d ON d.classid='pg_class'::regclass AND d.objid=c.oid AND d.deptype='e'
                WHERE n.nspname='user_service') THEN
    RAISE EXCEPTION 'User routine/type/extension ownership requires a separate reviewed transfer';
  END IF;

  FOR item IN SELECT name FROM (VALUES ('map_user_runtime'),('map_user_migrator'),('map_user_owner')) roles(name) LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=item.name) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', item.name);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=item.name AND
      (rolsuper OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
       OR (rolname='map_user_owner' AND rolcanlogin))) THEN
      RAISE EXCEPTION 'existing dedicated role has unexpected attributes';
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member
             WHERE r.rolname IN ('map_user_runtime','map_user_owner'))
     OR EXISTS (SELECT 1 FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member
                WHERE r.rolname='map_user_migrator'
                AND (pg_get_userbyid(a.roleid)<>'map_user_owner' OR a.admin_option)) THEN
    RAISE EXCEPTION 'unexpected dedicated role memberships';
  END IF;
END
$guard$;

GRANT map_user_owner TO map_user_migrator WITH INHERIT FALSE, SET TRUE;
CREATE SCHEMA IF NOT EXISTS user_service AUTHORIZATION map_user_owner;
ALTER SCHEMA user_service OWNER TO map_user_owner;

DO $transfer$
DECLARE item record;
BEGIN
  -- Tables first: PostgreSQL also updates their dependent row/array type and owned sequence owners.
  FOR item IN SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m') ORDER BY c.oid LOOP
    EXECUTE format('ALTER %s user_service.%I OWNER TO map_user_owner',
      CASE item.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END, item.relname);
  END LOOP;
  FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='user_service' AND c.relkind='S' ORDER BY c.oid LOOP
    EXECUTE format('ALTER SEQUENCE user_service.%I OWNER TO map_user_owner', item.relname);
  END LOOP;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO map_user_runtime, map_user_migrator', current_database());
END
$transfer$;

-- These REVOKEs are confined to the newly dedicated runtime role and User schema.
-- They cannot cancel grants inherited from PUBLIC; runtime/migrator guards check effective rights.
REVOKE ALL ON SCHEMA user_service FROM map_user_runtime;
GRANT USAGE ON SCHEMA user_service TO map_user_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA user_service FROM map_user_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA user_service FROM map_user_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA user_service TO map_user_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA user_service TO map_user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner IN SCHEMA user_service
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO map_user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner IN SCHEMA user_service
  GRANT USAGE, SELECT ON SEQUENCES TO map_user_runtime;
-- This is only the NEW owner's defaults, not all existing DB functions/roles.
ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

DO $history$
BEGIN
  IF to_regclass('user_service.flyway_schema_history') IS NOT NULL THEN
    REVOKE ALL ON TABLE user_service.flyway_schema_history FROM map_user_runtime;
    GRANT SELECT ON TABLE user_service.flyway_schema_history TO map_user_runtime;
  END IF;
END
$history$;

-- No old service row or migration-history row is changed. Catalog-only postcondition.
DO $verify$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','S')
             AND pg_get_userbyid(c.relowner)<>'map_user_owner') THEN
    RAISE EXCEPTION 'User ownership transfer incomplete';
  END IF;
END
$verify$;
COMMIT;

-- Still REQUIRED before starting R4 (not done by this proposal):
-- 1. Review existing DB PUBLIC TEMP / public CREATE and foreign service ACLs.
-- 2. Provision secrets privately, enable LOGIN on runtime/migrator only.
-- 3. Run exact-image migrator, runtime ACL guard and isolated PG17 semantic/deny probes.
-- Do not automatically revert to shared-superuser R3 serving on any failure.
