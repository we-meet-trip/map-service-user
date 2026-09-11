"""Exact bootJar isolated bootstrap configuration, no PostgreSQL or provider calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
jar = next(path for path in (ROOT / 'build/libs').glob('*.jar') if not path.name.endswith('-training-export.jar'))
with zipfile.ZipFile(jar) as archive:
    checks = {'bootstrap_main_packaged': 'BOOT-INF/classes/map/bootstrap/UserBootstrapApplication.class' in archive.namelist(),
              'serving_main_preserved': 'Start-Class: map.service.user.ServiceUserApplication' in archive.read('META-INF/MANIFEST.MF').decode()}
environment = {key: os.environ[key] for key in ('PATH', 'HOME', 'JAVA_HOME') if key in os.environ}
environment.update(USER_BOOTSTRAP_URL='jdbc:postgresql://127.0.0.1:1/synthetic', USER_BOOTSTRAP_USERNAME='map_user_bootstrap',
                   USER_BOOTSTRAP_PASSWORD='synthetic-unused', USER_BOOTSTRAP_EXPECTED_DATABASE='synthetic', USER_BOOTSTRAP_MARKER='a'*64)
command = ['java', '-Dloader.main=map.bootstrap.UserBootstrapApplication', '-cp', str(jar),
           'org.springframework.boot.loader.launch.PropertiesLauncher']
def launch(operation, env):
    result = subprocess.run(command + [operation], env=env, capture_output=True, text=True, timeout=20)
    return result.returncode, result.stdout.strip(), result.stderr.strip()
checks['config_only'] = launch('check-config', environment) == (0, '{"status":"configuration_valid","database_connected":false}', '')
for key in ('spring.datasource.url', 'SPRING.DATASOURCE.URL', 'spring_datasource_url', 'SPRING_DATASOURCE_URL',
            'USER_MIGRATION_PASSWORD', 'REDIS_HOST', 'GEMINI_API_KEY', 'USER_BOOTSTRAP_TARGET'):
    checks['reject_' + key] = launch('bootstrap', {**environment, key: 'must-not-echo'}) == (2, '{"status":"configuration_rejected"}', '')
for operation in ('migrate', 'clean', 'repair', 'baseline'):
    checks['reject_' + operation] = launch(operation, environment) == (2, '{"status":"configuration_rejected"}', '')
report = {'scope': 'exact bootJar configuration only; no DB connection', 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
          'checks': checks, 'overall': all(checks.values())}
(ROOT / 'build/user-bootstrap-launch-verification.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
if not report['overall']: raise SystemExit(1)
