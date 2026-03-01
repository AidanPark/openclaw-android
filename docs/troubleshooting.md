# Troubleshooting

Common issues and solutions when using OpenClaw on Termux.

## Gateway won't start: "gateway already running" or "Port is already in use"

```
Gateway failed to start: gateway already running (pid XXXXX); lock timeout after 5000ms
Port 18789 is already in use.
```

### Cause

A previous gateway process was terminated abnormally, leaving behind a lock file or a zombie process. This typically happens when:

- SSH connection drops, leaving the gateway process orphaned
- `Ctrl+Z` (suspend) was used instead of `Ctrl+C` (terminate), leaving the process alive in the background
- Termux was force-killed by Android

> **Note**: Always use `Ctrl+C` to stop the gateway. `Ctrl+Z` only suspends the process — it does not terminate it.

### Solution

**Step 1: Find and kill remaining processes**

```bash
ps aux | grep -E "node|openclaw" | grep -v grep
```

If processes are listed, note the PID and kill them:

```bash
kill -9 <PID>
```

**Step 2: Remove lock files**

```bash
rm -rf $PREFIX/tmp/openclaw-*
```

**Step 3: Restart the gateway**

```bash
openclaw gateway
```

### If it still doesn't work

If the above steps don't help, fully close and reopen the Termux app, then run `openclaw gateway`. Rebooting the phone will reliably clear all state.

## Gateway disconnected: "gateway not connected"

```
send failed: Error: gateway not connected
disconnected | error
```

### Cause

The gateway process has stopped or the SSH session was disconnected.

### Solution

Check the SSH session where the gateway was running. If the session was disconnected, reconnect via SSH and start the gateway:

```bash
openclaw gateway
```

