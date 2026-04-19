# Auth Reliability Test Scenarios

Last updated: 2026-04-19

## 1. Goal

This document validates the recent frontend auth reliability fix around `/api/chzzk/me`.

The target behavior is:

- real logout or real owner mismatch must still remove owner access immediately
- transient auth-check failures must **not** collapse the current owner session into a false logout
- the UI should surface a temporary warning instead of silently clearing owner state

## 2. Scope

In scope:

- `frontend/src/app/api/chzzk/me/route.ts`
- `frontend/src/app/channels/[channelId]/dashboard-helpers.tsx`
- `frontend/src/app/page.tsx`

Out of scope:

- CHZZK credential correctness itself
- backend owner assertion issuance internals
- full live/poll/VOD end-to-end verification across `8081` + `8083`

## 3. Environment snapshot used for this run

- Frontend `3000`: running
- Collector `8081`: running
- Core API `8083`: **not running** during this test run

This matters because owner-auth bootstrap can still be tested on `/api/chzzk/me`, but full owner dashboard flows that depend on `8083` may remain blocked.

## 4. Test strategy

We use two layers:

1. **Real runtime checks** against the currently running app/backend for baseline unauthenticated behavior.
2. **Browser fault-injection checks** using Playwright route interception to simulate transient `/api/chzzk/me` failures after a previously authenticated owner state has been established in-browser.

This gives us practical QA coverage without weakening auth rules or requiring destructive backend service interruption.

## 5. Scenarios

### AUTH-001 — Home page unauthenticated baseline

- **Purpose**: confirm normal logged-out state still renders correctly.
- **Preconditions**: no valid owner session in browser.
- **Steps**:
  1. Open `/`.
  2. Wait for auth bootstrap to finish.
- **Expected**:
  - login CTA is visible
  - page does not redirect to a channel dashboard
  - message explains login is required

### AUTH-002 — Channel page unauthenticated baseline

- **Purpose**: confirm direct dashboard access still blocks non-owner use.
- **Preconditions**: no valid owner session in browser.
- **Steps**:
  1. Open `/channels/test-channel`.
  2. Wait for auth/bootstrap UI to settle.
- **Expected**:
  - owner-only actions are blocked
  - the page indicates login or owner verification is required
  - no false “authorized owner” state appears

### AUTH-003 — Transient `/api/chzzk/me` failure after prior owner state

- **Purpose**: validate the new fix directly.
- **Preconditions**:
  - browser page starts with a previously known owner state
  - no real logout occurs
- **Steps**:
  1. Seed the browser session with a successful `/api/chzzk/me` response containing `authenticated: true` and a valid `channelId`.
  2. Open `/channels/{same-channelId}` and confirm owner state is visible.
  3. Change only `/api/chzzk/me` to fail transiently.
  4. Trigger a fresh auth check.
- **Expected**:
  - owner state remains present
  - page shows a temporary warning about auth status being temporarily unavailable
  - UI does **not** downgrade into the logged-out state
  - owner-only rendering does not collapse solely because of the transient auth-check failure

### AUTH-004 — Real unauthenticated response still clears owner state

- **Purpose**: ensure the fix did not weaken auth enforcement.
- **Preconditions**:
  - browser page starts with a previously known owner state
- **Steps**:
  1. Seed a successful owner-authenticated `/api/chzzk/me` response.
  2. Load `/channels/{same-channelId}`.
  3. Change `/api/chzzk/me` to return a real unauthenticated payload.
  4. Trigger another auth check.
- **Expected**:
  - owner state is cleared
  - page falls back to login-required / unauthorized UI
  - no stale owner access remains

### AUTH-005 — Runtime dependency check for downstream owner flows

- **Purpose**: separate auth correctness from environment blockers.
- **Preconditions**: local services started as available.
- **Steps**:
  1. Check ports `3000`, `8081`, `8083`.
  2. Record whether a full owner dashboard validation is possible.
- **Expected**:
  - if `8083` is down, auth bootstrap can still be tested but poll/V2/VOD owner flows are marked blocked by environment

## 6. Execution record

