# OpenClaw Android — Generación de Payload desde Termux (aarch64)

> **Propósito:** Construir el directorio `payload/` autocontenido que se empaqueta
> como assets del APK. Contiene glibc, Node.js, certificados CA y los scripts
> de runtime, permitiendo ejecutar Node.js sin dependencia de Termux en el
> dispositivo del usuario final.

---

## Requisitos previos

| Requisito | Detalle |
|-----------|---------|
| Teléfono Android | aarch64, Android 10+, con **Termux** instalado |
| Conexión de red | PC y teléfono en la **misma red Wi-Fi** |
| OpenSSH en Termux | `pkg install openssh` + `sshd` corriendo en el teléfono |
| PC Windows 10/11 | PowerShell con cliente OpenSSH (`ssh` y `scp`) disponibles |
| Script | `android/scripts/build-payload.sh` en el repositorio |

### Verificar que OpenSSH está disponible en PowerShell

```powershell
ssh -V
scp
```

Si no están disponibles: **Configuración → Aplicaciones → Características opcionales → Agregar: OpenSSH Client**.

---

## Paso 1 — Iniciar el servidor SSH en Termux

En el teléfono, dentro de Termux:

```bash
pkg install openssh -y
passwd          # establecer contraseña para la sesión SSH
sshd            # iniciar el servidor SSH (puerto 8022 por defecto)
whoami          # anotar el usuario, ej: u0_a340
ip addr         # anotar la IP local, ej: 10.163.7.228
```

> ⚠️ El servidor SSH de Termux escucha en el **puerto 8022**, no en el 22.

---

## Paso 2 — Verificar conectividad desde PowerShell

```powershell
Test-NetConnection 10.163.7.228 -Port 8022
```

**Resultado esperado:**

```
TcpTestSucceeded : True
```

Si falla: revisar que el teléfono y el PC estén en la misma red y que `sshd` esté corriendo.

---

## Paso 3 — Copiar el script al teléfono (PowerShell)

```powershell
scp -P 8022 "D:\Proyectos\Android\openclaw-android\android\scripts\build-payload.sh" u0_a340@10.163.7.228:~/
```

**Resultado esperado:**

```
build-payload.sh    100%   20KB   659.6KB/s   00:00
```

---

## Paso 4 — Conectarse por SSH a Termux (PowerShell)

```powershell
ssh -p 8022 u0_a340@10.163.7.228
```

Ingresar la contraseña configurada en el Paso 1.  
Resultado: prompt de Termux `~ $`.

---

## Paso 5 — Instalar dependencias en Termux

```bash
pkg update -y
pkg install -y nodejs xz-utils glibc ca-certificates coreutils
```

Verificar que glibc quedó correctamente instalado:

```bash
ls $PREFIX/glibc/lib/ld-linux-aarch64.so.1
```

**Resultado esperado:** muestra la ruta del archivo (no un error).

---

## Paso 6 — Ejecutar el script de build

```bash
chmod +x ~/build-payload.sh
./build-payload.sh
```

### Salida esperada (exitosa)

```
═══════════════════════════════════════════════════════
  OpenClaw Android — Payload Builder
  Target: aarch64 / Android 10+ / No Termux dependency
═══════════════════════════════════════════════════════

[HH:MM:SS] ▸ Checking prerequisites...
[HH:MM:SS] ✓ Prerequisites satisfied
[HH:MM:SS] ▸ Setting up build directories...
[HH:MM:SS] ✓ Directories created: /data/data/com.termux/files/home/payload
[HH:MM:SS] ▸ [1/6] Bundling glibc runtime...
[HH:MM:SS]   Compressing glibc (~111M)...
[HH:MM:SS] ✓ glibc bundled: 27M
[HH:MM:SS] ▸ [2/6] Bundling CA certificates...
[HH:MM:SS] ✓ CA certificates bundled: NNN certificates
[HH:MM:SS] ▸ [3/6] Bundling Node.js v22.22.0...
[HH:MM:SS] ✓ Node.js bundled: XXM
[HH:MM:SS] ▸ [4/6] Bundling OpenClaw...
[HH:MM:SS] ✓ OpenClaw bundled: XXM
[HH:MM:SS] ▸ [5/6] Bundling patches...
[HH:MM:SS] ✓ glibc-compat.js copied
[HH:MM:SS] ▸ [6/6] Generating runtime scripts...
[HH:MM:SS] ✓ Copied post-setup.sh
[HH:MM:SS] ✓ Copied run-openclaw.sh
[HH:MM:SS] ▸ Generating checksums...
[HH:MM:SS] ✓ Checksums generated: N files
```