If you get a "gateway already running" error, see the [Gateway won't start](#gateway-wont-start-gateway-already-running-or-port-is-already-in-use) section above.

## SSH connection failed: "Connection refused"

```
ssh: connect to host 192.168.45.139 port 8022: Connection refused
```

### Cause

The Termux SSH server (`sshd`) is not running. Closing the Termux app or rebooting the phone stops sshd.

### Solution

Open the Termux app on the phone and run `sshd`. Either type directly on the phone or send via adb:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

The IP address may have changed, so verify:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

> To start sshd automatically, add `sshd 2>/dev/null` to the end of your `~/.bashrc` file so the SSH server starts whenever Termux opens.

## `openclaw --version` fails

### Cause

Environment variables are not loaded.

### Solution

```bash
source ~/.bashrc
```

Or fully close and reopen the Termux app.

## "Cannot find module glibc-compat.js" error

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/glibc-compat.js'
```

> **Note**: This issue only affects pre-1.0.0 (Bionic) installations. In v1.0.0+ (glibc), `glibc-compat.js` is loaded by the node wrapper script, not `NODE_OPTIONS`.

### Cause

The `NODE_OPTIONS` environment variable in `~/.bashrc` still references the old installation path (`.openclaw-lite`). This happens when updating from an older version where the project was named "OpenClaw Lite".

### Solution

Run the updater to refresh the environment variable block:

```bash
oa --update && source ~/.bashrc
```

Or manually fix it:

```bash
sed -i 's/\.openclaw-lite/\.openclaw-android/g' ~/.bashrc && source ~/.bashrc
```

## "systemctl --user unavailable: spawn systemctl ENOENT" during update

```
Gateway service check failed: Error: systemctl --user unavailable: spawn systemctl ENOENT
```

### Cause

After running `openclaw update`, OpenClaw tries to restart the gateway service using `systemctl`. Since Termux doesn't have systemd, the `systemctl` binary doesn't exist and the command fails with `ENOENT`.

### Impact

**This error is harmless.** The update itself has already completed successfully — only the automatic service restart failed. Your OpenClaw installation is up to date.

### Solution

Simply start the gateway manually:

```bash
openclaw gateway
```

If the gateway was already running before the update, you may need to stop the old process first. See the [Gateway won't start](#gateway-wont-start-gateway-already-running-or-port-is-already-in-use) section above.

## sharp build fails during `openclaw update`

```
npm error gyp ERR! not ok
Update Result: ERROR
Reason: global update
```

### Cause

**v1.0.0+ (glibc)**: The `sharp` module uses prebuilt binaries (`@img/sharp-linux-arm64`) that load natively under the glibc environment. This error is rare — it typically means the prebuilt binary is missing or corrupted.

**Pre-1.0.0 (Bionic)**: When `openclaw update` ran npm as a subprocess, the Termux-specific build environment variables (`CXXFLAGS`, `GYP_DEFINES`) were not available in the subprocess context, causing the native module compilation to fail.

### Impact

**This error is non-critical.** OpenClaw itself has been updated successfully — only the `sharp` module (used for image processing) failed to rebuild. OpenClaw works normally without it.

### Solution

After the update, manually rebuild `sharp` using the provided script:

```bash
bash ~/.openclaw-android/scripts/build-sharp.sh
```

Alternatively, use `oa --update` instead of `openclaw update` — it handles sharp automatically:

```bash
oa --update && source ~/.bashrc
```

## `clawdhub` fails with "Cannot find package 'undici'"

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'undici' imported from /data/data/com.termux/files/usr/lib/node_modules/clawdhub/dist/http.js
```

### Cause

Node.js v24+ on Termux doesn't bundle the `undici` package, which `clawdhub` depends on for HTTP requests.

### Solution

Run the updater to automatically install `clawdhub` and its `undici` dependency:

```bash
oa --update && source ~/.bashrc
```

Or fix it manually:

```bash
cd $(npm root -g)/clawdhub && npm install undici
```

## "not supported on android" error

```
Gateway status failed: Error: Gateway service install not supported on android
```

> **Note**: This issue only affects pre-1.0.0 (Bionic) installations. In v1.0.0+ (glibc), Node.js natively reports `process.platform` as `'linux'`, so this error does not occur.

### Cause

**Pre-1.0.0 (Bionic)**: The `process.platform` override in `glibc-compat.js` is not being applied because `NODE_OPTIONS` is not set.

### Solution

Check which Node.js is being used:

```bash
node -e "console.log(process.platform)"
```

If it prints `android`, the glibc node wrapper is not being used. Load the environment:

```bash
source ~/.bashrc
```

If it still prints `android`, update to the latest version (v1.0.0+ uses glibc and resolves this permanently):

```bash
oa --update && source ~/.bashrc
```

## `openclaw update` fails with node-llama-cpp build error

```
[node-llama-cpp] Cloning ggml-org/llama.cpp (local bundle)
npm error 48%
Update Result: ERROR
```

### Cause

When OpenClaw updates via npm, `node-llama-cpp`'s postinstall script attempts to clone and compile `llama.cpp` from source. This fails on Termux because the build toolchain (`cmake`, `clang`) is linked against Bionic, while Node.js runs under glibc — the two are incompatible for native compilation.

### Impact

**This error is harmless.** The prebuilt `node-llama-cpp` binaries (`@node-llama-cpp/linux-arm64`) are already installed and work correctly under the glibc environment. The failed source build does not overwrite them.

Node-llama-cpp is used for optional local embeddings. If the prebuilt binaries don't load, OpenClaw automatically falls back to remote embedding providers (OpenAI, Gemini, etc.).

### Solution

No action needed. The error can be safely ignored. To verify that the prebuilt binaries are working:

```bash
node -e "require('$(npm root -g)/openclaw/node_modules/@node-llama-cpp/linux-arm64/bins/linux-arm64/llama-addon.node'); console.log('OK')"
```

## OpenCode install shows EACCES permission errors

```
EACCES: Permission denied while installing opencode-ai
Failed to install 118 packages
```

### Cause

Bun attempts to create hardlinks and symlinks when installing packages. Android's filesystem restricts these operations, causing `EACCES` errors for dependency packages.

### Impact

**These errors are harmless.** The main binaries (`opencode`, `oh-my-opencode`) are installed correctly despite the dependency link failures. The ld.so concatenation and proot wrapper handle execution.

### Solution

No action needed. Verify that OpenCode works:

```bash
opencode --version
```
