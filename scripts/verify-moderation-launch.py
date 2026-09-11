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

if sql("SELECT COUNT(*) FROM user_service.flyway_schema_history WHERE version IN ('026','26') AND success") != "1":
    raise SystemExit("Refusing: V026 moderation migration required")

run_id = uuid.uuid4().hex
password = "Synthetic-Map-" + uuid.uuid4().hex + "!9"
checks=[]
def check(name, condition):
    if not condition: raise AssertionError(name)
    checks.append(name)
def signup(label):
    return api("POST", "/api/v1/auth/signup", body={"email":f"{run_id}-{label}@map.test", "password":password,"nickname":"Synthetic "+label}, expected=201)
owner,author,third=[signup(label) for label in ("reporter","author","third")]
a,b,c=[v["user"]["id"] for v in (owner,author,third)]
ta,tb,tc=[v["accessToken"] for v in (owner,author,third)]
check("synthetic_accounts_only",set(json.loads(sql("SELECT COALESCE(json_agg(id),'[]') FROM user_service.users")))=={a,b,c})
job=str(uuid.uuid4())
sql(f"INSERT INTO user_service.recommend_jobs(job_id,owner_user_id,status,result_payload,finished_at) VALUES ('{job}',{a},'done','{{}}'::jsonb,NOW())")
schedule=int(sql(f"INSERT INTO user_service.schedules(user_id,job_id,title,date_start,date_end,payload) VALUES ({a},'{job}','Synthetic moderation',CURRENT_DATE,CURRENT_DATE+2,'{{}}'::jsonb) RETURNING schedule_id"))
room=api("POST","/api/v1/chat/rooms",ta,{"schedule_id":schedule},expected=201)["room_id"]
invite=api("POST",f"/api/v1/chat/rooms/{room}/invite",ta)["token"]
for token in (tb,tc): api("POST",f"/api/v1/chat/invites/{invite}/join",token)
streams=[StompSocket(token) for token in (ta,tb,tc)]
def send(token,text,expected=201): return api("POST",f"/api/v1/chat/rooms/{room}/messages",token,{"content":text},expected=expected)
def history(token): return api("GET",f"/api/v1/chat/rooms/{room}/messages?limit=100",token)["messages"]
def report(token,kind="CHAT_MESSAGE",expected=201,**target):
    body={"client_request_id":str(uuid.uuid4()),"content_type":kind,"reason":"OTHER","description":"Synthetic review only",**target}
    return body,api("POST","/api/v1/moderation/reports",token,body,expected=expected)
