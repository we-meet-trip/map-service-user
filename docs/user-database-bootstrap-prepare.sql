-- NEW EMPTY HOST ONLY. Separate operator; never run against existing GCP.
-- psql -X -v ON_ERROR_STOP=1; supply variables through private stdin, not process args.
-- expected_database, marker (64 lowercase hex), bootstrap_password are required.
-- The database was created from template0 and independently marked by the operator.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(736281904,104);
SELECT set_config('map.bootstrap_database', :'expected_database', true) \gset
SELECT set_config('map.bootstrap_marker', :'marker', true) \gset
SELECT set_config('map.bootstrap_password', :'bootstrap_password', true) \gset
DO $prepare$
DECLARE db text := current_setting('map.bootstrap_database'); mark text := current_setting('map.bootstrap_marker');
BEGIN
  IF current_setting('server_version_num')::integer < 170000
     OR NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user)
     OR db !~ '^[a-z][a-z0-9_]{0,62}$' OR db IN ('postgres','template0','template1')
     OR db<>current_database() OR mark !~ '^[0-9a-f]{64}$'
     OR NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=db AND pg_get_userbyid(datdba)=current_user
                    AND NOT datistemplate AND shobj_description(oid,'pg_database')='map-user-bootstrap:v1:'||mark) THEN
    RAISE EXCEPTION 'bootstrap operator or independent target marker rejected';
  END IF;
  IF current_setting('map.bootstrap_password')='' THEN RAISE EXCEPTION 'bootstrap credential required'; END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('map_user_bootstrap','map_user_owner','map_user_migrator','map_user_runtime'))
     OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg\_%' ESCAPE '\')
     OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\')
     OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\')
     OR EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\')
     OR EXISTS (SELECT 1 FROM pg_extension WHERE extname<>'plpgsql')
     OR EXISTS (SELECT 1 FROM pg_default_acl) OR EXISTS (SELECT 1 FROM pg_event_trigger)
     OR EXISTS (SELECT 1 FROM pg_foreign_server) OR EXISTS (SELECT 1 FROM pg_largeobject_metadata)
     OR EXISTS (SELECT 1 FROM pg_publication)
     OR EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()) THEN
    RAISE EXCEPTION 'bootstrap requires exclusive pristine database and new dedicated roles';
  END IF;
  CREATE ROLE map_user_bootstrap LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
  EXECUTE format('ALTER ROLE map_user_bootstrap PASSWORD %L VALID UNTIL %L', current_setting('map.bootstrap_password'), clock_timestamp()+interval '55 minutes');
  CREATE ROLE map_user_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  CREATE ROLE map_user_migrator NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  CREATE ROLE map_user_runtime NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC',db);
  REVOKE ALL ON SCHEMA public FROM PUBLIC;
  EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO map_user_bootstrap',db);
  CREATE SCHEMA user_service AUTHORIZATION map_user_bootstrap;
  EXECUTE format('COMMENT ON SCHEMA user_service IS %L','map-user-bootstrap:v1:'||mark||':ready');
END
$prepare$;
COMMIT;
