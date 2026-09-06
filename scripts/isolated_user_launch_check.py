#!/usr/bin/env python3
"""Run only against a new labelled User test DB and a loopback-only User BFF.
No production credentials, external providers, pre-existing account deletion or volume cleanup.
"""
import base64
import datetime as dt
import hashlib
import http.client
import json
import os
import re
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("MAP_USER_TEST_BASE", "http://127.0.0.1:18089").rstrip("/")
DB_CONTAINER = os.environ.get("MAP_USER_TEST_DB_CONTAINER", "")
DB_NAME = os.environ.get("MAP_USER_TEST_DB_NAME", "user_release_test")
parsed = urllib.parse.urlsplit(BASE)
if (os.environ.get("MAP_USER_ISOLATED_TEST") != "1" or parsed.scheme != "http"
        or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.path
        or not re.fullmatch(r"map-release-user-db-[A-Za-z0-9_-]+", DB_CONTAINER)
        or not re.fullmatch(r"user_release_test(?:_[A-Za-z0-9_]+)?", DB_NAME)):
    raise SystemExit("Refusing: explicit isolated flag, loopback URL, and dedicated User DB are required")
metadata = json.loads(subprocess.check_output(["docker", "inspect", DB_CONTAINER], text=True))[0]
if metadata.get("Config", {}).get("Labels", {}).get("map.synthetic") != "true":
    raise SystemExit("Refusing: DB container must have --label map.synthetic=true")

def sql(statement):
    run = subprocess.run(["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", "postgres",
                          "-d", DB_NAME, "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
                         input=statement, capture_output=True, text=True, check=False)
    if run.returncode:
        # Never echo SQL values or credentials in a shared tool log.
        raise AssertionError("isolated SQL check failed")
    return run.stdout.strip()

if sql("SELECT COUNT(*) FROM user_service.users") != "0":
    raise SystemExit("Refusing: User test DB contains pre-existing accounts")
if sql("SELECT COUNT(*) FROM user_service.chat_participants") != "0":
    raise SystemExit("Refusing: User test DB contains pre-existing memberships")
if sql("SELECT COUNT(*) FROM pg_constraint WHERE conname='fk_schedule_user' "
       "AND conrelid='user_service.schedules'::regclass") != "1":
    raise SystemExit("Refusing: latest schedule/account protection migration is required")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise AssertionError("Refusing redirected request")
http = urllib.request.build_opener(NoRedirect)

def api(method, path, token=None, body=None, expected=200, headers=None):
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    if token:
        request_headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path,
            data=None if body is None else json.dumps(body).encode(), headers=request_headers, method=method)
    try:
        with http.open(request, timeout=15) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as response:
        status, raw = response.code, response.read()
    allowed = expected if isinstance(expected, (tuple, list)) else (expected,)
    if status not in allowed:
        safe_path = re.sub(r"/invites/[^/]+", "/invites/<redacted>", path.split("?")[0])
        raise AssertionError(f"{method} {safe_path} expected {allowed}, got {status}")
    return json.loads(raw) if raw else None

