# OpenClaw-Android Version Lock Policy

**ESTA PROHIBIDO** cambiar las siguientes versiones en `libs.versions.toml` o `build.gradle.kts` sin consentimiento explícito del usuario. 

| Componente | Versión Bloqueada | Razón |
| :--- | :--- | :--- |
| **Gradle** | 8.11.1 | Compatibilidad con AGP 8.7 y Kotlin 2.0+ |
| **AGP** | 8.7.0 | Requerida para las últimas APIs de Android 15 |
| **Kotlin** | 2.0.21 | Versión estable que resuelve errores de classpath en scripts |
| **Compile SDK** | 35 | Target para Android 15 |
| **NDK** | 27.0.12077973 | Necesaria para el soporte de glibc y node nativo |

**Regla de Oro**: Si hay un error de compilación, busca la causa en el código o en el classpath, NO intentes subir de versión los plugins base o Gradle.

---
*Ultima actualización: 2026-05-02*
