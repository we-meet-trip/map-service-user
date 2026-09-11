# NCP User 최초 DB 실행기

`scripts/ncp-user-bootstrap.py`는 새 NCP PROD `map-prod/map_prod`에만 적용한다.
기존 GCP TEST·Admin, 기존 DB·컨테이너·볼륨을 변경하는 경로는 없다.
이 문서는 실행 절차이며 실제 NCP bootstrap 성공 증거가 아니다.

Infra receiver의 `prepare`가 독립 LUKS2 저장소 위 PostgreSQL과 `template0`에서
만든 빈 `map_prod`를 준비하고 종료한 다음 실행한다. 공개 서비스는 계속 HOLD다.
User와 Infra는 각각 `/opt/map-service-user`, `/opt/map-service-infra`에 설치한
root 소유의 독립 Git checkout이어야 한다. source/ancestor/Git metadata의
group·other 쓰기, symlink, hardlink, 공유 worktree/alternate objects는 거절한다.
User HEAD는 요청의 `user.source_sha`, Infra HEAD는 고정 release `infra_sha`와 같아야
하며 tracked 변경이 없어야 한다. 별도 빌드나 이미지 pull은 수행하지 않는다.

```text
env -i PATH=/usr/bin:/bin:/usr/local/bin python3 -I -B \
  /opt/map-service-user/scripts/ncp-user-bootstrap.py \
  --infra-root /opt/map-service-infra
```

실행기는 Infra의 고정 receiver 검증 함수를 재사용한다. host enrollment,
GCP TEST·Admin과 분리된 NCP identity, LUKS filesystem UUID, 승인 artifact/receiver,
immutable User 이미지·로컬 image ID, 새 호스트 증명, exact PostgreSQL container/image/
bind path와 private internal network를 검증한다. 신뢰되지 않은 checkout의 Python을
import하기 전에 파일 소유권과 source pin을 검사한다.

입력은 root0600 `/srv/map-prod/deploy/bootstrap-request.json`, `new-host-proof.json`이다.
닫힌 JSON schema와 canonical SHA256 방식은 Infra
`docs/NCP_PRODUCTION_RECEIVER.md` 및 `identity_request()`가 정의한다. marker를 포함한
5개 독립 64자리 소문자 hex secret 파일은 `/srv/map-prod/secrets` 아래 고정 이름이며
single-link root0600이어야 한다. raw marker, 비밀번호, SQL 및 Docker/JDBC 출력은
증거·오류 메시지에 포함하지 않는다.

`/srv/map-prod/deploy/deploy.lock`을 nonblocking flock으로 한 번만 획득한다.
receiver가 lock을 가진 채 실행기를 호출하지 않는다. 이미 attempt/receipt/job 또는
migration env 파일이 있으면 자동 재시도하지 않는다. 첫 변경 전에
`user-bootstrap-attempt.json`을 HOLD로 영구 기록한다.

실제 실행 순서는 다음과 같다.

1. 기존 `user-database-bootstrap-prepare.sql`의 pristine/marker/role/배타성 검증과
   55분짜리 단일 bootstrap 역할 발급을 실행한다.
2. exact User image의 기존 `UserBootstrapApplication bootstrap`을 실행한다.
   V001–V004 원본 resource digest와 실제 Flyway history를 검증한다. 컨테이너는
   nonroot/read-only/cap-drop/no-new-privileges/no-restart/no-log-driver이며,
   private DB network 외 연결·호스트 mount·공개 port가 없다. `env -i`로 환경을
   비우고 비밀번호/marker 두 줄을 private stdin으로 전달한다. secret은 Docker
   Config.Env/Cmd나 호스트 command argument에 남기지 않는다. 내부 120초 TERM 및
   5초 kill deadline과 외부 135초 watchdog을 두며 exact job의 종료 코드 0,
   non-OOM, stopped 상태까지 확인한 다음 그 job만 제거한다.
3. 기존 finalize SQL로 실제 4개 history/빈 테이블/소유권을 확인하고 bootstrap
   LOGIN·비밀번호·권한을 폐기한다. 새 `user-database-bootstrap-activate.sql`은
   같은 target/marker, finalized schema, 4개 NOLOGIN 역할, history와 권한을 다시
   확인한 후 runtime/migrator만 독립 비밀번호로 LOGIN을 활성화한다. 재실행은 거절한다.
4. PG host rule이 exact loopback 외에는 SCRAM-SHA-256인지 확인한다. private `postgres`
   주소로 접속하여 실제 non-loopback TCP 비밀번호 인증으로 runtime의 DML·history 쓰기 금지, migrator의 `SET ROLE`
   및 DB CREATE/TEMP 금지, bootstrap NOLOGIN/password NULL/세션 0과 PG identity
   보존을 확인한다. 일반 마이그레이션의 전체 privilege guard는 이후 Infra
   `migrate-user`가 실행한다. 이 단계의 role-ready는 서비스 전체 readiness를 뜻하지 않는다.
5. root0600 `user-migration.env`에 정확히 migration URL/username/password 3개를
   기록한다. 성공한 본 작업의 `USER_BOOTSTRAP_PASSWORD` 파일만 삭제하고 marker는
   private 상태로 남긴다. 동일 11개 identity와 실제 최종 검증 결과를 가진
   `bootstrap-receipt.json`을 write-once로 기록한다.

이후 Infra `accept-bootstrap`과 `migrate-user`가 각각 독립 lock을 획득한다.
User 일반 migrate/validate, Hub·Agent 마이그레이션, backup/restore, 운영 환경의
연결·권한·가용성 검증과 공개 서비스 개통은 별도 인수 조건이다.

실패·timeout·중단은 HOLD다. 소유권을 확인한 exact job만 정지·제거한다.
prepare의 성공 자체가 불확실하면 기존 역할을 건드리지 않고 `manual_required`로
남긴다. prepare가 실제 성공한 뒤 실패했다면 target를 재검증하고 기존 quarantine
SQL로 bootstrap LOGIN을 차단한다. exact DB/역할의 알려진 두 application name만
PID와 backend 시작 시각으로 목록화해 동일 세션만 종료하고 전체 bootstrap 세션 0을
검사한다. 어느 확인이라도 실패하면 수동 검토가 필요하다. 부분 history/테이블/DB/
볼륨, raw marker를 지우거나 재설정하지 않으며 자동 재시도는 없다. SIGKILL·호스트
소실처럼 controller가 수습할 수 없는 중단은 영구 attempt와 내부 deadline에 의해
재실행이 막히며 운영자가 기존 quarantine 절차를 수행해야 한다.

검증 경계: `scripts/test_ncp_user_bootstrap.py`는 명령·stdin·target·소유권·오류 억제
경계의 단위 검사이며 실제 DB 성공 증거가 아니다. 기존 hosted PG17 fixture
`verify-user-bootstrap-postgres.py`는 원본 bootstrap/finalize 후 같은 activation SQL,
잘못된 marker·중복 활성화 거절, 실제 정상 마이그레이션·runtime guard·42501을
검증한다. 실제 NCP host/controller Docker 경로는 별도 exact-source 실행 증거가
확보될 때까지 NOT_RUN이다.
