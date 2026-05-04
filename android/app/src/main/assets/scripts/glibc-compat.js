/**
 * glibc-compat.js - Minimal compatibility shim for glibc Node.js on Android
 *
 * This is the successor to bionic-compat.js, drastically reduced for glibc.
 *
 * What's NOT needed anymore (glibc handles these):
 * - process.platform override (glibc Node.js reports 'linux' natively)
 * - renameat2 / spawn.h stubs (glibc includes them)
 * - CXXFLAGS / GYP_DEFINES overrides (glibc is standard Linux)
 *
 * What's still needed (kernel/Android-level restrictions, not libc):
 * - os.cpus() fallback: SELinux blocks /proc/stat on Android 8+
 * - os.networkInterfaces() safety: EACCES on some Android configurations
 * - /bin/sh path shim: Android 7-8 lacks /bin/sh (Android 9+ has it)
 *
 * Loaded via node wrapper script: node --require <path>/glibc-compat.js
 */

'use strict';

const os = require('os');
const fs = require('fs');
const path = require('path');

// ─── process.execPath fix ────────────────────────────────────
// When node runs via grun (ld.so node.real), process.execPath points to
// ld.so instead of the node wrapper. Apps that spawn child node processes
// using process.execPath (e.g., openclaw) will call ld.so directly,
// bypassing the wrapper's LD_PRELOAD unset and compat loading.
// Fix: point process.execPath to the wrapper script.

const _wrapperPath = process.env._OA_WRAPPER_PATH || path.join(
  process.env.HOME || '/data/data/com.termux/files/home',
  '.openclaw-android', 'bin', 'node'
);
try {
  if (fs.existsSync(_wrapperPath)) {
    Object.defineProperty(process, 'execPath', {
      value: _wrapperPath,
      writable: true,
      configurable: true,
    });
  }
} catch {}


// ─── LD_PRELOAD cleanup ─────────────────────────────────────
// The node wrapper unsets LD_PRELOAD to prevent bionic libtermux-exec.so
// from loading into the glibc node.real process.
//
// Previously, we restored LD_PRELOAD here so bionic child processes
// (like /bin/sh) would get libtermux-exec.so for path translation
// (e.g., /usr/bin/env → $PREFIX/bin/env in shebang resolution).
//
// However, libtermux-exec.so re-injects LD_PRELOAD into execve() calls
// even after the shell unsets it. This causes glibc processes (ld.so)
// spawned from node to crash with "Could not find a PHDR" errors.
//
// Fix: Do NOT restore LD_PRELOAD. Instead, the spawn/spawnSync wrapper
// below handles shebang resolution (#!/usr/bin/env → PATH lookup) in
// JavaScript, eliminating the need for libtermux-exec.so in child processes.

delete process.env.LD_PRELOAD;
delete process.env._OA_ORIG_LD_PRELOAD;


// ─── os.cpus() fallback ─────────────────────────────────────
// Android 8+ (API 26+) blocks /proc/stat via SELinux + hidepid=2.
// libuv reads /proc/stat for CPU info → returns empty array.
// Tools using os.cpus().length for parallelism (e.g., make -j) break with 0.

const _originalCpus = os.cpus;

