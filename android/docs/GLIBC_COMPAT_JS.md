# glibc-compat.js — Documentación

Shim de compatibilidad para ejecutar Node.js (glibc, linux-arm64) dentro del sandbox de Android.

**Archivo:** `android/app/src/main/assets/glibc-compat.js`  
**Cargado via:** `NODE_OPTIONS="-r /path/to/glibc-compat.js"`  
**Aplica a:** Node.js v22+ compilado para linux-arm64 con glibc, corriendo en Android sin proot

---

## Por qué existe

Node.js oficial para linux-arm64 usa glibc. Android usa Bionic. Son incompatibles a nivel de libc, pero el kernel de Linux es el mismo. El truco es lanzar `node.real` via el loader glibc (`ld-linux-aarch64.so.1`) en lugar del linker de Android.

Eso resuelve el problema de libc, pero quedan restricciones a nivel de **kernel y SELinux** que glibc no puede resolver por sí solo. `glibc-compat.js` parchea esas restricciones desde JavaScript, en runtime, antes de que OpenClaw arranque.

---

## Cómo se carga

El archivo **nunca se importa directamente**. Se inyecta via `NODE_OPTIONS` en el wrapper script de node:

```sh
# ~/.openclaw-android/bin/node  (generado por InstallerManager / ScriptWriter)
unset LD_PRELOAD
export _OA_WRAPPER_PATH="/path/to/.openclaw-android/bin/node"

_OA_COMPAT="/path/to/.openclaw-android/patches/glibc-compat.js"
if [ -f "$_OA_COMPAT" ]; then
  case "${NODE_OPTIONS:-}" in
    *"$_OA_COMPAT"*) ;;   # ya está cargado, no duplicar
    *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }-r $_OA_COMPAT" ;;
  esac
fi

exec /path/to/glibc/lib/ld-linux-aarch64.so.1 \
    --library-path /path/to/glibc/lib \
    /path/to/node.real "$@"
```

Node.js procesa `NODE_OPTIONS` antes de ejecutar cualquier código de usuario, así que el shim está activo desde el primer tick del event loop.

---

## Qué hace — sección por sección

### 1. `process.execPath` fix

**Problema:** Cuando node corre via `ld-linux-aarch64.so.1 node.real`, `process.execPath` apunta al loader (`ld.so`), no al wrapper. OpenClaw usa `process.execPath` para lanzar procesos hijo de node — si apunta a `ld.so`, esos hijos no cargan el shim ni hacen `unset LD_PRELOAD`, y crashean.

**Fix:**
```js
Object.defineProperty(process, 'execPath', {
  value: process.env._OA_WRAPPER_PATH,  // el wrapper script
  writable: true, configurable: true,
});
```

`_OA_WRAPPER_PATH` lo inyecta el wrapper script antes de lanzar node.

---

### 2. `LD_PRELOAD` cleanup

**Problema:** Termux inyecta `libtermux-exec.so` via `LD_PRELOAD` para traducir rutas de shebangs (`/usr/bin/env` → `$PREFIX/bin/env`). Esa librería Bionic no puede cargarse en un proceso glibc — causa crash `"Could not find a PHDR"`.

El wrapper hace `unset LD_PRELOAD` antes de lanzar node, pero si algo lo restaura dentro del proceso, los procesos hijo crashean.

**Fix:**
```js
delete process.env.LD_PRELOAD;
delete process.env._OA_ORIG_LD_PRELOAD;
```

Se ejecuta al inicio del shim, garantizando que ningún código posterior pueda restaurarlo accidentalmente.

---

### 3. `os.cpus()` fallback

**Problema:** Android 8+ (API 26+) bloquea `/proc/stat` via SELinux + `hidepid=2`. libuv lee `/proc/stat` para obtener info de CPU → devuelve array vacío. Herramientas que usan `os.cpus().length` para paralelismo (ej: `make -j`) fallan con 0 workers.

