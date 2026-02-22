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

## "Cannot find module bionic-compat.js" error

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/bionic-compat.js'
```

### Cause

The `NODE_OPTIONS` environment variable in `~/.bashrc` still references the old installation path (`.openclaw-lite`). This happens when updating from an older version where the project was named "OpenClaw Lite".

### Solution

Run the updater to refresh the environment variable block:

```bash
oaupdate && source ~/.bashrc
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

When `openclaw update` runs npm to update the package, it spawns npm as a subprocess. The Termux-specific build environment variables required to compile `sharp`'s native module (`CXXFLAGS`, `GYP_DEFINES`, `CPATH`) are set in `~/.bashrc` but are not automatically available in that subprocess context.

### Impact

**This error is non-critical.** OpenClaw itself has been updated successfully — only the `sharp` module (used for image processing) failed to rebuild. OpenClaw works normally without it.

### Solution

After the update, manually rebuild `sharp` using the provided script:

```bash
bash ~/.openclaw-android/scripts/build-sharp.sh
```

Alternatively, use `oaupdate` instead of `openclaw update` — it sets the required environment variables and rebuilds sharp automatically:

```bash
oaupdate && source ~/.bashrc
```

## "not supported on android" error

```
Gateway status failed: Error: Gateway service install not supported on android
```

### Cause

The `process.platform` override in `bionic-compat.js` is not being applied.

### Solution

Check if the `NODE_OPTIONS` environment variable is set:

```bash
echo $NODE_OPTIONS
```

If empty, load the environment:

```bash
source ~/.bashrc
```

If `NODE_OPTIONS` is set but the error persists, check if the file is up to date:

```bash
node -e "console.log(process.platform)"
```

If it prints `android`, the file is outdated. Reinstall:

```bash
curl -sL myopenclawhub.com/install | bash
```
