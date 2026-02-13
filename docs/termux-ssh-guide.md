# Termux SSH Setup Guide

By connecting to Termux via SSH from your computer, you can type all commands using your computer keyboard.

## Prerequisites

- **USB cable**: To connect your phone to the computer
- **Same Wi-Fi network**: Both phone and computer must be on the same Wi-Fi network

## Step 1: Enable USB Debugging

> If you already enabled Developer Options in README Step 1, skip to **B**.

**A. Enable Developer Options**

1. On your phone, go to **Settings** > **About phone** (or **Device information**)
2. Tap **Build number** 7 times
3. You'll see "Developer mode has been enabled"

**B. Turn on USB Debugging**

1. Go to **Settings** > **Developer options**
2. Turn on **USB debugging**
3. Connect your phone to the computer with a USB cable
4. When prompted "Allow USB debugging?", tap **Allow**

## Step 2: Verify adb Connection

Open a terminal on your computer (Mac: Terminal, Windows: PowerShell or Command Prompt) and type:

```bash
adb devices
```

If your device appears like below, it's connected:

```
List of devices attached
XXXXXXXX    device
```

> If `adb` command is not found, install [Android Studio](https://developer.android.com/studio). adb is included with Android Studio.

## Step 3: Install SSH on Termux

With the **Termux app open on your phone**, enter the following commands one by one in **your computer's terminal**.

> `adb shell input text` automatically types text on the phone screen. `%s` represents a space character.

**Install openssh:**

```bash
adb shell input text 'pkg%sinstall%s-y%sopenssh'
```
```bash
adb shell input keyevent 66
```

> `keyevent 66` is the same as pressing the Enter key.

The installation will proceed on the phone screen. Wait until it completes (1-2 minutes).

## Step 4: Set Password

From your computer terminal:

```bash
adb shell input text 'passwd'
```
```bash
adb shell input keyevent 66
```

`New password:` will appear on the Termux screen.
Enter the password **directly on the phone** (e.g., `1234`).

```
New password: 1234          ← type on the phone
Retype new password: 1234   ← type the same password again
```

> It's normal that nothing shows on screen while typing the password. Just type it and press Enter.

## Step 5: Start SSH Server

From your computer terminal:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

If the prompt (`$`) returns with no error message, it's working.

## Step 6: Find the Phone's IP Address

From your computer terminal:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

Look for the `wlan0` section on the Termux screen:

```
wlan0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500
        inet 192.168.45.139  netmask 255.255.255.0
```

The number after `inet` is your phone's IP address (in this example, `192.168.45.139`).

## Step 7: Connect via SSH from Computer

From your computer terminal (replace the IP address with the one you found above):

```bash
ssh -p 8022 192.168.45.139
```

- `Are you sure you want to continue connecting?` → type `yes`
- `Password:` → enter the password you set in Step 4 (e.g., `1234`)

Once connected, you'll see the Termux `$` prompt. From now on, you can type all Termux commands using your computer keyboard.

## Notes

- Termux uses SSH port **8022** (not the standard Linux port 22)
- If you close the Termux app, the SSH server stops. To reconnect, open Termux on the phone and run `sshd`
- To start sshd automatically, add `sshd 2>/dev/null` to the end of your `~/.bashrc` file so the SSH server starts whenever Termux opens
