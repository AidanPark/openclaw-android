# TODO

Estado: ✅ Completado

## Mejoras UI/UX realizadas (2026-05-02)

1. ✅ **Bug fix: `runtime-grid` CSS faltante** — El Dashboard usaba `.runtime-grid` pero el CSS solo tenía `.dash-env-grid`. Corregido.
2. ✅ **Bug fix: `var(--primary)` / `var(--primary-rgb)` no definidas** — Añadidas como alias de `--accent` en los tokens CSS.
3. ✅ **Bug fix: `@keyframes pulse` faltante** — Animación del FAB de setup no estaba definida. Añadida.
4. ✅ **Bug fix: Iconos duplicados en Quick Actions** — "Sesiones" y "Nueva sesión" tenían el mismo icono 📋. Corregido.
5. ✅ **Bug fix: Status hardcodeado en español** — "detectado" ahora usa la clave i18n `env_detected`.
6. ✅ **Bug fix: `openssh-server` descripción incorrecta** — Usaba `tool_ttyd`. Corregido con `tool_ssh_server`.
7. ✅ **i18n: Nuevas claves** — `env_detected` y `tool_ssh_server` añadidas a EN y ES.
8. ✅ **CSS: Feedback táctil mejorado** — `.dash-env-item.clickable:active` añadido.
9. ✅ **CSS: Color de estado activo** — `.dash-env-item.active .dash-env-status` en verde.
10. ✅ **CHANGELOG actualizado** — Documentados todos los cambios en `docs/CHANGELOG.md`.
