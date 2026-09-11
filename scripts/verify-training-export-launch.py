"""Launch the exact built exporter JAR against a newly-created synthetic H2 source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--java", default="java")
java = parser.parse_args().java
root = Path(__file__).resolve().parents[1]
jar = next((root / "build/libs").glob("*-training-export.jar"))
h2 = next(path for path in (Path.home() / ".gradle/caches/modules-2/files-2.1/com.h2database/h2").glob("*/*/h2-*.jar") if not path.name.endswith(("-sources.jar", "-javadoc.jar")))
private = Path(tempfile.mkdtemp(prefix="map-export-jar-smoke-20260906-"))
output = private / "output"
output.mkdir(mode=0o700)
url = "jdbc:h2:" + str(private / "source") + ";MODE=PostgreSQL"
ddl = """CREATE SCHEMA user_service; CREATE TABLE user_service.schedules (
schedule_id BIGINT, user_id BIGINT, job_id UUID, title VARCHAR, date_start DATE,
date_end DATE, payload JSON, transport VARCHAR, active_start_hour INT, active_end_hour INT,
created_at TIMESTAMP WITH TIME ZONE, started_at TIMESTAMP WITH TIME ZONE, province VARCHAR,
city VARCHAR, weather_baseline JSON, weather_alert JSON, weather_checked_at TIMESTAMP WITH TIME ZONE,
deleted_at TIMESTAMP WITH TIME ZONE);"""
def run(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, timeout=60, **kwargs)
fixture = run([java, "-cp", str(h2), "org.h2.tools.Shell", "-url", url, "-user", "sa", "-password", "", "-sql", ddl])
if fixture.returncode: raise SystemExit("synthetic fixture failed")
env = {key:os.environ[key] for key in ("PATH", "JAVA_HOME") if key in os.environ}
env.update({"SPRING_DATASOURCE_URL":url + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "SPRING_DATASOURCE_USERNAME":"sa",
            "SPRING_DATASOURCE_PASSWORD":"", "SPRING_DATASOURCE_DRIVER_CLASS_NAME":"org.h2.Driver",
            "SPRING_JPA_HIBERNATE_DDL_AUTO":"none", "SPRING_JPA_DATABASE_PLATFORM":"org.hibernate.dialect.H2Dialect",
            "LOCATION_CRYPTO_ENABLED":"false", "TRAINING_CAPTURE_ENABLED":"false", "TESTER_SEED_ENABLED":"false",
            "TRAINING_EXPORT_ENABLED":"true", "TRAINING_EXPORT_APPROVED":"true",
            "TRAINING_EXPORT_USER_REF_SALT":"synthetic-artifact-smoke", "TRAINING_EXPORT_OUTPUT_DIR":str(output)})
command = [java, "-Dloader.path=" + str(h2), "-cp", str(jar), "org.springframework.boot.loader.launch.PropertiesLauncher"]
result = run(command, env=env)
checks = {"exit_zero":result.returncode == 0, "no_http_server": "Tomcat started" not in result.stdout,
          "no_stream_consumer": "StreamsConsumerConfig" not in result.stdout, "no_flyway": "Migrating schema" not in result.stdout}
files = list(output.glob("*.manifest.json"))
checks["one_manifest"] = len(files) == 1
if files:
    manifest = json.loads(files[0].read_text())
    data = output / manifest["artifact"]
    checks["checksum_matches"] = hashlib.sha256(data.read_bytes()).hexdigest() == manifest["sha256"]
    checks["empty_synthetic_source"] = manifest["rows"] == 0
    checks["private_artifact"] = data.stat().st_mode & 0o777 == 0o600
    checks["private_manifest"] = files[0].stat().st_mode & 0o777 == 0o600
hold_env = {**env, "TRAINING_EXPORT_APPROVED":"false", "SPRING_DATASOURCE_URL":"jdbc:postgresql://127.0.0.1:1/never-connect", "SPRING_DATASOURCE_DRIVER_CLASS_NAME":"org.postgresql.Driver"}
hold = run(command, env=hold_env)
checks["hold_rejects_before_source_pool"] = hold.returncode != 0 and "training export approval HOLD" in hold.stdout + hold.stderr and "HikariPool" not in hold.stdout + hold.stderr
report = {"scope":"local exact exporter JAR; synthetic read-only H2; no GCP/user data", "jar":jar.name,
          "sha256":hashlib.sha256(jar.read_bytes()).hexdigest(), "checks":checks, "overall":all(checks.values())}
print(json.dumps(report, indent=2))
if not report["overall"]:
    # This scope contains synthetic identifiers only, but avoid printing DB connection strings.
    (private / "failure.log").write_text(result.stdout + result.stderr)
    raise SystemExit(1)