internal=os.environ["MAP_USER_TEST_INTERNAL_TOKEN"]
admin_headers={"X-Internal-Token":internal,"X-Admin-Actor":"synthetic_operator_17"}
try:
    for i,stream in enumerate(streams):
        stream.send(f"SUBSCRIBE\nid:room-{i}\ndestination:/topic/rooms/{room}\n\n\x00")
        stream.send(f"SUBSCRIBE\nid:errors-{i}\ndestination:/user/queue/errors\n\n\x00")
    time.sleep(.3)
    baseline=send(tb,"baseline_"+run_id)
    check("actual_stomp_three_recipients",all("baseline_"+run_id in stream.collect() for stream in streams))
    api("PUT",f"/api/v1/moderation/blocks/{b}",ta,expected=204)
    api("PUT",f"/api/v1/moderation/blocks/{b}",ta,expected=204)
    check("block_idempotent_and_named",api("GET","/api/v1/moderation/blocks",ta)[0].get("nickname")=="Synthetic author")
    api("PUT",f"/api/v1/moderation/blocks/{a}",ta,expected=400)
    blocked=send(tb,"blocked_"+run_id)
    deliveries=[stream.collect() for stream in streams]
    check("stomp_per_recipient_block_preserves_group", "blocked_"+run_id not in deliveries[0] and all("blocked_"+run_id in x for x in deliveries[1:]))
    outbound=send(ta,"reverse_"+run_id)
    deliveries=[stream.collect() for stream in streams]
    check("stomp_reverse_block", "reverse_"+run_id not in deliveries[1] and "reverse_"+run_id in deliveries[2])
    check("rest_history_bidirectional_block", all(m.get("sender_id")!=b for m in history(ta)) and all(m.get("sender_id")!=a for m in history(tb)))
    check("rest_presence_block",b not in api("GET",f"/api/v1/chat/rooms/{room}/presence",ta))
    streams[1].send(f'SEND\ndestination:/app/rooms/{room}/typing\ncontent-type:application/json\n\n{{"typing":true}}\x00')
    deliveries=[stream.collect() for stream in streams]
    check("stomp_typing_block","TYPING" not in deliveries[0] and "TYPING" in deliveries[2])
    preserved=send(tc,"preserved_"+run_id)
    for stream in streams: stream.collect(.15)
    latest=api("GET","/api/v1/chat/rooms",ta)
    check("rest_preview_block",all("blocked_"+run_id not in (r.get("last_message") or "") for r in latest))
    send(tb,"I WILL KILL YOU",expected=400)
    streams[1].send(f'SEND\ndestination:/app/rooms/{room}/send\ncontent-type:application/json\n\n{{"content":"I.WILL.KILL.YOU"}}\x00')
    check("stomp_send_filter", "CHAT_012" in streams[1].collect())
    check("rest_stomp_rejection_not_persisted",sql("SELECT COUNT(*) FROM user_service.chat_messages WHERE content LIKE '%KILL%'")=="0")
    request,receipt=report(ta,room_id=room,message_seq=blocked["seq"])
    duplicate=api("POST","/api/v1/moderation/reports",ta,request,expected=200)
    check("report_receipt_idempotency",receipt["report_id"]==duplicate["report_id"])
    altered={**request,"reason":"SPAM"}; api("POST","/api/v1/moderation/reports",ta,altered,expected=409)
    check("report_description_encrypted",sql(f"SELECT description::jsonb ? 'ct' FROM user_service.moderation_reports WHERE report_id='{receipt['report_id']}'")=="t")
    report(tb,"TRIP",expected=404,schedule_id=schedule)
    report(ta,"TRIP",schedule_id=schedule)
    report(ta,"VISION")
    report(ta,"VISION",expected=429)
    check("strict_report_quota",True)
    image={"client_request_id":str(uuid.uuid4()),"content_type":"VISION","reason":"OTHER","description":"synthetic","image":"data:image/jpeg;base64,synthetic"}
    api("POST","/api/v1/moderation/reports",tc,image,expected=400)
    check("image_field_rejected",True)
    prefix="/internal/admin/moderation/reports/"+receipt["report_id"]
    api("GET",prefix,ta,expected=403)
    detail=api("GET",prefix,headers=admin_headers)
    check("guarded_operator_review",detail["description"]=="Synthetic review only")
    action={"action_id":str(uuid.uuid4()),"action":"HIDE_CHAT_MESSAGE"}
    api("POST",prefix+"/actions",body=action,headers=admin_headers)
    api("POST",prefix+"/actions",body=action,headers=admin_headers)
    check("hide_notification_delivered",all("MESSAGE_REMOVED" in stream.collect() for stream in streams))
    check("hidden_message_not_in_rest",all(blocked["seq"] not in {m["seq"] for m in history(token)} for token in (ta,tb,tc)))
    check("other_message_preserved",preserved["seq"] in {m["seq"] for m in history(tc)})
    _,restrict=report(tc,room_id=room,message_seq=baseline["seq"])
    api("POST","/internal/admin/moderation/reports/"+restrict["report_id"]+"/actions",body={"action_id":str(uuid.uuid4()),"action":"RESTRICT_CHAT","restriction_hours":24},headers=admin_headers)
    send(tb,"restricted",expected=403)
    streams[1].send(f'SEND\ndestination:/app/rooms/{room}/send\ncontent-type:application/json\n\n{{"content":"restricted"}}\x00')
    check("stomp_restriction","CHAT_013" in streams[1].collect())
    api("POST","/internal/admin/moderation/reports/"+restrict["report_id"]+"/actions",body={"action_id":str(uuid.uuid4()),"action":"LIFT_CHAT_RESTRICTION"},headers=admin_headers)
    check("operator_restriction_release",send(tb,"released")["content"]=="released")
    for stream in streams:stream.collect(.15)
    check("audit_exactly_once",sql(f"SELECT COUNT(*) FROM user_service.moderation_actions WHERE report_id='{receipt['report_id']}'")=="1")
    api("DELETE",f"/api/v1/moderation/blocks/{b}",ta,expected=204)
    check("unblock_rest_history_restored",baseline["seq"] in {m["seq"] for m in history(ta)})
    api("DELETE","/api/v1/users/me",tb,expected=204)
    check("withdrawn_author_redaction",sql(f"SELECT COUNT(*) FROM user_service.moderation_reports WHERE reported_user_id={b} OR description IS NOT NULL AND content_type='CHAT_MESSAGE'")=="0")
    check("withdrawal_preserves_other_chat",preserved["seq"] in {m["seq"] for m in history(tc)})
    api("DELETE","/api/v1/users/me",ta,expected=204)
    check("withdrawn_reporter_identifiers_erased",sql(f"SELECT COUNT(*) FROM user_service.moderation_reports WHERE reporter_id={a} OR request_fingerprint IS NOT NULL AND content_type IN ('TRIP','VISION')")=="0")
    check("withdrawal_preserves_actions",sql("SELECT COUNT(*) FROM user_service.moderation_actions")=="3")
    check("training_hold",sql("SELECT (SELECT COUNT(*) FROM user_service.recommend_training)+(SELECT COUNT(*) FROM user_service.recommend_edits)")=="0")
finally:
    for stream in streams: stream.close()
print(json.dumps({"result":"passed","checks":checks,"synthetic_accounts_created":3,"synthetic_accounts_remaining":1,"preexisting_accounts":0,"external_provider_calls":0}))
