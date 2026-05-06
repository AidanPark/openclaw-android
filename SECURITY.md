# 🔒 Política de Seguridad

## 📦 Versiones Soportadas

| Versión | Soporte |
|---|---|
| App v0.4.x | ✅ Soportada |
| Script v1.0.x | ✅ Soportada |
| Versiones anteriores | ❌ Sin soporte |

---

## 🚨 Reportar una Vulnerabilidad

**Por favor NO abras un issue público de GitHub para vulnerabilidades de seguridad.**

En su lugar, repórtalas de forma responsable:

**GitHub**: Usa [GitHub Security Advisories](https://github.com/AidanPark/openclaw-android/security/advisories/new)

### Qué Incluir

- Descripción de la vulnerabilidad
- Pasos para reproducir
- Evaluación del impacto
- Corrección sugerida (si la hay)

### Tiempos de Respuesta

| Acción | Plazo |
|---|---|
| ✉️ Acuse de recibo | 48 horas |
| 🔍 Evaluación | 1 semana |
| 🔧 Corrección (crítica) | 2 semanas |

---

## 🏗️ Arquitectura de Seguridad

OpenClaw en Android ejecuta binarios Linux estándar en Android sin proot-distro. El modelo de seguridad está determinado por este entorno de ejecución único.

### Aislamiento de Ejecución

```
┌──────────────────────────────────────────────────┐
│  Kernel Android (SELinux aplicado)               │
│  ┌────────────────────────────────────────────┐  │
│  │  Sandbox Termux                            │  │
│  │  /data/data/com.termux/files/              │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │  glibc-runner (solo userspace ld.so) │  │  │
│  │  │  grun → Node.js → OpenClaw gateway   │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

### Rutas Críticas del Sistema

```
/data/data/com.termux/files/
├── home/.openclaw-android/
│   ├── bin/          ← binarios OpenClaw (grun aquí)
│   ├── installed.json ← marcador de instalación
│   └── post-setup.sh
├── home/openclaw-start.sh  ← wrapper script (usa grun)
└── usr/              ← bootstrap Termux (bash, apt, libs)
```

### Capas de Aislamiento

| Capa | Descripción |
|---|---|
| 🤖 Sandbox de app Android | Termux se ejecuta en su propio namespace de usuario Linux; sin acceso a datos de otras apps |
| 🛡️ SELinux | El control de acceso obligatorio de Android aplica a todos los procesos Termux |
| 👤 Sin root | Todo el stack se ejecuta como usuario sin privilegios regular |
| 🚫 Sin proot | Sin capa de traducción de sistema de archivos; glibc-runner proporciona solo el enlazador dinámico |
| 🔄 Conversión de rutas | Las rutas Linux estándar se mapean a equivalentes Termux en tiempo de instalación, no en runtime via intercepción de syscalls |

### Modelo de Ejecución Segura

```bash
# ✅ CORRECTO — usa grun (glibc-runner), entorno login completo
bash -l -c "grun openclaw gateway --host 0.0.0.0"

# ✅ CORRECTO — via wrapper script
bash -l -c "~/openclaw-start.sh"

# ❌ INCORRECTO — node directamente falla en Android
node openclaw gateway
```

---

## ✅ Qué Protegemos

- Acceso no autorizado a datos del sistema Android o de otras apps (aplicado por el sandbox Android)
- Ejecución de código arbitrario fuera de Termux (prevenido por SELinux + sandbox de app)
- Path traversal desde Termux hacia rutas del sistema Android (aislamiento del prefix Termux)
- Contaminación del entorno del usuario (registros npm, gitconfig, variables de entorno)

## ❌ Fuera de Alcance

| Componente | Dónde Reportar |
|---|---|
| Vulnerabilidades en OpenClaw core | [OpenClaw upstream](https://github.com/openclaw/openclaw) |
| Vulnerabilidades en Termux | [Termux App](https://github.com/termux/termux-app) |
| Vulnerabilidades en glibc-runner | [termux-pacman](https://github.com/AidanPark/openclaw-android) |
| Seguridad a nivel de dispositivo | Dispositivos rooteados, bootloaders desbloqueados |

---

## 🔑 Permisos Android

| Permiso | Justificación |
|---|---|
| `INTERNET` | Descarga bootstrap Termux y actualizaciones OTA |
| `FOREGROUND_SERVICE` | Mantiene el gateway activo en background |
| `WAKE_LOCK` | Evita suspensión durante instalación larga |
| `RECEIVE_BOOT_COMPLETED` | Auto-inicio del gateway al arrancar el dispositivo |
| `READ/WRITE_EXTERNAL_STORAGE` | Requerido por termux-setup-storage en Android 6–10 |
| `MANAGE_EXTERNAL_STORAGE` | Requerido por termux-setup-storage en Android 11+ |

> ℹ️ `targetSdk 28` es intencional — permite la ejecución de binarios en `/data/data/` (bypass W^X). No es una vulnerabilidad sino un requisito de diseño del entorno Termux.