class StompSocket:
    def __init__(self, token):
        self.socket = socket.create_connection((parsed.hostname, parsed.port), timeout=5)
        self.socket.settimeout(5)
        key = base64.b64encode(os.urandom(16)).decode()
        origin = os.environ.get("MAP_USER_TEST_ORIGIN", "http://localhost:3000")
        path = os.environ.get("MAP_USER_TEST_WS_PATH", "/ws/chat")
        self.socket.sendall((f"GET {path} HTTP/1.1\r\nHost: {parsed.netloc}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
            f"Origin: {origin}\r\n\r\n").encode())
        response = b""
        while not response.endswith(b"\r\n\r\n"):
            response += self.socket.recv(1)
            if len(response) > 16384:
                raise AssertionError("Invalid WS handshake")
        assert response.startswith(b"HTTP/1.1 101"), "WS handshake was not accepted"
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        assert accept.lower() in response.lower(), "Invalid WS accept"
        self.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n\x00")
        assert "CONNECTED" in self.frame(), "STOMP did not authenticate"
    def send(self, text, opcode=1):
        data = text.encode() if isinstance(text, str) else text
        mask = os.urandom(4)
        length = len(data)
        assert length < 65536
        header = bytes([0x80 | opcode, 0x80 | length]) if length < 126 else bytes([0x80 | opcode, 0xFE]) + struct.pack("!H", length)
        self.socket.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
    def exact(self, count):
        result = b""
        while len(result) < count:
            chunk = self.socket.recv(count - len(result))
            if not chunk:
                raise AssertionError("WS closed unexpectedly")
            result += chunk
        return result
    def frame(self):
        head = self.exact(2)
        opcode, length = head[0] & 0x0F, head[1] & 0x7F
        if length == 126: length = struct.unpack("!H", self.exact(2))[0]
        if length == 127: length = struct.unpack("!Q", self.exact(8))[0]
        assert length < 1048576
        mask = self.exact(4) if head[1] & 0x80 else None
        body = self.exact(length)
        if mask: body = bytes(b ^ mask[i % 4] for i, b in enumerate(body))
        if opcode == 9:
            self.send(body, 10)
            return ""
        if opcode == 8: return ""
        return body.decode("utf-8", errors="replace")
    def collect(self, duration=0.6):
        deadline, chunks = time.monotonic() + duration, []
        self.socket.settimeout(duration)
        while time.monotonic() < deadline:
            try: chunks.append(self.frame())
            except socket.timeout: break
        return "".join(chunks)
    def close(self):
        self.socket.close()

run_id = uuid.uuid4().hex
password = "Synthetic-Map-" + uuid.uuid4().hex + "!9"
def signup(label):
    return api("POST", "/api/v1/auth/signup", body={"email": f"{run_id}-{label}@map.test",
                "password": password, "nickname": "Synthetic " + label}, expected=201)
owner, member = signup("owner"), signup("member")
a, b = owner["user"]["id"], member["user"]["id"]
ta, tb = owner["accessToken"], member["accessToken"]
assert set(json.loads(sql("SELECT COALESCE(json_agg(id),'[]') FROM user_service.users"))) == {a, b}
job = str(uuid.uuid4())
payload = {"job_id": job, "status": "done", "places": [{"place_id": 1, "day": 1,
    "name": "Synthetic place", "address": "Isolated fixture", "lat": 37.5, "lng": 127.0,
    "visit_start": "09:00", "visit_end": "10:00", "stay_minutes": 60, "grounded": True}],
    "visit_order": [1], "legs": [], "timeline_status": "ok"}
# Only the newly created account owns this isolated worker-result fixture; no provider calls.
sql("INSERT INTO user_service.recommend_jobs(job_id,owner_user_id,status,result_payload,finished_at) "
    f"VALUES ('{job}',{a},'done','{json.dumps(payload)}'::jsonb,NOW())")
api("GET", "/api/v1/recommend/" + job, tb, expected=403)
api("POST", "/api/v1/recommend/" + job + "/edit", tb, {"visit_order": [1]}, expected=403)
api("POST", "/api/v1/recommend/" + job + "/edit", ta, {"visit_order": [1]},
    headers={"Idempotency-Key": run_id})
assert sql(f"SELECT COUNT(*) FROM user_service.recommend_edit_requests WHERE job_id='{job}'") == "1"
assert sql("SELECT (SELECT COUNT(*) FROM user_service.recommend_training)+(SELECT COUNT(*) FROM user_service.recommend_edits)") == "0"
day = (dt.date.today() + dt.timedelta(days=2)).isoformat()
save = {"job_id": job, "title": "Synthetic itinerary", "date_start": day, "date_end": day,
        "transport": "walk", "active_start_hour": 9, "active_end_hour": 18}