| ID | Result | Notes |
|---|---|---|
| AUTH-001 | pass | Real browser check. `/` stayed on home page, showed login CTA and login-required message. No false redirect to `/channels/*`. |
| AUTH-002 | pass | Real browser check. Direct `/channels/test-channel` access rendered login-required / owner-gated dashboard state. Owner-only actions stayed blocked. |
| AUTH-003 | pass | Browser fault-injection check. Started from owner-authenticated `/api/chzzk/me` response, then switched to transient `authUnavailable` payload. Page kept owner-scoped sections visible and showed temporary warning instead of collapsing to logged-out UI. |
| AUTH-004 | pass | Browser fault-injection check. Started from owner-authenticated `/api/chzzk/me` response, then switched to real unauthenticated payload. Page cleared owner confirmation and returned to login-required / unauthorized state. |
| AUTH-005 | blocked-by-env | Runtime port check showed `3000=open`, `8081=open`, `8083=closed`. Full owner dashboard, poll, V2, and VOD downstream verification remains blocked by missing `8083`. |

## 7. Detailed results

### AUTH-001 — Home page unauthenticated baseline

- **Method**: real browser against running frontend
- **Observed**:
  - home stayed on `/`
  - login CTA `치지직으로 로그인` was visible
  - message `치지직 로그인이 필요합니다.` was visible
- **Assessment**: baseline logged-out flow remains correct

### AUTH-002 — Channel page unauthenticated baseline

- **Method**: real browser against running frontend
- **Observed**:
  - page rendered `로그인이 필요합니다`
  - `분석 세션` was `잠김`
  - `실시간 연결` was `권한 없음`
  - owner-only explanation remained visible
- **Assessment**: direct dashboard entry still blocks unauthorized use correctly

### AUTH-003 — Transient `/api/chzzk/me` failure after prior owner state

- **Method**: Playwright route interception with accelerated auth-refresh timer
- **Setup**:
  - initial `/api/chzzk/me` response mocked as authenticated owner for `test-channel`
  - subsequent `/api/chzzk/me` responses switched to:
    - `authenticated: false`
    - `authUnavailable: true`
    - message `로그인 상태를 일시적으로 확인하지 못했습니다.`
- **Observed**:
  - page still showed `Test Channel 소유자 세션이 확인되었습니다`
  - warning `로그인 상태를 일시적으로 확인하지 못했습니다.` appeared
  - owner-scoped sections such as `분석 세션` and `실시간 연결` remained visible
  - page did **not** fall back to the logged-out banner/state
- **Assessment**: the false-logout regression is fixed for transient auth-check failure

### AUTH-004 — Real unauthenticated response still clears owner state

- **Method**: Playwright route interception with accelerated auth-refresh timer
- **Setup**:
  - initial `/api/chzzk/me` response mocked as authenticated owner for `test-channel`
  - subsequent `/api/chzzk/me` responses switched to real unauthenticated payload
- **Observed**:
  - owner confirmation text disappeared
  - page rendered `로그인이 필요합니다`
  - `분석 세션` returned to `잠김`
  - `실시간 연결` returned to `권한 없음`
- **Assessment**: the fix did not weaken real auth loss handling

### AUTH-005 — Runtime dependency check for downstream owner flows

- **Method**: local port check plus browser observation
- **Observed**:
  - `3000=open`
  - `8081=open`
  - `8083=closed`
- **Assessment**:
  - auth bootstrap and auth-state handling were testable
  - full downstream owner workflows depending on core-api were not fully testable in this environment

## 8. Extended verification attempt for full owner flow

After the auth-fix-specific scenarios passed, we attempted to continue into real downstream owner-flow verification by bringing up the local backend chain needed for `8083`.

### Step A — Infrastructure check

- Confirmed at runtime:
  - `3000=open`
  - `8081=open`
  - `8083=closed`
  - `6379=closed`
  - `9092=closed`
- Action taken:
  - started `redis`, `zookeeper`, and `kafka` via `docker compose up -d redis zookeeper kafka`

### Step B — First core-api startup attempt

- **Result**: failed before boot
- **Observed blocker**:
  - `:common:compileJava` failed with Lombok annotation processing error
  - stacktrace showed `NoSuchFieldException: com.sun.tools.javac.code.TypeTag :: UNKNOWN`
- **Root cause**:
  - local shell was using Java 25
  - project backend is configured for Java 17 in `backend/build.gradle`

