#!/usr/bin/env python3
"""Hosted-only synthetic bootstrap proof. Run BEFORE the legacy isolation fixture.

Only this harness's marked new databases/roles are removed, after evidence is captured.
On failure leave them for runner-scoped diagnosis; never point this at GCP/local PG.
"""
import datetime as dt
import hashlib
import inspect
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'build/user-bootstrap-postgres-verification.json'
PRIVATE = Path(tempfile.mkdtemp(prefix='map-user-bootstrap-ci-'))
os.chmod(PRIVATE, 0o700)
os.umask(0o077)
ROLES = ('map_user_bootstrap', 'map_user_runtime', 'map_user_migrator', 'map_user_owner')
checks = []
report = {'scope': 'GitHub hosted disposable PG17; genuine bootstrap and subsequent normal migration; no GCP',
          'source': os.environ.get('GITHUB_SHA'), 'started_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
          'checks': checks, 'success': False}
phase = 'environment_gate'
processes = []
database = None

class Failure(Exception):
    pass

def require(value, name):
    if not value: raise Failure(name)

def check(name, value=True):
    require(value, name)
    checks.append({'name': name, 'pass': True})

def process_environment():
    return {key: os.environ[key] for key in ('PATH', 'HOME', 'JAVA_HOME', 'LANG') if key in os.environ}

def execute(command, *, env=None, text=None, timeout=90):
    proc = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, start_new_session=True)
    processes.append(proc)
    try:
        stdout, stderr = proc.communicate(text, timeout=timeout)
    except BaseException:
        os.killpg(proc.pid, signal.SIGTERM)
        try: proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL); proc.communicate()
        raise
    return subprocess.CompletedProcess(command, proc.returncode, stdout, stderr)

def sql(statement, *, db=None, role='postgres', expected=None):
    env = {**process_environment(), 'PGHOST': '127.0.0.1', 'PGPORT': '5432', 'PGDATABASE': db or database or 'postgres',
           'PGUSER': role, 'PGPASSWORD': passwords[role]}
    run = execute(['psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate'], env=env, text=statement, timeout=40)
    if expected:
        require(run.returncode != 0 and re.search(r'\b'+expected+r'\b', run.stderr), 'sqlstate_'+expected)
        return expected
    if run.returncode:
        state = re.search(r'ERROR:\s+([0-9A-Z]{5})\b', run.stderr)
        report['sql_failure_state'] = state.group(1) if state else 'unclassified'
        caller = inspect.currentframe().f_back
        report['sql_failure_callsite'] = {'function': caller.f_code.co_name, 'line': caller.f_lineno}
        report['sql_failure_statement_sha256'] = hashlib.sha256(statement.encode()).hexdigest()
        # Private synthetic SQL diagnostic retained only on runner, never uploaded or printed.
        (PRIVATE / 'sql-error.txt').write_text(run.stderr)
        raise Failure('fixture_sql_failed')
    return run.stdout.strip()

def operator(filename, expected=0, extra=''):
    script = ('\\set expected_database '+database+'\n\\set marker '+marker+'\n'
              +'\\set bootstrap_password '+passwords['map_user_bootstrap']+'\n'+extra
              +(ROOT / 'docs' / filename).read_text())
    env = {**process_environment(), 'PGHOST':'127.0.0.1','PGPORT':'5432','PGDATABASE':database,
           'PGUSER':'postgres','PGPASSWORD':passwords['postgres']}
    run = execute(['psql','-XqAt','-v','ON_ERROR_STOP=1','-v','VERBOSITY=sqlstate'], env=env, text=script)
    if (run.returncode == 0) != (expected == 0):
        (PRIVATE / 'operator-error.txt').write_text(run.stderr)
        report['operator_file'] = filename
        state = re.search(r'ERROR:\s+([0-9A-Z]{5})\b', run.stderr)
        report['operator_failure_state'] = state.group(1) if state else 'unclassified'
        raise Failure('operator_expected_'+str(expected))
    return run

def stop_blocker(proc):
    # Only this harness's labeled operator blocker on its exact synthetic database.
    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND usename='postgres' AND application_name='synthetic-bootstrap-blocker' AND pid<>pg_backend_pid()")
    try: proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid,signal.SIGTERM); proc.wait(timeout=5)