api("POST", "/api/v1/schedules", tb, save, expected=403)
schedule = api("POST", "/api/v1/schedules", ta, save, expected=200)["schedule_id"]
room = api("POST", "/api/v1/chat/rooms", ta, {"schedule_id": schedule}, expected=201)["room_id"]
invite = api("POST", f"/api/v1/chat/rooms/{room}/invite", ta)["token"]
api("POST", f"/api/v1/chat/invites/{invite}/join", tb)
stream = StompSocket(tb)
try:
    stream.send(f"SUBSCRIBE\nid:isolated-room\ndestination:/topic/rooms/{room}\n\n\x00")
    time.sleep(0.1)
    def message(token, text):
        return api("POST", f"/api/v1/chat/rooms/{room}/messages", token, {"content": text}, expected=201)
    def history(token):
        return api("GET", f"/api/v1/chat/rooms/{room}/messages?limit=100", token)["messages"]
    visible = message(ta, "visible_before_" + run_id)
    assert "visible_before_" + run_id in stream.collect(), "Expected subscribed message missing"
    member_message = message(tb, "member_survives_" + run_id)
    stream.collect(0.1)
    api("DELETE", f"/api/v1/chat/rooms/{room}/participants/me", tb, expected=204)
    hidden = message(ta, "hidden_gap_" + run_id)
    assert "hidden_gap_" + run_id not in stream.collect(), "Left member received future WS message"
    assert hidden["seq"] not in {m["seq"] for m in history(tb)}
    assert visible["seq"] in {m["seq"] for m in history(tb)}
    api("POST", f"/api/v1/chat/rooms/{room}/messages", tb, {"content": "must fail"}, expected=403)
    api("POST", f"/api/v1/chat/invites/{invite}/join", tb)
    message(ta, "visible_after_" + run_id)
    texts = {m.get("content") for m in history(tb)}
    assert "hidden_gap_" + run_id not in texts and "visible_after_" + run_id in texts
    # Refresh then logout using the original refresh token must revoke the rotated descendant.
    rotated = api("POST", "/api/v1/auth/token/refresh", body={"refreshToken": member["refreshToken"]})
    api("POST", "/api/v1/auth/logout", tb, {"refreshToken": member["refreshToken"]}, expected=204)
    api("GET", "/api/v1/users/me", rotated["accessToken"], expected=401)
    api("POST", "/api/v1/auth/token/refresh", body={"refreshToken": rotated["refreshToken"]}, expected=401)
finally:
    stream.close()
# Restore the same synthetic member's session, then delete only the synthetic owner.
member = api("POST", "/api/v1/auth/login", body={"email": f"{run_id}-member@map.test", "password": password})
tb = member["accessToken"]
api("DELETE", "/api/v1/users/me", ta, expected=204)
api("GET", "/api/v1/users/me", ta, expected=401)
remaining = api("GET", f"/api/v1/chat/rooms/{room}", tb)
assert remaining["read_only"] and remaining.get("owner_id") is None and remaining.get("schedule_id") is None
texts = {m.get("content") for m in api("GET", f"/api/v1/chat/rooms/{room}/messages?limit=100", tb)["messages"]}
assert "member_survives_" + run_id in texts
assert not any(text and ("visible_before_" + run_id in text or "visible_after_" + run_id in text) for text in texts)
api("POST", f"/api/v1/chat/rooms/{room}/messages", tb, {"content": "closed"}, expected=410)
assert sql(f"SELECT COUNT(*) FROM user_service.schedules WHERE user_id={a}") == "0"
assert sql(f"SELECT COUNT(*) FROM user_service.recommend_jobs WHERE owner_user_id={a}") == "0"
assert sql(f"SELECT COUNT(*) FROM user_service.recommend_cancellations WHERE job_id='{job}'") == "1"
assert sql(f"SELECT COUNT(*) FROM user_service.recommend_edit_requests WHERE job_id='{job}'") == "0"
assert set(json.loads(sql("SELECT COALESCE(json_agg(id),'[]') FROM user_service.users"))) == {b}
# An already-started persistence request cannot recreate the deleted account's personal itinerary.
sql("DO $$ BEGIN BEGIN INSERT INTO user_service.schedules(user_id,date_start,date_end,payload) "
    f"VALUES ({a},CURRENT_DATE,CURRENT_DATE,'{{}}'::jsonb); "
    "RAISE EXCEPTION 'late account schedule was accepted'; "
    "EXCEPTION WHEN foreign_key_violation THEN NULL; END; END $$;")
# Leave the surviving synthetic member/room as evidence; the disposable environment is root-owned.
print(json.dumps({"result":"passed", "checks":["job ownership", "training hold", "durable edit receipt",
    "membership interval union", "left subscriber cutoff", "refresh/logout family revocation",
    "account erasure", "other member messages preserved", "late account schedule rejected"], "synthetic_accounts_created":2,
    "synthetic_accounts_remaining":1, "preexisting_accounts":0}))