**Fix:**
```js
os.cpus = function cpus() {
  const result = _originalCpus.call(os);
  if (result.length > 0) return result;
  // Devolver al menos 1 CPU falso para que .length >= 1
  return [{ model: 'unknown', speed: 0, times: { user:0, nice:0, sys:0, idle:0, irq:0 } }];
};
```

---

### 4. `os.networkInterfaces()` safety + Bonjour

**Problema 1:** Algunas configuraciones Android lanzan `EACCES` al leer interfaces de red.

**Problema 2:** Android/Termux solo expone la interfaz loopback (`lo`) a Node.js. El advertiser Bonjour de OpenClaw no puede enviar multicast y llena los logs con `"Announcement failed as of socket errors!"`.

**Fix:**
```js
os.networkInterfaces = function networkInterfaces() {
  let interfaces;
  try {
    interfaces = _originalNetworkInterfaces.call(os);
  } catch {
    interfaces = { lo: [{ address: '127.0.0.1', ... }] };  // fallback seguro
  }
  // Auto-deshabilitar Bonjour si solo hay loopback
  if (!process.env.OPENCLAW_DISABLE_BONJOUR && !_hasNonLoopbackInterface(interfaces)) {
    process.env.OPENCLAW_DISABLE_BONJOUR = '1';
  }
  return interfaces;
};
```

---

### 5. Shell override para `exec`/`execSync`

**Problema:** `child_process.exec` y `execSync` usan `/bin/sh` como shell por defecto en Linux. En Android:
- Android 7-8: `/bin/sh` no existe
- Android 9+: `/bin/sh` existe pero es minimal (toybox/mksh), sin PATH de Termux

**Fix:**
```js
const termuxSh = process.env.PREFIX + '/bin/sh';
if (fs.existsSync(termuxSh)) {
  child_process.exec = function exec(command, options, callback) {
    options = options || {};
    if (!options.shell) options.shell = termuxSh;  // usar sh de Termux
    return _originalExec.call(child_process, command, options, callback);
  };
  // mismo para execSync
}
```

---

### 6. DNS resolver fix

**Problema:** glibc lee `/etc/resolv.conf` para DNS. En el sandbox de la app, esa ruta no existe o no es accesible. `dns.lookup()` falla con `EAI_AGAIN` para cualquier hostname.

**Fix:** Sobreescribir `dns.lookup` y `dns.promises.lookup` para usar el resolver c-ares (que respeta `dns.setServers()`) en lugar de `getaddrinfo()` de glibc:

```js
dns.setServers(['8.8.8.8', '8.8.4.4']);  // fallback si resolv.conf no existe

dns.lookup = function lookup(hostname, options, callback) {
  // Short-circuit localhost — nunca va a DNS externo
  if (_localhostNames.has(hostname)) {
    return callback(null, '127.0.0.1', 4);
  }
  // Intentar con c-ares (dns.resolve4/resolve6)
  // Si falla, caer al getaddrinfo original
  tryResolve(family === 6 ? 6 : 4);
};
```

También sobreescribe `dns.promises.lookup` (usado por el SSRF guard de OpenClaw para `web_search`).

---

### 7. ELF auto-wrapping para `spawn`/`spawnSync`

**Problema:** Binarios nativos instalados via npm (ej: `@zed-industries/codex-acp`) son ELF linux-arm64 con interpreter `/lib/ld-linux-aarch64.so.1`. Android no tiene ese path → el kernel devuelve `ENOENT` al intentar ejecutarlos directamente.

**Fix:** Interceptar todas las APIs de `child_process` y detectar si el binario a ejecutar es un ELF glibc. Si lo es, envolverlo automáticamente con el loader:

```js
// Antes (falla):
spawn('/path/to/codex-acp', args)

// Después (funciona):
spawn('/path/to/glibc/lib/ld-linux-aarch64.so.1',
      ['--library-path', '/path/to/glibc/lib', '/path/to/codex-acp', ...args])
```

