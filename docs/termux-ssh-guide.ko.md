# Termux SSH 접속 가이드

폰의 Termux에 컴퓨터에서 SSH로 접속하면, 컴퓨터 키보드로 모든 명령어를 입력할 수 있습니다.

## 준비물

- **USB 케이블**: 폰과 컴퓨터를 연결
- **같은 Wi-Fi**: 폰과 컴퓨터가 같은 Wi-Fi 네트워크에 연결되어 있어야 합니다

## 1단계: USB 디버깅 활성화

> 이미 README의 1단계에서 개발자 옵션을 활성화했다면, **B**부터 진행하세요.

**A. 개발자 옵션 활성화**

1. 폰에서 **설정** > **휴대전화 정보** (또는 **디바이스 정보**)
2. **빌드 번호**를 7번 연속 탭
3. "개발자 모드가 활성화되었습니다" 메시지 확인

**B. USB 디버깅 켜기**

1. **설정** > **개발자 옵션**
2. **USB 디버깅**을 **ON**
3. USB 케이블로 폰과 컴퓨터를 연결
4. 폰에 "USB 디버깅을 허용하시겠습니까?" 팝업이 뜨면 **허용** 선택

## 2단계: adb 연결 확인

컴퓨터 터미널(맥: 터미널, 윈도우: PowerShell 또는 명령 프롬프트)을 열고 입력합니다:

```bash
adb devices
```

아래처럼 기기가 표시되면 정상입니다:

```
List of devices attached
XXXXXXXX    device
```

> `adb` 명령어가 없다고 나오면 [Android Studio](https://developer.android.com/studio)를 설치하세요. Android Studio에 adb가 포함되어 있습니다.

## 3단계: Termux에 SSH 설치

폰에서 **Termux 앱을 열어놓은 상태**에서, **컴퓨터 터미널**에서 아래 명령어를 하나씩 입력합니다.

> `adb shell input text`는 폰 화면에 글자를 자동으로 타이핑해주는 명령어입니다. `%s`는 스페이스(공백)를 의미합니다.

**openssh 설치:**

```bash
adb shell input text 'pkg%sinstall%s-y%sopenssh'
```
```bash
adb shell input keyevent 66
```

> `keyevent 66`은 Enter 키를 누르는 것과 같습니다.

폰 화면에서 설치가 진행됩니다. 완료될 때까지 기다리세요 (1~2분).

## 4단계: 비밀번호 설정

컴퓨터 터미널에서:

```bash
adb shell input text 'passwd'
```
```bash
adb shell input keyevent 66
```

폰의 Termux 화면에 `New password:` 가 표시됩니다.
**폰에서 직접** 비밀번호를 입력하세요 (예: `1234`).

```
New password: 1234          ← 폰에서 입력
Retype new password: 1234   ← 같은 비밀번호 다시 입력
```

> 비밀번호 입력 시 화면에 아무것도 표시되지 않는 것이 정상입니다. 그냥 입력하고 Enter를 누르면 됩니다.

## 5단계: SSH 서버 시작

컴퓨터 터미널에서:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

아무 메시지 없이 프롬프트(`$`)가 다시 나오면 정상입니다.

## 6단계: 폰의 IP 주소 확인

컴퓨터 터미널에서:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

폰의 Termux 화면에서 `wlan0` 항목을 찾으세요:

```
wlan0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500
        inet 192.168.45.139  netmask 255.255.255.0
```

`inet` 뒤의 숫자가 폰의 IP 주소입니다 (위 예시에서는 `192.168.45.139`).

## 7단계: 컴퓨터에서 SSH 접속

컴퓨터 터미널에서 (IP 주소를 위에서 확인한 값으로 변경):

```bash
ssh -p 8022 192.168.45.139
```

- `Are you sure you want to continue connecting?` → `yes` 입력
- `Password:` → 4단계에서 설정한 비밀번호 입력 (예: `1234`)

접속 성공하면 Termux의 `$` 프롬프트가 나타납니다. 이제부터 컴퓨터 키보드로 모든 Termux 명령어를 입력할 수 있습니다.

## 참고 사항

- Termux의 SSH 포트는 **8022**입니다 (일반 리눅스의 22가 아님)
- Termux 앱을 종료하면 SSH 서버도 꺼집니다. 다시 접속하려면 폰에서 Termux를 열고 `sshd`를 실행하세요
- 매번 `sshd`를 수동 실행하기 번거로우면 `~/.bashrc` 파일 맨 아래에 `sshd 2>/dev/null` 을 추가하면 Termux 시작 시 자동으로 SSH 서버가 켜집니다
