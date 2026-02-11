# OpenClaw Lite Android

Run [OpenClaw](https://github.com/openclaw) on Android using Termux — **without proot-distro**.

> [한국어](README.ko.md)

## Why?

The standard approach to running OpenClaw on Android requires installing proot-distro with Ubuntu, adding 700MB-1GB of overhead. OpenClaw Lite Android eliminates this by patching compatibility issues directly, letting you run OpenClaw in pure Termux.

| | Standard (proot-distro) | Lite (this project) |
|---|---|---|
| Storage overhead | 700MB - 1GB | ~50MB |
| Setup time | 10-15 min | 3-5 min |
| Performance | Slower (proot layer) | Native speed |
| Complexity | High | One command |

## Requirements

- Android 7.0 or higher
- ~500MB free storage
- Wi-Fi or mobile data connection

## Step-by-Step Setup (from a fresh phone)

### Step 1: Enable Developer Options and Stay Awake

OpenClaw runs as a server, so the screen turning off can cause Android to throttle or kill the process. Keeping the screen on while charging ensures stable operation.

**A. Enable Developer Options**

1. Go to **Settings** > **About phone**
2. Tap **Build number** 7 times
3. You'll see "Developer mode has been enabled"
4. Enter your lock screen password if prompted

> On some devices, Build number is under **Settings** > **About phone** > **Software information**.

**B. Stay Awake While Charging**

1. Go to **Settings** > **Developer options** (the menu you just enabled)
2. Turn on **Stay awake**
3. The screen will now stay on whenever the device is charging (USB or wireless)

> The screen will still turn off normally when unplugged. Keep the charger connected when running the server for extended periods.

### Step 2: Install Termux

> **Important**: The Play Store version of Termux is discontinued and will not work. You must install from F-Droid.

1. Open your phone's browser and go to [f-droid.org](https://f-droid.org)
2. Download and install `F-Droid.apk`
   - Allow "Install from unknown sources" when prompted
3. Open F-Droid and search for `Termux`
4. Install **Termux** (by Fredrik Fornwall)
5. Optionally install **Termux:API** as well (recommended)

### Step 3: Initial Termux Setup and Background Kill Prevention

Open the Termux app and run:

```bash
# Update repos (required on first run)
pkg update -y

# Install curl (needed for bootstrap download)
pkg install -y curl
```

You may be asked to choose a mirror on first run. Pick any — a geographically closer mirror will be faster.

Next, protect Termux from being killed during installation. The install takes 3–10 minutes, and if Android kills the process mid-way, it will fail.

**A. Enable Termux Wake Lock**

```bash
termux-wake-lock
```

This pins a notification and prevents Android from killing the Termux process. To release it later, run `termux-wake-unlock` or swipe the notification away.

**B. Disable Battery Optimization for Termux**

1. Go to Android **Settings** > **Battery** (or **Battery and device care**)
2. Open **Battery optimization** (or **App power management**)
3. Find **Termux** and set it to **Not optimized** (or **Unrestricted**)

> The exact menu path varies by manufacturer (Samsung, Pixel, etc.) and Android version. Search your settings for "battery optimization" to find it.

### Step 4: Install OpenClaw

```bash
curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-lite-android/main/bootstrap.sh | bash
```

This takes 3–10 minutes depending on network speed and device. Wi-Fi is recommended.

### Step 5: Apply Environment

Either restart the Termux app completely, or run:

```bash
source ~/.bashrc
```

### Step 6: Verify

```bash
openclaw --version
```

If a version number prints, you're done.

<details>
<summary>Alternative: git clone</summary>

```bash
pkg update -y && pkg install -y git
git clone https://github.com/AidanPark/openclaw-lite-android.git
cd openclaw-lite-android
bash install.sh
source ~/.bashrc
```
</details>

## What It Does

The installer handles 4 compatibility issues between Termux and standard Linux:

1. **Bionic libc crash** — `os.networkInterfaces()` crashes on Android's Bionic libc. A preloaded JS shim wraps it in try-catch with a safe fallback.

2. **Hardcoded system paths** — Node packages expect `/bin/sh`, `/tmp`, etc. The installer patches these to use Termux's `$PREFIX` paths.

3. **No `/tmp` access** — Android blocks writes to `/tmp`. Redirected to `$PREFIX/tmp`.

4. **No systemd** — Some install steps check for systemd. The `CONTAINER=1` env var bypasses these checks.

## Project Structure

```
openclaw-lite-android/
├── install.sh                  # One-click installer (entry point)
├── uninstall.sh                # Clean removal
├── patches/
│   ├── bionic-compat.js        # os.networkInterfaces() safe wrapper
│   ├── patch-paths.sh          # Fix hardcoded paths in OpenClaw
│   └── apply-patches.sh        # Patch orchestrator
├── scripts/
│   ├── check-env.sh            # Pre-flight environment check
│   ├── install-deps.sh         # Install Termux packages
│   ├── setup-env.sh            # Configure environment variables
│   └── setup-paths.sh          # Create directories and symlinks
└── tests/
    └── verify-install.sh       # Post-install verification
```

## Uninstall

```bash
bash uninstall.sh
```

This removes the OpenClaw package, patches, environment variables, and temp files. Your OpenClaw data (`~/.openclaw`) is optionally preserved.

## Troubleshooting

### `openclaw --version` fails after install
Restart Termux or run `source ~/.bashrc` to load the environment variables.

### npm install fails with native module errors
Ensure build tools are installed:
```bash
pkg install python make cmake clang
```

### "Not running in Termux" error
This project is designed for Termux only. Make sure you're running from within the Termux app, not adb shell or another terminal.

## License

MIT
