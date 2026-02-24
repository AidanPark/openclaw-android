# Phantom Process Killer 비활성화 (Android 12+)

Android 12 이상에는 **Phantom Process Killer**라는 기능이 포함되어 있어, 백그라운드 프로세스를 자동으로 종료합니다. 이로 인해 Termux에서 실행 중인 `openclaw gateway`, `sshd`, `ttyd` 등이 예고 없이 종료될 수 있습니다.

## 증상

Termux에서 다음과 같은 메시지가 보이면 Android가 프로세스를 강제 종료한 것입니다:

```
[Process completed (signal 9) - press Enter]
```

<img src="images/signal9/01-signal9-killed.png" width="300" alt="Process completed signal 9">

Signal 9 (SIGKILL)는 어떤 프로세스도 가로채거나 차단할 수 없습니다 — Android가 OS 수준에서 종료한 것입니다.

## 요구사항

- **Android 12 이상** (Android 11 이하는 해당 없음)
- **Termux**에 `android-tools` 설치 (OpenClaw on Android에 포함)

## 1단계: Wake Lock 활성화

알림 바를 내려서 Termux 알림을 찾으세요. **Acquire wakelock**을 탭하면 Android가 Termux를 중단시키는 것을 방지할 수 있습니다.

<p>
  <img src="images/signal9/02-termux-acquire-wakelock.png" width="300" alt="Acquire wakelock 탭">
  <img src="images/signal9/03-termux-wakelock-held.png" width="300" alt="Wake lock held">
</p>

활성화되면 알림에 **"wake lock held"**가 표시되고 버튼이 **Release wakelock**으로 바뀝니다.

> Wake lock만으로는 Phantom Process Killer를 완전히 막을 수 없습니다. 아래 단계를 계속 진행하세요.

## 2단계: 무선 디버깅 활성화

1. **설정** > **개발자 옵션**으로 이동
2. **무선 디버깅** (Wireless debugging)을 찾아서 활성화
3. 확인 다이얼로그가 나타나면 — **"이 네트워크에서 항상 허용"**을 체크하고 **허용** 탭

<img src="images/signal9/04-wireless-debugging-allow.png" width="300" alt="무선 디버깅 허용">

## 3단계: ADB 설치 (아직 설치하지 않은 경우)

Termux에서 `android-tools`를 설치합니다:

```bash
pkg install -y android-tools
```

> OpenClaw on Android를 설치했다면 `android-tools`가 이미 포함되어 있습니다.

## 4단계: ADB 페어링

1. **무선 디버깅** 설정에서 **페어링 코드로 기기 페어링** (Pair device with pairing code) 탭
2. **Wi-Fi 페어링 코드**와 **IP 주소 및 포트**가 표시된 다이얼로그가 나타남

<img src="images/signal9/05-pairing-code-dialog.png" width="300" alt="페어링 코드 다이얼로그">

3. Termux에서 화면에 표시된 포트와 코드를 사용하여 페어링 명령을 실행합니다:

```bash
adb pair localhost:<페어링_포트> <페어링_코드>
```

예시:

```bash
adb pair localhost:39555 269556
```

<img src="images/signal9/06-adb-pair-success.png" width="600" alt="adb pair 성공">

`Successfully paired`가 표시되면 성공입니다.

## 5단계: ADB 연결

페어링 후 **무선 디버깅** 메인 화면으로 돌아가세요. 상단에 표시된 **IP 주소 및 포트**를 확인합니다 — 이 포트는 페어링 포트와 다릅니다.

<img src="images/signal9/07-wireless-debugging-paired.png" width="300" alt="무선 디버깅 페어링 완료">

Termux에서 메인 화면에 표시된 포트로 연결합니다:

```bash
adb connect localhost:<연결_포트>
```

예시:

```bash
adb connect localhost:35541
```

`connected to localhost:35541`이 표시되면 성공입니다.

> 페어링 포트와 연결 포트는 다릅니다. `adb connect`에는 무선 디버깅 메인 화면에 표시된 포트를 사용하세요.

## 6단계: Phantom Process Killer 비활성화

다음 명령을 실행하여 Phantom Process Killer를 비활성화합니다:

```bash
adb shell "settings put global settings_enable_monitor_phantom_procs false"
```

설정이 적용되었는지 확인합니다:

```bash
adb shell "settings get global settings_enable_monitor_phantom_procs"
```

출력이 `false`이면 Phantom Process Killer가 성공적으로 비활성화된 것입니다.

<img src="images/signal9/08-adb-disable-ppk-done.png" width="600" alt="Phantom Process Killer 비활성화 완료">

## 참고 사항

- 이 설정은 **재부팅해도 유지**됩니다 — 한 번만 하면 됩니다
- 이 과정을 완료한 후 무선 디버깅을 켜둘 필요는 없습니다. 꺼도 됩니다
- 일반 앱 동작에는 영향을 주지 않습니다 — Termux의 백그라운드 프로세스가 종료되는 것만 방지합니다
- 폰을 초기화하면 이 과정을 다시 수행해야 합니다

## 추가 참고

일부 제조사(삼성, 샤오미, 화웨이 등)는 자체적으로 공격적인 배터리 최적화를 적용하여 백그라운드 앱을 종료시킬 수 있습니다. Phantom Process Killer를 비활성화한 후에도 프로세스가 종료되는 경우, [dontkillmyapp.com](https://dontkillmyapp.com)에서 기기별 가이드를 확인하세요.
