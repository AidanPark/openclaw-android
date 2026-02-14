# OpenClaw for Android

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw for Android">

나야, [OpenClaw](https://github.com/openclaw). 근데,, 이제 Android-Termux 를 곁들인...

## 왜 만들었나?

안드로이드 폰은 OpenClaw 서버를 돌리기에 좋은 환경입니다:

- **충분한 성능** — 최신 폰은 물론, 몇 년 전 모델도 OpenClaw을 구동하기에 충분한 사양을 갖추고 있습니다
- **남는 폰 재활용** — 서랍에 굴러다니는 폰을 활용할 수 있습니다. 미니PC를 따로 구매할 필요가 없습니다
- **저전력 + 자체 UPS** — PC 대비 아주 적은 전력으로 24시간 운영이 가능하고, 배터리가 있어서 정전에도 꺼지지 않습니다
- **개인정보 걱정 없음** — 초기화된 폰에 계정 로그인 없이 OpenClaw만 설치하면, 개인정보가 전혀 없는 깨끗한 환경이 됩니다. PC를 이렇게 쓰기엔 부담스럽지만, 남는 폰이라면 부담 없습니다

## 리눅스 설치 없이

기존 방식으로 Android에서 OpenClaw를 실행하려면 proot-distro로 Ubuntu를 설치해야 하고, 700MB~1GB의 저장공간이 필요합니다. OpenClaw for Android는 호환성 문제를 직접 패치하여 순수 Termux 환경에서 OpenClaw를 실행할 수 있게 합니다.

| | 기존 방식 (proot-distro) | 이 프로젝트 |
|---|---|---|
| 저장공간 오버헤드 | 700MB - 1GB | ~50MB |
| 설치 시간 | 10-15분 | 3-5분 |
| 성능 | 느림 (proot 레이어) | 네이티브 속도 |
| 복잡도 | 높음 | 명령어 하나 |

## 요구사항

- Android 7.0 이상
- 약 500MB 이상의 여유 저장공간
- Wi-Fi 또는 모바일 데이터 연결

## 처음부터 설치하기 (초기화된 폰 기준)

### 1단계: 개발자 옵션 활성화 및 화면 켜짐 유지 설정

OpenClaw는 서버로 동작하므로 화면이 꺼지면 Android가 프로세스를 제한할 수 있습니다. 충전 중 화면이 꺼지지 않도록 설정하면 안정적으로 운영할 수 있습니다.

**A. 개발자 옵션 활성화**

1. **설정** > **휴대전화 정보** (또는 **디바이스 정보**)
2. **빌드 번호**를 7번 연속 탭
3. "개발자 모드가 활성화되었습니다" 메시지 확인
4. 잠금화면 비밀번호가 설정되어 있으면 입력

> 일부 기기에서는 **설정** > **휴대전화 정보** > **소프트웨어 정보** 안에 빌드 번호가 있습니다.

**B. 충전 중 화면 켜짐 유지 (Stay Awake)**

1. **설정** > **개발자 옵션** (위에서 활성화한 메뉴)
2. **화면 켜짐 유지** (Stay awake) 옵션을 **ON**
3. 이제 USB 또는 무선 충전 중에는 화면이 자동으로 꺼지지 않습니다

> 충전기를 분리하면 일반 화면 꺼짐 설정이 적용됩니다. 서버를 장시간 운영할 때는 충전기를 연결해두세요.

**C. 충전 제한 설정 (필수)**

폰을 24시간 충전 상태로 두면 배터리가 팽창할 수 있습니다. 최대 충전량을 80%로 제한하면 배터리 수명과 안전성이 크게 향상됩니다.

- **삼성**: **설정** > **배터리** > **배터리 보호** → **최대 80%** 선택
- **Google Pixel**: **설정** > **배터리** > **배터리 보호** → ON

> 제조사마다 메뉴 이름이 다를 수 있습니다. "배터리 보호" 또는 "충전 제한"으로 검색하세요. 해당 기능이 없는 기기에서는 충전기를 수동으로 관리하거나 스마트 플러그를 활용할 수 있습니다.

### 2단계: Termux 설치

> **중요**: Google Play Store의 Termux는 업데이트가 중단되어 정상 동작하지 않습니다. 반드시 F-Droid에서 설치하세요.

1. 폰 브라우저에서 [F-Droid 공식 사이트](https://f-droid.org)에 접속
2. `F-Droid.apk` 다운로드 및 설치
   - "출처를 알 수 없는 앱" 설치 허용 팝업이 뜨면 **허용**
3. F-Droid 앱을 열고 검색창에 `Termux` 입력
4. **Termux** 설치 (개발자: Fredrik Fornwall)
5. 같은 방법으로 **Termux:API** 도 설치 (선택이지만 권장)

### 3단계: Termux 초기 설정 및 백그라운드 종료 방지

Termux 앱을 열고 아래 명령어를 붙여넣으세요. 저장소 업데이트, curl 설치, 백그라운드 종료 방지가 한 번에 처리됩니다.

```bash
pkg update -y && pkg upgrade -y && pkg install -y curl && termux-wake-lock
```

> 처음 실행하면 저장소 미러를 선택하라는 메시지가 나올 수 있습니다. 아무거나 선택해도 되지만, 지역적으로 가까운 미러를 고르면 더 빠릅니다.

`termux-wake-lock`이 실행되면 상단 알림바에 Termux 알림이 고정되면서 시스템이 프로세스를 종료하지 않습니다. 해제하려면 `termux-wake-unlock`을 실행하거나 알림을 스와이프하면 됩니다.

**배터리 최적화에서 Termux 제외**

1. Android **설정** > **배터리** (또는 **배터리 및 기기 관리**)
2. **배터리 최적화** (또는 **앱 절전**) 메뉴 진입
3. 앱 목록에서 **Termux** 를 찾아서 **최적화하지 않음** (또는 **제한 없음**) 선택

> 메뉴 경로는 제조사(삼성, LG 등)와 Android 버전에 따라 다를 수 있습니다. "배터리 최적화 제외" 또는 "앱 절전 해제"로 검색하면 해당 기기의 정확한 경로를 찾을 수 있습니다.

### 4단계: OpenClaw 설치

3단계가 끝나면, 이어서 아래 명령어를 붙여넣으세요.

(컴퓨터에서 SSH로 접속하면 명령어 입력이 훨씬 수월합니다. [Termux SSH 접속 가이드](docs/termux-ssh-guide.ko.md)를 참고하세요.)

```bash
curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/bootstrap.sh | bash && source ~/.bashrc
```

설치 스크립트가 아래 7단계를 자동으로 처리합니다. 3~10분 정도 소요되며 (네트워크 속도와 기기 성능에 따라 다름), Wi-Fi 환경을 권장합니다.

```
install.sh (진입점)
  ├── [1/7] scripts/check-env.sh      # Termux 환경 검증
  ├── [2/7] scripts/install-deps.sh    # pkg install (nodejs-lts 등)
  ├── [3/7] scripts/setup-paths.sh     # 디렉토리 생성
  ├── [4/7] scripts/setup-env.sh       # .bashrc에 환경변수 추가
  ├── [5/7] patches/apply-patches.sh   # OpenClaw 설치 및 패치 적용
  │         ├── bionic-compat.js 복사
  │         └── patches/patch-paths.sh # sed로 하드코딩 경로 치환
  ├── [6/7] tests/verify-install.sh    # 설치 검증
  └── [7/7] openclaw update            # 최신 버전으로 업데이트
```

설치가 완료되면 OpenClaw 버전이 출력되고, `openclaw onboard`로 설정을 시작하라는 안내가 나타납니다.

### 5단계: OpenClaw 설정 시작

설치 완료 메시지의 안내에 따라 아래 명령어를 실행합니다.

```bash
openclaw onboard
```

화면의 안내에 따라 초기 설정을 진행합니다.

![openclaw onboard](docs/images/openclaw-onboard.png)

### 6단계: 게이트웨이 실행 및 Termux 탭 구성

설정이 끝나면 게이트웨이를 실행합니다. 게이트웨이는 폰의 Termux에서 직접 실행하는 것이 안정적입니다.

```bash
openclaw gateway
```

게이트웨이를 유지하면서 다른 작업도 하려면 Termux의 **탭** 기능을 활용하세요. 화면 하단을 왼쪽에서 오른쪽으로 스와이프하면 탭 메뉴가 나타납니다. **NEW SESSION**을 눌러 새 탭을 추가할 수 있습니다.

<img src="docs/images/termux_menu.png" width="300" alt="Termux 탭 메뉴">

권장 탭 구성:

- **탭 1**: `openclaw gateway` — 게이트웨이 상태를 실시간으로 확인

<img src="docs/images/termux_tab_1.png" width="300" alt="탭 1 - openclaw gateway">

- **탭 2**: `sshd` — 컴퓨터에서 SSH로 접속하여 명령어 입력 ([SSH 접속 가이드](docs/termux-ssh-guide.ko.md))

<img src="docs/images/termux_tab_2.png" width="300" alt="탭 2 - sshd">

이렇게 두 탭을 유지해 두면 게이트웨이가 안정적으로 동작하면서, 컴퓨터에서 SSH로 접속하여 추가 작업을 할 수 있습니다.

> 게이트웨이를 중지하려면 탭 1에서 `Ctrl+C`를 누르세요. `Ctrl+Z`는 프로세스를 종료하지 않고 일시 중지만 시키므로, 반드시 `Ctrl+C`를 사용하세요.

## 동작 원리

설치 스크립트는 Termux와 일반 Linux 환경의 차이를 자동으로 해결합니다. 사용자가 직접 할 일은 없으며, 설치 명령어 하나로 아래 5가지가 모두 처리됩니다:

1. **플랫폼 인식** — Android를 Linux로 인식하도록 설정
2. **네트워크 관련 오류 방지** — Android 환경에서 발생하는 네트워크 관련 크래시를 자동 우회
3. **경로 변환** — 일반 Linux 경로를 Termux 경로로 자동 변환
4. **임시 폴더 설정** — Android에서 접근 가능한 임시 폴더로 자동 설정
5. **서비스 관리자 우회** — systemd 없이도 정상 동작하도록 설정

## 성능

`openclaw status` 같은 명령어는 PC보다 느리게 느껴질 수 있습니다. 이는 명령어를 실행할 때마다 많은 파일을 읽어야 하는데, 폰의 저장장치가 PC보다 느리고 Android의 보안 처리가 추가되기 때문입니다.

단, **게이트웨이가 실행된 이후에는 차이가 없습니다**. 프로세스가 메모리에 상주하므로 파일을 다시 읽지 않고, AI 응답은 외부 서버에서 처리되므로 PC와 동일한 속도입니다.

<details>
<summary>개발자용 기술 문서</summary>

## 프로젝트 구조

```
openclaw-android/
├── bootstrap.sh                # curl | bash 원라이너 설치 (다운로더)
├── install.sh                  # 원클릭 설치 스크립트 (진입점)
├── uninstall.sh                # 깔끔한 제거
├── patches/
│   ├── bionic-compat.js        # 플랫폼 오버라이드 + os.networkInterfaces() 안전 래퍼
│   ├── patch-paths.sh          # OpenClaw 내 하드코딩 경로 수정
│   └── apply-patches.sh        # 패치 오케스트레이터
├── scripts/
│   ├── check-env.sh            # 사전 환경 점검
│   ├── install-deps.sh         # Termux 패키지 설치
│   ├── setup-env.sh            # 환경변수 설정
│   └── setup-paths.sh          # 디렉토리 및 심볼릭 링크 생성
└── tests/
    └── verify-install.sh       # 설치 후 검증
```

## 설치 흐름 상세

`bash install.sh`를 실행하면 아래 7단계가 순서대로 실행됩니다.

### [1/7] 환경 체크 — `scripts/check-env.sh`

설치를 시작하기 전에 현재 환경이 적합한지 검증합니다.

- **Termux 감지**: `$PREFIX` 환경변수 존재 여부로 Termux 환경인지 확인. 없으면 즉시 종료
- **아키텍처 확인**: `uname -m`으로 CPU 아키텍처 확인 (aarch64 권장, armv7l 지원, x86_64은 에뮬레이터로 판단)
- **디스크 여유 공간**: `$PREFIX` 파티션에 최소 500MB 이상 여유 공간이 있는지 확인. 부족하면 오류
- **기존 설치 감지**: `openclaw` 명령어가 이미 존재하면 현재 버전을 표시하고 재설치/업데이트임을 안내
- **Node.js 사전 확인**: 이미 설치된 Node.js가 있으면 버전을 표시하고, 22 미만이면 업그레이드 예고

### [2/7] 패키지 설치 — `scripts/install-deps.sh`

OpenClaw 빌드 및 실행에 필요한 Termux 패키지를 설치합니다.

- `pkg update -y`로 패키지 저장소 갱신
- 다음 패키지를 일괄 설치:

| 패키지 | 역할 | 필요한 이유 |
|--------|------|------------|
| `nodejs-lts` | Node.js LTS 런타임 (>= 22) + npm 패키지 매니저 | OpenClaw 자체가 Node.js 애플리케이션. `npm install -g openclaw`로 설치하므로 Node.js와 npm이 필수. LTS 버전을 사용하는 이유는 OpenClaw가 Node >= 22.12.0을 요구하기 때문 |
| `git` | 분산 버전 관리 시스템 | 일부 npm 패키지가 설치 과정에서 git 의존성을 가짐. OpenClaw의 하위 의존성 중 git URL로 참조되는 패키지가 있을 수 있으며, 이 저장소 자체를 `git clone`으로 받을 때도 필요 |
| `python` | Python 인터프리터 | `node-gyp`가 네이티브 C/C++ 애드온을 빌드할 때 Python을 빌드 스크립트 실행에 사용. OpenClaw 의존성 트리에 네이티브 모듈(예: `better-sqlite3`, `bcrypt`)이 포함될 경우 필수 |
| `make` | 빌드 자동화 도구 | `node-gyp`가 생성한 Makefile을 실행하여 네이티브 모듈을 컴파일하는 데 사용. `python`과 함께 네이티브 빌드 파이프라인의 핵심 |
| `cmake` | 크로스 플랫폼 빌드 시스템 | 일부 네이티브 모듈이 Makefile 대신 CMake 기반 빌드를 사용. 특히 암호화 관련 라이브러리(`argon2` 등)가 CMakeLists.txt를 포함하는 경우가 많음 |
| `clang` | C/C++ 컴파일러 | Termux의 기본 C/C++ 컴파일러. `node-gyp`가 네이티브 모듈의 C/C++ 소스를 컴파일할 때 사용. Termux에서는 GCC 대신 Clang이 표준 |
| `tmux` | 터미널 멀티플렉서 | OpenClaw 서버를 백그라운드 세션에서 실행할 수 있게 해줌. Termux에서는 앱이 백그라운드로 가면 프로세스가 중단될 수 있으므로, tmux 세션 안에서 실행하면 안정적으로 유지 가능 |
| `termux-api` | Termux와 Android API 간 브리지 | 네트워크 상태 확인, 알림, 클립보드 등 Android 시스템 기능에 접근하기 위한 도구. OpenClaw가 직접 사용하지는 않지만 Termux 환경에서 유용한 유틸리티 |

- 설치 후 Node.js >= 22 버전 및 npm 존재 여부를 검증. 실패 시 종료

### [3/7] 경로 설정 — `scripts/setup-paths.sh`

Termux에서 필요한 디렉토리 구조를 생성합니다.

- `$PREFIX/tmp/openclaw` — OpenClaw 전용 임시 디렉토리 (`/tmp` 대체)
- `$HOME/.openclaw-android/patches` — 패치 파일 저장 위치
- `$HOME/.openclaw` — OpenClaw 데이터 디렉토리
- 표준 Linux 경로(`/bin/sh`, `/usr/bin/env`, `/tmp`)가 Termux의 `$PREFIX` 하위 경로로 매핑되는 현황을 표시

### [4/7] 환경변수 설정 — `scripts/setup-env.sh`

`~/.bashrc`에 환경변수 블록을 추가합니다.

- `# >>> OpenClaw for Android >>>` / `# <<< OpenClaw for Android <<<` 마커로 블록을 감싸서 관리
- 이미 블록이 존재하면 기존 블록을 제거하고 새로 추가 (중복 방지)
- 설정되는 환경변수:
  - `TMPDIR=$PREFIX/tmp` — `/tmp` 대신 Termux 임시 디렉토리 사용
  - `TMP`, `TEMP` — `TMPDIR`과 동일 (일부 도구 호환용)
  - `NODE_OPTIONS="-r .../bionic-compat.js"` — 모든 Node 프로세스에 Bionic 호환 패치 자동 로드
  - `CONTAINER=1` — systemd 존재 여부 확인을 우회

### [5/7] OpenClaw 설치 및 패치 — `npm install` + `patches/apply-patches.sh`

OpenClaw을 글로벌로 설치하고 Termux 호환 패치를 적용합니다.

1. `bionic-compat.js`를 `~/.openclaw-android/patches/`에 복사 (npm install 과정에서도 필요)
2. `npm install -g openclaw@latest` 실행
3. `patches/apply-patches.sh`가 패치를 일괄 적용:
   - `bionic-compat.js` 최종 복사 확인
   - `patches/patch-paths.sh` 실행 — 설치된 OpenClaw JS 파일 내 하드코딩된 경로를 sed로 치환:
     - `"/tmp"` / `'/tmp'` → `"$PREFIX/tmp"` / `'$PREFIX/tmp'`
     - `"/bin/sh"` → `"$PREFIX/bin/sh"`
     - `"/bin/bash"` → `"$PREFIX/bin/bash"`
     - `"/usr/bin/env"` → `"$PREFIX/bin/env"`
   - 패치 결과를 `~/.openclaw-android/patch.log`에 기록

### [6/7] 설치 검증 — `tests/verify-install.sh`

설치가 정상적으로 완료되었는지 7가지 항목을 확인합니다.

| 검증 항목 | PASS 조건 |
|-----------|----------|
| Node.js 버전 | `node -v` >= 22 |
| npm | `npm` 명령어 존재 |
| openclaw | `openclaw --version` 성공 |
| TMPDIR | 환경변수 설정됨 |
| NODE_OPTIONS | 환경변수 설정됨 |
| CONTAINER | `1`로 설정됨 |
| bionic-compat.js | `~/.openclaw-android/patches/`에 파일 존재 |
| 디렉토리 | `~/.openclaw-android`, `~/.openclaw`, `$PREFIX/tmp` 존재 |
| .bashrc | 환경변수 블록 포함 |

모든 항목 통과 시 PASSED, 하나라도 실패 시 FAILED를 출력하고 재설치를 안내합니다.

### [7/7] OpenClaw 업데이트

`openclaw update`를 실행하여 최신 상태로 업데이트합니다. 완료 후 OpenClaw 버전을 출력하고 `openclaw onboard`로 설정을 시작하라는 안내를 표시합니다.

</details>

## 제거

```bash
bash ~/.openclaw-android/uninstall.sh
```

OpenClaw 패키지, 패치, 환경변수, 임시 파일이 모두 제거됩니다. OpenClaw 데이터(`~/.openclaw`)는 선택적으로 보존할 수 있습니다.

## 문제 해결

자세한 트러블슈팅 가이드는 [문제 해결 문서](docs/troubleshooting.ko.md)를 참고하세요.

## 라이선스

MIT
