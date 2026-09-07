"""CI only: launch exact serving bootJar's isolated migrator, without a DB connection."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
jars = [path for path in (root / "build/libs").glob("*.jar") if not path.name.endswith("-training-export.jar")]
if len(jars) != 1:
    raise SystemExit("expected one exact serving bootJar")
jar = jars[0]
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    checks = {
        "properties_launcher_packaged": "org/springframework/boot/loader/launch/PropertiesLauncher.class" in names,
        "standalone_main_packaged": "BOOT-INF/classes/map/migration/UserMigrationApplication.class" in names,
        "postgresql_driver_packaged": any(name.startswith("BOOT-INF/lib/postgresql-") for name in names),
        "test_h2_driver_absent": not any(name.startswith("BOOT-INF/lib/h2-") for name in names),
        "normal_entrypoint_preserved": "Start-Class: map.service.user.ServiceUserApplication" in archive.read("META-INF/MANIFEST.MF").decode(),
    }

environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "HOME") if key in os.environ}
environment.update({"USER_MIGRATION_URL": "jdbc:postgresql://127.0.0.1:1/synthetic?currentSchema=user_service",
                    "USER_MIGRATION_USERNAME": "map_user_migrator", "USER_MIGRATION_PASSWORD": "synthetic-no-database"})
command = ["java", "-Dloader.main=map.migration.UserMigrationApplication", "-cp", str(jar),
           "org.springframework.boot.loader.launch.PropertiesLauncher"]

def launch(args, env):
    result = subprocess.run(command + args, env=env, capture_output=True, text=True, timeout=20)
    # Output equality also rejects Spring/Flyway banners, app bean startup logs and connection traces.
    return result.returncode, result.stdout.strip(), result.stderr.strip()

exit_code, stdout, stderr = launch(["check-config"], environment)
checks["exact_main_config_only_no_database"] = (exit_code, stdout, stderr) == (
    0, '{"status":"configuration_valid","database_connected":false}', "")
for flag in ("TRAINING_EXPORT_ENABLED", "REDIS_HOST", "LOCATION_CRYPTO_KEYS", "SPRING_DATASOURCE_PASSWORD",
             "USER_DATABASE_PASSWORD", "USER_ADMIN_INTERNAL_TOKEN", "TESTER_SEED_ENABLED"):
    exit_code, stdout, stderr = launch(["migrate"], {**environment, flag: "synthetic-do-not-echo"})
    checks["reject_leak_" + flag.lower()] = (exit_code, stdout, stderr) == (2, '{"status":"configuration_rejected"}', "")
for operation in ("clean", "repair", "baseline"):
    exit_code, stdout, stderr = launch([operation], environment)
    checks["reject_" + operation] = (exit_code, stdout, stderr) == (2, '{"status":"configuration_rejected"}', "")

report = {"scope": "CI exact bootJar no-DB launcher/configuration checks; no PostgreSQL ACL or migration proof",
          "jar": jar.name, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "checks": checks,
          "overall": all(checks.values())}
print(json.dumps(report, indent=2))
(root / "build/user-migration-launch-verification.json").write_text(json.dumps(report, indent=2) + "\n")
if not report["overall"]:
    raise SystemExit(1)
