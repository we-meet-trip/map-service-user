#!/usr/bin/env python3
"""GitHub-hosted-only destructive *synthetic fixture* checks. Never use against an existing DB.

Only redacted booleans, checksums, source/image identities and SQLSTATEs leave this process.
JVM logs, generated credentials, JWTs, fixture bodies and syscall traces stay private on the runner.
"""
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
DB = "user_runtime_fixture"
EMPTY = "user_runtime_empty_fixture"
LEGACY_SHA = "1d4f394b51f471319eb9403f6e926a8d0a511c72"
PORT = 18089
BASE = f"http://127.0.0.1:{PORT}"
REPORT = ROOT / "build/user-postgres-isolation-verification.json"
PRIVATE = Path(tempfile.mkdtemp(prefix="map-user-pg-ci-"))
os.chmod(PRIVATE, 0o700)
os.umask(0o077)
checks = []
processes = []
phase = "environment_gate"
result = {"scope": "GitHub hosted PG17/Redis disposable synthetic services; no GCP or local DB",
          "legacy_source": LEGACY_SHA, "candidate_source": os.environ.get("GITHUB_SHA"),
          "checks": checks, "success": False}


class FixtureFailure(Exception):
    pass


def require(value, name):
    if not value:
        raise FixtureFailure(name)


def check(name, value=True):
    require(value, name)
    checks.append({"name": name, "pass": True})


def execute(args, *, env=None, text=None, timeout=90):
    process = subprocess.Popen(args, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, start_new_session=True)
    try:
        stdout, stderr = process.communicate(text, timeout=timeout)
    except BaseException:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.communicate(timeout=5)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()
        raise
    return subprocess.CompletedProcess(args, process.returncode, stdout, stderr)


def command(args, **kwargs):
    run = execute(args, **kwargs)
    require(run.returncode == 0, "fixture_command_failed")
    return run.stdout.strip()


def os_environment():
    return {key: os.environ[key] for key in ("PATH", "HOME", "JAVA_HOME", "LANG") if key in os.environ}


def sql(statement, *, role="postgres", database=DB, expect_state=None):
    env = {**os_environment(), "PGHOST": "127.0.0.1", "PGPORT": "5432", "PGDATABASE": database,
           "PGUSER": role, "PGPASSWORD": passwords[role]}
    run = execute(["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate"],
                  env=env, text=statement, timeout=30)
    if expect_state is not None:
        require(run.returncode != 0 and re.search(r"\b" + expect_state + r"\b", run.stderr),
                "expected_sqlstate_" + expect_state)
        return expect_state
    if run.returncode:
        state = re.search(r"ERROR:\s+([0-9A-Z]{5})\b", run.stderr)
        result["sql_failure_state"] = state.group(1) if state else "unclassified"
        result["sql_failure_statement_sha256"] = hashlib.sha256(statement.encode()).hexdigest()
        raise FixtureFailure("fixture_sql_failed")
    return run.stdout.strip()


def redis(*args):
    return command(["redis-cli", "-h", "127.0.0.1", "-p", "6379", "--raw", *args])


def redis_fingerprint():
    rows = []
    for key in sorted(redis("KEYS", "*").splitlines()):
        blob = subprocess.check_output(["redis-cli", "-h", "127.0.0.1", "-p", "6379", "--raw", "DUMP", key])
        rows.append(hashlib.sha256(key.encode() + b"\0" + blob).hexdigest())
    return hashlib.sha256("\n".join(rows).encode()).hexdigest()


def fingerprint():
    return {table: sql("SELECT md5(COALESCE(string_agg(row_to_json(t)::text,'' ORDER BY " + key + "),'')) "
                       "FROM user_service." + table + " t")
            for table, key in (("users", "id"), ("schedules", "schedule_id"),
                               ("chat_messages", "id"), ("moderation_reports", "report_id"),
                               ("service_policy_acceptances", "user_id"))}


def stop(app):
    if app.poll() is None:
        os.killpg(app.pid, signal.SIGTERM)
        try:
            app.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(app.pid, signal.SIGKILL)
            app.wait(timeout=10)