def bootstrap_environment():
    return {**process_environment(), 'USER_BOOTSTRAP_URL':f'jdbc:postgresql://127.0.0.1:5432/{database}',
            'USER_BOOTSTRAP_EXPECTED_DATABASE':database, 'USER_BOOTSTRAP_USERNAME':'map_user_bootstrap',
            'USER_BOOTSTRAP_PASSWORD':passwords['map_user_bootstrap'], 'USER_BOOTSTRAP_MARKER':marker}

def java_command(main):
    return ['java','-Xmx192m','-Dloader.main='+main,'-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher']

def launch(operation='bootstrap', *, env=None, expected=0, main='map.bootstrap.UserBootstrapApplication'):
    trace = PRIVATE / (secrets.token_hex(8)+'.trace')
    run = execute(['strace','-f','-qq','-e','trace=bind,listen,connect','-o',str(trace)]+java_command(main)+[operation],
                  env=env or bootstrap_environment(), timeout=150)
    try: body = json.loads(run.stdout)
    except ValueError:
        (PRIVATE / 'java-output.txt').write_text(run.stdout+run.stderr)
        raise Failure('launcher_output_not_bounded_json') from None
    if run.returncode != expected:
        report['launcher_failure_status'] = body.get('status')
        report['launcher_failure_code'] = body.get('code')
        raise Failure('launcher_exit_expected_'+str(expected)+'_got_'+str(run.returncode))
    require(not run.stderr.strip(), 'launcher_stderr')
    calls = trace.read_text()
    require('listen(' not in calls and 'htons(6379)' not in calls, 'bootstrap_serving_or_redis_syscall')
    inet_ports = re.findall(r'sin6?_port=htons\((\d+)\)', calls)
    require(set(inet_ports) <= {'5432'}, 'unexpected_network_destination')
    return body

def snapshot():
    return sql("SELECT md5(COALESCE(string_agg(n.nspname||'.'||c.relname||':'||c.relkind::text||':'||pg_get_userbyid(c.relowner),',' ORDER BY n.nspname,c.relname),'')) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service'")

