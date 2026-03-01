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

The standard approach to running OpenClaw on Android requires installing proot-distro with Linux, adding 700MB-1GB of overhead. OpenClaw on Android eliminates this by installing a lightweight glibc runtime directly into Termux, letting you run OpenClaw without a full Linux distribution.

**Standard approach**: Install a full Linux distribution on top of Termux.

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│ ┌───────────────────────────────────────────────┐ │
│ │ Android · Bionic libc                         │ │
│ │ ┌───────────────────────────────────────────┐ │ │
│ │ │ Termux                                    │ │ │
│ │ │ ┌───────────────────────────────────────┐ │ │ │
│ │ │ │ proot-distro · Debian/Ubuntu          │ │ │ │
│ │ │ │ ┌───────────────────────────────────┐ │ │ │ │
│ │ │ │ │ GNU glibc                         │ │ │ │ │
│ │ │ │ │ Node.js → OpenClaw                │ │ │ │ │
│ │ │ │ └───────────────────────────────────┘ │ │ │ │
│ │ │ └───────────────────────────────────────┘ │ │ │
│ │ └───────────────────────────────────────────┘ │ │
│ └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

**This project**: No proot-distro — just the glibc dynamic linker.

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│ ┌───────────────────────────────────────────────┐ │
│ │ Android · Bionic libc                         │ │
│ │ ┌───────────────────────────────────────────┐ │ │
│ │ │ Termux + glibc-runner                     │ │ │
│ │ │ ┌───────────────────────────────────────┐ │ │ │
│ │ │ │ glibc ld.so (linker only)             │ │ │ │
│ │ │ │ ld.so → Node.js → OpenClaw            │ │ │ │
│ │ │ └───────────────────────────────────────┘ │ │ │
│ │ │ OpenCode · code-server · git ...          │ │ │
│ │ └───────────────────────────────────────────┘ │ │
│ └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

| | Standard (proot-distro) | This project |
|---|---|---|
| Storage overhead | 1-2GB (Linux + packages) | ~200MB |
| Setup time | 20-30 min | 3-10 min |
| Performance | Slower (proot layer) | Native speed |
| Setup steps | Install distro, configure Linux, install Node.js, fix paths... | Run one command |

## Requirements

- Android 7.0 or higher (Android 10+ recommended)
- ~1GB free storage
- Wi-Fi or mobile data connection

## Step-by-Step Setup (from a fresh phone)

