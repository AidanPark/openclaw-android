# Manejo de dpkg --configure -a — Referencia Técnica

## El Problema

Al instalar el bootstrap de Termux en un paquete de Android diferente a `com.termux`, dpkg queda en estado `half-configured` porque las rutas hardcodeadas en los binarios de Termux apuntan a `/data/data/com.termux/files/usr/`, pero el sandbox real de la app es `/data/data/com.openclaw.android/files/usr/`.

Cuando se ejecuta `pkg update` o `dpkg --configure -a` sin las variables de entorno correctas, dpkg muestra este prompt interactivo:

```
Setting up apt (2.8.1-2) ...
Configuration file '/data/data/com.termux/files/usr/etc/apt/sources.list'
==> File on system created by you or by a script.
==> File also in package provided by package maintainer.
What would you like to do about it ?  Your options are:
 Y or I  : install the package maintainer's version
 N or O  : keep your currently-installed version
 D       : show the differences between the versions
 Z       : start a shell to examine the situation
The default action is to keep your current version.
*** sources.list (Y/I/N/O/D/Z) [default=N] ?
```

Sin respuesta, el proceso se cuelga indefinidamente bloqueando la instalación.

## La Respuesta Correcta

**Siempre `N`** — mantener la versión actual del `sources.list`.

El `sources.list` de Termux ya está configurado correctamente para los repositorios de Termux. Sobreescribirlo con la versión del paquete podría romper las URLs de los repositorios.

## Soluciones Implementadas

### 1. `TermuxBootstrapManager.runDpkgConfigure()`

Se ejecuta inmediatamente después de extraer el bootstrap, antes de `pkg update`.

```kotlin
// Variables de entorno que suprimen prompts
val fullEnv = buildTermuxEnv() + mapOf(
    "DEBIAN_FRONTEND" to "noninteractive",       // suprime prompts debconf
    "DEBCONF_NONINTERACTIVE_SEEN" to "true",
    "DPKG_FRONTEND_LOCKED" to "1",
)

// Comando con --force-confold: mantener config existente sin preguntar
ProcessBuilder(dpkg.absolutePath, "--configure", "-a", "--force-confold")

// Hilo separado que envía "N" por stdin como seguro adicional
Thread {
    process.outputStream.bufferedWriter().use { writer ->
        Thread.sleep(500)
        repeat(10) {
            writer.write("N\n")
            writer.flush()
            Thread.sleep(200)
        }
    }
}.start()
```

### 2. `TermuxBootstrapManager.runSinglePkgUpdate()`

Cada intento de `pkg update` usa `yes N |` como pipe:

```bash
yes N | pkg update -y -o Dpkg::Options::="--force-confold" 2>&1
```

- `yes N` — genera "N\n" continuamente en stdout, que se convierte en stdin del siguiente comando
- `-o Dpkg::Options::="--force-confold"` — pasa el flag a dpkg a través de apt

### 3. `TerminalManager.buildOnlineInstallScript()`

El script que se ejecuta en el terminal embebido:

```bash
_oca_fix_dpkg() {
  export DEBIAN_FRONTEND=noninteractive
  export DEBCONF_NONINTERACTIVE_SEEN=true
  yes N | dpkg --configure -a --force-confold 2>&1 || true
}

_oca_run_install() {
  curl -sL myopenclawhub.com/install | bash
  return $?
}

# Intento 1
_oca_run_install
_INSTALL_EXIT=$?

if [ "$_INSTALL_EXIT" -ne 0 ]; then
  _oca_fix_dpkg          # Reparar dpkg
  _oca_run_install       # Intento 2
fi
```

## Variables de Entorno Críticas para dpkg/apt

```bash
# Ruta base del sandbox de la app
PREFIX=/data/user/0/com.openclaw.android/files/usr

# CRÍTICO: sin estas variables, dpkg busca en /data/data/com.termux/...
DPKG_ADMINDIR=$PREFIX/var/lib/dpkg
DPKG_ROOT=$PREFIX
APT_CONFIG=$PREFIX/etc/apt/apt.conf

# Suprime prompts interactivos
DEBIAN_FRONTEND=noninteractive
DEBCONF_NONINTERACTIVE_SEEN=true
```

## Flujo de Reintentos

```
runPkgUpdateWithRetry(maxAttempts = 3)
    │
    ├─ Intento 1
    │    DEBIAN_FRONTEND=noninteractive
    │    yes N | pkg update -y --force-confold
    │    ──────────────────────────────────────
    │    ¿exit code 0 o "Reading package lists" en output?
    │    → return true ✓
    │
    ├─ Falla (exit code != 0)
    │    runDpkgConfigure()
    │      DEBIAN_FRONTEND=noninteractive
    │      yes N | dpkg --configure -a --force-confold
    │      Timeout: 60 segundos
    │    Thread.sleep(1000)
    │
    ├─ Intento 2
    │    [igual que intento 1]
    │    → return true ✓ o continúa
    │
    ├─ Falla
    │    runDpkgConfigure()
    │
    └─ Intento 3
         [igual que intento 1]
         → return true ✓ o return false
         [si false: instalación continúa con advertencia en logs]
```

## Diagnóstico

Si dpkg sigue fallando, verificar en los logs:

```
adb logcat | grep -E "TermuxBootstrap|dpkg-configure|pkg update"
```

Mensajes esperados:
```
[dpkg-configure] Setting up apt (2.8.1-2) ...
[dpkg-configure] sources.list (Y/I/N/O/D/Z) [default=N] ?
TermuxBootstrapManager: dpkg interactive prompt detected
TermuxBootstrapManager: dpkg --configure -a exited with code 0
TermuxBootstrapManager: pkg update succeeded on attempt 2
```

Si aparece `pkg update timed out`, el dispositivo puede ser muy lento o no tener conexión a internet.