def api(method, path, body=None, token=None, status=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                    data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            code, raw = response.status, response.read(1_000_000)
    except urllib.error.HTTPError as response:
        code, raw = response.code, response.read(1_000_000)
    require(code == status, "http_status_" + str(status) + "_received_" + str(code))
    return json.loads(raw) if raw else None


def launch(jar, environment, *, expected_guard=None, args=()):
    name = uuid.uuid4().hex
    log = PRIVATE / (name + ".log")
    before_redis = redis_fingerprint()
    with log.open("w") as stream:
        app = subprocess.Popen(["java", "-Xmx384m", "-jar", str(jar),
                                f"--server.port={PORT}", "--server.address=127.0.0.1", *args],
                               env=environment, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    processes.append(app)
    deadline = time.monotonic() + (60 if expected_guard else 120)
    while time.monotonic() < deadline:
        if app.poll() is not None:
            contents = log.read_text(errors="replace")
            if expected_guard:
                check("startup_rejected_" + expected_guard, app.returncode != 0 and expected_guard in contents)
                check("no_serving_before_" + expected_guard,
                      "Tomcat started" not in contents and "StreamsConsumerConfig" not in contents)
                check("redis_unchanged_before_" + expected_guard, redis_fingerprint() == before_redis)
                return app
            # Keep only controlled guard codes, never the original log/exception/body.
            codes = re.findall(r"\b(?:database_privilege_[a-z0-9_]+|runtime_[a-z0-9_]+|serving_[a-z0-9_]+)\b", contents)
            result["startup_guard_codes"] = sorted(set(codes))
            raise FixtureFailure("serving_exited_before_readiness")
        try:
            with urllib.request.urlopen(BASE + "/actuator/health", timeout=1) as response:
                if response.status == 200:
                    require(not expected_guard, "unsafe_serving_became_ready")
                    return app
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.25)
    stop(app)
    raise FixtureFailure("serving_readiness_timeout")


def migrate(operation="migrate", *, database=DB, expected=0, extra=None):
    env = {**os_environment(), "USER_MIGRATION_URL": f"jdbc:postgresql://127.0.0.1:5432/{database}?currentSchema=user_service",
           "USER_MIGRATION_USERNAME": "map_user_migrator", "USER_MIGRATION_PASSWORD": passwords["map_user_migrator"],
           **(extra or {})}
    trace = PRIVATE / (uuid.uuid4().hex + ".trace")
    before = redis_fingerprint()
    run = execute(["strace", "-f", "-qq", "-e", "trace=bind,listen,connect", "-o", str(trace),
                   "java", "-Xmx192m", "-Dloader.main=map.migration.UserMigrationApplication", "-cp", str(candidate),
                   "org.springframework.boot.loader.launch.PropertiesLauncher", operation], env=env, timeout=120)
    require(run.returncode == expected, "migrator_exit_expected_" + str(expected) + "_got_" + str(run.returncode))
    try:
        body = json.loads(run.stdout)
    except ValueError:
        raise FixtureFailure("migrator_output_not_bounded_json") from None
    require(not run.stderr.strip(), "migrator_unexpected_stderr")
    calls = trace.read_text()
    require("listen(" not in calls and "htons(6379)" not in calls, "migrator_started_serving_or_redis")
    require(redis_fingerprint() == before, "migrator_changed_redis")
    require("Spring" not in run.stdout and "Tomcat" not in run.stdout, "migrator_started_spring")
    return body


def consent(token):
    state = api("GET", "/api/v1/consents", token=token)
    accepted = api("POST", "/api/v1/consents", token=token, body={
        "terms_version": state["terms_version"], "privacy_version": state["privacy_version"],
        "is_18_or_older": True, "terms_accepted": True, "privacy_accepted": True})
    require(accepted["accepted"], "synthetic_consent_not_accepted")


def job(owner, *, role="postgres", stay=60):
    ident = str(uuid.uuid4())
    payload = {"job_id": ident, "status": "done", "places": [{"place_id": 1, "day": 1,
               "name": "Synthetic fixture place", "address": "Synthetic only", "lat": 37.5, "lng": 127.0,
               "visit_start": "09:00", "visit_end": "10:00", "stay_minutes": stay, "grounded": True}],
               "visit_order": [1], "legs": [], "timeline_status": "ok"}
    sql("INSERT INTO user_service.recommend_jobs(job_id,owner_user_id,status,result_payload,finished_at) VALUES "
        f"('{ident}',{int(owner)},'done','{json.dumps(payload)}'::jsonb,NOW())", role=role)
    return ident


def save_job(ident, token, title="Synthetic itinerary"):
    date = (dt.date.today() + dt.timedelta(days=2)).isoformat()
    return api("POST", "/api/v1/schedules", token=token, body={"job_id": ident, "title": title,
               "date_start": date, "date_end": date, "transport": "walk",
               "active_start_hour": 9, "active_end_hour": 18})["schedule_id"]


try:
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("CI") == "true"
            and os.environ.get("MAP_HOSTED_DATABASE_FIXTURE") == "true"
            and os.environ.get("PGHOST") == "127.0.0.1" and os.environ.get("PGPORT") == "5432"
            and os.environ.get("PGDATABASE") == DB and os.environ.get("PGUSER") == "postgres",
            "refuse_non_hosted_or_non_fixture_environment")
    passwords = {"postgres": os.environ["PGPASSWORD"], "map_user_runtime": secrets.token_hex(24),
                 "map_user_migrator": secrets.token_hex(24)}
    candidate = ROOT / "build/libs/service-user-0.0.1-SNAPSHOT.jar"
    legacy = ROOT / "legacy-source/build/libs/service-user-0.0.1-SNAPSHOT.jar"
    proof = json.loads((ROOT / "build/user-migration-launch-verification.json").read_text())
    check("candidate_exact_artifact", hashlib.sha256(candidate.read_bytes()).hexdigest() == proof["sha256"])
    check("legacy_exact_source", command(["git", "-C", str(ROOT / "legacy-source"), "rev-parse", "HEAD"]) == LEGACY_SHA)
    result.update(candidate_jar_sha256=proof["sha256"], legacy_jar_sha256=hashlib.sha256(legacy.read_bytes()).hexdigest())
    check("empty_disposable_database", sql("SELECT count(*) FROM pg_namespace WHERE nspname IN ('user_service','hub_data','admin_service')") == "0")
    result["postgres_version"] = sql("SHOW server_version")
    check("postgres17", sql("SHOW server_version_num").startswith("17"))
    result["postgres_image"] = "postgres@sha256:7456ef82e5f5bc43d997f4781bbd7c0d6389bff397564649a356e206ba473aee"
    result["redis_version"] = next(line.split(":", 1)[1] for line in redis("INFO", "server").splitlines() if line.startswith("redis_version:"))
    sql("COMMENT ON DATABASE " + DB + " IS 'map.synthetic.github-hosted.user-runtime-isolation';")
    command(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(PRIVATE / "jwt.pem")])
    command(["openssl", "pkey", "-in", str(PRIVATE / "jwt.pem"), "-pubout", "-out", str(PRIVATE / "jwt.pub")])
    serving = {**os_environment(), "POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": "5432", "POSTGRES_DB": DB,
        "REDIS_HOST": "127.0.0.1", "REDIS_PORT": "6379", "REDIS_PASSWORD": "", "AUTH_ENFORCED": "true",
        "TRAINING_CAPTURE_ENABLED": "false", "TRAINING_EXPORT_ENABLED": "false", "TESTER_SEED_ENABLED": "false",
        "APPLE_ENABLED": "false", "WEATHER_WATCH_ENABLED": "false", "REVIEWS_PREWARM_ENABLED": "false",
        "AGENT_BASE_URL": "http://127.0.0.1:1", "HUB_BASE_URL": "http://127.0.0.1:1",
        "INTERNAL_SERVICE_TOKEN": secrets.token_hex(24), "USER_ADMIN_INTERNAL_TOKEN": secrets.token_hex(24),
        "USER_INTERNAL_TRUSTED_CIDRS": "127.0.0.1/32", "JWT_PRIVATE_KEY": (PRIVATE / "jwt.pem").read_text(),
        "JWT_PUBLIC_KEY": (PRIVATE / "jwt.pub").read_text(), "LOCATION_ENC_ENABLED": "true",
        "LOCATION_ENC_ACTIVE_KID": "synthetic", "LOCATION_ENC_KEYS": "synthetic:" + base64.b64encode(os.urandom(32)).decode(),
        "LOCATION_WIRE_ENABLED": "true", "LOCATION_WIRE_KEY": base64.b64encode(os.urandom(32)).decode()}

    phase = "legacy_real_bootstrap_and_encrypted_seed"
    app = launch(legacy, {**serving, "POSTGRES_USER": "postgres", "POSTGRES_PASSWORD": passwords["postgres"]},
                 args=("--spring.flyway.target=27",))
    account_password = "Synthetic-" + secrets.token_hex(20) + "!9"
    email = uuid.uuid4().hex + "@map.test"
    account = api("POST", "/api/v1/auth/signup", body={"email": email, "password": account_password,
                  "nickname": "Synthetic owner"}, status=201)
    owner, token = account["user"]["id"], account["accessToken"]
    consent(token)
    schedule = save_job(job(owner), token)
    room = api("POST", "/api/v1/chat/rooms", token=token, body={"schedule_id": schedule}, status=201)["room_id"]
    message = api("POST", f"/api/v1/chat/rooms/{room}/messages", token=token,
                  body={"content": "Synthetic preserved message"}, status=201)
    report = api("POST", "/api/v1/moderation/reports", token=token, status=201,
                 body={"client_request_id": str(uuid.uuid4()), "content_type": "VISION", "reason": "OTHER",
                       "description": "Synthetic preserved encrypted description"})
    check("legacy_schedule_encrypted", sql("SELECT payload ? 'ct' FROM user_service.schedules") == "t")
    check("legacy_report_encrypted", sql("SELECT description::jsonb ? 'ct' FROM user_service.moderation_reports") == "t")
    check("genuine_v027_history_before_transition", sql("SELECT max(version::int) FROM user_service.flyway_schema_history WHERE success") == "27")
    stop(app)
    before = fingerprint()

    phase = "forward_role_provisioning"
    sql("CREATE SCHEMA hub_data; CREATE SCHEMA admin_service; "
        "CREATE TABLE hub_data.sentinel(id integer PRIMARY KEY,payload text); "
        "INSERT INTO hub_data.sentinel VALUES(1,'synthetic-other-service'); "
        "CREATE TABLE admin_service.sentinel(id integer PRIMARY KEY,payload text); "
        "INSERT INTO admin_service.sentinel VALUES(1,'synthetic-control-data');")
    foreign_before = sql("SELECT md5((SELECT row_to_json(t)::text FROM hub_data.sentinel t)||(SELECT row_to_json(t)::text FROM admin_service.sentinel t))")
    script = (ROOT / "docs/user-database-role-provisioning.sql").read_text()
    sql("\\set expected_old_owner postgres\n" + script)
    for role in ("map_user_runtime", "map_user_migrator"):
        sql(f"ALTER ROLE {role} LOGIN PASSWORD '{passwords[role]}';")
    # Explicitly scoped to this brand-new CI database. Production proposal never applies these globally.
    sql(f"REVOKE TEMP,CREATE ON DATABASE {DB} FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC; "
        "GRANT USAGE ON SCHEMA public,hub_data,admin_service TO map_user_runtime,map_user_owner;")
    check("role_transfer_preserves_all_synthetic_rows", fingerprint() == before)
    sql("\\set expected_old_owner postgres\n" + script)
    check("provisioning_repeat_preserves_rows", fingerprint() == before)

    phase = "actual_standalone_migration"
    check("real_migration_executes_only_pending_v028", migrate()["migrations_executed"] == 1)
    check("new_history_owned_by_dedicated_owner", sql("SELECT installed_by FROM user_service.flyway_schema_history WHERE version::int=28") == "map_user_owner")
    check("real_migration_noop", migrate()["migrations_executed"] == 0)
    check("real_migration_validate", migrate("validate")["status"] == "complete")
    check("migration_preserves_ciphertext_and_rows", fingerprint() == before)
    result["preserved_row_fingerprints"] = before
    check("migration_forbids_serving_environment", migrate(expected=2, extra={"REDIS_HOST": "127.0.0.1"})["status"] == "configuration_rejected")

    phase = "actual_postgresql_denials"
    denials = {
        "schema_ddl": "CREATE TABLE user_service.must_not_exist(id int)",
        "alter_table": "ALTER TABLE user_service.users ADD COLUMN must_not_exist int",
        "truncate": "TRUNCATE user_service.users CASCADE",
        "create_schema": "CREATE SCHEMA must_not_exist",
        "create_temp": "CREATE TEMP TABLE must_not_exist(id int)",
        "cross_schema_read": "SELECT payload FROM hub_data.sentinel",
        "cross_schema_write": "UPDATE hub_data.sentinel SET payload='must-not-write'",
        "control_schema_read": "SELECT payload FROM admin_service.sentinel",
        "history_write": "UPDATE user_service.flyway_schema_history SET description=description",
        "sequence_setval": "SELECT setval('user_service.users_id_seq',100000)",
        "set_owner_role": "SET ROLE map_user_owner",
        "grant_membership": "GRANT map_user_owner TO map_user_runtime",
        "alter_self_superuser": "ALTER ROLE map_user_runtime SUPERUSER",
    }
    for label, statement in denials.items():
        sql(statement, role="map_user_runtime", expect_state="42501")
        check("postgres_denied_" + label)
    check("migrator_has_no_inherited_serving_read", sql("SELECT * FROM user_service.users", role="map_user_migrator", expect_state="42501") == "42501")
    sql("SET ROLE map_user_owner; SELECT * FROM hub_data.sentinel", role="map_user_migrator", expect_state="42501")
    check("migrator_owner_cannot_read_other_service")
    sql("ALTER ROLE map_user_migrator SUPERUSER")
    check("migrator_rejects_superuser", migrate(expected=1)["status"] == "migration_failed")
    sql("ALTER ROLE map_user_migrator NOSUPERUSER")
    sql("GRANT map_user_owner TO map_user_migrator WITH INHERIT TRUE")
    check("migrator_rejects_inherited_owner", migrate(expected=1)["status"] == "migration_failed")
    sql("GRANT map_user_owner TO map_user_migrator WITH INHERIT FALSE")

    phase = "runtime_unsafe_privileges_fail_before_serving"
    runtime = {**serving, "USER_DATABASE_USER": "map_user_runtime", "USER_DATABASE_PASSWORD": passwords["map_user_runtime"]}
    launch(candidate, runtime, args=("--spring.flyway.enabled=true",), expected_guard="serving_flyway_forbidden")
    launch(candidate, {**runtime, "USER_DATABASE_USER": "postgres"}, expected_guard="runtime_role_required")
    for label, grant, revoke, code in (
        ("temporary", f"GRANT TEMP ON DATABASE {DB} TO map_user_runtime", f"REVOKE TEMP ON DATABASE {DB} FROM map_user_runtime", "database_create_or_temp"),
        ("public_create", "GRANT CREATE ON SCHEMA public TO PUBLIC", "REVOKE CREATE ON SCHEMA public FROM PUBLIC", "schema_create"),
        ("column_read", "GRANT SELECT(payload) ON hub_data.sentinel TO map_user_runtime", "REVOKE SELECT(payload) ON hub_data.sentinel FROM map_user_runtime", "cross_schema_data"),
    ):
        sql(grant)
        launch(candidate, runtime, expected_guard="database_privilege_" + code)
        sql(revoke)
        check("unsafe_grant_recovered_" + label)
    sql("CREATE FUNCTION hub_data.read_hidden() RETURNS text LANGUAGE sql SECURITY DEFINER "
        "SET search_path=pg_catalog,hub_data AS $$ SELECT payload FROM hub_data.sentinel WHERE id=1 $$; "
        "REVOKE ALL ON FUNCTION hub_data.read_hidden() FROM PUBLIC;")
    sql("SELECT hub_data.read_hidden()", role="map_user_runtime", expect_state="42501")
    check("postgres_denied_security_definer")
    sql("GRANT EXECUTE ON FUNCTION hub_data.read_hidden() TO map_user_runtime")
    check("security_definer_is_real_elevation", sql("SELECT hub_data.read_hidden()", role="map_user_runtime") == "synthetic-other-service")
    launch(candidate, runtime, expected_guard="database_privilege_security_definer")
    sql("REVOKE EXECUTE ON FUNCTION hub_data.read_hidden() FROM map_user_runtime")
    check("guard_failures_preserved_synthetic_rows", fingerprint() == before)

    phase = "runtime_real_http_encrypted_crud"
    app = launch(candidate, runtime)
    check("startup_preserves_existing_rows", fingerprint() == before)
    session = api("POST", "/api/v1/auth/login", body={"email": email, "password": account_password})
    token = session["accessToken"]
    check("runtime_login", session["user"]["id"] == owner)
    check("runtime_profile_update", api("PATCH", "/api/v1/users/me", token=token,
          body={"nickname": "Synthetic updated owner", "birthDate": "2000-01-01"})["nickname"] == "Synthetic updated owner")
    detail = api("GET", f"/api/v1/schedules/{schedule}", token=token)
    check("old_encrypted_schedule_decrypts", detail["stops"][0]["name"] == "Synthetic fixture place")
    history = api("GET", f"/api/v1/chat/rooms/{room}/messages?limit=100", token=token)
    check("old_chat_preserved", any(item["seq"] == message["seq"] for item in history["messages"]))
    check("old_report_receipt_preserved", any(item["report_id"] == report["report_id"] for item in api("GET", "/api/v1/moderation/reports", token=token)))
    new_schedule = save_job(job(owner, role="map_user_runtime"), token, "Synthetic new itinerary")
    revise = job(owner, role="map_user_runtime", stay=45)
    changed = api("PUT", f"/api/v1/schedules/{new_schedule}", token=token, body={"job_id": revise})
    check("runtime_schedule_update", changed["job_id"] == revise)
    check("new_schedule_still_encrypted", sql(f"SELECT payload ? 'ct' FROM user_service.schedules WHERE schedule_id={new_schedule}") == "t")
    api("DELETE", f"/api/v1/schedules/{new_schedule}", token=token, status=204)
    api("GET", f"/api/v1/schedules/{new_schedule}", token=token, status=404)
    check("runtime_schedule_delete")
    api("POST", "/api/v1/moderation/reports", token=token, status=201,
        body={"client_request_id": str(uuid.uuid4()), "content_type": "REVIEW_SUMMARY", "reason": "OTHER",
              "description": "Synthetic new report after forward migration"})
    check("new_v028_type_encrypted", sql("SELECT bool_and(description::jsonb ? 'ct') FROM user_service.moderation_reports WHERE content_type='REVIEW_SUMMARY'") == "t")
    check("serving_connections_use_runtime_role", sql("SELECT count(*)>0 AND bool_and(usename='map_user_runtime') FROM pg_stat_activity WHERE datname='user_runtime_fixture' AND backend_type='client backend' AND pid<>pg_backend_pid()") == "t")
    stop(app)
    check("training_hold", sql("SELECT (SELECT count(*) FROM user_service.recommend_training)+(SELECT count(*) FROM user_service.recommend_edits)") == "0")
    check("other_service_rows_unchanged", sql("SELECT md5((SELECT row_to_json(t)::text FROM hub_data.sentinel t)||(SELECT row_to_json(t)::text FROM admin_service.sentinel t))") == foreign_before)

    phase = "empty_database_legacy_bootstrap_boundary"
    sql(f"CREATE DATABASE {EMPTY}")
    sql("CREATE SCHEMA user_service AUTHORIZATION map_user_owner; "
        f"GRANT CONNECT ON DATABASE {EMPTY} TO map_user_migrator,map_user_runtime; "
        f"REVOKE TEMP,CREATE ON DATABASE {EMPTY} FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC", database=EMPTY)
    check("plain_migrator_rejects_empty_schema", migrate(database=EMPTY, expected=1)["status"] == "migration_failed")
    check("empty_rejection_created_no_relations", sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service'", database=EMPTY) == "0")
    sql("SET ROLE map_user_owner; CREATE SCHEMA IF NOT EXISTS user_service", role="map_user_migrator", database=EMPTY, expect_state="42501")
    check("postgres_v004_requires_db_create_even_when_schema_exists")
    result["empty_bootstrap"] = "normal migrator correctly rejects; separate production bootstrap still required"
    result["success"] = True
    phase = "complete"
except Exception as error:
    result["failure_phase"] = phase
    result["failure_code"] = str(error) if isinstance(error, FixtureFailure) else type(error).__name__
finally:
    for app in reversed(processes):
        stop(app)
    result["completed_checks"] = len(checks)
    result["no_gcp_calls"] = True
    result["real_user_data_used"] = False
    result["migration_source_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in sorted((ROOT / "src/main/resources/db/migration").glob("*.sql"))}
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"success": result["success"], "checks": len(checks), "phase": phase,
                      "failure_code": result.get("failure_code"), "report": REPORT.name}))
if not result["success"]:
    raise SystemExit(1)
