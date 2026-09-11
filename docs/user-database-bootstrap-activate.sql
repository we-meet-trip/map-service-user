-- Only after genuine bootstrap finalization on the independently verified new DB.
-- Supply variables over private psql stdin; never print SQL/passwords.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(736281904,104);
SELECT set_config('map.bootstrap_database', :'expected_database', true) \gset
SELECT set_config('map.bootstrap_marker', :'marker', true) \gset
SELECT set_config('map.runtime_password', :'runtime_password', true) \gset
SELECT set_config('map.migrator_password', :'migrator_password', true) \gset
DO $activate$
DECLARE db text := current_setting('map.bootstrap_database');
        mark text := current_setting('map.bootstrap_marker');
        runtime_secret text := current_setting('map.runtime_password');
        migrator_secret text := current_setting('map.migrator_password');
BEGIN
  IF current_setting('server_version_num')::integer < 170000
     OR NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user)
     OR db !~ '^[a-z][a-z0-9_]{0,62}$' OR db IN ('postgres','template0','template1')
     OR db<>current_database() OR mark !~ '^[a-f0-9]{64}$'
     OR NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=db AND NOT datistemplate
                    AND pg_get_userbyid(datdba)=current_user
                    AND shobj_description(oid,'pg_database')='map-user-bootstrap:v1:'||mark)
     OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='user_service'
                    AND pg_get_userbyid(nspowner)='map_user_owner'
                    AND obj_description(oid,'pg_namespace')='map-user-bootstrap:v1:finalized') THEN
    RAISE EXCEPTION 'bootstrap activation target rejected';
  END IF;
  IF runtime_secret !~ '^[a-f0-9]{64}$' OR migrator_secret !~ '^[a-f0-9]{64}$'
     OR runtime_secret=migrator_secret OR runtime_secret=mark OR migrator_secret=mark THEN
    RAISE EXCEPTION 'independent activation secrets required';
  END IF;
  IF (SELECT count(*) FROM pg_authid WHERE rolname IN
      ('map_user_bootstrap','map_user_owner','map_user_runtime','map_user_migrator')
      AND NOT rolcanlogin AND rolpassword IS NULL AND NOT rolsuper AND NOT rolcreatedb
      AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit)<>4
     OR EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename IN
        ('map_user_bootstrap','map_user_owner','map_user_runtime','map_user_migrator'))
     OR (SELECT count(*) FROM pg_auth_members WHERE member IN
         (SELECT oid FROM pg_roles WHERE rolname IN ('map_user_bootstrap','map_user_owner','map_user_runtime','map_user_migrator'))
         OR roleid IN (SELECT oid FROM pg_roles WHERE rolname IN
            ('map_user_bootstrap','map_user_owner','map_user_runtime','map_user_migrator')))<>1
     OR NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE
         pg_get_userbyid(member)='map_user_migrator' AND pg_get_userbyid(roleid)='map_user_owner'
         AND NOT admin_option AND NOT inherit_option AND set_option) THEN
    RAISE EXCEPTION 'bootstrap activation role state rejected';
  END IF;
  IF NOT (SELECT count(*)=4 AND bool_and(success AND type='SQL' AND installed_by='map_user_bootstrap')
       AND array_agg(version ORDER BY installed_rank)=ARRAY['001','002','003','004']::varchar[]
       AND array_agg(checksum ORDER BY installed_rank)=ARRAY[-192854188,-2113231432,1973132559,-777713445]
       FROM user_service.flyway_schema_history)
     OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='user_service' AND pg_get_userbyid(c.relowner)<>'map_user_owner')
     OR EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname IN
          ('map_user_bootstrap','map_user_owner','map_user_migrator','map_user_runtime')
          AND (has_database_privilege(r.oid,db,'CREATE') OR has_database_privilege(r.oid,db,'TEMP')))
     OR has_database_privilege('map_user_bootstrap',db,'CONNECT')
     OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege('map_user_runtime',oid,'CREATE'))
     OR NOT has_table_privilege('map_user_runtime','user_service.flyway_schema_history','SELECT')
     OR has_table_privilege('map_user_runtime','user_service.flyway_schema_history','INSERT,UPDATE,DELETE') THEN
    RAISE EXCEPTION 'bootstrap activation privileges rejected';
  END IF;
  EXECUTE format('ALTER ROLE map_user_runtime LOGIN PASSWORD %L',runtime_secret);
  EXECUTE format('ALTER ROLE map_user_migrator LOGIN PASSWORD %L',migrator_secret);
END
$activate$;
COMMIT;