### Estructura generada en `~/payload/`

```
payload/
├── glibc-aarch64.tar.xz      (~27M — linker + libc + libstdc++)
├── certs/
│   └── cert.pem               (bundle de CAs)
├── bin/
│   ├── node
│   ├── npm
│   └── npx
├── lib/
│   ├── node/
│   │   ├── bin/node.real
│   │   └── lib/node_modules/
│   └── openclaw/
├── patches/
│   └── glibc-compat.js
├── post-setup.sh
├── run-openclaw.sh
└── PAYLOAD_CHECKSUM.sha256
```

---

## Paso 7 — Copiar el payload de vuelta al PC (PowerShell)

```powershell
scp -P 8022 -r u0_a340@10.163.7.228:~/payload/ "D:\Proyectos\Android\openclaw-android\android\app\src\main\assets\"
```

---

## 🔥 Optimización de transferencia (RECOMENDADO)

### ¿Por qué `scp -r` es lento?
Transferir miles de archivos pequeños (como los de `node_modules` en OpenClaw) de forma recursiva es ineficiente. El protocolo SSH negocia cada archivo individualmente, lo que añade latencia y reduce la velocidad efectiva a pocos KB/s.

### Estrategia de Archivo Único
La forma profesional es empaquetar todo el payload en un único archivo comprimido en Termux antes de transferirlo. Esto reduce la negociación SSH a **un solo paso** y utiliza el ancho de banda al 100%.

#### 1. En Termux (empaquetar):
```bash
# Crear un archivo comprimido del directorio payload
tar czf payload.tar.gz payload/
```

#### 2. En PowerShell (transferir):
```powershell
# Copiar el archivo único (mucho más rápido)
scp -P 8022 u0_a340@10.163.7.228:~/payload.tar.gz .
```

#### 3. En el PC (extraer):
```powershell
# Extraer el contenido al destino final
# Nota: Windows 10/11 incluye tar por defecto
tar xzf payload.tar.gz -C "D:\Proyectos\Android\openclaw-android\android\app\src\main\assets\"
```

---

## 🚀 Modo Avanzado (PRO): Transferencia de alta velocidad

Para optimizar el uso de CPU en el teléfono y maximizar la velocidad de red, se recomienda usar el nivel de compresión más bajo (`gzip -1`). Esto evita que el procesador del móvil se convierta en el cuello de botella.

```bash
# Empaquetado optimizado en Termux
tar cf - payload | gzip -1 > payload.tar.gz
```

**Ventaja:** En procesadores de gama media/baja, esto es hasta 3 veces más rápido que `tar czf` estándar.

---

## ⚡ Alternativa: Empaquetado por componentes

Si realizas cambios frecuentes solo en una parte del sistema, puedes empaquetar por separado:

* `glibc-runtime.tar.gz` (Solo si cambia la versión de glibc)
* `node-dist.tar.gz` (Solo si actualizas Node.js)
* `openclaw-core.tar.gz` (Para actualizaciones rápidas del juego)

**Ventaja:** Permite actualizar componentes específicos sin transferir los ~500MB del payload completo cada vez.

---

## ⚠️ Advertencias críticas

### No usar `zip`
No utilices el comando `zip` para empaquetar el payload. `zip` no preserva correctamente los enlaces simbólicos (*symlinks*) y los permisos de ejecución de Linux de la misma forma que `tar`. El uso de `zip` romperá el runtime de glibc al extraerse.