os.cpus = function cpus() {
  const result = _originalCpus.call(os);
  if (result.length > 0) {
    return result;
  }
  // Return a single fake CPU entry so .length is at least 1
  return [{ model: 'unknown', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
};

// ─── os.networkInterfaces() safety ──────────────────────────
// Some Android configurations throw EACCES when reading network
// interface information. Wrap with try-catch to prevent crashes.
//
// Additionally, Android/Termux typically only exposes the loopback
// interface (`lo`) to Node.js. In that situation, OpenClaw's Bonjour
// advertiser can't send multicast announcements and logs noisy
// "Announcement failed as of socket errors!" repeatedly.
// Auto-disable Bonjour via OPENCLAW_DISABLE_BONJOUR when only
// loopback interfaces are visible.

const _originalNetworkInterfaces = os.networkInterfaces;

function _createLoopbackInterfaces() {
  return {
    lo: [
      {
        address: '127.0.0.1',
        netmask: '255.0.0.0',
        family: 'IPv4',
        mac: '00:00:00:00:00:00',
        internal: true,
        cidr: '127.0.0.1/8',
      },
    ],
  };
}

function _hasNonLoopbackInterface(interfaces) {
  try {
    return Object.values(interfaces).some(entries =>
      Array.isArray(entries) && entries.some(entry => entry && entry.internal === false)
    );
  } catch {
    return false;
  }
}

os.networkInterfaces = function networkInterfaces() {
  let interfaces;
  try {
    interfaces = _originalNetworkInterfaces.call(os);
  } catch {
    interfaces = _createLoopbackInterfaces();
  }
  if (!process.env.OPENCLAW_DISABLE_BONJOUR && !_hasNonLoopbackInterface(interfaces)) {
    process.env.OPENCLAW_DISABLE_BONJOUR = '1';
  }
  return interfaces;
};

// ─── Shell override for exec/execSync ────────────────────────
// Node.js child_process hardcodes /bin/sh as the default shell on Linux.
// On Android:
//   - Android 7-8: /bin/sh doesn't exist at all
//   - Android 9+: /bin/sh exists (/system/bin/sh) but is minimal (toybox/mksh)
//     and lacks Termux PATH, environment, and proper command support
// Always use Termux's shell for exec/execSync to ensure consistent behavior.

{
  const child_process = require('child_process');
  const termuxSh = (process.env.PREFIX || '/data/data/com.termux/files/usr') + '/bin/sh';

  if (fs.existsSync(termuxSh)) {
    const _originalExec = child_process.exec;
    const _originalExecSync = child_process.execSync;

    child_process.exec = function exec(command, options, callback) {
      if (typeof options === 'function') {
        callback = options;
        options = {};
      }
      options = options || {};
      if (!options.shell) {
        options.shell = termuxSh;
      }
      return _originalExec.call(child_process, command, options, callback);
    };

    child_process.execSync = function execSync(command, options) {
      options = options || {};
      if (!options.shell) {
        options.shell = termuxSh;
      }
      return _originalExecSync.call(child_process, command, options);
    };
  }
}

// ─── DNS resolver fix ────────────────────────────────────────
// glibc's getaddrinfo() reads /data/data/com.termux/files/usr/glibc/etc/resolv.conf
// for DNS servers. This file may be missing or inaccessible:
// - Standalone APK: runs under com.openclaw.android, can't access com.termux paths
// - Termux: resolv-conf package may not be installed
// Without a valid resolv.conf, dns.lookup() fails with EAI_AGAIN errors.
//
// Fix: Override both dns.lookup (callback) and dns.promises.lookup (promise)
// to use c-ares resolver (dns.resolve) which respects dns.setServers(),
// then fall back to getaddrinfo.

try {
  const dns = require('dns');

  // Read DNS servers from our resolv.conf or use Google DNS as fallback
  let dnsServers = ['8.8.8.8', '8.8.4.4'];
  try {
    const resolvConf = fs.readFileSync(
      (process.env.PREFIX || '/data/data/com.termux/files/usr') + '/etc/resolv.conf',
      'utf8'
    );
    const parsed = resolvConf.match(/^nameserver\s+(.+)$/gm);
    if (parsed && parsed.length > 0) {
      dnsServers = parsed.map(l => l.replace(/^nameserver\s+/, '').trim());
    }
  } catch {}

  // Set DNS servers for c-ares resolver
  try { dns.setServers(dnsServers); } catch {}

  // Override dns.lookup (callback API) to use c-ares resolver
  const _originalLookup = dns.lookup;

  // Localhost must never go to external DNS. Android/glibc may lack /etc/hosts,
  // causing getaddrinfo to fail or return 0.0.0.0. Short-circuit it here.
  const _localhostNames = new Set(['localhost', 'localhost.localdomain', 'ip6-localhost', 'ip6-loopback']);

  dns.lookup = function lookup(hostname, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    const originalOptions = options;
    const opts = typeof options === 'number' ? { family: options } : (options || {});
    const wantAll = opts.all === true;
    const family = opts.family || 0;

    // Short-circuit localhost — never send to external DNS
    if (_localhostNames.has(hostname)) {
      if (family === 6) {
        if (wantAll) return callback(null, [{ address: '::1', family: 6 }]);
        return callback(null, '::1', 6);
      }
      if (wantAll) return callback(null, [{ address: '127.0.0.1', family: 4 }]);
      return callback(null, '127.0.0.1', 4);
    }

    const resolve = (fam, cb) => {
      const fn = fam === 6 ? dns.resolve6 : dns.resolve4;
      fn(hostname, cb);
    };

    const tryResolve = (fam) => {
      resolve(fam, (err, addresses) => {
        if (!err && addresses && addresses.length > 0) {
          const resFam = fam === 6 ? 6 : 4;
          if (wantAll) {
            callback(null, addresses.map(a => ({ address: a, family: resFam })));
          } else {
            callback(null, addresses[0], resFam);
          }
        } else if (family === 0 && fam === 4) {
          tryResolve(6);
        } else {
          _originalLookup.call(dns, hostname, originalOptions, callback);
        }
      });
    };

    tryResolve(family === 6 ? 6 : 4);
  };

  // Override dns.promises.lookup (promise API) to use c-ares resolver.
  // OpenClaw's SSRF guard uses this API for web_search DNS resolution.
  const _originalPromiseLookup = dns.promises.lookup;
  dns.promises.lookup = async function lookup(hostname, options) {
    const opts = typeof options === 'number' ? { family: options } : (options || {});
    const wantAll = opts.all === true;
    const family = opts.family || 0;

    // Short-circuit localhost
    if (_localhostNames.has(hostname)) {
      if (family === 6) {
        return wantAll ? [{ address: '::1', family: 6 }] : { address: '::1', family: 6 };
      }
      return wantAll ? [{ address: '127.0.0.1', family: 4 }] : { address: '127.0.0.1', family: 4 };
    }

    const resolve = (fam) => {
      return new Promise((res, rej) => {
        const fn = fam === 6 ? dns.resolve6 : dns.resolve4;
        fn(hostname, (err, addresses) => err ? rej(err) : res(addresses));
      });
    };

    const tryResolve = async (fam) => {
      try {
        const addresses = await resolve(fam);
        if (addresses && addresses.length > 0) {
          const resFam = fam === 6 ? 6 : 4;
          if (wantAll) {
            return addresses.map(a => ({ address: a, family: resFam }));
          }
          return { address: addresses[0], family: resFam };
        }
      } catch {}
      if (family === 0 && fam === 4) return tryResolve(6);
      return _originalPromiseLookup.call(dns.promises, hostname, options);
    };

    return tryResolve(family === 6 ? 6 : 4);
  };
} catch {}

// ─── ELF binary auto-wrapping for spawn/spawnSync ──────────
// npm/npx-installed native binaries (e.g., @zed-industries/codex-acp)
// are standard Linux ELF files whose interpreter is /lib/ld-linux-aarch64.so.1.
// Android lacks this path, so the kernel returns ENOENT on direct execution.
// Intercept child_process spawn APIs to detect ELF binaries and automatically
// route them through the glibc dynamic linker (ld.so).

const _glibcLdso = (process.env.PREFIX || '/data/data/com.termux/files/usr')
  + '/glibc/lib/ld-linux-aarch64.so.1';
const _glibcLibPath = (process.env.PREFIX || '/data/data/com.termux/files/usr')
  + '/glibc/lib';

function _needsGlibcWrap(filePath) {
  // Read ELF header and check PT_INTERP to distinguish glibc binaries
  // from bionic (Android/Termux) binaries. Only glibc binaries need wrapping.
  // Bionic binaries use /system/bin/linker64; glibc use /lib/ld-linux-*.so.1
  try {
    const fd = fs.openSync(filePath, 'r');
    try {
      const ehdr = Buffer.alloc(64);
      if (fs.readSync(fd, ehdr, 0, 64, 0) < 64) return false;
      // Check ELF magic
      if (ehdr[0] !== 0x7f || ehdr[1] !== 0x45 || ehdr[2] !== 0x4c || ehdr[3] !== 0x46) return false;
      // ELF64: e_phoff at offset 32 (8 bytes), e_phentsize at 54 (2 bytes), e_phnum at 56 (2 bytes)
      const phoff = Number(ehdr.readBigUInt64LE(32));
      const phentsize = ehdr.readUInt16LE(54);
      const phnum = ehdr.readUInt16LE(56);
      // Scan program headers for PT_INTERP (type = 3)
      for (let i = 0; i < phnum; i++) {
        const phBuf = Buffer.alloc(phentsize);
        if (fs.readSync(fd, phBuf, 0, phentsize, phoff + i * phentsize) < phentsize) continue;
        const pType = phBuf.readUInt32LE(0);
        if (pType === 3) { // PT_INTERP
          const interpOff = Number(phBuf.readBigUInt64LE(8));
          const interpSize = Number(phBuf.readBigUInt64LE(32));
          const interpBuf = Buffer.alloc(Math.min(interpSize, 256));
          fs.readSync(fd, interpBuf, 0, interpBuf.length, interpOff);
          const interp = interpBuf.toString('utf8').replace(/\0+$/, '');
          return interp.includes('ld-linux');
        }
      }
      return false;
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return false;
  }
}

function _resolveCommand(command, env) {
  if (command.includes('/')) {
    try { return fs.realpathSync(command); } catch { return command; }
  }
  const searchPath = (env && env.PATH) || process.env.PATH || '';
  const dirs = searchPath.split(':');
  for (const dir of dirs) {
    const full = path.join(dir, command);
    try {
      fs.accessSync(full, fs.constants.X_OK);
      return full;
    } catch {}
  }
  return null;
}

function _readShebang(filePath) {
  // Read first 256 bytes to extract shebang line from script files.
  // Returns null if not a script, or { interpreter, args } if shebang found.
  try {
    const fd = fs.openSync(filePath, 'r');
    try {
      const buf = Buffer.alloc(256);
      const n = fs.readSync(fd, buf, 0, 256, 0);
      if (n < 2 || buf[0] !== 0x23 || buf[1] !== 0x21) return null; // not #!
      const line = buf.toString('utf8', 2, n).split('\n')[0].trim();
      const parts = line.split(/\s+/);
      return { interpreter: parts[0], args: parts.slice(1) };
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return null;
  }
}

function _resolveShebang(resolved, spawnEnv) {
  // For a resolved file path, check if it has a shebang pointing to a
  // non-existent interpreter (e.g. #!/usr/bin/env node on Android).
  // Returns { interpPath, interpArgs } or null if no fixup needed.
  const shebang = _readShebang(resolved);
  if (!shebang) return null;

  let interpPath = shebang.interpreter;
  let interpArgs = shebang.args;

  if (interpPath === '/usr/bin/env' && interpArgs.length > 0) {
    // #!/usr/bin/env <cmd> — resolve <cmd> from PATH
    let cmdIdx = 0;
    while (cmdIdx < interpArgs.length && interpArgs[cmdIdx].startsWith('-')) cmdIdx++;
    if (cmdIdx >= interpArgs.length) return null;
    const cmd = interpArgs[cmdIdx];
    const resolvedCmd = _resolveCommand(cmd, spawnEnv);
    if (!resolvedCmd) return null;
    interpPath = resolvedCmd;
    interpArgs = interpArgs.slice(0, cmdIdx).concat(interpArgs.slice(cmdIdx + 1));
  } else if (!fs.existsSync(interpPath)) {
    // Interpreter at non-existent absolute path — try resolving by basename from PATH
    const basename = path.basename(interpPath);
    const resolvedInterp = _resolveCommand(basename, spawnEnv);
    if (!resolvedInterp) return null;
    interpPath = resolvedInterp;
  } else {
    // Shebang interpreter exists, kernel can handle it
    return null;
  }

  return { interpPath, interpArgs };
}

// Detect shell invocation patterns: spawn('/path/to/sh', ['-c', 'cmd args...'])
// or spawn('cmd', args, { shell: true })
function _isShellInvocation(file, args) {
  if (!args || args.length < 2 || args[0] !== '-c') return null;
  const base = path.basename(file);
  if (base !== 'sh' && base !== 'bash' && base !== 'dash' && base !== 'zsh') return null;
  return args[1]; // the shell command string
}

function _tryFixShellCommand(cmdStr, spawnEnv) {
  // Extract the command name from a simple shell command string.
  // Only handle simple cases: "cmd arg1 arg2..." — no pipes, redirects, etc.
  const shellChars = /[|><&;`$(){}]/;
  if (shellChars.test(cmdStr)) return null;

  const parts = cmdStr.trim().split(/\s+/);
  if (parts.length === 0) return null;
  const cmd = parts[0];
  const cmdArgs = parts.slice(1);

  const resolved = _resolveCommand(cmd, spawnEnv);
  if (!resolved) return null;

  // glibc ELF binary
  if (_needsGlibcWrap(resolved)) {
    const env = Object.assign({}, spawnEnv);
    delete env.LD_PRELOAD;
    return {
      file: _glibcLdso,
      args: ['--library-path', _glibcLibPath, resolved].concat(cmdArgs),
      env: env,
    };
  }

  // Script with broken shebang
  const shebang = _resolveShebang(resolved, spawnEnv);
  if (shebang) {
    return {
      file: shebang.interpPath,
      args: shebang.interpArgs.concat([resolved]).concat(cmdArgs),
    };
  }

  return null;
}

function _tryWrapSpawn(file, args, options) {
  const spawnEnv = options && options.env ? options.env : process.env;

  // Detect shell invocations: spawn('sh', ['-c', 'command...']) or spawn(cmd, args, { shell: true })
  // npm/npx uses spawn('/path/to/sh', ['-c', 'cmd args...']) to execute package binaries.
  // Without libtermux-exec.so, #!/usr/bin/env shebangs in these scripts fail.
  if (options && options.shell) {
    // shell: true — Node.js will convert to sh -c 'file args...'
    if (!file.includes(' ') && !(/[|><&;`$()]/).test(file)) {
      const resolved = _resolveCommand(file, spawnEnv);
      if (resolved) {
        if (_needsGlibcWrap(resolved)) {
          const env = Object.assign({}, spawnEnv);
          delete env.LD_PRELOAD;
          return {
            file: _glibcLdso,
            args: ['--library-path', _glibcLibPath, resolved].concat(args || []),
            options: Object.assign({}, options, { env: env, shell: false }),
          };
        }
        const shebang = _resolveShebang(resolved, spawnEnv);
        if (shebang) {
          return {
            file: shebang.interpPath,
            args: shebang.interpArgs.concat([resolved]).concat(args || []),
            options: Object.assign({}, options, { shell: false }),
          };
        }
      }
    }
    return null;
  }

  // Direct shell invocation: spawn('/path/to/sh', ['-c', 'cmd args...'])
  const shellCmd = _isShellInvocation(file, args);
  if (shellCmd) {
    const fix = _tryFixShellCommand(shellCmd, spawnEnv);
    if (fix) {
      return {
        file: fix.file,
        args: fix.args,
        options: fix.env ? Object.assign({}, options, { env: fix.env }) : options,
      };
    }
    return null;
  }

  const resolved = _resolveCommand(file, spawnEnv);
  if (!resolved) return null;
  if (resolved === _glibcLdso || resolved.endsWith('/ld-linux-aarch64.so.1')) return null;

  // Case 1: glibc ELF binary — wrap with ld.so
  if (_needsGlibcWrap(resolved)) {
    const env = Object.assign({}, spawnEnv);
    delete env.LD_PRELOAD;
    return {
      file: _glibcLdso,
      args: ['--library-path', _glibcLibPath, resolved].concat(args || []),
      options: Object.assign({}, options, { env: env }),
    };
  }

  // Case 2: Script with shebang pointing to non-existent path
  const shebang = _resolveShebang(resolved, spawnEnv);
  if (!shebang) return null;

  return {
    file: shebang.interpPath,
    args: shebang.interpArgs.concat([resolved]).concat(args || []),
    options: options,
  };
}

if (fs.existsSync(_glibcLdso)) {
  const _cp = require('child_process');

  // Normalize optional args parameter: spawn(cmd[, args][, opts])
  function _normalizeArgs(args, options) {
    if (args != null && !Array.isArray(args)) {
      return { args: [], options: args };
    }
    return { args: args || [], options: options };
  }

  const _origSpawn = _cp.spawn;
  _cp.spawn = function spawn(command, args, options) {
    const n = _normalizeArgs(args, options);
    const w = _tryWrapSpawn(command, n.args, n.options);
    if (w) return _origSpawn.call(_cp, w.file, w.args, w.options);
    return _origSpawn.call(_cp, command, args, options);
  };

  const _origSpawnSync = _cp.spawnSync;
  _cp.spawnSync = function spawnSync(command, args, options) {
    const n = _normalizeArgs(args, options);
    const w = _tryWrapSpawn(command, n.args, n.options);
    if (w) return _origSpawnSync.call(_cp, w.file, w.args, w.options);
    return _origSpawnSync.call(_cp, command, args, options);
  };

  const _origExecFile = _cp.execFile;
  _cp.execFile = function execFile(file, args, options, callback) {
    // execFile(file[, args][, options], callback)
    if (typeof args === 'function') {
      callback = args; args = []; options = {};
    } else if (typeof options === 'function') {
      callback = options;
      if (Array.isArray(args)) { options = {}; } else { options = args; args = []; }
    }
    const w = _tryWrapSpawn(file, args, options);
    if (w) return _origExecFile.call(_cp, w.file, w.args, w.options, callback);
    return _origExecFile.call(_cp, file, args, options, callback);
  };

  const _origExecFileSync = _cp.execFileSync;
  _cp.execFileSync = function execFileSync(file, args, options) {
    const n = _normalizeArgs(args, options);
    const w = _tryWrapSpawn(file, n.args, n.options);
    if (w) return _origExecFileSync.call(_cp, w.file, w.args, w.options);
    return _origExecFileSync.call(_cp, file, args, options);
  };
}