**Detección de ELF glibc** — lee el header ELF y busca `PT_INTERP`:
```js
function _needsGlibcWrap(filePath) {
  // Lee los primeros 64 bytes (ELF header)
  // Verifica magic: 7f 45 4c 46
  // Escanea program headers buscando PT_INTERP (type=3)
  // Si el interpreter contiene 'ld-linux' → es glibc → necesita wrap
  // Si contiene 'linker64' → es Bionic → no necesita wrap
}
```

También maneja:
- **Shebangs rotos** (`#!/usr/bin/env node` cuando `env` no está en `/usr/bin/`): resuelve el intérprete desde `PATH`
- **`shell: true`**: detecta cuando Node.js va a convertir el comando en `sh -c 'cmd'` y aplica el wrap antes
- **Invocaciones directas de shell**: `spawn('/path/sh', ['-c', 'cmd'])` — intenta resolver `cmd` y wrapearlo si es ELF glibc

APIs interceptadas: `spawn`, `spawnSync`, `execFile`, `execFileSync`

---

## Dónde se instala en el dispositivo

```
~/.openclaw-android/patches/glibc-compat.js
```

Rutas de origen (en orden de prioridad):

| Fuente | Ruta |
|---|---|
| payload-final.tar.gz | `payload/patches/glibc-compat.js` |
| APK assets (fallback) | `assets/glibc-compat.js` |
| Descarga online | `https://repo/patches/glibc-compat.js` |

---

## Dónde se referencia en el código Kotlin

| Archivo | Qué hace |
|---|---|
| `InstallerManager.setupNodeWrapper()` | Copia desde payload o assets a `ocaDir/patches/`. Genera el wrapper `bin/node` que lo carga via `NODE_OPTIONS` |
| `ScriptWriter.writeNodeWrapper()` | Genera el wrapper `bin/node` con la lógica de `NODE_OPTIONS` |
| `CommandRunner.createWrapperScript()` | Genera `openclaw-start.sh` que carga el shim antes de lanzar el gateway |
| `EnvironmentResolver.buildEnvMap()` | Expone `_OA_COMPAT_PATH` como variable de entorno para referencia |
| `InstallValidator.validatePayload()` | Verifica que `patches/glibc-compat.js` existe (warning si falta) |
| `RootfsManager` | Lo copia desde assets al instalar el rootfs |

---

## Qué NO hace (ya no necesario con glibc)

A diferencia del anterior `bionic-compat.js`:

- ❌ No sobreescribe `process.platform` — glibc Node.js reporta `'linux'` nativamente
- ❌ No parchea `renameat2` / `spawn.h` — glibc los incluye
- ❌ No define `CXXFLAGS` / `GYP_DEFINES` — glibc es Linux estándar
- ❌ No intercepta `require('bindings')` — los módulos nativos compilan normalmente

---

## Regla crítica de LD_LIBRARY_PATH

`glibc-compat.js` asume que `LD_LIBRARY_PATH` con `glibc/lib` **NO está** en el entorno del proceso node. Ese path lo agrega el loader (`ld-linux-aarch64.so.1 --library-path`) internamente, no como variable de entorno heredable.

Si `LD_LIBRARY_PATH=glibc/lib` llega a un shell Bionic (`/system/bin/sh`), el linker de Android encuentra `libc.so` de glibc y falla:
```
CANNOT LINK EXECUTABLE "sh": cannot find "libc.so" from verneed[0]
```

Por eso `EnvironmentResolver.buildEnvMap()`, `TerminalManager.buildEnvBlock()` y `TerminalSessionManager` eliminan `LD_LIBRARY_PATH` antes de pasarlo a shells Bionic.

---

*OpenClaw 2026.4.29 | Node.js v22.22.0 linux-arm64 glibc | Android aarch64*
