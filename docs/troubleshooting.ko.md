# 트러블슈팅

Termux에서 OpenClaw 사용 중 발생할 수 있는 문제와 해결 방법을 정리합니다.

## 게이트웨이가 시작되지 않음: "gateway already running" 또는 "Port is already in use"

```
Gateway failed to start: gateway already running (pid XXXXX); lock timeout after 5000ms
Port 18789 is already in use.
```

### 원인

이전 게이트웨이 프로세스가 비정상 종료되면서 잠금 파일이 남아있거나, 프로세스가 좀비 상태로 남아있는 경우 발생합니다. 주로 다음 상황에서 일어납니다:

- SSH 연결이 끊어지면서 게이트웨이 프로세스가 고아(orphan) 상태로 남음
- `Ctrl+Z`(일시정지)로 중단한 경우 프로세스가 종료되지 않고 백그라운드에 남음
- Termux가 Android에 의해 강제 종료된 경우

> **참고**: 게이트웨이를 종료할 때는 반드시 `Ctrl+C`를 사용하세요. `Ctrl+Z`는 프로세스를 일시정지시킬 뿐 종료하지 않습니다.

### 해결 방법

**1단계: 남아있는 프로세스 확인 및 종료**

```bash
ps aux | grep -E "node|openclaw" | grep -v grep
```

프로세스가 보이면 PID를 확인하고 종료:

```bash
kill -9 <PID>
```

**2단계: 잠금 파일 삭제**

```bash
rm -rf $PREFIX/tmp/openclaw-*
```

**3단계: 게이트웨이 재시작**

```bash
openclaw gateway
```

### 그래도 안 되면

위 과정으로도 해결되지 않으면 Termux 앱을 완전히 종료했다가 다시 열고 `openclaw gateway`를 실행하세요. 폰을 재시작하면 확실하게 모든 상태가 초기화됩니다.

## 게이트웨이 연결 끊김: "gateway not connected"

```
send failed: Error: gateway not connected
disconnected | error
```

### 원인

게이트웨이 프로세스가 종료되었거나 SSH 세션이 끊어진 경우 발생합니다.

### 해결 방법

게이트웨이를 실행했던 SSH 세션을 확인하세요. 세션이 끊어졌다면 다시 SSH 접속 후 게이트웨이를 시작합니다:

```bash
openclaw gateway
```

"gateway already running" 에러가 나오면 위의 [게이트웨이가 시작되지 않음](#게이트웨이가-시작되지-않음-gateway-already-running-또는-port-is-already-in-use) 섹션을 참고하세요.

## SSH 접속 실패: "Connection refused"

```
ssh: connect to host 192.168.45.139 port 8022: Connection refused
```

### 원인

Termux의 SSH 서버(`sshd`)가 실행되지 않은 상태입니다. Termux 앱을 종료하거나 폰을 재시작하면 sshd가 꺼집니다.

### 해결 방법

폰에서 Termux 앱을 열고 `sshd`를 실행하세요. 폰에서 직접 타이핑하거나 adb로 전송:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

IP가 변경되었을 수 있으니 확인:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

> 매번 수동으로 `sshd`를 실행하기 번거로우면 `~/.bashrc` 맨 아래에 `sshd 2>/dev/null`을 추가하면 Termux 시작 시 자동으로 SSH 서버가 켜집니다.

## `openclaw --version` 실패

### 원인

환경변수가 로드되지 않은 상태입니다.

### 해결 방법

```bash
source ~/.bashrc
```

또는 Termux 앱을 완전히 종료했다가 다시 여세요.

## "Cannot find module bionic-compat.js" 에러

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/bionic-compat.js'
```

### 원인

`~/.bashrc`의 `NODE_OPTIONS` 환경변수가 이전 설치 경로(`.openclaw-lite`)를 참조하고 있습니다. 프로젝트명이 "OpenClaw Lite"였던 이전 버전에서 업데이트한 경우 발생합니다.

### 해결 방법

업데이터를 실행하면 환경변수 블록이 갱신됩니다:

```bash
oaupdate && source ~/.bashrc
```

또는 수동으로 수정:

```bash
sed -i 's/\.openclaw-lite/\.openclaw-android/g' ~/.bashrc && source ~/.bashrc
```

## 업데이트 중 "systemctl --user unavailable: spawn systemctl ENOENT" 에러

```
Gateway service check failed: Error: systemctl --user unavailable: spawn systemctl ENOENT
```

### 원인

`openclaw update` 실행 후, OpenClaw이 `systemctl`로 게이트웨이 서비스를 재시작하려고 합니다. Termux에는 systemd가 없으므로 `systemctl` 바이너리를 찾을 수 없어 `ENOENT` 에러가 발생합니다.

### 영향

**이 에러는 무해합니다.** 업데이트 자체는 이미 성공적으로 완료되었으며, 자동 서비스 재시작만 실패한 것입니다. OpenClaw은 최신 상태로 업데이트되어 있습니다.

### 해결 방법

수동으로 게이트웨이를 시작하면 됩니다:

```bash
openclaw gateway
```

업데이트 전에 게이트웨이가 실행 중이었다면 기존 프로세스를 먼저 종료해야 할 수 있습니다. 위의 [게이트웨이가 시작되지 않음](#게이트웨이가-시작되지-않음-gateway-already-running-또는-port-is-already-in-use) 섹션을 참고하세요.

## `openclaw update` 중 sharp 빌드 실패

```
npm error gyp ERR! not ok
Update Result: ERROR
Reason: global update
```

### 원인

`openclaw update`가 npm으로 패키지를 업데이트할 때, npm을 서브프로세스로 실행합니다. `sharp` 네이티브 모듈 컴파일에 필요한 Termux 전용 빌드 환경변수(`CXXFLAGS`, `GYP_DEFINES`, `CPATH`)가 `~/.bashrc`에 설정되어 있지만, 해당 서브프로세스 환경에서는 자동으로 적용되지 않아 빌드가 실패합니다.

### 영향

**이 에러는 무해합니다.** OpenClaw 자체는 정상적으로 업데이트되었으며, `sharp` 모듈(이미지 처리용)만 리빌드에 실패한 것입니다. OpenClaw는 sharp 없이도 정상적으로 작동합니다.

### 해결 방법

업데이트 후 아래 스크립트로 sharp를 수동 빌드하세요:

```bash
bash ~/.openclaw-android/scripts/build-sharp.sh
```

또는 `openclaw update` 대신 `oaupdate`를 사용하면, 필요한 환경변수를 자동으로 설정하고 sharp 빌드까지 처리합니다:

```bash
oaupdate && source ~/.bashrc
```

## "not supported on android" 에러

```
Gateway status failed: Error: Gateway service install not supported on android
```

### 원인

`bionic-compat.js`의 `process.platform` 오버라이드가 적용되지 않은 상태입니다.

### 해결 방법

`NODE_OPTIONS` 환경변수가 설정되어 있는지 확인:

```bash
echo $NODE_OPTIONS
```

비어있으면 환경변수를 로드하세요:

```bash
source ~/.bashrc
```

`NODE_OPTIONS`가 설정되어 있는데도 에러가 나면, `bionic-compat.js` 파일이 최신인지 확인:

```bash
node -e "console.log(process.platform)"
```

`android`가 출력되면 파일이 오래된 버전입니다. 재설치하세요:

```bash
curl -sL myopenclawhub.com/install | bash
```