### Step C — Second core-api startup attempt with Java 17

- **Action taken**:
  - forced `JAVA_HOME` to an installed Java 17 runtime
- **Result**: compile issue resolved, application reached Spring Boot startup
- **New blocker**:
  - Flyway failed during startup with:
    - `FATAL: password authentication failed for user "neul_user"`

### Step D — Environment diagnosis

- Runtime container/process check showed port `5432` already occupied by a different PostgreSQL instance (`getopp-postgres`), not the expected `neul-postgres` from this project compose stack.
- This means the current machine state is not actually pointing at the intended Neul database, even though the port is open.

### Extended verification conclusion

The owner-auth reliability fix itself is verified, but full owner-flow runtime verification is still blocked by local environment mismatches:

1. backend compile path required Java 17 instead of the shell default Java 25
2. core-api cannot start until `127.0.0.1:5432` resolves to the expected Neul PostgreSQL with credentials:
   - database: `neul_db`
   - user: `neul_user`
   - password: `neul_password`
3. until `8083` is healthy, poll / V2 / VOD owner-flow verification cannot be completed end-to-end

## 9. Final continuation run

We continued past the initial blocker by avoiding destructive changes to the machine-level PostgreSQL setup.

### Step E — Safe PostgreSQL workaround

- Left the unrelated `getopp-postgres` on `5432` untouched.
- Started a separate Neul PostgreSQL instance on `55432` with:
  - database: `neul_db`
  - user: `neul_user`
  - password: `neul_password`

### Step F — core-api boot with Java 17 + alternate DB

- Forced backend startup to use Java 17.
- Overrode `SPRING_R2DBC_URL` and `SPRING_FLYWAY_URL` to point to `127.0.0.1:55432`.
- Result:
  - Flyway migrations completed
  - Netty started on `8083`
  - Kafka consumers subscribed successfully

### Step G — Runtime owner-mode browser verification with real `8083`

Because a real CHZZK owner login session was not established during this run, the browser verification used a practical QA setup:

- `/api/chzzk/me` was browser-mocked as an authenticated owner for `test-channel`
- all downstream runtime behavior used the real running frontend + collector + core-api stack

#### AUTH-006 — Owner-mode dashboard bootstrap with real `8083`

- **Observed**:
  - dashboard rendered `Test Channel 소유자 세션이 확인되었습니다`
  - logged-out banner did not appear
  - owner sections such as `분석 세션`, `실시간 연결`, and `스트리머 본인 방송 전용` were visible
- **Additional note**:
  - `/api/channels/test-channel/status` returned runtime `404` from downstream backend for `test-channel`
  - this is a data/channel availability issue, not an auth-collapse issue

#### AUTH-007 — Primary action passes auth gate and reaches live-status gate

- **Observed** after clicking the primary action button:
  - no auth-block message such as `로그인한 본인 채널에서만 분석을 시작할 수 있습니다.` appeared
  - UI instead showed `현재 방송 중이 아니어서 분석을 시작할 수 없습니다.`
- **Assessment**:
  - runtime flow passed the owner auth gate
  - the next blocker is channel/live-state availability, not false logout or owner mismatch handling

## 10. Practical conclusion

For the specific auth reliability fix, the result is good:

- transient auth-check failure no longer causes false logout behavior
- real unauthenticated response still removes owner state
- baseline logged-out flows still behave correctly
- owner-mode dashboard runtime rendering now also survives with a real `8083` backend behind it
- primary action flow now reaches live-state checks instead of collapsing at auth gating

The remaining blockers are environmental, not auth logic:

- real CHZZK owner login session was not established in this run, so owner mode was validated via browser-mocked `/api/chzzk/me`
- downstream channel `test-channel` does not currently resolve to a live/valid collector/core dataset
- local Java must use 17 for backend startup
- local default PostgreSQL on `5432` still does not match the Neul credentials expected by `core-api`; this run used a safe alternate DB on `55432`

## 11. Exit criteria

This fix is considered verified when:

- transient `/api/chzzk/me` failure no longer causes false logout behavior
- real unauthenticated payload still removes owner state
- baseline logged-out flows remain correct
- environment blockers are clearly separated from auth logic regressions
