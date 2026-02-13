# OpenClaw Lite Android

Run [OpenClaw](https://github.com/openclaw) on Android using Termux — **without proot-distro**.

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

### Step 2: Install Termux

> **Important**: The Play Store version of Termux is discontinued and will not work. You must install from F-Droid.

1. Open your phone's browser and go to [f-droid.org](https://f-droid.org)
2. Download and install `F-Droid.apk`
   - Allow "Install from unknown sources" when prompted
3. Open F-Droid and search for `Termux`
4. Install **Termux** (by Fredrik Fornwall)
5. Also install **Termux:API** (optional but recommended)

### Step 3: Initial Termux Setup and Background Kill Prevention

Open the Termux app and run:

(Typing commands is much easier via SSH from a computer. See the [Termux SSH Setup Guide](docs/termux-ssh-guide.md) for details.)

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

This pins a notification and prevents Android from killing the Termux process.

> To release it later, run `termux-wake-unlock` or swipe the notification away.

**B. Disable Battery Optimization for Termux**

1. Go to Android **Settings** > **Battery** (or **Battery and device care**)
2. Open **Battery optimization** (or **App power management**)
3. Find **Termux** and set it to **Not optimized** (or **Unrestricted**)

> The exact menu path varies by manufacturer (Samsung, LG, etc.) and Android version. Search your settings for "battery optimization" to find it.

### Step 4: Install OpenClaw

(Typing commands is much easier via SSH from a computer. See the [Termux SSH Setup Guide](docs/termux-ssh-guide.md) for details.)

```bash
curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-lite-android/main/bootstrap.sh | bash
```

This takes 3–10 minutes depending on network speed and device. Package downloads and compilation will occur, so Wi-Fi is recommended.

### Step 5: Apply Environment

Once installation is complete, apply the environment variables. Choose one:

- **Option A**: Close and reopen the Termux app completely
- **Option B**: Run the following command
  ```bash
  source ~/.bashrc
  ```

### Step 6: Verify

```bash
openclaw --version
```

If a version number prints, you're done.

### Step 7: Start OpenClaw Setup

```bash
openclaw onboard
```

Follow the on-screen instructions to complete the initial setup.

![openclaw onboard](docs/images/openclaw-onboard.png)

## What It Does

The installer handles 5 compatibility issues between Termux and standard Linux:

1. **Android platform detection** — Node.js reports `process.platform` as `'android'` in Termux, causing OpenClaw to reject the platform. A preloaded JS shim overrides it to `'linux'`.

2. **Bionic libc crash** — `os.networkInterfaces()` crashes on Android's Bionic libc. The same preloaded shim wraps it in try-catch with a safe fallback.

3. **Hardcoded system paths** — Node packages expect `/bin/sh`, `/tmp`, etc. The installer patches these to use Termux's `$PREFIX` paths.

4. **No `/tmp` access** — Android blocks writes to `/tmp`. Redirected to `$PREFIX/tmp`.

5. **No systemd** — Some install steps check for systemd. The `CONTAINER=1` env var bypasses these checks.

## Project Structure

```
openclaw-lite-android/
├── install.sh                  # One-click installer (entry point)
├── uninstall.sh                # Clean removal
├── patches/
│   ├── bionic-compat.js        # Platform override + os.networkInterfaces() safe wrapper
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

## Detailed Installation Flow

Running `bash install.sh` executes the following 6 steps in order.

### [1/6] Environment Check — `scripts/check-env.sh`

Validates that the current environment is suitable before starting installation.

- **Termux detection**: Checks for the `$PREFIX` environment variable. Exits immediately if not in Termux
- **Architecture check**: Runs `uname -m` to verify CPU architecture (aarch64 recommended, armv7l supported, x86_64 treated as emulator)
- **Disk space**: Ensures at least 500MB free on the `$PREFIX` partition. Errors if insufficient
- **Existing installation**: If `openclaw` command already exists, shows current version and notes this is a reinstall/upgrade
- **Node.js pre-check**: If Node.js is already installed, shows version and warns if below 22

### [2/6] Package Installation — `scripts/install-deps.sh`

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
| `tmux` | Terminal multiplexer | Allows running the OpenClaw server in a background session. In Termux, apps going to background may suspend processes, so running inside a tmux session keeps it stable |
| `termux-api` | Bridge between Termux and Android APIs | Tools for accessing Android system features like network status, notifications, and clipboard. Not directly used by OpenClaw but useful utilities in the Termux environment |

- After installation, verifies Node.js >= 22 and npm presence. Exits on failure

### [3/6] Path Setup — `scripts/setup-paths.sh`

Creates the directory structure needed for Termux.

- `$PREFIX/tmp/openclaw` — OpenClaw temp directory (replaces `/tmp`)
- `$HOME/.openclaw-lite/patches` — Patch file storage location
- `$HOME/.openclaw` — OpenClaw data directory
- Displays how standard Linux paths (`/bin/sh`, `/usr/bin/env`, `/tmp`) map to Termux's `$PREFIX` subdirectories

### [4/6] Environment Variables — `scripts/setup-env.sh`

Adds an environment variable block to `~/.bashrc`.

- Wraps the block with `# >>> OpenClaw Lite Android >>>` / `# <<< OpenClaw Lite Android <<<` markers for management
- If the block already exists, removes the old one and adds a fresh one (prevents duplicates)
- Environment variables set:
  - `TMPDIR=$PREFIX/tmp` — Use Termux temp directory instead of `/tmp`
  - `TMP`, `TEMP` — Same as `TMPDIR` (for compatibility with some tools)
  - `NODE_OPTIONS="-r .../bionic-compat.js"` — Auto-load Bionic compatibility patch for all Node processes
  - `CONTAINER=1` — Bypass systemd existence checks

### [5/6] OpenClaw Installation & Patching — `npm install` + `patches/apply-patches.sh`

Installs OpenClaw globally and applies Termux compatibility patches.

1. Copies `bionic-compat.js` to `~/.openclaw-lite/patches/` (needed during npm install as well)
2. Runs `npm install -g openclaw@latest`
3. `patches/apply-patches.sh` applies all patches:
   - Verifies `bionic-compat.js` final copy
   - Runs `patches/patch-paths.sh` — uses sed to replace hardcoded paths in installed OpenClaw JS files:
     - `"/tmp"` / `'/tmp'` → `"$PREFIX/tmp"` / `'$PREFIX/tmp'`
     - `"/bin/sh"` → `"$PREFIX/bin/sh"`
     - `"/bin/bash"` → `"$PREFIX/bin/bash"`
     - `"/usr/bin/env"` → `"$PREFIX/bin/env"`
   - Logs patch results to `~/.openclaw-lite/patch.log`

### [6/6] Installation Verification — `tests/verify-install.sh`

Checks 7 items to confirm installation completed successfully.

| Check Item | PASS Condition |
|------------|---------------|
| Node.js version | `node -v` >= 22 |
| npm | `npm` command exists |
| openclaw | `openclaw --version` succeeds |
| TMPDIR | Environment variable is set |
| NODE_OPTIONS | Environment variable is set |
| CONTAINER | Set to `1` |
| bionic-compat.js | File exists in `~/.openclaw-lite/patches/` |
| Directories | `~/.openclaw-lite`, `~/.openclaw`, `$PREFIX/tmp` exist |
| .bashrc | Contains environment variable block |

All items pass → PASSED. Any failure → FAILED with reinstall instructions.

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
