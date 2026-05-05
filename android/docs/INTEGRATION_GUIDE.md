# Guía de integración — OpenClaw Android

> Referencia rápida para desarrolladores que trabajan en el proyecto.

---

## Estructura de componentes

### Kotlin — capas principales

```
InstallerManager                    ← punto de entrada para instalación
    └── InstallationOrchestrator    ← orquestador unificado
            ├── PayloadInstaller    ← modo offline
            ├── TermuxBootstrapOrchestrator ← modo online (entorno base)
            └── ProotCommandExecutor ← modo proot

EnvironmentResolver                 ← única fuente de verdad para rutas
    └── EnvironmentConfig           ← snapshot inmutable

JsBridgeFacade                      ← bridge WebView ↔ Kotlin
    ├── TerminalBridge
    ├── SetupBridge
    ├── PlatformBridge
    ├── ToolsBridge
    └── SystemBridge

ModernPermissionManager             ← permisos con suspend API
```

### React — capas principales

```
AppProvider (AppContext)            ← estado centralizado
    ├── bridge.ts (batchCall)       ← comunicación con Kotlin
    ├── useNativeEvent              ← eventos Kotlin → React
    └── screens/
        ├── Dashboard               ← consume AppContext
        ├── Setup                   ← flujo de instalación
        └── Settings*               ← lazy loaded
```

---

## Añadir un nuevo método al bridge

### 1. Implementar en el bridge de dominio correspondiente

```kotlin
// En bridge/SystemBridge.kt (o el bridge apropiado)
fun myNewMethod(): String {
    return try {
        val result = /* lógica */
        gson.toJson(mapOf("value" to result))
    } catch (e: Exception) {
        AppLogger.e(TAG, "myNewMethod failed", e)
        """{"error": "${e.message}"}"""
    }
}
```

### 2. Exponer en JsBridgeFacade

```kotlin
// En bridge/JsBridgeFacade.kt
@JavascriptInterface fun myNewMethod(): String = system.myNewMethod()
```

### 3. Añadir al batchQuery si es un método de estado

```kotlin
// En JsBridgeFacade.batchQuery()
"myNewMethod" -> system.myNewMethod()
```

### 4. Declarar en bridge.ts

```typescript
// En lib/bridge.ts — interfaz OpenClawBridge
myNewMethod(): string
```

### 5. Usar desde React

```typescript
// Llamada directa
const result = bridge.callJson<MyType>('myNewMethod')

// Via AppContext (si es estado que debe estar centralizado)
// Añadir al refresh() en AppContext.tsx
```

---

## Añadir una nueva pantalla de settings

### 1. Crear el componente

```typescript
// src/screens/SettingsMyFeature.tsx
export function SettingsMyFeature() {
  const { navigate } = useRoute()
  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">My Feature</div>
      </div>
      {/* contenido */}
    </div>
  )
}
```

### 2. Añadir lazy import en App.tsx

```typescript
const SettingsMyFeature = lazy(() =>
  import('./screens/SettingsMyFeature').then(m => ({ default: m.SettingsMyFeature }))
)
```

### 3. Añadir ruta en SettingsRouter

```typescript
if (path === '/settings/my-feature') return <SettingsMyFeature />
```

### 4. Añadir entrada en Settings.tsx

```typescript
<SettingsRow
  label="My Feature"
  desc="Description"
  onClick={() => navigate('/settings/my-feature')}
/>
```

---

## Añadir un nuevo evento nativo

### Kotlin — emitir evento

```kotlin
// Desde cualquier bridge o manager
eventBridge.emit("my_event", mapOf(
    "key" to "value",
    "progress" to 0.5,
))
```

### React — escuchar evento

```typescript
// En cualquier componente
useNativeEvent('my_event', (data) => {
    const d = data as { key: string; progress: number }
    // manejar evento
})
```

### AppContext — auto-refresh en evento

```typescript
// En AppContext.tsx, añadir en la sección de Effects
useNativeEvent('my_event', () => refresh())
```

---

## Añadir una nueva herramienta en SettingsTools

```typescript
// En src/screens/SettingsTools.tsx — función getTools()
{ id: 'my-tool', name: 'My Tool', desc: t('tool_my_tool'), category: 'terminal', size: '~10MB' },
```

```typescript
// En src/i18n/en.ts
tool_my_tool: 'Description of my tool',

// En src/i18n/es.ts
tool_my_tool: 'Descripción de mi herramienta',
```

```kotlin
// En bridge/ToolsBridge.kt — manejar installTool("my-tool")
"my-tool" -> installMyTool(context)
```

---

## Debugging

### Logs Android

```bash
# Todos los logs de OpenClaw
adb logcat | grep -E "OpenClaw|JsBridge|Installer|Permission"

# Solo errores
adb logcat *:E | grep openclaw

# Logs del bridge
adb logcat | grep JsBridgeFacade
```

### Estado de instalación

```kotlin
// Desde cualquier lugar con acceso a context
val config = EnvironmentResolver.resolve(context)
AppLogger.d("Debug", "node: ${config.nodeBin.exists()}")
AppLogger.d("Debug", "glibc: ${config.linker.exists()}")
AppLogger.d("Debug", "openclaw: ${config.openClawMjs.exists()}")
```

### Estado del frontend

```typescript
// En la consola del WebView (chrome://inspect)
window.OpenClaw.getSetupStatus()
window.OpenClaw.getEnvironmentInfo()
window.OpenClaw.batchQuery('test', JSON.stringify(['getSetupStatus', 'getStorageInfo']))
```

---

## Reglas de diseño

| Regla | Motivo |
|---|---|
| `EnvironmentResolver` para todas las rutas | Nunca hardcodear `/data/data/` |
| `runSyncUnsafe` / `runStreamingUnsafe` para comandos internos | `sanitizeCommand` es para input externo |
| `LD_LIBRARY_PATH` sin `glibc/lib` en shells Bionic | Evita `CANNOT LINK EXECUTABLE` |
| `unset LD_PRELOAD` antes de lanzar node | Evita crash con `libtermux-exec.so` |
| `AppContext` para estado compartido | Evita llamadas duplicadas al bridge |
| `memo` en componentes de lista | Evita re-renders en actualizaciones de estado |
| `useCallback` en handlers | Estabiliza referencias para `memo` |
