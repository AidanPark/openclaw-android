# Checklist de integración — OpenClaw Android

> Lista de verificación para validar que todos los sistemas funcionan correctamente tras un cambio o release.

---

## Build

- [ ] `./gradlew assembleDebug` — sin errores de compilación Kotlin
- [ ] `./gradlew test` — todos los tests unitarios pasan
- [ ] `cd android/www && npm run build` — sin errores TypeScript, build limpio
- [ ] `cd android/www && npm test` — tests Vitest pasan

---

## Instalación — modo offline (payload)

- [ ] `hasPayloadAsset()` devuelve `true` cuando el APK incluye `payload.tar.gz`
- [ ] La UI preselecciona modo offline automáticamente
- [ ] `PayloadInstaller` extrae correctamente sin saturar RAM
- [ ] `InstallValidator` verifica `node.real`, `openclaw.mjs`, `ld-linux-aarch64.so.1`
- [ ] `installed.json` se escribe en `home/.openclaw-android/`
- [ ] El terminal arranca con el shell correcto tras la instalación

## Instalación — modo online

- [ ] `TermuxBootstrapOrchestrator` descarga y extrae el bootstrap (~50MB)
- [ ] `dpkg --configure -a` no se cuelga (responde N automáticamente)
- [ ] `pkg update` completa con hasta 3 reintentos
- [ ] El terminal ejecuta `curl -sL myopenclawhub.com/install | bash`
- [ ] La salida del curl es visible en tiempo real en el terminal
- [ ] `installed.json` se escribe al completar

## Instalación — modo proot

- [ ] Proot binary se descarga correctamente
- [ ] Ubuntu rootfs se extrae sin errores
- [ ] El gateway arranca dentro del contenedor proot
- [ ] `.proot-installed` se escribe

---

## Permisos

- [ ] Android 11+: aparece diálogo "Permiso de Almacenamiento" con botones Permitir/Más tarde
- [ ] Denegar permisos: la app no crashea, continúa sin acceso externo
- [ ] Conceder permisos: redirige a Settings del sistema, al volver continúa la instalación
- [ ] Android 13+: solicita `POST_NOTIFICATIONS` en primera apertura
- [ ] `hasStoragePermission()` devuelve el estado correcto

---

## Bridge y comunicación

- [ ] `window.OpenClaw` disponible en WebView
- [ ] `bridge.callJson('getSetupStatus')` devuelve objeto válido
- [ ] `bridge.batchCall(['getSetupStatus', 'getEnvironmentInfo'])` resuelve en < 2s
- [ ] Evento `native:setup_progress` llega al frontend durante instalación
- [ ] Evento `native:session_changed` llega al frontend al crear/cambiar sesión
- [ ] `AppContext` se refresca automáticamente al recibir eventos

---

## Seguridad

- [ ] `OpenClaw.runCommand('rm -rf /')` → devuelve error, no ejecuta
- [ ] `OpenClaw.runCommand('ls; rm -rf ~')` → bloqueado
- [ ] `OpenClaw.runCommand('openclaw --version')` → ejecuta correctamente
- [ ] `batchQuery` con método desconocido → resultado con `error`, no crashea

---

## Frontend — Dashboard

- [ ] Skeleton loader visible mientras `loading = true`
- [ ] Versiones de Node.js, npm, git, openclaw se muestran correctamente
- [ ] Botón refresh actualiza el estado sin parpadeos
- [ ] Comandos del dashboard abren el terminal y ejecutan el comando
- [ ] Herramientas instaladas aparecen en la sección de tools

## Frontend — Setup

- [ ] Stepper muestra el paso correcto en cada fase
- [ ] Modo offline preseleccionado si hay payload en assets
- [ ] Progress ring se anima durante la instalación
- [ ] Tips rotan cada 4 segundos durante la instalación
- [ ] Pantalla de error muestra el mensaje y permite reintentar
- [ ] Verificación de conexión funciona (curl a registry.npmjs.org)

## Frontend — SettingsTools

- [ ] Lista de herramientas se carga correctamente
- [ ] Instalar herramienta muestra progress bar
- [ ] Desinstalar herramienta muestra diálogo de confirmación modal (no `window.confirm`)
- [ ] Confirmar desinstalación elimina la herramienta de la lista
- [ ] Cancelar desinstalación no hace nada

## Frontend — Code splitting

- [ ] Navegar a `/settings/tools` por primera vez → spinner breve → carga
- [ ] Segunda visita → instantáneo (chunk cacheado)
- [ ] DevTools Network: `SettingsTools-*.js` se carga solo al navegar a esa ruta

---

## Terminal

- [ ] Escribir comandos funciona correctamente
- [ ] Copiar/pegar funciona
- [ ] Teclas extra (Esc, Tab, Ctrl, Alt, flechas) funcionan
- [ ] Múltiples sesiones: crear, cambiar, cerrar
- [ ] El terminal no se congela durante la instalación en background

---

## Regresión

- [ ] Abrir app con instalación ya completa → va directo al dashboard
- [ ] Recargar WebView → estado se restaura correctamente
- [ ] Cambiar idioma (EN/ES) → todas las cadenas se traducen
- [ ] Pantallas de settings muestran información correcta
- [ ] OTA: `checkForUpdates()` devuelve respuesta válida

---

## Dispositivos de prueba recomendados

| Dispositivo | Android | Arquitectura | Prioridad |
|---|---|---|---|
| Cualquier ARM64 | 7.0 (API 24) | arm64-v8a | Alta |
| Cualquier ARM64 | 12.0 (API 31) | arm64-v8a | Alta (Phantom Process Killer) |
| Cualquier ARM64 | 14.0 (API 34) | arm64-v8a | Media |
| Emulador | cualquiera | x86_64 | Baja (solo UI) |
