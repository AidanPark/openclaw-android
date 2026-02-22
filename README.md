# OpenClaw on Android

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![Termux](https://img.shields.io/badge/Termux-Required-orange)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)
![GitHub Stars](https://img.shields.io/github/stars/AidanPark/openclaw-android)

Because Android deserves a shell.

## Why?

An Android phone is a great environment for running an OpenClaw server:

- **Sufficient performance** — Even models from a few years ago have more than enough specs to run OpenClaw
- **Repurpose old phones** — Put that phone sitting in your drawer to good use. No need to buy a mini PC
- **Low power + built-in UPS** — Runs 24/7 on a fraction of the power a PC would consume, and the battery keeps it alive through power outages
- **No personal data at risk** — Install OpenClaw on a factory-reset phone with no accounts logged in, and there's zero personal data on the device. Dedicating a PC to this feels wasteful — a spare phone is perfect

## No Linux install required

The standard approach to running OpenClaw on Android requires installing proot-distro with Linux, adding 700MB-1GB of overhead. OpenClaw on Android eliminates this by patching compatibility issues directly, letting you run OpenClaw in pure Termux.

| | Standard (proot-distro) | This project |
|---|---|---|
| Storage overhead | 1-2GB (Linux + packages) | ~50MB |
| Setup time | 20-30 min | 3-10 min |
| Performance | Slower (proot layer) | Native speed |
| Setup steps | Install distro, configure Linux, install Node.js, fix paths... | Run one command |

## Requirements

- Android 7.0 or higher (Android 10+ recommended)
- ~500MB free storage
- Wi-Fi or mobile data connection

## Step-by-Step Setup (from a fresh phone)

1. [Enable Developer Options and Stay Awake](#step-1-enable-developer-options-and-stay-awake)
2. [Install Termux](#step-2-install-termux)
3. [Initial Termux Setup and Background Kill Prevention](#step-3-initial-termux-setup-and-background-kill-prevention)
4. [Install OpenClaw](#step-4-install-openclaw) — one command
5. [Start OpenClaw Setup](#step-5-start-openclaw-setup)
6. [Start OpenClaw (Gateway)](#step-6-start-openclaw-gateway)
7. [Access the Dashboard from Your PC](#step-7-access-the-dashboard-from-your-pc)

### Step 1: Enable Developer Options and Stay Awake

OpenClaw runs as a server, so the screen turning off can cause Android to throttle or kill the process. Keeping the screen on while charging ensures stable operation.

**A. Enable Developer Options**

1. Go to **Settings** > **About phone** (or **Device information**)
2. Tap **Build number** 7 times
3. You'll see "Developer mode has been enabled"
4. Enter your lock screen password if prompted

> On some devices, Build number is under **Settings** > **About phone** > **Software information**.

**B. Stay Awake While Charging**

1. Go to **Settings** > **Developer options** (the menu you just enabled)
2. Turn on **Stay awake**
3. The screen will now stay on whenever the device is charging (USB or wireless)

> The screen will still turn off normally when unplugged. Keep the charger connected when running the server for extended periods.

**C. Set Charge Limit (Required)**

Keeping a phone plugged in 24/7 at 100% can cause battery swelling. Limiting the maximum charge to 80% greatly improves battery lifespan and safety.

- **Samsung**: **Settings** > **Battery** > **Battery Protection** → Select **Maximum 80%**
- **Google Pixel**: **Settings** > **Battery** > **Battery Protection** → ON

> Menu names vary by manufacturer. Search for "battery protection" or "charge limit" in your settings. If your device doesn't have this feature, consider managing the charger manually or using a smart plug.

### Step 2: Install Termux

> **Important**: The Play Store version of Termux is discontinued and will not work. You must install from F-Droid.

1. Open your phone's browser and go to [f-droid.org](https://f-droid.org)
2. Search for `Termux`, then tap **Download APK** to download and install
   - Allow "Install from unknown sources" when prompted

### Step 3: Initial Termux Setup and Background Kill Prevention

Open the Termux app and paste the following command. It updates repos, installs curl, and enables background kill prevention — all in one go.

```bash
pkg update -y && pkg upgrade -y && pkg install -y curl && termux-wake-lock
```

> You may be asked to choose a mirror on first run. Pick any — a geographically closer mirror will be faster.

Once `termux-wake-lock` runs, a notification pins in the status bar and prevents Android from killing the Termux process. To release it later, run `termux-wake-unlock` or swipe the notification away.

**Disable Battery Optimization for Termux**

1. Go to Android **Settings** > **Battery** (or **Battery and device care**)
2. Open **Battery optimization** (or **App power management**)
3. Find **Termux** and set it to **Not optimized** (or **Unrestricted**)

> The exact menu path varies by manufacturer (Samsung, LG, etc.) and Android version. Search your settings for "battery optimization" to find it.

### Step 4: Install OpenClaw

> **Tip: Use SSH for easier typing**
> From this step on, you can type commands from your computer keyboard instead of the phone screen.
> Run the following on your phone first, then connect from your PC with `ssh -p 8022 <phone-ip>`:
> ```
> pkg install -y openssh && passwd && sshd
> ```

Paste the following command in Termux.

```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
```

Everything is installed automatically with a single command. This takes 3–10 minutes depending on network speed and device. Wi-Fi is recommended.

Once complete, the OpenClaw version is displayed along with instructions to run `openclaw onboard`.

### Step 5: Start OpenClaw Setup

As instructed in the installation output, run:

```bash
openclaw onboard
```

Follow the on-screen instructions to complete the initial setup.

![openclaw onboard](docs/images/openclaw-onboard.png)

### Step 6: Start OpenClaw (Gateway)

Once setup is complete, start the gateway:

> **Important**: Run `openclaw gateway` directly in the Termux app on your phone, not via SSH. If you run it over SSH, the gateway will stop when the SSH session disconnects.

```bash
openclaw gateway
```

> To stop the gateway, press `Ctrl+C`. Do not use `Ctrl+Z` — it only suspends the process without terminating it.

### Step 7: Access the Dashboard from Your PC

To manage OpenClaw from your PC browser, you need to set up an SSH connection to your phone. See the [Termux SSH Setup Guide](docs/termux-ssh-guide.md) to configure SSH access first.

Once SSH is ready, find your phone's IP address. Run the following in Termux and look for the `inet` address under `wlan0` (e.g. `192.168.0.100`).

```bash
ifconfig
```

Then open a new terminal on your PC and set up an SSH tunnel:

```bash
ssh -N -L 18789:127.0.0.1:18789 -p 8022 <phone-ip>
```

Then open in your PC browser: `http://localhost:18789/`

> Run `openclaw dashboard` on the phone to get the full URL with token.

## Managing Multiple Devices

If you run OpenClaw on multiple devices on the same network, use the <a href="https://myopenclawhub.com" target="_blank">Dashboard Connect</a> tool to manage them from your PC.

- Save connection settings (IP, token, ports) for each device with a nickname
- Generates the SSH tunnel command and dashboard URL automatically
- **Your data stays local** — Connection settings (IP, token, ports) are saved only in your browser's localStorage and are never sent to any server.

## Bonus: AI CLI Tools on Your Phone

The compatibility patches included in this project fix Termux's native build environment, enabling popular AI CLI tools to install and run:

| Tool | Install |
|------|---------|
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | `npm i -g @anthropic-ai/claude-code` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | `npm i -g @google/gemini-cli` |
| [Codex CLI](https://github.com/openai/codex) (OpenAI) | `npm i -g @openai/codex` |

Install OpenClaw on Android first, then install any of these tools — the patches handle the rest.

<p>
  <img src="docs/images/run_claude.png" alt="Claude Code on Termux" width="32%">
  <img src="docs/images/run_gemini.png" alt="Gemini CLI on Termux" width="32%">
  <img src="docs/images/run_codex.png" alt="Codex CLI on Termux" width="32%">
</p>

## Update

If you already have OpenClaw on Android installed and want to apply the latest patches and environment updates:

```bash
oaupdate && source ~/.bashrc
```

This single command updates both OpenClaw (`openclaw update`) and the Android compatibility patches from this project. Safe to run multiple times.

> If the `oaupdate` command is not available (older installations), run it with curl:
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## Uninstall

```bash
bash ~/.openclaw-android/uninstall.sh
```

This removes the OpenClaw package, patches, environment variables, and temp files. Your OpenClaw data (`~/.openclaw`) is optionally preserved.

## Troubleshooting

See the [Troubleshooting Guide](docs/troubleshooting.md) for detailed solutions.

## What It Does

The installer automatically resolves the differences between Termux and standard Linux. There's nothing you need to do manually — the single install command handles all 5 of these:

1. **Platform recognition** — Configures Android to be recognized as Linux
2. **Network error prevention** — Automatically works around network-related crashes on Android
3. **Path conversion** — Automatically converts standard Linux paths to Termux paths
4. **Temp folder setup** — Automatically configures an accessible temp folder for Android
5. **Service manager bypass** — Configures normal operation without systemd

## Performance

CLI commands like `openclaw status` may feel slower than on a PC. This is because each command needs to read many files, and the phone's storage is slower than a PC's, with Android's security processing adding overhead.

However, **once the gateway is running, there's no difference**. The process stays in memory so files don't need to be re-read, and AI responses are processed on external servers — the same speed as on a PC.

<details>
<summary>Technical Documentation for Developers</summary>

## Project Structure

```
openclaw-android/
├── bootstrap.sh                # curl | bash one-liner installer (downloader)
├── install.sh                  # One-click installer (entry point)
├── update.sh                   # Thin wrapper (downloads and runs update-core.sh)
├── update-core.sh              # Lightweight updater for existing installations
├── uninstall.sh                # Clean removal
├── patches/
│   ├── bionic-compat.js        # Platform override + os.networkInterfaces() + os.cpus() patches
│   ├── termux-compat.h         # C/C++ compatibility shim (renameat2 syscall wrapper)
│   ├── spawn.h                 # POSIX spawn stub header for Termux
│   ├── patch-paths.sh          # Fix hardcoded paths in OpenClaw
│   └── apply-patches.sh        # Patch orchestrator
├── scripts/
│   ├── build-sharp.sh          # Build sharp native module (image processing)
│   ├── check-env.sh            # Pre-flight environment check
│   ├── install-deps.sh         # Install Termux packages
│   ├── setup-env.sh            # Configure environment variables
│   └── setup-paths.sh          # Create directories and symlinks
├── tests/
│   └── verify-install.sh       # Post-install verification
└── docs/
    ├── termux-ssh-guide.md     # Termux SSH setup guide (EN)
    ├── termux-ssh-guide.ko.md  # Termux SSH setup guide (KO)
    ├── troubleshooting.md      # Troubleshooting guide (EN)
    ├── troubleshooting.ko.md   # Troubleshooting guide (KO)
    └── images/                 # Screenshots and images
```

## Detailed Installation Flow

Running `bash install.sh` executes the following 7 steps in order.

### [1/7] Environment Check — `scripts/check-env.sh`

Validates that the current environment is suitable before starting installation.

- **Termux detection**: Checks for the `$PREFIX` environment variable. Exits immediately if not in Termux
- **Architecture check**: Runs `uname -m` to verify CPU architecture (aarch64 recommended, armv7l supported, x86_64 treated as emulator)
- **Disk space**: Ensures at least 500MB free on the `$PREFIX` partition. Errors if insufficient
- **Existing installation**: If `openclaw` command already exists, shows current version and notes this is a reinstall/upgrade
- **Node.js pre-check**: If Node.js is already installed, shows version and warns if below 22

### [2/7] Package Installation — `scripts/install-deps.sh`

Installs Termux packages required for building and running OpenClaw.

- Runs `pkg update -y` to refresh package repos
- Installs the following packages:

| Package | Role | Why It's Needed |
|---------|------|-----------------|
| `nodejs-lts` | Node.js LTS runtime (>= 22) + npm package manager | OpenClaw is a Node.js application. Node.js and npm are required to install it via `npm install -g openclaw`. LTS is used because OpenClaw requires Node >= 22.12.0 |
| `git` | Distributed version control | Some npm packages have git dependencies. Sub-dependencies of OpenClaw may reference packages via git URLs. Also needed if installing this repo via `git clone` |
| `python` | Python interpreter | Used by `node-gyp` to run build scripts when compiling native C/C++ addons. Required when OpenClaw's dependency tree includes native modules (e.g., `better-sqlite3`, `bcrypt`) |
| `make` | Build automation tool | Executes Makefiles generated by `node-gyp` to compile native modules. Core part of the native build pipeline alongside `python` |
| `cmake` | Cross-platform build system | Some native modules use CMake-based builds instead of Makefiles. Cryptography-related libraries (`argon2`, etc.) often include CMakeLists.txt |
| `clang` | C/C++ compiler | Default C/C++ compiler in Termux. Used by `node-gyp` to compile C/C++ source of native modules. Termux uses Clang as standard instead of GCC |
| `binutils` | Binary utilities (ar, strip, etc.) | Provides `llvm-ar` for creating static archives during native module builds. The installer also creates an `ar → llvm-ar` symlink since many build systems expect a plain `ar` command |
| `tmux` | Terminal multiplexer | Allows running the OpenClaw server in a background session. In Termux, apps going to background may suspend processes, so running inside a tmux session keeps it stable |
| `ttyd` | Web terminal | Shares a terminal over the web. Used by [My OpenClaw Hub](https://myopenclawhub.com) to provide browser-based terminal access to the host |
| `pyyaml` (pip) | YAML parser for Python | Required for `.skill` packaging in OpenClaw. Installed via `pip install pyyaml` after the Termux packages |

- After installation, verifies Node.js >= 22 and npm presence. Exits on failure

### [3/7] Path Setup — `scripts/setup-paths.sh`

Creates the directory structure needed for Termux.

- `$PREFIX/tmp/openclaw` — OpenClaw temp directory (replaces `/tmp`)
- `$HOME/.openclaw-android/patches` — Patch file storage location
- `$HOME/.openclaw` — OpenClaw data directory
- Displays how standard Linux paths (`/bin/sh`, `/usr/bin/env`, `/tmp`) map to Termux's `$PREFIX` subdirectories

### [4/7] Environment Variables — `scripts/setup-env.sh`

Adds an environment variable block to `~/.bashrc`.

- Wraps the block with `# >>> OpenClaw on Android >>>` / `# <<< OpenClaw on Android <<<` markers for management
- If the block already exists, removes the old one and adds a fresh one (prevents duplicates)
- Environment variables set:
  - `TMPDIR=$PREFIX/tmp` — Use Termux temp directory instead of `/tmp`
  - `TMP`, `TEMP` — Same as `TMPDIR` (for compatibility with some tools)
  - `NODE_OPTIONS="-r .../bionic-compat.js"` — Auto-load Bionic compatibility patch for all Node processes
  - `CONTAINER=1` — Bypass systemd existence checks
  - `CFLAGS="-Wno-error=implicit-function-declaration"` — Prevent Clang from treating implicit function declarations as errors (needed for building native modules like `@discordjs/opus` that compile cleanly with GCC but fail under Clang's stricter defaults)
  - `CXXFLAGS="-include .../termux-compat.h"` — Force-include C/C++ compatibility shim for native module builds
  - `GYP_DEFINES="OS=linux ..."` — Override node-gyp OS detection for Android
  - `CPATH="...glib-2.0..."` — Provide glib header paths for sharp builds
- Creates an `ar → llvm-ar` symlink if missing (Termux provides `llvm-ar` but many build systems expect `ar`)

After running `setup-env.sh`, `install.sh` re-exports all environment variables in the current process. Since `setup-env.sh` runs as a subprocess, its exports don't propagate to the parent. This re-export ensures Step 5's `npm install` inherits the correct build environment (CFLAGS, CXXFLAGS, GYP_DEFINES, etc.).

### [5/7] OpenClaw Installation & Patching — `npm install` + `patches/apply-patches.sh`

Installs OpenClaw globally and applies Termux compatibility patches.

1. Copies compatibility patches to `~/.openclaw-android/patches/`:
   - `bionic-compat.js` — Node.js runtime patches (needed during npm install)
   - `termux-compat.h` — C/C++ build compatibility (renameat2 syscall wrapper)
   - `spawn.h` → `$PREFIX/include/spawn.h` — POSIX spawn stub header (if missing)
2. Installs `update.sh` wrapper as `$PREFIX/bin/oaupdate` for convenient updating
3. Runs `npm install -g openclaw@latest`
4. `patches/apply-patches.sh` applies all patches:
   - Verifies `bionic-compat.js` final copy
   - Installs `systemctl` stub to `$PREFIX/bin/systemctl` — a minimal script that intercepts systemd service management calls (which would fail in Termux since there is no systemd)
   - Runs `patches/patch-paths.sh` — uses sed to replace hardcoded paths in installed OpenClaw JS files:
     - `"/tmp"` / `'/tmp'` → `"$PREFIX/tmp"` / `'$PREFIX/tmp'`
     - `"/bin/sh"` → `"$PREFIX/bin/sh"`
     - `"/bin/bash"` → `"$PREFIX/bin/bash"`
     - `"/usr/bin/env"` → `"$PREFIX/bin/env"`
   - Logs patch results to `~/.openclaw-android/patch.log`
5. `scripts/build-sharp.sh` builds the sharp native module for image processing (non-critical):
   - Installs `libvips` and `binutils` packages
   - Installs `node-gyp` globally
   - Sets `GYP_DEFINES` and `CPATH` for Android/Termux cross-compilation
   - Runs `npm rebuild sharp` inside the OpenClaw directory
   - If the build fails, prints a warning and continues — image processing won't work but the gateway runs normally

### [6/7] Installation Verification — `tests/verify-install.sh`

Checks 7 items to confirm installation completed successfully.

| Check Item | PASS Condition |
|------------|---------------|
| Node.js version | `node -v` >= 22 |
| npm | `npm` command exists |
| openclaw | `openclaw --version` succeeds |
| TMPDIR | Environment variable is set |
| NODE_OPTIONS | Environment variable is set |
| CONTAINER | Set to `1` |
| bionic-compat.js | File exists in `~/.openclaw-android/patches/` |
| Directories | `~/.openclaw-android`, `~/.openclaw`, `$PREFIX/tmp` exist |
| .bashrc | Contains environment variable block |

All items pass → PASSED. Any failure → FAILED with reinstall instructions.

### [7/7] OpenClaw Update

Runs `openclaw update` to ensure the latest version. On completion, displays the OpenClaw version and instructs the user to run `openclaw onboard` to start setup.

## Lightweight Updater Flow — `oaupdate` / `update.sh`

Running `oaupdate` (or `curl ... update.sh | bash`) downloads `update-core.sh` from GitHub and executes the following 6 steps. Unlike the full installer, it skips environment checks, path setup, and verification — focusing only on refreshing patches, environment variables, and the OpenClaw package.

### [1/6] Pre-flight Check

Validates the minimum conditions for updating.

- Checks `$PREFIX` exists (Termux environment)
- Checks `openclaw` command exists (must already be installed)
- Checks `curl` is available (needed to download files)
- Migrates old directory name if needed (`.openclaw-lite` → `.openclaw-android` — legacy compatibility)

### [2/6] Installing New Packages

Installs packages that may have been added since the user's initial installation.

- `ttyd` — Web terminal for browser-based access. Skipped if already installed
- `PyYAML` — YAML parser for `.skill` packaging. Skipped if already installed

Both are non-critical — failures print a warning but don't stop the update.

### [3/6] Downloading Latest Scripts

Downloads the latest patch files and scripts from GitHub.

| File | Purpose | On Failure |
|------|---------|------------|
| `setup-env.sh` | Refresh `.bashrc` environment block | **Exit** (required) |
| `bionic-compat.js` | Node.js runtime compatibility patch | Warning |
| `termux-compat.h` | C/C++ build compatibility header | Warning |
| `spawn.h` | POSIX spawn stub (skipped if exists) | Warning |
| `systemctl` | systemd stub for Termux | Warning |
| `update.sh` | Install/update `oaupdate` command | Warning |
| `build-sharp.sh` | sharp native module build script | Warning |

Only `setup-env.sh` is required — all other failures are non-critical.

### [4/6] Updating Environment Variables

Runs the downloaded `setup-env.sh` to refresh the `.bashrc` environment block with the latest variables. Then re-exports all variables in the current process so that Step 5's `npm install` inherits the correct build environment.

### [5/6] Updating OpenClaw Package

- Installs build dependencies: `libvips` (for sharp) and `binutils` (for native builds)
- Creates `ar → llvm-ar` symlink if missing
- Runs `npm install -g openclaw@latest` — environment variables from Step 4 are inherited, enabling native modules (sharp, `@discordjs/opus`, etc.) to build correctly
- On failure, prints a warning and continues

### [6/6] Building sharp (image processing)

Runs `build-sharp.sh` to ensure the sharp native module is built. If sharp was already compiled successfully during Step 5's `npm install`, this step detects it and skips the rebuild.

</details>

## License

MIT
