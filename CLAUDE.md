# CLAUDE.md - OpenClaw Lite Android

## Project Overview

Android Termux에서 proot-distro 없이 OpenClaw(Node.js AI 에이전트 게이트웨이)를 설치하는 스크립트 + 패치 세트.
범위는 `npm install -g openclaw@latest` 성공까지. 설치 이후 설정/운영은 범위 밖.

- **GitHub**: https://github.com/AidanPark/openclaw-lite-android
- **라이선스**: MIT

## Architecture

두 가지 설치 경로가 있음:

```
[경로 1: curl 원라이너 (권장)]
curl | bash bootstrap.sh
  └── $HOME/.openclaw-lite/installer/ 에 전체 파일 다운로드
      └── install.sh 실행 (아래와 동일)

[경로 2: git clone]
git clone → bash install.sh
```

install.sh 내부 흐름:
```
install.sh (진입점)
  ├── [1/6] scripts/check-env.sh      # Termux 환경 검증
  ├── [2/6] scripts/install-deps.sh    # pkg install (nodejs-lts 등)
  ├── [3/6] scripts/setup-paths.sh     # 디렉토리 생성
  ├── [4/6] scripts/setup-env.sh       # .bashrc에 환경변수 추가
  ├── [5/6] patches/apply-patches.sh   # 패치 적용
  │         ├── bionic-compat.js 복사
  │         └── patches/patch-paths.sh # sed로 하드코딩 경로 치환
  └── [6/6] tests/verify-install.sh    # 설치 검증
```

## Key Technical Decisions

- **bionic-compat.js**: `os.networkInterfaces()`를 monkey-patch. `NODE_OPTIONS="-r ..."` 로 자동 로드
- **경로 패치**: 설치된 OpenClaw JS 파일 내 `/tmp`, `/bin/sh`, `/bin/bash`, `/usr/bin/env`를 `$PREFIX/...`로 sed 치환
- **환경변수 블록**: `.bashrc`에 `# >>> OpenClaw Lite Android >>>` / `# <<< OpenClaw Lite Android <<<` 마커로 관리 (중복 방지, 제거 용이)
- **CONTAINER=1**: systemd 의존성 우회용 환경변수
- **bootstrap.sh**: git clone 없이 curl 한 줄로 설치 가능하도록 만든 경량 다운로더. 개별 파일을 curl로 받아 임시 디렉토리에 저장 후 install.sh 실행, 완료 후 정리

## Conventions

### Shell Scripts
- 모든 스크립트는 `#!/usr/bin/env bash` + `set -euo pipefail`
- ANSI 색상 코드: `RED`, `GREEN`, `YELLOW`, `BOLD`, `NC` (No Color) 변수 사용
- 출력 포맷: `[OK]`, `[FAIL]`, `[WARN]`, `[SKIP]`, `[INFO]` 접두사
- 스크립트 상단에 한 줄 주석으로 목적 설명: `# filename.sh - 설명`

### JavaScript (patches/)
- CommonJS (`require`) 사용 — `'use strict'` 필수
- Node.js 내장 모듈만 사용 (외부 의존성 없음)

### README
- 영문(`README.md`)과 한국어(`README.ko.md`) 두 버전 유지. 내용 변경 시 양쪽 모두 동기화 필요
- 설치 가이드는 초기화된 폰 기준으로 1단계(개발자 옵션)부터 안내
- `curl | bash` 방식이 기본, `git clone`은 `<details>` 접힘 블록으로 대안 표시

## Useful Commands

```bash
# 문법 체크 (모든 쉘 스크립트)
for f in install.sh uninstall.sh bootstrap.sh patches/*.sh scripts/*.sh tests/*.sh; do bash -n "$f"; done

# JS 문법 체크
node -c patches/bionic-compat.js

# shellcheck 정적 분석 (설치된 경우)
shellcheck install.sh uninstall.sh bootstrap.sh patches/*.sh scripts/*.sh tests/*.sh

# 실제 Termux에서 end-to-end 테스트
bash install.sh
```

## File Roles

| 파일 | 수정 시 주의사항 |
|------|-----------------|
| `bootstrap.sh` | `REPO_BASE` URL이 GitHub raw URL을 가리킴. 저장소 이름/소유자 변경 시 업데이트 필요. `FILES` 배열에 새 파일 추가 시 여기도 반영 |
| `patches/bionic-compat.js` | `NODE_OPTIONS`로 모든 Node 프로세스에 주입됨. 사이드이펙트 최소화 필수 |
| `scripts/setup-env.sh` | `.bashrc` 직접 수정. 마커 기반 블록 관리 로직 변경 시 `uninstall.sh`도 함께 수정 |
| `patches/patch-paths.sh` | OpenClaw 업데이트 시 재실행 필요. sed 패턴 변경 시 광범위 영향 |
| `install.sh` | 6단계 순서 의존성 있음. 단계 추가/제거 시 step 번호와 총 개수 업데이트 |
| `README.md` / `README.ko.md` | 항상 양쪽 동기화. 단계 번호, URL 등 변경 시 둘 다 수정 |

## Target Environment

- **Runtime**: Termux on Android (aarch64 primary, armv7l supported)
- **Node.js**: >= 22.12.0 (LTS)
- **Shell**: Bash (Termux 기본)
- **NOT supported**: proot-distro, adb shell, standard Linux

## 작업 이력

### 완료된 작업
1. **프로젝트 초기 구현** — 12개 파일 생성 (install.sh, uninstall.sh, patches/*, scripts/*, tests/*, LICENSE, README.md)
2. **CLAUDE.md 생성** — 프로젝트 컨텍스트, 컨벤션, 명령어 정리
3. **README.ko.md 생성** — README 한국어 버전 작성, 영문 README에 상호 링크 추가
4. **설치 흐름 상세 문서화** — README.ko.md에 install.sh 6단계 각 스크립트의 동작을 상세 기술
5. **패키지 설명 보강** — install-deps.sh의 8개 패키지 각각의 역할과 필요한 이유를 표로 정리
6. **bootstrap.sh 생성** — git clone 없이 `curl | bash` 한 줄로 설치 가능하도록 부트스트랩 스크립트 추가. README 양쪽 업데이트
7. **초기화된 폰 기준 가이드** — F-Droid에서 Termux 설치부터 시작하는 단계별 가이드를 양쪽 README에 추가
8. **백그라운드 종료 방지** — `termux-wake-lock`, 배터리 최적화 제외 설정을 양쪽 README에 추가
9. **개발자 옵션 + Stay Awake** — 빌드 번호 7회 탭, 충전 중 화면 켜짐 유지 설정을 1단계로 추가
10. **단계 순서 재배치** — 폰/시스템 설정(개발자 옵션, Termux 설치, Wake Lock, 배터리 최적화)을 OpenClaw 설치 앞으로 이동하여 6단계로 정리
11. **GitHub 게시** — `AidanPark/openclaw-lite-android` 저장소 생성, URL placeholder를 실제 경로로 치환

### 현재 README 단계 구조 (양쪽 동일)
1. 개발자 옵션 활성화 + 충전 중 화면 켜짐 유지
2. Termux 설치 (F-Droid)
3. Termux 초기 설정 + Wake Lock + 배터리 최적화 제외
4. OpenClaw 설치 (`curl | bash`)
5. 환경변수 적용
6. 설치 확인

### 아직 하지 않은 것
- 실제 Termux 기기에서 end-to-end 테스트
- OpenClaw 설치 이후 설정/운영 가이드 (현재 범위 밖)
- shellcheck 정적 분석 (현재 환경에 shellcheck 미설치)
