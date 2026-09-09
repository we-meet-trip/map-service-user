#!/usr/bin/env python3
"""One-use NCP PROD User bootstrap controller; no serving or normal migrations.

Run from the reviewed User source checkout with the reviewed Infra checkout.
Only the receiver's private, pinned request is accepted. A durable attempt makes
any interrupted/uncertain result HOLD; this command never resets or retries a DB.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
STATE = Path('/srv/map-prod/deploy')
SECRETS = Path('/srv/map-prod/secrets')
ENV = {'PATH': '/usr/bin:/bin:/usr/local/bin', 'HOME': '/var/empty',
       'DOCKER_HOST': 'unix:///var/run/docker.sock', 'DOCKER_CONFIG': '/var/empty'}
HEX = re.compile(r'[a-f0-9]{64}')
IDENTITIES = ('environment', 'project', 'database', 'enrollment_sha256', 'security_approval_sha256',
              'release_manifest_sha256', 'runtime_contract_sha256', 'new_host_proof_sha256',
              'user', 'postgres', 'marker_sha256')
SCRAM_HOST_GUARD = ("SELECT NOT EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE error IS NOT NULL OR "
                    "(type LIKE 'host%' AND auth_method<>'scram-sha-256' AND NOT "
                    "((address='127.0.0.1' AND netmask='255.255.255.255') OR "
                    "(address='::1' AND netmask='ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'))));")


class BootstrapError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise BootstrapError(code)


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def secure_path(path, *, private=False):
    require(path.is_absolute(), 'absolute_path_required')
    for part in (path, *path.parents):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'root_owned_path_required')
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and
            (not private or stat.S_IMODE(info.st_mode) == 0o600), 'private_file_required')
    return path


def read_json(path):
    path = secure_path(path, private=True)
    require(path.stat().st_size <= 262144, 'input_size_invalid')
    return json.loads(path.read_text())


def command(args, *, payload=None, timeout=30, cwd=ROOT):
    # Child output may contain SQL/credentials. Never include it in errors/evidence.
    try:
        proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, env=ENV, cwd=cwd,
                                start_new_session=True)
        try:
            stdout, stderr = proc.communicate(payload, timeout=timeout)
        except BaseException:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL); proc.communicate()
            raise
    except (OSError, subprocess.TimeoutExpired):
        raise BootstrapError('bootstrap_command_unavailable') from None
    require(proc.returncode == 0, 'bootstrap_command_failed')
    return stdout.strip()


def checkout(path, expected):
    require(isinstance(expected, str) and re.fullmatch(r'[a-f0-9]{40}', expected), 'source_pin_invalid')
    require(path in (Path('/opt/map-service-user'), Path('/opt/map-service-infra'))
            and (path / '.git').is_dir() and not (path / '.git').is_symlink(), 'standalone_checkout_required')
    # Validate Git metadata and all importable files before invoking Git/loading
    # Infra Python; a root-owned tracked file alone does not make .git trustworthy.
    for parent in (path, *path.parents):
        info = parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'root_source_ownership_required')
    for directory, dirs, files in os.walk(path, followlinks=False):
        for name in dirs + files:
            info = (Path(directory) / name).lstat()
            require((stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)) and info.st_uid == 0
                    and not info.st_mode & 0o022 and (not stat.S_ISREG(info.st_mode) or info.st_nlink == 1),
                    'root_source_ownership_required')
    require(not (path / '.git/commondir').exists() and not (path / '.git/objects/info/alternates').exists(),
            'standalone_checkout_required')
    git = ['git', '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null']
    require(command(git + ['rev-parse', '--show-toplevel'], cwd=path) == str(path)
            and command(git + ['rev-parse', 'HEAD'], cwd=path) == expected
            and not command(git + ['status', '--porcelain', '--untracked-files=no'], cwd=path),
            'source_checkout_mismatch')
    for name in command(git + ['ls-files'], cwd=path).splitlines():
        secure_path(path / name)


def load_receiver(infra_root, request):
    require(infra_root == Path('/opt/map-service-infra'), 'reviewed_infra_path_required')
    config = read_json(SECRETS / 'production-runtime.json')
    name = config.get('release_name')
    require(isinstance(name, str) and re.fullmatch(r'[a-z][a-z0-9-]{1,63}', name), 'release_name_invalid')
    release = read_json(Path('/srv/map-prod/installations') / name / 'release.json')
    require(hashlib.sha256((Path('/srv/map-prod/installations') / name / 'release.json').read_bytes()).hexdigest()
            == request['release_manifest_sha256'], 'release_pin_mismatch')
    checkout(ROOT, request['user']['source_sha'])
    checkout(infra_root, release['infra_sha'])
    path = secure_path(infra_root / 'scripts/ncp-production-receiver.py')
    sys.path.insert(0, str(infra_root / 'scripts'))
    spec = importlib.util.spec_from_file_location('ncp_user_receiver', path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module, module.configuration(config)


def verify_request(receiver, config, request, proof, backend):
    enrollment, contract, manifest = backend.verify(config)
    expected = receiver.identity_request(config, enrollment, contract, manifest, proof,
                                         request['postgres']['container_id'])
    expected['created_at'] = request['created_at']
    require(request == expected, 'bootstrap_request_drift')
    receiver.private.utc(request['created_at'])
    require(receiver.private.utc(proof['observed_at']) <= receiver.private.utc(request['created_at'])
            <= datetime.now(timezone.utc), 'bootstrap_request_time_invalid')
    require(proof['environment'] == 'prod' and proof['project'] == 'map-prod'
            and proof['database'] == 'map_prod' and proof['machine_id'] == enrollment['machine_id']
            and proof['instance_id'] == enrollment['instance_id'] and proof['data_uuid'] == enrollment['data_uuid']
            and proof['enrollment_sha256'] == request['enrollment_sha256']
            and proof['runtime_contract_sha256'] == canonical(config)
            and proof['empty_docker_containers'] is True and proof['empty_docker_volumes'] is True
            and proof['new_data_path'] == '/srv/map-prod/data/postgres', 'new_host_proof_invalid')
    require(HEX.fullmatch(request['postgres']['container_id']) is not None, 'postgres_id_invalid')
    backend.postgres(request['postgres']['container_id'], request['postgres']['image_id'])
    require(backend.docker(['ps', '-aq', '--no-trunc']).splitlines() == [request['postgres']['container_id']]
            and not backend.docker(['volume', 'ls', '-q']), 'unknown_existing_resources')
    secrets = backend.secrets()
    require(hashlib.sha256(secrets['USER_BOOTSTRAP_MARKER'].encode()).hexdigest() == request['marker_sha256'],
            'bootstrap_marker_pin_mismatch')
    return secrets


def bootstrap_command():
    # Private stdin supplies two hex lines, never Docker Env/Cmd/host argv. env -i
    # strips image HOSTNAME/JAVA_VERSION/other variables before the strict main.
    script = ('set -eu; IFS= read -r USER_BOOTSTRAP_PASSWORD; IFS= read -r USER_BOOTSTRAP_MARKER; '
              'USER_BOOTSTRAP_URL=jdbc:postgresql://postgres:5432/map_prod; '
              'USER_BOOTSTRAP_USERNAME=map_user_bootstrap; USER_BOOTSTRAP_EXPECTED_DATABASE=map_prod; '
              'export USER_BOOTSTRAP_PASSWORD USER_BOOTSTRAP_MARKER USER_BOOTSTRAP_URL '
              'USER_BOOTSTRAP_USERNAME USER_BOOTSTRAP_EXPECTED_DATABASE; unset PWD OLDPWD SHLVL _; '
              'exec /usr/bin/env -u PWD -u OLDPWD -u SHLVL -u _ '
              'java -Xmx192m -Dloader.main=map.bootstrap.UserBootstrapApplication '
              '-cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher bootstrap')
    return ['--signal=TERM', '--kill-after=5s', '120s', '/usr/bin/env', '-i', 'PATH=/opt/java/openjdk/bin:/usr/bin:/bin',
            '/bin/sh', '-c', script]


class Controller:
    def __init__(self, receiver, backend, request, secrets):
        self.receiver, self.backend, self.request, self.secrets = receiver, backend, request, secrets
        self.job_id = None
        self.prepared = False
        self.request_sha = canonical(request)

    def postgres(self):
        self.backend.postgres(self.request['postgres']['container_id'], self.request['postgres']['image_id'])

    def sql(self, text, *, role='postgres'):
        args = ['docker', 'exec', '-i', self.request['postgres']['container_id']]
        if role == 'postgres':
            args += ['psql', '-XqAt', '-U', 'postgres', '-d', 'map_prod', '-v', 'ON_ERROR_STOP=1']
        else:
            require(role in ('map_user_runtime', 'map_user_migrator'), 'role_invalid')
            key = 'USER_DATABASE_PASSWORD' if role == 'map_user_runtime' else 'USER_MIGRATION_PASSWORD'
            # TCP + private password stdin tests actual SCRAM authentication, not local trust.
            args += ['/bin/sh', '-c', 'IFS= read -r PGPASSWORD; export PGPASSWORD; '
                     'exec psql -XqAt -h postgres -U ' + role + ' -d map_prod -v ON_ERROR_STOP=1']
            text = self.secrets[key] + '\n' + text
        return command(args, payload=text, timeout=45)

    def operator(self, filename):
        values = {'expected_database': 'map_prod', 'marker': self.secrets['USER_BOOTSTRAP_MARKER'],
                  'bootstrap_password': self.secrets['USER_BOOTSTRAP_PASSWORD'],
                  'runtime_password': self.secrets['USER_DATABASE_PASSWORD'],
                  'migrator_password': self.secrets['USER_MIGRATION_PASSWORD']}
        text = ''.join('\\set ' + key + ' ' + value + '\n' for key, value in values.items())
        return self.sql(text + (ROOT / 'docs' / filename).read_text())

    def job_state(self):
        actual = json.loads(self.backend.docker(['inspect', '--format',
            '{"id":{{json .Id}},"image":{{json .Image}},"running":{{json .State.Running}},'
            '"exit_code":{{json .State.ExitCode}},"oom_killed":{{json .State.OOMKilled}},'
            '"labels":{{json .Config.Labels}},"user":{{json .Config.User}},'
            '"cmd":{{json .Config.Cmd}},"entrypoint":{{json .Config.Entrypoint}}}', self.job_id]))
        require(actual['id'] == self.job_id and actual['image'] == self.request['user']['image_id']
                and actual['user'] == '10001:10001' and actual['cmd'] == bootstrap_command()
                and actual['entrypoint'] == ['/usr/bin/timeout']
                and actual['labels'].get('kr.mapservice.bootstrap-request') == self.request_sha,
                'bootstrap_job_identity_drift')
        return actual

    def stop_job(self):
        if self.job_id is None:
            return
        if self.job_state()['running']:
            self.backend.docker(['stop', '--time', '5', self.job_id], timeout=15)
        require(self.job_state()['running'] is False, 'bootstrap_job_not_stopped')
        self.backend.docker(['rm', self.job_id])
        self.job_id = None

    def launch(self):
        image = self.request['user']['image']
        args = ['create', '--pull=never', '--interactive', '--name', 'map-prod-user-bootstrap-' + self.request_sha[:16],
                '--label', 'kr.mapservice.bootstrap-request=' + self.request_sha,
                '--network', self.request['postgres']['network'], '--restart=no', '--no-healthcheck',
                '--read-only', '--user', '10001:10001', '--cap-drop=ALL',
                '--security-opt=no-new-privileges:true', '--pids-limit=64', '--memory=384m', '--cpus=1',
                '--log-driver=none', '--tmpfs', '/tmp:rw,noexec,nosuid,size=33554432',
                '--entrypoint', '/usr/bin/timeout', image, *bootstrap_command()]
        cid = self.backend.docker(args)
        require(HEX.fullmatch(cid) is not None, 'bootstrap_job_id_invalid')
        self.job_id = cid
        self.receiver.write_once(STATE / 'user-bootstrap-job.json',
                                 {'container_id': cid, 'input_request_sha256': self.request_sha})
        self.job_state()
        output = command(['docker', 'start', '-ai', cid], payload=self.secrets['USER_BOOTSTRAP_PASSWORD'] + '\n'
                         + self.secrets['USER_BOOTSTRAP_MARKER'] + '\n', timeout=135)
        require(json.loads(output) == {'status': 'bootstrap_complete', 'migrations_executed': 4,
                                      'operator_finalization_required': True}, 'bootstrap_completion_invalid')
        state = self.job_state()
        require(state['running'] is False and state['exit_code'] == 0 and state['oom_killed'] is False,
                'bootstrap_job_completion_not_confirmed')
        self.stop_job()

    def quarantine(self):
        try:
            self.stop_job()
            # A rejected/uncertain prepare does not establish ownership of any
            # existing role. Preserve it for manual target/transaction review.
            if not self.prepared:
                return 'manual_required'
            self.postgres()
            self.operator('user-database-bootstrap-quarantine.sql')
            # Both the held guard connection and Flyway connections belong to
            # this exact role/DB. Capture start time too: PostgreSQL PIDs can be
            # reused between inventory and termination. Never kill unknown apps.
            scope = ("datname=current_database() AND usename='map_user_bootstrap' AND "
                     "application_name IN ('map-user-bootstrap','map-user-privilege-check')")
            sessions = json.loads(self.sql("SELECT COALESCE(json_agg(json_build_object('pid',pid,"
                "'started_us',(extract(epoch FROM backend_start)*1000000)::bigint)),'[]'::json) "
                "FROM pg_stat_activity WHERE " + scope + ';'))
            require(isinstance(sessions, list) and len(sessions) <= 3, 'bootstrap_session_inventory_invalid')
            for session in sessions:
                require(isinstance(session, dict) and set(session) == {'pid', 'started_us'}
                        and type(session['pid']) is int and session['pid'] > 0
                        and type(session['started_us']) is int and session['started_us'] > 0,
                        'bootstrap_session_inventory_invalid')
                self.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE " + scope
                         + ' AND pid=' + str(session['pid'])
                         + ' AND (extract(epoch FROM backend_start)*1000000)::bigint='
                         + str(session['started_us']) + ';')
            require(self.sql("SELECT NOT rolcanlogin AND rolpassword IS NULL AND NOT EXISTS "
                             "(SELECT 1 FROM pg_stat_activity WHERE usename='map_user_bootstrap') "
                             "FROM pg_authid WHERE rolname='map_user_bootstrap';") == 't', 'quarantine_incomplete')
            return 'confirmed'
        except Exception:
            return 'manual_required'

    def execute(self):
        self.operator('user-database-bootstrap-prepare.sql')
        self.prepared = True
        self.launch(); self.postgres()
        self.operator('user-database-bootstrap-finalize.sql')
        # initdb can trust loopback even when Docker appends a SCRAM host rule.
        # Authenticate through the pinned bridge alias, and forbid non-SCRAM
        # host rules except exact loopback addresses before enabling final roles.
        require(self.sql(SCRAM_HOST_GUARD) == 't',
                'nonloopback_scram_authentication_required')
        self.operator('user-database-bootstrap-activate.sql')
        network_auth = ("inet_server_addr() IS NOT NULL AND "
                        "NOT (inet_server_addr()<<inet'127.0.0.0/8') AND inet_server_addr()<>inet'::1' AND ")
        require(self.sql("SELECT current_user='map_user_runtime' AND session_user=current_user AND "
                         + network_auth +
                         "has_database_privilege(current_database(),'CONNECT') AND "
                         "NOT has_database_privilege(current_database(),'CREATE,TEMP') AND "
                         "has_table_privilege('user_service.users','SELECT') AND "
                         "has_table_privilege('user_service.users','INSERT') AND "
                         "has_table_privilege('user_service.users','UPDATE') AND "
                         "has_table_privilege('user_service.users','DELETE') AND "
                         "NOT has_table_privilege('user_service.flyway_schema_history','INSERT,UPDATE,DELETE');",
                         role='map_user_runtime') == 't', 'runtime_authentication_not_ready')
        require(self.sql("SET ROLE map_user_owner; SELECT current_user='map_user_owner' "
                         "AND session_user='map_user_migrator' AND " + network_auth +
                         "NOT has_database_privilege(current_database(),'CREATE,TEMP');",
                         role='map_user_migrator') == 't', 'migrator_authentication_not_ready')
        require(self.sql("SELECT NOT rolcanlogin AND rolpassword IS NULL AND NOT EXISTS "
                         "(SELECT 1 FROM pg_stat_activity WHERE usename='map_user_bootstrap') "
                         "FROM pg_authid WHERE rolname='map_user_bootstrap';") == 't', 'bootstrap_login_not_revoked')
        self.postgres()


def run(infra_root):
    require(os.geteuid() == 0 and sys.platform == 'linux', 'ncp_root_linux_required')
    request = read_json(STATE / 'bootstrap-request.json')
    require(request.get('environment') == 'prod' and request.get('project') == 'map-prod'
            and request.get('database') == 'map_prod', 'ncp_production_only')
    receiver, config = load_receiver(infra_root, request)
    backend = receiver.Backend()
    with receiver.writer(STATE):
        require(read_json(STATE / 'bootstrap-request.json') == request, 'request_changed_before_lock')
        proof = read_json(STATE / 'new-host-proof.json')
        secrets = verify_request(receiver, config, request, proof, backend)
        for path in (STATE / 'user-bootstrap-attempt.json', STATE / 'bootstrap-receipt.json',
                     STATE / 'user-bootstrap-job.json', SECRETS / 'user-migration.env'):
            require(not path.exists() and not path.is_symlink(), 'prior_attempt_requires_hold')
        started = now()
        receiver.write_once(STATE / 'user-bootstrap-attempt.json',
                            {'status': 'HOLD', 'input_request_sha256': canonical(request),
                             'started_at': started, 'automatic_retry_permitted': False})
        controller = Controller(receiver, backend, request, secrets)
        try:
            controller.execute()
            migration_env = ('USER_MIGRATION_URL=jdbc:postgresql://postgres:5432/map_prod?currentSchema=user_service\n'
                             'USER_MIGRATION_USERNAME=map_user_migrator\n'
                             'USER_MIGRATION_PASSWORD=' + secrets['USER_MIGRATION_PASSWORD'] + '\n')
            receiver.host.write_once(SECRETS / 'user-migration.env', migration_env.encode())
            password_path = secure_path(SECRETS / 'USER_BOOTSTRAP_PASSWORD', private=True)
            require(password_path.read_text().removesuffix('\n') == secrets['USER_BOOTSTRAP_PASSWORD'],
                    'bootstrap_secret_changed')
            password_path.unlink()  # Only the consumed one-use secret; marker retained privately.
            receipt = {'schema_version': 1, 'status': 'PASS', 'input_request_sha256': canonical(request),
                       'identities': {key: request[key] for key in IDENTITIES}, 'migrations_executed': 4,
                       'finalization_complete': True, 'bootstrap_login_disabled': True,
                       'bootstrap_sessions_zero': True, 'runtime_and_migrator_roles_ready': True,
                       'postgres_identity_preserved': True, 'started_at': started, 'completed_at': now()}
            receiver.verify_bootstrap_receipt(receipt, request)
            receiver.write_once(STATE / 'bootstrap-receipt.json', receipt)
            return {'status': 'PASS', 'input_request_sha256': canonical(request),
                    'bootstrap_receipt_sha256': canonical(receipt), 'public_serving': 'HOLD',
                    'normal_service_migrations_required': True}
        except BaseException:
            quarantine = controller.quarantine()
            receiver.write_once(STATE / 'user-bootstrap-failure.json',
                                {'status': 'HOLD', 'input_request_sha256': canonical(request),
                                 'quarantine': quarantine, 'automatic_retry_permitted': False, 'observed_at': now()})
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--infra-root', required=True, type=Path)
    args = parser.parse_args(argv)
    os.umask(0o077)
    def interrupted(signum, frame):
        raise BootstrapError('bootstrap_interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        result = run(args.infra_root)
    except BaseException as error:
        result = {'status': 'HOLD', 'public_serving': 'HOLD', 'automatic_retry_permitted': False,
                  'error_code': str(error) if isinstance(error, BootstrapError) else 'bootstrap_guard_failed'}
        print(json.dumps(result, sort_keys=True)); return 1
    print(json.dumps(result, sort_keys=True)); return 0


if __name__ == '__main__':
    raise SystemExit(main())
