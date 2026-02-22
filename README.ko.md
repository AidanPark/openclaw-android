# OpenClaw on Android

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![Termux](https://img.shields.io/badge/Termux-Required-orange)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)
![GitHub Stars](https://img.shields.io/github/stars/AidanPark/openclaw-android)

나야, [OpenClaw](https://github.com/openclaw). 근데,, 이제 Android-Termux 를 곁들인...

## 왜 만들었나?

안드로이드 폰은 OpenClaw 서버를 돌리기에 좋은 환경입니다:

- **충분한 성능** — 최신 폰은 물론, 몇 년 전 모델도 OpenClaw을 구동하기에 충분한 사양을 갖추고 있습니다
- **남는 폰 재활용** — 서랍에 굴러다니는 폰을 활용할 수 있습니다. 미니PC를 따로 구매할 필요가 없습니다
- **저전력 + 자체 UPS** — PC 대비 아주 적은 전력으로 24시간 운영이 가능하고, 배터리가 있어서 정전에도 꺼지지 않습니다
- **개인정보 걱정 없음** — 초기화된 폰에 계정 로그인 없이 OpenClaw만 설치하면, 개인정보가 전혀 없는 깨끗한 환경이 됩니다. PC를 이렇게 쓰기엔 부담스럽지만, 남는 폰이라면 부담 없습니다

## 리눅스 설치 없이

일반적으로 Android에서 OpenClaw를 실행하려면 proot-distro로 Linux를 설치해야 하고, 700MB~1GB의 저장공간이 필요합니다. OpenClaw on Android는 호환성 문제를 직접 패치하여 순수 Termux 환경에서 OpenClaw를 실행할 수 있게 합니다.

| | 기존 방식 (proot-distro) | 이 프로젝트 |
|---|---|---|
| 저장공간 오버헤드 | 1-2GB (Linux + 패키지) | ~50MB |
| 설치 시간 | 20-30분 | 3-10분 |
| 성능 | 느림 (proot 레이어) | 네이티브 속도 |
| 설정 과정 | 디스트로 설치, Linux 설정, Node.js 설치, 경로 수정... | 명령어 하나 실행 |

## 요구사항

- Android 7.0 이상 (Android 10 이상 권장)
- 약 500MB 이상의 여유 저장공간
- Wi-Fi 또는 모바일 데이터 연결

## 처음부터 설치하기 (초기화된 폰 기준)

1. [개발자 옵션 활성화 및 화면 켜짐 유지 설정](#1단계-개발자-옵션-활성화-및-화면-켜짐-유지-설정)
2. [Termux 설치](#2단계-termux-설치)
3. [Termux 초기 설정 및 백그라운드 종료 방지](#3단계-termux-초기-설정-및-백그라운드-종료-방지)
4. [OpenClaw 설치](#4단계-openclaw-설치) — 명령어 하나
5. [OpenClaw 설정 시작](#5단계-openclaw-설정-시작)
6. [OpenClaw(게이트웨이) 실행](#6단계-openclaw게이트웨이-실행)
7. [PC에서 대시보드 접속](#7단계-pc에서-대시보드-접속)

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
2. `Termux` 검색 후 **Download APK**를 눌러 다운로드 및 설치
   - "출처를 알 수 없는 앱" 설치 허용 팝업이 뜨면 **허용**

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

> **팁: SSH로 편하게 입력하기**
> 이 단계부터는 폰 화면 대신 컴퓨터 키보드로 명령어를 입력할 수 있습니다.
> 폰에서 아래 명령어를 먼저 실행한 뒤, PC에서 `ssh -p 8022 <폰IP>`로 접속하세요:
> ```
> pkg install -y openssh && passwd && sshd
> ```

Termux에 아래 명령어를 붙여넣으세요.

```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
```

명령어 하나로 모든 설치가 자동으로 진행됩니다. 3~10분 정도 소요되며 (네트워크 속도와 기기 성능에 따라 다름), Wi-Fi 환경을 권장합니다.

설치가 완료되면 OpenClaw 버전이 출력되고, `openclaw onboard`로 설정을 시작하라는 안내가 나타납니다.

### 5단계: OpenClaw 설정 시작

설치 완료 메시지의 안내에 따라 아래 명령어를 실행합니다.

```bash
openclaw onboard
```

화면의 안내에 따라 초기 설정을 진행합니다.

![openclaw onboard](docs/images/openclaw-onboard.png)

### 6단계: OpenClaw(게이트웨이) 실행

설정이 끝나면 게이트웨이를 실행합니다:

> **중요**: `openclaw gateway`는 SSH가 아닌, 폰의 Termux 앱에서 직접 실행하세요. SSH로 실행하면 SSH 연결이 끊어질 때 게이트웨이도 함께 종료됩니다.

```bash
openclaw gateway
```

> 게이트웨이를 중지하려면 `Ctrl+C`를 누르세요. `Ctrl+Z`는 프로세스를 종료하지 않고 일시 중지만 시키므로, 반드시 `Ctrl+C`를 사용하세요.

### 7단계: PC에서 대시보드 접속

PC 브라우저에서 OpenClaw를 관리하려면 폰에 SSH 연결을 설정해야 합니다. 먼저 [Termux SSH 접속 가이드](docs/termux-ssh-guide.ko.md)를 참고하여 SSH를 설정하세요.

SSH가 준비되면, 폰의 IP 주소를 확인합니다. Termux에서 다음을 실행하고 `wlan0` 항목의 `inet` 주소를 확인하세요 (예: `192.168.0.100`).

```bash
ifconfig
```

그 다음 PC의 새 터미널에서 SSH 터널을 설정합니다:

```bash
ssh -N -L 18789:127.0.0.1:18789 -p 8022 <폰IP>
```

그 다음 PC 브라우저에서 `http://localhost:18789/` 을 엽니다.

> 토큰이 포함된 전체 URL은 폰에서 `openclaw dashboard`를 실행하면 확인할 수 있습니다.

## 여러 디바이스 관리

같은 네트워크에서 여러 기기에 OpenClaw를 운영한다면, <a href="https://myopenclawhub.com" target="_blank">Dashboard Connect</a> 도구로 PC에서 편리하게 관리할 수 있습니다.

- 각 기기의 연결 정보(IP, 토큰, 포트)를 닉네임과 함께 저장
- SSH 터널 명령어와 대시보드 URL을 자동 생성
- **데이터는 로컬에만 저장** — 연결 정보(IP, 토큰, 포트)는 브라우저의 localStorage에만 저장되며 어떤 서버로도 전송되지 않습니다.

## 보너스: 폰에서 AI CLI 도구 사용

이 프로젝트에 포함된 호환 패치가 Termux의 네이티브 빌드 환경을 개선하여, 주요 AI CLI 도구를 설치하고 실행할 수 있습니다:

| 도구 | 설치 |
|------|------|
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | `npm i -g @anthropic-ai/claude-code` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | `npm i -g @google/gemini-cli` |
| [Codex CLI](https://github.com/openai/codex) (OpenAI) | `npm i -g @openai/codex` |

OpenClaw on Android를 먼저 설치한 후 위 도구를 설치하면 패치가 자동으로 적용됩니다.

<p>
  <img src="docs/images/run_claude.png" alt="Claude Code on Termux" width="32%">
  <img src="docs/images/run_gemini.png" alt="Gemini CLI on Termux" width="32%">
  <img src="docs/images/run_codex.png" alt="Codex CLI on Termux" width="32%">
</p>

## 업데이트

이미 OpenClaw on Android가 설치되어 있고, 최신 패치와 환경 설정을 적용하고 싶다면:

```bash
oaupdate && source ~/.bashrc
```

이 명령어 하나로 OpenClaw(`openclaw update`)와 이 프로젝트의 Android 호환 패치가 함께 업데이트됩니다. 여러 번 실행해도 안전합니다.

> `oaupdate` 명령어가 없는 경우 (이전 설치 사용자), curl로 실행:
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## 제거

```bash
bash ~/.openclaw-android/uninstall.sh
```

OpenClaw 패키지, 패치, 환경변수, 임시 파일이 모두 제거됩니다. OpenClaw 데이터(`~/.openclaw`)는 선택적으로 보존할 수 있습니다.

## 문제 해결

자세한 트러블슈팅 가이드는 [문제 해결 문서](docs/troubleshooting.ko.md)를 참고하세요.

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
├── update.sh                   # Thin wrapper (update-core.sh 다운로드 후 실행)
├── update-core.sh              # 기존 설치 환경 경량 업데이터
├── uninstall.sh                # 깔끔한 제거
├── patches/
│   ├── bionic-compat.js        # 플랫폼 오버라이드 + os.networkInterfaces() + os.cpus() 패치
│   ├── termux-compat.h         # C/C++ 호환 심 (renameat2 syscall 래퍼)
│   ├── spawn.h                 # Termux용 POSIX spawn 스텁 헤더
│   ├── patch-paths.sh          # OpenClaw 내 하드코딩 경로 수정
│   └── apply-patches.sh        # 패치 오케스트레이터
├── scripts/
│   ├── build-sharp.sh          # sharp 네이티브 모듈 빌드 (이미지 처리)
│   ├── check-env.sh            # 사전 환경 점검
│   ├── install-deps.sh         # Termux 패키지 설치
│   ├── setup-env.sh            # 환경변수 설정
│   └── setup-paths.sh          # 디렉토리 및 심볼릭 링크 생성
├── tests/
│   └── verify-install.sh       # 설치 후 검증
└── docs/
    ├── termux-ssh-guide.md     # Termux SSH 접속 가이드 (영문)
    ├── termux-ssh-guide.ko.md  # Termux SSH 접속 가이드 (한국어)
    ├── troubleshooting.md      # 트러블슈팅 가이드 (영문)
    ├── troubleshooting.ko.md   # 트러블슈팅 가이드 (한국어)
    └── images/                 # 스크린샷 및 이미지
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
| `binutils` | 바이너리 유틸리티 (ar, strip 등) | 네이티브 모듈 빌드 시 정적 아카이브 생성에 필요한 `llvm-ar` 제공. 많은 빌드 시스템이 `ar` 명령을 기대하므로 `ar → llvm-ar` 심볼릭 링크도 생성 |
| `tmux` | 터미널 멀티플렉서 | OpenClaw 서버를 백그라운드 세션에서 실행할 수 있게 해줌. Termux에서는 앱이 백그라운드로 가면 프로세스가 중단될 수 있으므로, tmux 세션 안에서 실행하면 안정적으로 유지 가능 |
| `ttyd` | 웹 터미널 | 터미널을 웹으로 공유하는 도구. [My OpenClaw Hub](https://myopenclawhub.com)에서 브라우저 기반 터미널 접속을 제공하는 데 사용 |
| `pyyaml` (pip) | Python용 YAML 파서 | OpenClaw의 `.skill` 패키징에 필요. Termux 패키지 설치 후 `pip install pyyaml`로 설치 |

- 설치 후 Node.js >= 22 버전 및 npm 존재 여부를 검증. 실패 시 종료

### [3/7] 경로 설정 — `scripts/setup-paths.sh`

Termux에서 필요한 디렉토리 구조를 생성합니다.

- `$PREFIX/tmp/openclaw` — OpenClaw 전용 임시 디렉토리 (`/tmp` 대체)
- `$HOME/.openclaw-android/patches` — 패치 파일 저장 위치
- `$HOME/.openclaw` — OpenClaw 데이터 디렉토리
- 표준 Linux 경로(`/bin/sh`, `/usr/bin/env`, `/tmp`)가 Termux의 `$PREFIX` 하위 경로로 매핑되는 현황을 표시

### [4/7] 환경변수 설정 — `scripts/setup-env.sh`

`~/.bashrc`에 환경변수 블록을 추가합니다.

- `# >>> OpenClaw on Android >>>` / `# <<< OpenClaw on Android <<<` 마커로 블록을 감싸서 관리
- 이미 블록이 존재하면 기존 블록을 제거하고 새로 추가 (중복 방지)
- 설정되는 환경변수:
  - `TMPDIR=$PREFIX/tmp` — `/tmp` 대신 Termux 임시 디렉토리 사용
  - `TMP`, `TEMP` — `TMPDIR`과 동일 (일부 도구 호환용)
  - `NODE_OPTIONS="-r .../bionic-compat.js"` — 모든 Node 프로세스에 Bionic 호환 패치 자동 로드
  - `CONTAINER=1` — systemd 존재 여부 확인을 우회
  - `CFLAGS="-Wno-error=implicit-function-declaration"` — Clang이 implicit function declaration을 에러로 처리하는 것을 방지 (GCC에서는 정상 빌드되지만 Clang의 엄격한 기본 설정에서 실패하는 `@discordjs/opus` 같은 네이티브 모듈 빌드에 필요)
  - `CXXFLAGS="-include .../termux-compat.h"` — 네이티브 모듈 빌드 시 C/C++ 호환 심 자동 포함
  - `GYP_DEFINES="OS=linux ..."` — node-gyp의 OS 감지를 Android에 맞게 오버라이드
  - `CPATH="...glib-2.0..."` — sharp 빌드에 필요한 glib 헤더 경로 제공
- `ar → llvm-ar` 심볼릭 링크가 없으면 생성 (Termux는 `llvm-ar`만 제공하지만 많은 빌드 시스템이 `ar`을 기대함)

`setup-env.sh` 실행 후, `install.sh`는 현재 프로세스에서 모든 환경변수를 다시 export합니다. `setup-env.sh`는 서브프로세스로 실행되므로 export가 부모 프로세스에 전달되지 않기 때문입니다. 이 재export를 통해 Step 5의 `npm install`이 올바른 빌드 환경(CFLAGS, CXXFLAGS, GYP_DEFINES 등)을 상속받습니다.

### [5/7] OpenClaw 설치 및 패치 — `npm install` + `patches/apply-patches.sh`

OpenClaw을 글로벌로 설치하고 Termux 호환 패치를 적용합니다.

1. 호환 패치 파일을 `~/.openclaw-android/patches/`에 복사:
   - `bionic-compat.js` — Node.js 런타임 패치 (npm install 과정에서도 필요)
   - `termux-compat.h` — C/C++ 빌드 호환 심 (renameat2 syscall 래퍼)
   - `spawn.h` → `$PREFIX/include/spawn.h` — POSIX spawn 스텁 헤더 (없는 경우 설치)
2. `update.sh` wrapper를 `$PREFIX/bin/oaupdate`에 설치 (간편 업데이트용)
3. `npm install -g openclaw@latest` 실행
4. `patches/apply-patches.sh`가 패치를 일괄 적용:
   - `bionic-compat.js` 최종 복사 확인
   - `systemctl` 스텁을 `$PREFIX/bin/systemctl`에 설치 — Termux에는 systemd가 없으므로, systemd 서비스 관리 호출을 가로채는 최소한의 스크립트
   - `patches/patch-paths.sh` 실행 — 설치된 OpenClaw JS 파일 내 하드코딩된 경로를 sed로 치환:
     - `"/tmp"` / `'/tmp'` → `"$PREFIX/tmp"` / `'$PREFIX/tmp'`
     - `"/bin/sh"` → `"$PREFIX/bin/sh"`
     - `"/bin/bash"` → `"$PREFIX/bin/bash"`
     - `"/usr/bin/env"` → `"$PREFIX/bin/env"`
   - 패치 결과를 `~/.openclaw-android/patch.log`에 기록
5. `scripts/build-sharp.sh`가 이미지 처리용 sharp 네이티브 모듈을 빌드 (비필수):
   - `libvips`와 `binutils` 패키지 설치
   - `node-gyp` 글로벌 설치
   - Android/Termux 크로스 컴파일을 위한 `GYP_DEFINES`와 `CPATH` 설정
   - OpenClaw 디렉토리에서 `npm rebuild sharp` 실행
   - 빌드 실패 시 경고만 출력하고 계속 진행 — 이미지 처리는 안 되지만 게이트웨이는 정상 동작

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

## 경량 업데이터 흐름 — `oaupdate` / `update.sh`

`oaupdate` (또는 `curl ... update.sh | bash`)를 실행하면 GitHub에서 `update-core.sh`를 다운로드하여 아래 6단계를 순서대로 실행합니다. 전체 설치와 달리 환경 체크, 경로 설정, 검증을 생략하고 — 패치, 환경변수, OpenClaw 패키지 갱신에만 집중합니다.

### [1/6] 사전 점검

업데이트를 위한 최소 조건을 확인합니다.

- `$PREFIX` 존재 확인 (Termux 환경)
- `openclaw` 명령 존재 확인 (이미 설치되어 있어야 함)
- `curl` 사용 가능 여부 확인 (파일 다운로드에 필요)
- 구버전 디렉토리 마이그레이션 (`.openclaw-lite` → `.openclaw-android` — 레거시 호환)

### [2/6] 신규 패키지 설치

초기 설치 이후 추가된 패키지를 보충 설치합니다.

- `ttyd` — 브라우저 기반 터미널 접속을 위한 웹 터미널. 이미 설치되어 있으면 스킵
- `PyYAML` — `.skill` 패키징용 YAML 파서. 이미 설치되어 있으면 스킵

둘 다 비필수 — 실패 시 경고만 출력하고 업데이트를 중단하지 않습니다.

### [3/6] 최신 스크립트 다운로드

GitHub에서 최신 패치 파일과 스크립트를 다운로드합니다.

| 파일 | 용도 | 실패 시 |
|------|------|---------|
| `setup-env.sh` | `.bashrc` 환경변수 블록 갱신 | **종료** (필수) |
| `bionic-compat.js` | Node.js 런타임 호환 패치 | 경고 |
| `termux-compat.h` | C/C++ 빌드 호환 헤더 | 경고 |
| `spawn.h` | POSIX spawn 스텁 (이미 있으면 스킵) | 경고 |
| `systemctl` | Termux용 systemd 스텁 | 경고 |
| `update.sh` | `oaupdate` 명령어 설치/갱신 | 경고 |
| `build-sharp.sh` | sharp 네이티브 모듈 빌드 스크립트 | 경고 |

`setup-env.sh`만 필수 — 나머지는 모두 실패해도 비필수입니다.

### [4/6] 환경변수 갱신

다운로드한 `setup-env.sh`를 실행하여 `.bashrc` 환경변수 블록을 최신 내용으로 갱신합니다. 이후 현재 프로세스에서 모든 변수를 다시 export하여 Step 5의 `npm install`이 올바른 빌드 환경을 상속받도록 합니다.

### [5/6] OpenClaw 패키지 업데이트

- 빌드 의존성 설치: `libvips` (sharp용)와 `binutils` (네이티브 빌드용)
- `ar → llvm-ar` 심볼릭 링크가 없으면 생성
- `npm install -g openclaw@latest` 실행 — Step 4의 환경변수가 상속되어 네이티브 모듈(sharp, `@discordjs/opus` 등) 빌드가 정상 동작
- 실패 시 경고만 출력하고 계속 진행

### [6/6] sharp 빌드 (이미지 처리)

`build-sharp.sh`를 실행하여 sharp 네이티브 모듈을 빌드합니다. Step 5의 `npm install`에서 이미 성공적으로 컴파일되었으면 이 단계에서 감지하고 rebuild를 건너뜁니다.

</details>

## 라이선스

MIT
