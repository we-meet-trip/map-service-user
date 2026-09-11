-- Recovery ONLY for the dedicated bootstrap role after stopping its exact process.
-- Does not undo history, drop objects, or retry bootstrap. Preserve failure evidence.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT set_config('map.bootstrap_database', :'expected_database', true) \gset
SELECT set_config('map.bootstrap_marker', :'marker', true) \gset
DO $quarantine$
DECLARE db text:=current_setting('map.bootstrap_database'); mark text:=current_setting('map.bootstrap_marker');
BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) OR db<>current_database()
     OR mark !~ '^[0-9a-f]{64}$' OR NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=db
         AND shobj_description(oid,'pg_database')='map-user-bootstrap:v1:'||mark)
     OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='map_user_bootstrap' AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb)
     OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='map_user_bootstrap')
                    OR roleid=(SELECT oid FROM pg_roles WHERE rolname='map_user_bootstrap')) THEN
    RAISE EXCEPTION 'bootstrap quarantine target rejected';
  END IF;
  ALTER ROLE map_user_bootstrap NOLOGIN PASSWORD NULL VALID UNTIL '1970-01-01';
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM map_user_bootstrap',db);
END
$quarantine$;
COMMIT;
-- NOLOGIN does not stop existing sessions. The operator's exact session cleanup is separate.