def prepare(suffix):
    global database, marker
    database = 'user_bootstrap_fixture_'+suffix
    marker = secrets.token_hex(32)
    require(sql('SELECT count(*) FROM pg_database WHERE datname=\''+database+'\'', db='postgres')=='0','fixture_database_must_be_new')
    require(sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('map_user_bootstrap','map_user_runtime','map_user_migrator','map_user_owner')", db='postgres')=='0','fixture_roles_must_be_new')
    sql('CREATE DATABASE '+database+' TEMPLATE template0', db='postgres')
    sql("COMMENT ON DATABASE "+database+" IS 'map-user-bootstrap:v1:"+marker+"'")
    operator('user-database-bootstrap-prepare.sql')

def cleanup():
    # Exact synthetic resources created by prepare(), with independent marker verified again.
    require(database.startswith('user_bootstrap_fixture_'),'cleanup_database_prefix')
    require(sql("SELECT shobj_description(oid,'pg_database')='map-user-bootstrap:v1:"+marker+"' FROM pg_database WHERE datname=current_database()")=='t','cleanup_marker')
    require(sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()")=='0','cleanup_no_sessions')
    sql('DROP DATABASE '+database, db='postgres')
    for role in ROLES: sql('DROP ROLE '+role, db='postgres')
    check('own_synthetic_cleanup_'+database.rsplit('_',1)[-1])

def runtime_guard():
    classes = PRIVATE / 'guard-classes'; classes.mkdir()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/classes/map/database/') and name.endswith('.class'):
                target = classes / name.removeprefix('BOOT-INF/classes/'); target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(archive.read(name))
            if name.startswith('BOOT-INF/lib/postgresql-'):
                (PRIVATE / 'jdbc.jar').write_bytes(archive.read(name))
    source = PRIVATE / 'RuntimeGuardProbe.java'
    source.write_text('public class RuntimeGuardProbe { public static void main(String[] a) throws Exception { try (var c=map.database.UserDatabaseContract.connect(System.getenv("PROBE_URL"),"map_user_runtime",System.getenv("PROBE_PASSWORD"))) { map.database.UserDatabasePrivileges.verifyRuntime(c); } System.out.println("runtime_guard_pass"); } }')
    env={**process_environment(),'PROBE_URL':f'jdbc:postgresql://127.0.0.1:5432/{database}','PROBE_PASSWORD':passwords['map_user_runtime']}
    run=execute(['java','-cp',str(classes)+os.pathsep+str(PRIVATE/'jdbc.jar'),str(source)],env=env)
    require((run.returncode,run.stdout.strip(),run.stderr.strip())==(0,'runtime_guard_pass',''),'unchanged_runtime_guard')

try:
    require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted'
            and os.environ.get('MAP_HOSTED_DATABASE_FIXTURE')=='true' and os.environ.get('PGHOST')=='127.0.0.1'
            and os.environ.get('PGPORT')=='5432' and os.environ.get('PGUSER')=='postgres','hosted_only_gate')
    passwords={role:secrets.token_hex(24) for role in ROLES}; passwords['postgres']=os.environ['PGPASSWORD']
    jar=next(p for p in (ROOT/'build/libs').glob('*.jar') if not p.name.endswith('-training-export.jar'))
    report['jar_sha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
    check('postgres_17',int(sql("SELECT current_setting('server_version_num')",db='postgres'))>=170000)

    phase='wrong_target_and_existing_objects'
    prepare('success')
    original=snapshot()
    env=bootstrap_environment(); env['USER_BOOTSTRAP_MARKER']='0'*64
    check('wrong_marker_rejected',launch(env=env,expected=1)['code']=='bootstrap_target_marker_mismatch')
    env=bootstrap_environment(); env['USER_BOOTSTRAP_EXPECTED_DATABASE']='wrong_database'
    check('wrong_database_rejected_before_connect',launch(env=env,expected=2)['status']=='configuration_rejected')
    for name,create,drop in (
        ('table','CREATE TABLE user_service.synthetic_existing(id int)','DROP TABLE user_service.synthetic_existing'),
        ('rows','CREATE TABLE user_service.synthetic_existing(id int); INSERT INTO user_service.synthetic_existing VALUES (1)','DROP TABLE user_service.synthetic_existing'),
        ('sequence','CREATE SEQUENCE user_service.synthetic_existing','DROP SEQUENCE user_service.synthetic_existing'),
        ('domain','CREATE DOMAIN user_service.synthetic_existing AS text','DROP DOMAIN user_service.synthetic_existing'),
        ('type',"CREATE TYPE user_service.synthetic_existing AS ENUM ('synthetic')",'DROP TYPE user_service.synthetic_existing'),
        ('routine',"CREATE FUNCTION user_service.synthetic_existing() RETURNS int LANGUAGE SQL AS 'SELECT 1'",'DROP FUNCTION user_service.synthetic_existing()'),
        ('old_history','CREATE TABLE user_service.flyway_schema_history(id int)','DROP TABLE user_service.flyway_schema_history'),
        ('foreign_schema','CREATE SCHEMA synthetic_foreign','DROP SCHEMA synthetic_foreign')):
        sql(create); before=snapshot(); launch(expected=1)
        check('existing_'+name+'_rejected_preserved',snapshot()==before)
        sql(drop)  # Only this fixture's just-created synthetic negative-case object.
    check('negative_guards_created_no_relations',snapshot()==original)
    for name,grant,revoke in (
        ('superuser','ALTER ROLE map_user_bootstrap SUPERUSER','ALTER ROLE map_user_bootstrap NOSUPERUSER'),
        ('membership','GRANT map_user_owner TO map_user_bootstrap','REVOKE map_user_owner FROM map_user_bootstrap'),
        ('public_temp','GRANT TEMP ON DATABASE '+database+' TO PUBLIC','REVOKE TEMP ON DATABASE '+database+' FROM PUBLIC'),
        ('schema_owner','ALTER SCHEMA user_service OWNER TO postgres','ALTER SCHEMA user_service OWNER TO map_user_bootstrap')):
        sql(grant); launch(expected=1); check('wrong_'+name+'_rejected',snapshot()==original or name=='schema_owner'); sql(revoke)

    phase='duplicate_lock'
    lockfile=PRIVATE/'lock.out'
    lockenv={**process_environment(),'PGHOST':'127.0.0.1','PGPORT':'5432','PGDATABASE':database,'PGUSER':'postgres','PGPASSWORD':passwords['postgres'],'PGAPPNAME':'synthetic-bootstrap-blocker'}
    with lockfile.open('w') as out:
        blocker=subprocess.Popen(['psql','-XqAt','-c','SELECT pg_advisory_lock(736281904,104); SELECT pg_sleep(60)'],env=lockenv,stdout=out,stderr=out,start_new_session=True)
    processes.append(blocker)
    for _ in range(100):
        if sql('SELECT count(*) FROM pg_locks WHERE locktype=\'advisory\' AND classid=736281904 AND objid=104 AND granted')=='1': break
        time.sleep(.05)
    check('concurrent_attempt_rejected',launch(expected=1)['code']=='bootstrap_already_running')
    stop_blocker(blocker)

    phase='genuine_bootstrap_and_finalize'
    check('genuine_v001_v004',launch()['migrations_executed']==4)
    history=sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY installed_rank)) FROM user_service.flyway_schema_history t")
    check('duplicate_success_rejected',launch(expected=1)['code']=='bootstrap_attempt_not_ready')
    operator('user-database-bootstrap-finalize.sql',expected=1,extra='\\set marker '+('f'*64)+'\n')
    check('wrong_finalize_preserved',sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY installed_rank)) FROM user_service.flyway_schema_history t")==history)
    operator('user-database-bootstrap-finalize.sql')
    check('genuine_history_preserved_after_transfer',sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY installed_rank)) FROM user_service.flyway_schema_history t")==history)
    check('bootstrap_login_password_revoked',sql("SELECT NOT rolcanlogin AND rolpassword IS NULL FROM pg_authid WHERE rolname='map_user_bootstrap'")=='t')
    for privilege in ('CONNECT','CREATE','TEMP'):
        check('bootstrap_denied_'+privilege.lower(),sql("SELECT NOT has_database_privilege('map_user_bootstrap',current_database(),'"+privilege+"')")=='t')
    for role in ('map_user_migrator','map_user_runtime'):
        sql("ALTER ROLE "+role+" LOGIN PASSWORD '"+passwords[role]+"'")
    env={**process_environment(),'USER_MIGRATION_URL':f'jdbc:postgresql://127.0.0.1:5432/{database}',
         'USER_MIGRATION_USERNAME':'map_user_migrator','USER_MIGRATION_PASSWORD':passwords['map_user_migrator']}
    check('normal_forward_migrations',launch('migrate',env=env,main='map.migration.UserMigrationApplication')['migrations_executed']>=24)
    check('normal_validate',launch('validate',env=env,main='map.migration.UserMigrationApplication')['status']=='complete')
    check('normal_repeat_zero',launch('migrate',env=env,main='map.migration.UserMigrationApplication')['migrations_executed']==0)
    runtime_guard(); check('unchanged_runtime_guard_pass')
    sql("INSERT INTO user_service.users(email,nickname) VALUES ('synthetic-bootstrap@example.invalid','Synthetic'); UPDATE user_service.users SET nickname='Synthetic changed'; DELETE FROM user_service.users",role='map_user_runtime')
    check('runtime_real_crud')
    for name,statement in [('schema_create','CREATE SCHEMA forbidden'),('table_create','CREATE TABLE user_service.forbidden(id int)'),
                           ('history_update','UPDATE user_service.flyway_schema_history SET success=false'),('temp','CREATE TEMP TABLE forbidden(id int)')]:
        check('runtime_42501_'+name,sql(statement,role='map_user_runtime',expected='42501')=='42501')
    cleanup()

    phase='timeout_partial_preservation'
    prepare('partial')
    lockfile=PRIVATE/'timeout-lock.out'
    # Durable attempt consumption is the timeout boundary. Terminate only the exact
    # synthetic bootstrap process after observing :started while Flyway is blocked.
    lockenv['PGDATABASE']=database
    with lockfile.open('w') as out:
        blocker=subprocess.Popen(['psql','-XqAt','-c',"BEGIN; LOCK TABLE pg_catalog.pg_class IN SHARE MODE; SELECT pg_sleep(90)"],env=lockenv,stdout=out,stderr=out,start_new_session=True)
    processes.append(blocker)
    for _ in range(100):
        if sql("SELECT count(*) FROM pg_locks WHERE relation='pg_class'::regclass AND mode='ShareLock' AND granted")=='1': break
        time.sleep(.05)
    out=(PRIVATE/'timeout-java.out').open('w')
    proc=subprocess.Popen(java_command('map.bootstrap.UserBootstrapApplication')+['bootstrap'],env=bootstrap_environment(),stdout=out,stderr=out,start_new_session=True)
    processes.append(proc)
    consumed=False
    for _ in range(100):
        consumed=sql("SELECT obj_description(oid,'pg_namespace') LIKE '%:started' FROM pg_namespace WHERE nspname='user_service'")=='t'
        if consumed or proc.poll() is not None: break
        time.sleep(.05)
    require(consumed,'timeout_marker_consumed')
    if proc.poll() is None: os.killpg(proc.pid,signal.SIGTERM)
    proc.wait(timeout=10); out.close()
    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND usename='map_user_bootstrap'")
    stop_blocker(blocker)
    check('timeout_attempt_rejected_on_retry',launch(expected=1)['code']=='bootstrap_attempt_not_ready')
    retained=snapshot()
    operator('user-database-bootstrap-finalize.sql',expected=1)
    operator('user-database-bootstrap-quarantine.sql')
    check('timeout_objects_preserved_on_quarantine',snapshot()==retained)
    check('timeout_bootstrap_credential_revoked',sql("SELECT NOT rolcanlogin AND rolpassword IS NULL FROM pg_authid WHERE rolname='map_user_bootstrap'")=='t')
    cleanup()

    phase='genuine_partial_history_preservation'
    prepare('fault')
    lockenv['PGDATABASE']=database
    with lockfile.open('w') as out:
        blocker=subprocess.Popen(['psql','-XqAt','-c',"BEGIN; LOCK TABLE pg_catalog.pg_class IN SHARE MODE; SELECT pg_sleep(90)"],env=lockenv,stdout=out,stderr=out,start_new_session=True)
    processes.append(blocker)
    for _ in range(100):
        if sql("SELECT count(*) FROM pg_locks WHERE relation='pg_class'::regclass AND mode='ShareLock' AND granted")=='1': break
        time.sleep(.05)
    out=(PRIVATE/'partial-java.out').open('w')
    proc=subprocess.Popen(java_command('map.bootstrap.UserBootstrapApplication')+['bootstrap'],env=bootstrap_environment(),stdout=out,stderr=out,start_new_session=True)
    processes.append(proc)
    consumed=False
    for _ in range(100):
        consumed=sql("SELECT obj_description(oid,'pg_namespace') LIKE '%:started' FROM pg_namespace WHERE nspname='user_service'")=='t'
        if consumed or proc.poll() is not None: break
        time.sleep(.05)
    require(consumed,'partial_marker_consumed')
    # Operator-only fault injected AFTER pristine guards, before history DDL is released.
    # Flyway really commits V001-V003 and V004 really fails; no history writes by harness.
    sql("CREATE FUNCTION public.synthetic_bootstrap_fault() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN IF current_query() LIKE '%CREATE TABLE users%' THEN RAISE EXCEPTION 'synthetic V004 failure'; END IF; END $$; CREATE EVENT TRIGGER synthetic_bootstrap_fault ON ddl_command_start WHEN TAG IN ('CREATE TABLE') EXECUTE FUNCTION public.synthetic_bootstrap_fault()")
    stop_blocker(blocker)
    proc.wait(timeout=90); out.close()
    check('genuine_v004_failure',proc.returncode==1)
    check('genuine_partial_v001_v003_retained',sql("SELECT count(*)=3 AND bool_and(success) AND max(version::integer)=3 FROM user_service.flyway_schema_history")=='t')
    partial_history=sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY installed_rank)) FROM user_service.flyway_schema_history t")
    retained=snapshot()
    check('partial_retry_rejected',launch(expected=1)['code']=='bootstrap_attempt_not_ready')
    operator('user-database-bootstrap-finalize.sql',expected=1)
    operator('user-database-bootstrap-quarantine.sql')
    check('partial_objects_preserved',snapshot()==retained)
    check('partial_history_preserved',sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY installed_rank)) FROM user_service.flyway_schema_history t")==partial_history)
    cleanup()
    report['success']=True
except Exception as error:
    report['failed_phase']=phase
    report['failure']=str(error) if isinstance(error,Failure) else type(error).__name__
finally:
    for proc in processes:
        if proc.poll() is None:
            os.killpg(proc.pid,signal.SIGTERM)
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL); proc.wait()
    report['ended_utc']=dt.datetime.now(dt.timezone.utc).isoformat()
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    REPORT.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if not report['success']: raise SystemExit(1)