### No usar `scp -r` en producción
Aunque funciona para pruebas rápidas, `scp -r` puede fallar silenciosamente si hay demasiados archivos o archivos especiales. El flujo basado en `tar` es el estándar de oro en sistemas Unix/Linux.

---

## Resolución de problemas conocidos

### ✗ Transferencia lenta o congelada
**Causa:** Estás usando `scp -r` para transferir miles de archivos individuales.
**Solución:** Utiliza la sección **Optimización de transferencia** anterior y crea un archivo `payload.tar.gz`.

### ✗ `glibc archive verification failed`

**Causa raíz:** `SIGPIPE` + `set -o pipefail`.

El código original usaba `tar -tJf ... | grep -q "ld-linux"`. Con `pipefail`
activo, cuando `grep -q` encuentra el patrón y termina, cierra su extremo del
pipe. `tar` recibe `SIGPIPE` y sale con código 141 (128 + señal 13). `pipefail`
interpreta eso como un fallo del pipeline, aunque el archivo sea perfectamente
válido y el patrón esté presente. **Es un falso positivo 100%.**

**El archivo `.tar.xz` NO está corrupto.** El debug mismo muestra el linker.

**Fix aplicado en el script** (función `_verify_glibc_archive`):
- Se captura la salida de `tar` en una variable antes de hacer `grep`.
- Sin pipe activo = sin SIGPIPE = sin falso positivo.
- Se añaden capas de verificación: tamaño, integridad xz, linker presente.

**Diagnóstico manual:**
```bash
# 1. Verificar integridad xz (rápido, no descomprime)
xz -t ~/payload/glibc-aarch64.tar.xz && echo "✅ xz OK" || echo "❌ corrupto"

# 2. Listar sin pipe (evita SIGPIPE)
tar -tJf ~/payload/glibc-aarch64.tar.xz 2>/dev/null > /tmp/glibc-list.txt
grep "ld-linux" /tmp/glibc-list.txt && echo "✅ linker OK" || echo "❌ falta linker"
```

---

### ✗ `glibc not found at /data/data/com.termux/files/usr/glibc`

**Causa:** El paquete `glibc` no está instalado.

```bash
pkg install glibc -y
# Si el paquete no existe:
pkg install glibc-repo -y && pkg install glibc -y
```

---

### ✗ `TcpTestSucceeded : False` en el Paso 2

- Verificar que `sshd` esté corriendo en Termux: `ps aux | grep sshd`
- Verificar que PC y teléfono estén en la misma red Wi-Fi.
- Volver a ejecutar `sshd` en Termux.

---

### Modo debug del script completo

```bash
bash -x ~/build-payload.sh 2>&1 | tee ~/build.log
tail -40 ~/build.log
```

---

## Comandos útiles en Termux

| Objetivo | Comando |
|----------|---------|
| Ver espacio libre | `df -h /data` |
| Verificar conectividad | `ping -c 3 8.8.8.8` |
| Comprobar red del teléfono | `curl -I https://ftp.gnu.org/gnu/glibc/` |
| Versión de Termux tools | `pkg list-installed \| grep termux-tools` |
| Reiniciar sshd | `pkill sshd; sshd` |
| Ver usuarios Termux | `whoami` |
| Ver IP local | `ip addr show wlan0` |
| Salir de SSH | `exit` o `Ctrl+D` |

---

## Referencia de versiones

| Componente | Versión |
|------------|---------|
| Node.js | 22.22.0 |
| glibc | 2.42-0 |
| gcc-libs | 14.2.1-1 |
| Target ABI | aarch64 / Android 10+ |
| Puerto SSH Termux | 8022 |

---

## Variables de entorno relevantes del script

| Variable | Valor por defecto |
|----------|-------------------|
| `PAYLOAD_DIR` | `$(pwd)/payload` |
| `WORK_DIR` | `$(pwd)/.build-work` |
| `LOG_FILE` | `$(pwd)/build-payload.log` |
| `TERMUX_PREFIX` | `/data/data/com.termux/files/usr` |
| `TERMUX_HOME` | `/data/data/com.termux/files/home` |

> El log detallado del build se guarda automáticamente en `~/build-payload.log`.