1. [Enable Developer Options and Stay Awake](#step-1-enable-developer-options-and-stay-awake)
2. [Install Termux](#step-2-install-termux)
3. [Initial Termux Setup](#step-3-initial-termux-setup)
4. [Install OpenClaw](#step-4-install-openclaw) — one command
5. [Start OpenClaw Setup](#step-5-start-openclaw-setup)
6. [Start OpenClaw (Gateway)](#step-6-start-openclaw-gateway)

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

### Step 3: Initial Termux Setup

Open the Termux app and paste the following command to install curl (needed for the next step).

```bash
pkg update -y && pkg install -y curl
```

> You may be asked to choose a mirror on first run. Pick any — a geographically closer mirror will be faster.

**Disable Battery Optimization for Termux**

1. Go to Android **Settings** > **Battery** (or **Battery and device care**)
2. Open **Battery optimization** (or **App power management**)
3. Find **Termux** and set it to **Not optimized** (or **Unrestricted**)

> The exact menu path varies by manufacturer (Samsung, LG, etc.) and Android version. Search your settings for "battery optimization" to find it.

### Step 4: Install OpenClaw

> **Tip: Use SSH for easier typing**
> From this step on, you can type commands from your computer keyboard instead of the phone screen. See the [Termux SSH Setup Guide](docs/termux-ssh-guide.md) for details.

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

The gateway occupies the terminal while running, so open a new tab for it. Tap the **hamburger icon (☰)** on the bottom menu bar, or swipe right from the left edge of the screen (above the bottom menu bar) to open the side menu. Then tap **NEW SESSION**.

<img src="docs/images/termux_menu.png" width="300" alt="Termux side menu">

In the new tab, run:

```bash
openclaw gateway
```

<img src="docs/images/termux_tab_1.png" width="300" alt="openclaw gateway running">

> To stop the gateway, press `Ctrl+C`. Do not use `Ctrl+Z` — it only suspends the process without terminating it.

## Disable Phantom Process Killer (Android 12+)

Android 12 and above may forcibly kill background processes like `openclaw gateway` and `sshd` without warning (you'll see `[Process completed (signal 9)]`). Disabling the Phantom Process Killer prevents this. The setting persists across reboots — you only need to do it once.

See the [step-by-step guide with screenshots](docs/disable-phantom-process-killer.md) to disable it using ADB from within Termux.

## Access the Dashboard from Your PC

To manage OpenClaw from your PC browser, you need to set up an SSH connection to your phone. See the [Termux SSH Setup Guide](docs/termux-ssh-guide.md) to configure SSH access first. Open another new tab on the phone for `sshd` (same method as Step 6).

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

## CLI Reference

After installation, the `oa` command is available for managing your installation:

| Option | Description |
|--------|-------------|
| `oa --update` | Update OpenClaw and Android patches |
| `oa --uninstall` | Remove OpenClaw on Android |
| `oa --status` | Show installation status and all installed components |
| `oa --version` | Show version |
| `oa --help` | Show available options |

## Status Check

```bash
oa --status
```

Shows the installation status of OpenClaw and all installed components — code-server, ttyd, dufs, OpenCode, oh-my-opencode, AI CLI tools (Claude Code, Gemini CLI, Codex CLI), glibc environment, and system configuration. Useful for diagnosing issues or verifying your setup at a glance.


## Update

```bash
oa --update && source ~/.bashrc
```

This single command updates all installed components at once:

- **OpenClaw** — Core package (`openclaw@latest`)
- **code-server** — Browser IDE
- **OpenCode + oh-my-opencode** — AI coding assistant
- **AI CLI tools** — Claude Code, Gemini CLI, Codex CLI
- **Android patches** — Compatibility patches from this project

Already up-to-date components are skipped. Components you haven't installed are not touched — only what's already on your device gets updated. Safe to run multiple times.

> If the `oa` command is not available (older installations), run it with curl:
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## Uninstall

```bash
oa --uninstall
```

This removes the OpenClaw package, patches, environment variables, and temp files. You'll be prompted individually for OpenCode, oh-my-opencode, installation directory, OpenClaw data, and AI CLI tools.

## Troubleshooting

See the [Troubleshooting Guide](docs/troubleshooting.md) for detailed solutions.

## What It Does

The installer automatically resolves the differences between Termux and standard Linux. There's nothing you need to do manually — the single install command handles all of these:

1. **glibc environment** — Installs a glibc runtime (via pacman's glibc-runner) so standard Linux binaries run without modification
2. **Node.js (glibc)** — Downloads official Node.js linux-arm64 and wraps it with an ld.so loader script (no patchelf, which causes segfault on Android)
3. **Path conversion** — Automatically converts standard Linux paths (`/tmp`, `/bin/sh`, `/usr/bin/env`) to Termux paths
4. **Temp folder setup** — Configures an accessible temp folder for Android
5. **Service manager bypass** — Configures normal operation without systemd
6. **OpenCode integration** — Installs OpenCode + oh-my-opencode using proot + ld.so concatenation for Bun standalone binaries

## Performance

CLI commands like `openclaw status` may feel slower than on a PC. This is because each command needs to read many files, and the phone's storage is slower than a PC's, with Android's security processing adding overhead.

However, **once the gateway is running, there's no difference**. The process stays in memory so files don't need to be re-read, and AI responses are processed on external servers — the same speed as on a PC.

## Local LLM on Android

OpenClaw supports local LLM inference via [node-llama-cpp](https://github.com/withcatai/node-llama-cpp). The prebuilt native binary (`@node-llama-cpp/linux-arm64`) is included with the installation and loads successfully under the glibc environment — **local LLM is technically functional on the phone**.

However, there are practical constraints:

| Constraint | Details |
|------------|---------|
| RAM | GGUF models need at least 2-4GB of free memory (7B model, Q4 quantization). Phone RAM is shared with Android and other apps |
| Storage | Model files range from 4GB to 70GB+. Phone storage fills up fast |
| Speed | CPU-only inference on ARM is very slow. Android does not support GPU offloading for llama.cpp |
| Use case | OpenClaw primarily routes to cloud LLM APIs (OpenAI, Gemini, etc.) which respond at the same speed as on a PC. Local inference is a supplementary feature |

For experimentation, small models like TinyLlama 1.1B (Q4, ~670MB) can run on the phone. For production use, cloud LLM providers are recommended.

> **Why `--ignore-scripts`?** The installer uses `npm install -g openclaw@latest --ignore-scripts` because node-llama-cpp's postinstall script attempts to compile llama.cpp from source via cmake — a process that takes 30+ minutes on a phone and fails due to toolchain incompatibilities. The prebuilt binaries work without this compilation step, so the postinstall is safely skipped.

<details>
<summary>Technical Documentation for Developers</summary>

## Project Structure

```
openclaw-android/
├── bootstrap.sh                # curl | bash one-liner installer (downloader)
├── install.sh                  # One-click installer (entry point)
├── oa.sh                       # Unified CLI (installed as $PREFIX/bin/oa)
├── update.sh                   # Thin wrapper (downloads and runs update-core.sh)
├── update-core.sh              # Lightweight updater for existing installations
├── uninstall.sh                # Clean removal
├── patches/
│   ├── glibc-compat.js        # Node.js runtime patches (os.cpus, networkInterfaces)
│   ├── argon2-stub.js          # JS stub for argon2 native module (code-server)
│   ├── termux-compat.h         # C header for Bionic native builds (sharp)
│   ├── spawn.h                 # POSIX spawn stub header
│   ├── systemctl               # systemd stub for Termux
│   ├── patch-paths.sh          # Fix hardcoded paths in OpenClaw
│   └── apply-patches.sh        # Patch orchestrator
├── scripts/
│   ├── build-sharp.sh          # Build sharp native module (image processing)
│   ├── check-env.sh            # Pre-flight environment check
│   ├── install-code-server.sh  # Install/update code-server (browser IDE)
│   ├── install-deps.sh         # Install Termux packages
│   ├── install-glibc-env.sh    # Install glibc environment (glibc-runner + Node.js)
│   ├── install-opencode.sh     # Install OpenCode + oh-my-opencode
│   ├── install-ai-tools.sh     # Interactive AI CLI tool selector
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

Running `bash install.sh` executes the following 11 steps in order.

### [1/11] Environment Check — `scripts/check-env.sh`

Validates that the current environment is suitable before starting installation.

- **Termux detection**: Checks for the `$PREFIX` environment variable. Exits immediately if not in Termux
- **Architecture check**: Runs `uname -m` to verify CPU architecture (aarch64 recommended, armv7l supported, x86_64 treated as emulator)
- **Disk space**: Ensures at least 1000MB free on the `$PREFIX` partition. Errors if insufficient
- **Existing installation**: If `openclaw` command already exists, shows current version and notes this is a reinstall/upgrade
- **Node.js pre-check**: If Node.js is already installed, shows version and warns if below 22
- **Phantom Process Killer** (Android 12+): Shows an informational note about the Phantom Process Killer with a link to the [disable guide](docs/disable-phantom-process-killer.md)

### [2/11] Base Dependencies — `scripts/install-deps.sh`

Installs Termux packages required for building and running OpenClaw.

- Runs `pkg update -y && pkg upgrade -y` to refresh and upgrade packages
- Installs the following packages:

| Package | Role | Why It's Needed |
|---------|------|-----------------|
| `git` | Distributed version control | Some npm packages have git dependencies. Also needed if installing this repo via `git clone` |
| `python` | Python interpreter | Used by `node-gyp` to run build scripts when compiling native C/C++ addons |
| `make` | Build automation tool | Executes Makefiles generated by `node-gyp` to compile native modules |
| `cmake` | Cross-platform build system | Some native modules use CMake-based builds instead of Makefiles |
| `clang` | C/C++ compiler | Default C/C++ compiler in Termux. Used by `node-gyp` to compile native modules |
| `binutils` | Binary utilities (ar, strip, etc.) | Provides `llvm-ar` for creating static archives during native module builds |
| `tmux` | Terminal multiplexer | Allows running the OpenClaw server in a background session |
| `ttyd` | Web terminal | Shares a terminal over the web for browser-based terminal access |
| `dufs` | HTTP/WebDAV file server | Provides file upload/download via browser |
| `android-tools` | Android Debug Bridge (adb) | Used to disable Android's Phantom Process Killer from within Termux |
| `pyyaml` (pip) | YAML parser for Python | Required for `.skill` packaging in OpenClaw |

Note: Node.js is **not** installed here — it is installed as a glibc linux-arm64 binary in the next step.

### [3/11] glibc Environment — `scripts/install-glibc-env.sh`

Installs the glibc runtime environment that allows standard Linux binaries to run on Android.

1. Installs `pacman` and `proot` Termux packages
2. Initializes pacman and installs `glibc-runner` from Termux's pacman repos (provides glibc dynamic linker at `$PREFIX/glibc/lib/ld-linux-aarch64.so.1`)
3. Downloads official Node.js v22 LTS (linux-arm64) from nodejs.org
4. Creates grun-style wrapper scripts: `node` becomes a bash script that runs `ld.so node.real "$@"` (no patchelf — it causes segfault on Android due to seccomp)
5. Configures npm and verifies everything works
6. Creates `.glibc-arch` marker file to identify the architecture

### [4/11] Path Setup — `scripts/setup-paths.sh`

Creates the directory structure needed for Termux.

- `$PREFIX/tmp/openclaw` — OpenClaw temp directory (replaces `/tmp`)
- `$HOME/.openclaw-android/patches` — Patch file storage location
- `$HOME/.openclaw` — OpenClaw data directory
- Displays how standard Linux paths (`/bin/sh`, `/usr/bin/env`, `/tmp`) map to Termux's `$PREFIX` subdirectories

### [5/11] Environment Variables — `scripts/setup-env.sh`

Adds an environment variable block to `~/.bashrc`.

- Wraps the block with `# >>> OpenClaw on Android >>>` / `# <<< OpenClaw on Android <<<` markers for management
- If the block already exists, removes the old one and adds a fresh one (prevents duplicates)
- Environment variables set:
  - `PATH` — Prepends glibc Node.js directory (`~/.openclaw-android/node/bin`)
  - `TMPDIR=$PREFIX/tmp` — Use Termux temp directory instead of `/tmp`
  - `TMP`, `TEMP` — Same as `TMPDIR` (for compatibility with some tools)
  - `CONTAINER=1` — Bypass systemd existence checks
  - `CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"` — Direct clawdhub to install skills into OpenClaw's workspace
  - `OA_GLIBC=1` — Marks this as a glibc-based installation
- Creates an `ar → llvm-ar` symlink if missing

The glibc architecture no longer needs `NODE_OPTIONS`, `CFLAGS`, `CXXFLAGS`, or `GYP_DEFINES` — these were required for the old Bionic architecture but are unnecessary with a standard glibc environment. `CPATH` is still set, but only for glib-2.0 headers needed by some native module builds (not the Bionic-specific headers from before).

### [6/11] OpenClaw Installation & Patching — `npm install` + `patches/apply-patches.sh`

Installs OpenClaw globally and applies Termux compatibility patches.

1. Copies `glibc-compat.js` to `~/.openclaw-android/patches/` — provides `os.cpus()` fallback (Android kernel returns 0) and `os.networkInterfaces()` try-catch wrapper (EACCES on Android)
2. Installs `oa.sh` as `$PREFIX/bin/oa` and `update.sh` wrapper as `$PREFIX/bin/oaupdate`
3. Runs `npm install -g openclaw@latest`
4. Installs `clawdhub` (skill manager) globally via `npm install -g clawdhub`
5. `patches/apply-patches.sh` applies patches:
   - Copies `glibc-compat.js` to the patches directory
   - Installs `systemctl` stub to `$PREFIX/bin/systemctl`
   - Runs `patches/patch-paths.sh` to replace hardcoded paths in OpenClaw JS files (`/tmp`, `/bin/sh`, `/bin/bash`, `/usr/bin/env`)
   - Logs patch results to `~/.openclaw-android/patch.log`

### [7/11] code-server Installation — `scripts/install-code-server.sh`

Installs code-server, a browser-based VS Code IDE, with Termux-specific workarounds. This step is non-critical — failure prints a warning but does not abort the installer.

The code-server standalone release bundles glibc-linked binaries that cannot run on Termux (Bionic libc). The installer applies three workarounds:

1. **Replace bundled node** — The bundled `lib/node` binary is replaced with a symlink to Termux's native Node.js (`$PREFIX/bin/node`)
2. **Patch argon2 native module** — The `argon2` module ships a glibc-compiled `.node` binary. Since code-server runs with `--auth none`, argon2 is never called. The module entry point is replaced with `patches/argon2-stub.js` (a JS stub that throws if called)
3. **Handle hard link failures** — Android's filesystem does not support hard links. `tar` extraction fails for hardlinked `.node` files. The script ignores tar errors and manually recovers `.node` files from `obj.target/` directories to `Release/`

Installation flow:
- Checks if already installed (skips if so)
- Fetches the latest version from GitHub API
- Downloads the `linux-arm64` tarball
- Extracts and recovers `.node` files
- Installs to `~/.local/lib/code-server-<version>`
- Applies the three workarounds above
- Creates a symlink at `~/.local/bin/code-server`
- Verifies with `code-server --version`

After installation, run `code-server --auth none` to start the browser IDE.

### [8/11] OpenCode + oh-my-opencode — `scripts/install-opencode.sh`

Installs OpenCode and oh-my-opencode (AI coding assistant and its plugin framework). This step is non-critical — failure prints a warning but does not abort the installer.

OpenCode and oh-my-opencode are Bun standalone binaries that require special handling on Android:

1. **Bun uses raw syscalls** — `LD_PRELOAD` shims don't work, so `proot` is needed to intercept syscalls
2. **Bun reads embedded JS via `/proc/self/exe`** — The `grun` approach (which makes `/proc/self/exe` point to `ld.so`) breaks the offset calculation. The ld.so concatenation method (prepending `ld.so` to the binary) preserves the correct offsets

Installation flow:
- Creates a minimal proot rootfs at `~/.openclaw-android/proot-root/`
- Installs Bun via the official installer
- Uses Bun to install `opencode-ai` and `oh-my-opencode` packages
- Creates ld.so concatenation files (`$PREFIX/tmp/ld.so.opencode`, `$PREFIX/tmp/ld.so.omo`)
- Creates proot wrapper scripts at `$PREFIX/bin/opencode` and `$PREFIX/bin/oh-my-opencode`
- Sets up OpenCode config with oh-my-opencode plugin

After installation, run `opencode` to start OpenCode.

### [9/11] AI CLI Tools (Optional) — `scripts/install-ai-tools.sh`

Presents an interactive checkbox UI for selecting AI CLI tools to install. Users can choose from:

| Tool | Package | Provider |
|------|---------|----------|
| Claude Code | `@anthropic-ai/claude-code` | Anthropic |
| Gemini CLI | `@google/gemini-cli` | Google |
| Codex CLI | `@openai/codex` | OpenAI |

Use arrow keys to navigate, Space to toggle, Enter to confirm. In non-interactive mode (e.g. `curl | bash`), this step is automatically skipped.

### [10/11] Installation Verification — `tests/verify-install.sh`

Checks the following items to confirm installation completed successfully.

| Check Item | PASS Condition |
|------------|---------------|
| Node.js version | `node -v` >= 22 |
| npm | `npm` command exists |
| openclaw | `openclaw --version` succeeds |
| TMPDIR | Environment variable is set |
| CONTAINER | Set to `1` |
| OA_GLIBC | Set to `1` |
| glibc-compat.js | File exists in `~/.openclaw-android/patches/` |
| .glibc-arch | Marker file exists in `~/.openclaw-android/` |
| glibc dynamic linker | `ld-linux-aarch64.so.1` exists in `$PREFIX/glibc/lib/` |
| glibc node wrapper | Wrapper script exists at `~/.openclaw-android/node/bin/node` |
| Directories | `~/.openclaw-android`, `~/.openclaw`, `$PREFIX/tmp` exist |
| code-server | `code-server --version` succeeds (WARN level, non-critical) |
| opencode | `opencode` command available (WARN level, non-critical) |
| .bashrc | Contains environment variable block |

All items pass → PASSED. Any failure → FAILED with reinstall instructions. WARN-level items do not cause failure.

### [11/11] OpenClaw Update

Runs `openclaw update` to ensure the latest version. On completion, displays the OpenClaw version and instructs the user to run `openclaw onboard` to start setup.

## Lightweight Updater Flow — `oa --update`

Running `oa --update` (or `oaupdate` for backward compatibility) downloads `update-core.sh` from GitHub and executes the following 10 steps. Unlike the full installer, it skips environment checks, path setup, and verification — focusing only on refreshing patches, environment variables, and packages.

### [1/10] Pre-flight Check

Validates the minimum conditions for updating.

- Checks `$PREFIX` exists (Termux environment)
- Checks `openclaw` command exists (must already be installed)
- Checks `curl` is available (needed to download files)
- Detects architecture: glibc (`.glibc-arch` marker) or Bionic (legacy)
- Migrates old directory name if needed (`.openclaw-lite` → `.openclaw-android` — legacy compatibility)
- **Phantom Process Killer** (Android 12+): Shows an informational note with a link to the [disable guide](docs/disable-phantom-process-killer.md)

### [2/10] Installing New Packages

Installs packages that may have been added since the user's initial installation.

- `ttyd` — Web terminal for browser-based access. Skipped if already installed
- `dufs` — HTTP/WebDAV file server for browser-based file management. Skipped if already installed
- `android-tools` — ADB for disabling Phantom Process Killer. Skipped if already installed
- `PyYAML` — YAML parser for `.skill` packaging. Skipped if already installed

All are non-critical — failures print a warning but don't stop the update.

### [3/10] Downloading Latest Scripts

Downloads the latest patch files and scripts from GitHub.

| File | Purpose | On Failure |
|------|---------|------------|
| `setup-env.sh` | Refresh `.bashrc` environment block | **Exit** (required) |
| `glibc-compat.js` | Node.js runtime compatibility patch | Warning |
| `spawn.h` | POSIX spawn stub (skipped if exists) | Warning |
| `argon2-stub.js` | JS stub for argon2 native module (code-server) | Warning |
| `systemctl` | systemd stub for Termux | Warning |
| `oa.sh` | Unified CLI (`oa` command) | Warning |
| `install-code-server.sh` | code-server install/update script | Warning |
| `build-sharp.sh` | sharp native module build script | Warning |
| `install-glibc-env.sh` | glibc environment installer (for migration) | Warning |
| `install-opencode.sh` | OpenCode + oh-my-opencode installer | Warning |

Only `setup-env.sh` is required — all other failures are non-critical.

### [4/10] Updating Environment Variables

Runs the downloaded `setup-env.sh` to refresh the `.bashrc` environment block with the latest variables. If the installation is detected as Bionic (pre-1.0.0), the updater also performs an automatic migration to the glibc architecture — installing glibc-runner, downloading Node.js, and creating wrapper scripts.

### [5/10] Updating OpenClaw Package

- Installs build dependencies: `libvips` (for sharp) and `binutils` (for native builds)
- Creates `ar → llvm-ar` symlink if missing
- Runs `npm install -g openclaw@latest`
- On failure, prints a warning and continues

### [6/10] Building sharp (image processing)

Runs `build-sharp.sh` to ensure the sharp native module is built. If sharp was already compiled successfully during Step 5's `npm install`, this step detects it and skips the rebuild.

### [7/10] Updating clawdhub (skill manager)

Installs or updates `clawdhub`, the CLI tool for searching and installing OpenClaw skills.

- If `clawdhub` is not installed, installs it via `npm install -g clawdhub`
- On Node.js v24+ in Termux, the `undici` package is not bundled with Node.js. If `undici` is missing, it's installed directly into clawdhub's directory
- Migrates skills from `~/skills/` to `~/.openclaw/workspace/skills/` if installed before `CLAWDHUB_WORKDIR` was configured
- All operations are non-critical

### [8/10] Updating code-server (IDE)

Runs `install-code-server.sh` in `update` mode to install or update code-server. If already installed and up to date, this step is skipped. This step is non-critical — failure prints a warning but does not stop the update.

### [9/10] Updating AI CLI Tools

Checks for installed AI CLI tools (Claude Code, Gemini CLI, Codex CLI) and updates them to the latest version. Compares installed vs latest npm version — skips if already up to date. Tools that are not installed are not offered for installation (use the full installer for that). All operations are non-critical.

### [10/10] Updating OpenCode + oh-my-opencode

Updates OpenCode and oh-my-opencode if already installed. If not installed, asks the user whether to install (defaults to skip). Requires glibc architecture — skipped on Bionic installations that failed migration. This step is non-critical.

</details>

## Additional Install Options

The installer includes these additional components. Some are auto-installed, while others can be selected during the installation process. You can also manage them later with `oa --update` and `oa --uninstall`.

| Tool | Description | Install |
|------|-------------|---------|
| [code-server](https://github.com/coder/code-server) | Browser-based VS Code IDE | Included in installer (auto-installed) |
| [ttyd](https://github.com/tsl0922/ttyd) | Web terminal — access Termux from a browser | Included in installer (auto-installed) |
| [dufs](https://github.com/sigoden/dufs) | File server — upload/download files via browser | Included in installer (auto-installed) |
| [OpenCode](https://opencode.ai/) | AI coding assistant (TUI) | Prompted during install |
| [oh-my-opencode](https://github.com/code-yeongyu/oh-my-opencode) | Plugin framework for OpenCode | Prompted during install (with OpenCode) |
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | AI CLI tool | Prompted during install |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | AI CLI tool | Prompted during install |
| [Codex CLI](https://github.com/openai/codex) (OpenAI) | AI CLI tool | Prompted during install |

The glibc environment installed by this project provides a standard Linux runtime, enabling these tools to install and run on Android.

Run each tool directly from the command line (e.g., `code-server --auth none`, `opencode`).

<p>
  <img src="docs/images/run_claude.png" alt="Claude Code on Termux" width="32%">
  <img src="docs/images/run_gemini.png" alt="Gemini CLI on Termux" width="32%">
  <img src="docs/images/run_codex.png" alt="Codex CLI on Termux" width="32%">
</p>

## License

MIT
