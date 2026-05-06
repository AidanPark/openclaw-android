# payload.tar.gz — Referencia

## Qué es

`payload.tar.gz` es el paquete de OpenClaw pre-compilado que se incluye directamente en el APK. Contiene Node.js, glibc y openclaw.mjs listos para extraer.

**No es Termux.** No contiene dpkg, apt ni bash. El bootstrap de Termux se instala por separado y siempre va primero.

## Contenido

```
payload/
├── lib/
│   ├── node/bin/node.real          ← Node.js ELF (~120MB)
│   ├── node/lib/node_modules/npm/  ← npm
│   └── openclaw/openclaw.mjs       ← Entry point OpenClaw
├── glibc/
│   ├── lib/ld-linux-aarch64.so.1   ← Linker dinámico glibc
│   ├── lib/libc.so.6               ← libc glibc
│   └── etc/resolv.conf
├── certs/cert.pem                  ← Certificados SSL
├── patches/glibc-compat.js         ← Shim de compatibilidad
└── run-openclaw.sh                 ← Script de lanzamiento
```

## Gestión del Archivo

| Aspecto | Valor |
|---------|-------|
| Ruta en proyecto | `android/app/src/main/assets/payload.tar.gz` |
| Tamaño | ~118MB comprimido |
| En Git | ❌ Excluido por `.gitignore` |
| En APK | ✅ Incluido por Gradle al compilar |
| `noCompress` | `"gz"` en `build.gradle.kts` |

### Por qué no está en Git

El archivo pesa ~118MB. Git no está diseñado para archivos binarios grandes. El archivo existe en el disco local del desarrollador y Gradle lo empaqueta en el APK al compilar.

### Por qué `noCompress = "gz"`

Sin esta configuración, `aapt2` recomprimiría el `.tar.gz` dentro del APK. Al intentar abrirlo con `GZIPInputStream`, el stream estaría doblemente comprimido y fallaría con `java.util.zip.ZipException: Not in GZIP format`.

```kotlin
// android/app/build.gradle.kts
androidResources {
    noCompress += listOf("gz", "xz", "tar.gz", "tar.xz", ...)
}
```

## Detección en Código

`InstallerManager.hasPayloadAsset()` busca estos nombres en orden:

```kotlin
val names = listOf(
    "payload.tar.gz",           // ← nombre actual
    "payload-final.tar.gz",
    "openclaw-payload.tar.gz",
    "payload/openclaw-payload.tar.gz",
    "payload/payload.tar.gz",
)
```

Si ninguno existe, loguea los assets disponibles para diagnóstico:
```
InstallerManager: No payload asset found. Root assets: [www, glibc-compat.js, ...]
```

## Flujo de Extracción

```
installOffline()
    │
    ├─ Copiar de assets a homeDir/openclaw-payload.tar.gz
    │    context.assets.open("payload.tar.gz")
    │    → homeDir/openclaw-payload.tar.gz
    │
    ├─ Extraer con PayloadExtractor.extractTarGzFile()
    │    GZIPInputStream → TarArchiveInputStream
    │    Buffer: 256KB (streaming, sin cargar en memoria)
    │    Symlinks: android.system.Os.symlink()
    │    Permisos: setExecutable() para bin/ y modo 0755
    │
    ├─ Resolver directorio del payload
    │    Busca: homeDir/payload, homeDir/openclaw-payload,
    │           filesDir/payload, filesDir/openclaw-payload
    │
    ├─ Verificar y reparar glibc
    │    ld-linux-aarch64.so.1 existe y >100KB?
    │    Reparar symlinks: libc.so → libc.so.6
    │
    ├─ Crear wrapper node en .openclaw-android/bin/
    │    exec ld-linux-aarch64.so.1 --library-path glibc/lib node.real "$@"
    │
    ├─ Configurar DNS y SSL
    │    glibc/etc/resolv.conf: 8.8.8.8, 1.1.1.1
    │    ssl/cert.pem: desde payload/certs/ o Android system certs
    │
    ├─ Crear openclaw-start.sh
    │
    ├─ Aplicar permisos recursivos en payload dir
    │
    ├─ InstallValidator.validatePayload()
    │    ✓ ld-linux-aarch64.so.1 existe y >100KB
    │    ✓ node binary existe y >1MB
    │    ✓ openclaw.mjs existe
    │    ✓ /system/bin/sh disponible
    │
    └─ Escribir .installed (solo si validación pasa)
```

## Orden de Instalación con Bootstrap

```
CORRECTO:
  1. TermuxBootstrapManager.install()  ← bash, dpkg, apt, pkg
  2. installOffline()                  ← Node.js, glibc, openclaw

INCORRECTO (no hacer):
  1. installOffline()                  ← falla: no hay bash para ejecutar scripts
  2. TermuxBootstrapManager.install()  ← sobreescribiría archivos del payload
```

El bootstrap instala en `filesDir/usr/` (el PREFIX). El payload también extrae en `homeDir/` y crea su propia estructura. El bootstrap va primero para que bash y curl estén disponibles cuando el payload necesite ejecutar scripts post-instalación.
