# Documentación Técnica — OpenClaw Android

## Índice

| Documento | Descripción |
|-----------|-------------|
| [INSTALLATION_FLOW.md](INSTALLATION_FLOW.md) | Flujo completo de instalación, todos los modos, diagramas |
| [DPKG_FIX.md](DPKG_FIX.md) | Manejo del prompt interactivo de dpkg --configure -a |
| [ENVIRONMENT_VARS.md](ENVIRONMENT_VARS.md) | Variables de entorno críticas y por qué son necesarias |
| [PAYLOAD_ASSET.md](PAYLOAD_ASSET.md) | Qué es payload.tar.gz, cómo se gestiona y extrae |

## Resumen Rápido

### Flujo de instalación

```
Termux Bootstrap (~50MB)     ← siempre primero
        +
payload.tar.gz (OpenClaw)    ← si está en el APK (modo auto/offline)
        o
curl -sL myopenclawhub.com/install | bash  ← en el terminal (modo online)
```

### Modos disponibles

| Modo | Cuándo usar |
|------|-------------|
| `auto` | APK con payload bundled — instalación sin internet |
| `online` | APK sin payload — instala OpenClaw desde internet |
| `termux-bootstrap` | Solo preparar el entorno base |
| `proot` | Usuarios avanzados, resistencia a Phantom Process Killer |

### Archivos de marcador

| Archivo | Indica |
|---------|--------|
| `.termux-bootstrap-installed` | Bootstrap de Termux instalado |
| `.installed` | Payload de OpenClaw instalado |
| `.proot-installed` | Ubuntu via proot instalado |
| `home/.openclaw-android/installed.json` | Instalación online completada |

### Problema más común

**dpkg --configure -a se cuelga** → Ver [DPKG_FIX.md](DPKG_FIX.md)

Solución rápida:
```bash
yes N | dpkg --configure -a --force-confold
```
