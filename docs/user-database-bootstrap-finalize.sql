-- NEW EMPTY HOST ONLY. Exact artifact bootstrap must have exited successfully.
-- A separate provisioning operator transfers only genuine V001-V004 objects and
-- revokes the one-use login atomically. Final credentials remain operator-owned.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(736281904,104);
SELECT set_config('map.bootstrap_database', :'expected_database', true) \gset
SELECT set_config('map.bootstrap_marker', :'marker', true) \gset
DO $finalize$
DECLARE item record; has_rows boolean; db text := current_setting('map.bootstrap_database'); mark text := current_setting('map.bootstrap_marker');
BEGIN
  IF current_setting('server_version_num')::integer < 170000 OR NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user)
     OR db<>current_database() OR mark !~ '^[0-9a-f]{64}$'
     OR NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=db AND pg_get_userbyid(datdba)=current_user
                    AND shobj_description(oid,'pg_database')='map-user-bootstrap:v1:'||mark)
     OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='user_service' AND pg_get_userbyid(nspowner)='map_user_bootstrap'
                    AND obj_description(oid,'pg_namespace')='map-user-bootstrap:v1:'||mark||':started') THEN
    RAISE EXCEPTION 'bootstrap finalization target or attempt rejected';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename='map_user_bootstrap') THEN
    RAISE EXCEPTION 'stop dedicated bootstrap process and sessions before finalization';
  END IF;
  IF NOT (SELECT count(*)=4 AND bool_and(success AND type='SQL' AND installed_by='map_user_bootstrap')
      AND array_agg(installed_rank ORDER BY installed_rank)=ARRAY[1,2,3,4]
      AND array_agg(version ORDER BY installed_rank)=ARRAY['001','002','003','004']::varchar[]
      AND array_agg(script ORDER BY installed_rank)=ARRAY['V001__init.sql','V002__schedules.sql','V003__schedules_date_check.sql','V004__init_user_service.sql']::varchar[]
      AND array_agg(checksum ORDER BY installed_rank)=ARRAY[-192854188,-2113231432,1973132559,-777713445]
      FROM user_service.flyway_schema_history) THEN
    RAISE EXCEPTION 'genuine expected bootstrap history required';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('map_user_owner','map_user_migrator','map_user_runtime')
       AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls))
     OR (SELECT count(*) FROM pg_roles WHERE rolname IN ('map_user_owner','map_user_migrator','map_user_runtime'))<>3
     OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member IN (SELECT oid FROM pg_roles WHERE rolname IN ('map_user_bootstrap','map_user_owner','map_user_migrator','map_user_runtime'))
                    OR roleid=(SELECT oid FROM pg_roles WHERE rolname='map_user_bootstrap')) THEN
    RAISE EXCEPTION 'dedicated roles changed before finalization';
  END IF;
  IF (SELECT array_agg(c.relname::text ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='user_service' AND c.relkind='r') IS DISTINCT FROM
     ARRAY['flyway_schema_history','friends','media_objects','oauth_accounts','refresh_tokens','schedules','share_members','share_sessions','trip_recommendations','trip_segments','trips','user_devices','users']::text[]
     OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service'
                AND (pg_get_userbyid(c.relowner)<>'map_user_bootstrap' OR c.relkind NOT IN ('r','S','i')))
     OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='user_service')
     OR EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='user_service' AND t.typtype IN ('d','e')) THEN
    RAISE EXCEPTION 'bootstrap object shape or ownership changed';
  END IF;
  -- Bootstrap produces no application rows. Preserve unexpected rows by failing.
  FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname='user_service' AND c.relkind='r' AND c.relname<>'flyway_schema_history' LOOP
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM user_service.%I)',item.relname) INTO STRICT has_rows;
    IF has_rows THEN RAISE EXCEPTION 'unexpected application rows before serving'; END IF;
  END LOOP;
  ALTER ROLE map_user_bootstrap NOLOGIN PASSWORD NULL VALID UNTIL '1970-01-01';
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM map_user_bootstrap',db);
  FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind='r' LOOP
    EXECUTE format('ALTER TABLE user_service.%I OWNER TO map_user_owner',item.relname);
  END LOOP;
  FOR item IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind='S' LOOP
    EXECUTE format('ALTER SEQUENCE user_service.%I OWNER TO map_user_owner',item.relname);
  END LOOP;
  ALTER SCHEMA user_service OWNER TO map_user_owner;
  REVOKE ALL ON SCHEMA user_service FROM map_user_bootstrap, PUBLIC;
  REVOKE ALL ON ALL TABLES IN SCHEMA user_service FROM map_user_bootstrap, PUBLIC;
  REVOKE ALL ON ALL SEQUENCES IN SCHEMA user_service FROM map_user_bootstrap, PUBLIC;
  GRANT map_user_owner TO map_user_migrator WITH INHERIT FALSE, SET TRUE;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO map_user_migrator, map_user_runtime',db);
  GRANT USAGE ON SCHEMA user_service TO map_user_runtime;
  GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA user_service TO map_user_runtime;
  GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA user_service TO map_user_runtime;
  REVOKE ALL ON user_service.flyway_schema_history FROM map_user_runtime;
  GRANT SELECT ON user_service.flyway_schema_history TO map_user_runtime;
  ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner IN SCHEMA user_service GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO map_user_runtime;
  ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner IN SCHEMA user_service GRANT USAGE, SELECT ON SEQUENCES TO map_user_runtime;
  ALTER DEFAULT PRIVILEGES FOR ROLE map_user_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
  COMMENT ON SCHEMA user_service IS 'map-user-bootstrap:v1:finalized';
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND pg_get_userbyid(c.relowner)<>'map_user_owner')
     OR EXISTS (SELECT 1 FROM pg_authid WHERE rolname='map_user_bootstrap' AND (rolcanlogin OR rolpassword IS NOT NULL))
     OR has_database_privilege('map_user_bootstrap',db,'CONNECT') OR has_database_privilege('map_user_bootstrap',db,'CREATE')
     OR has_database_privilege('map_user_bootstrap',db,'TEMP') OR has_schema_privilege('map_user_bootstrap','user_service','USAGE')
     OR has_database_privilege('map_user_owner',db,'CREATE') OR has_database_privilege('map_user_owner',db,'TEMP') THEN
    RAISE EXCEPTION 'bootstrap finalization privilege postcondition failed';
  END IF;
END
$finalize$;
COMMIT;
