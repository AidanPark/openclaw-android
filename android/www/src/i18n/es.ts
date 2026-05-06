export const es = {
  // Barra de pestañas
  tab_terminal: '🖥 Terminal',
  tab_dashboard: '📊 Panel',
  tab_settings: '⚙ Ajustes',

  // Setup - pasos
  step_platform: 'Plataforma',
  step_path: 'Ruta',
  step_tools: 'Herramientas',
  step_setup: 'Instalación',

  // Setup - selección de plataforma
  setup_choose_platform: 'Elige tu plataforma',
  setup_more_platforms: 'Más plataformas disponibles en Ajustes.',
  setup_mode_title: 'Modo de instalación',
  setup_mode_online_label: 'Instalación Online',
  setup_mode_online_desc: 'Descarga los componentes desde internet (~200MB).',
  setup_mode_offline_label: 'Instalación Offline',
  setup_mode_offline_desc: 'Instala desde un archivo local. Ideal sin internet.',
  setup_offline_not_found: 'Archivo de instalación no encontrado en la app.',
  setup_offline_select: 'Seleccionar archivo .tar.gz',
  setup_offline_selected: 'Archivo seleccionado: {name}',

  // Setup - selección de ruta
  setup_path_title: 'Ubicación de instalación',
  setup_path_desc: 'Elige dónde instalar el entorno. Usa la ruta local de la app salvo que tengas Termux instalado.',
  setup_path_local_label: 'Local de la app',
  setup_path_local_default: 'Almacenamiento interno de la app',
  setup_path_termux_label: 'Usar Termux',
  setup_path_recommended: 'Recomendado',
  setup_next: 'Siguiente',

  // Setup - selección de herramientas
  setup_optional_tools: 'Herramientas opcionales',
  setup_tools_desc: 'Selecciona herramientas para instalar junto a {platform}. Puedes añadir más en Ajustes.',
  setup_start: 'Iniciar instalación',

  // Setup - instalando
  setup_setting_up: 'Instalando...',
  setup_preparing: 'Preparando instalación...',
  setup_retry: 'Reintentar instalación',
  setup_check_connection: 'Verificar conexión',
  setup_checking_connection: 'Verificando...',
  setup_connection_ok: 'Conexión OK — reintentar',
  setup_connection_failed: 'Sin conexión a internet',
  setup_install_failed: 'La instalación falló',
  setup_failed_hint: 'Verifica tu conexión e intenta de nuevo. También puedes abrir el terminal para ver el registro completo.',
  setup_open_log: 'Ver terminal',
  setup_back_to_tools: 'Volver a herramientas',

  // Setup - listo
  setup_done_title: '¡Todo listo!',
  setup_done_desc: 'El terminal instalará los componentes de ejecución y las herramientas seleccionadas. Tarda entre 3 y 10 minutos.',
  setup_open_terminal: 'Abrir terminal',

  // Setup - consejos
  tip_1: 'Puedes instalar varias plataformas de IA y cambiar entre ellas en cualquier momento.',
  tip_2: 'La instalación es única. Los arranques posteriores son instantáneos.',
  tip_3: 'Una vez completada, tu IA funciona a plena velocidad, igual que en un ordenador.',
  tip_4: 'Todo el procesamiento ocurre localmente en tu dispositivo. Tus datos no salen del móvil.',

  // Setup - descripciones de herramientas
  tool_tmux: 'Multiplexor de terminal para sesiones en segundo plano',
  tool_ttyd: 'Terminal web — accede desde el navegador',
  tool_ssh_server: 'Servidor OpenSSH — acceso remoto por SSH',
  tool_dufs: 'Servidor de archivos (WebDAV)',
  tool_code_server: 'VS Code en el navegador',
  tool_claude_code: 'CLI de IA de Anthropic',
  tool_gemini_cli: 'CLI de IA de Google',
  tool_codex_cli: 'CLI de IA de OpenAI',

  // Panel
  dash_setup_required: 'Instalación requerida',
  dash_setup_desc: 'El entorno de ejecución aún no está instalado.',
  dash_commands: 'Comandos',
  dash_runtime: 'Entorno de ejecución',
  dash_management: 'Gestión',

  env_not_detected: 'no encontrado',
  env_detected: 'detectado',

  // Panel - comandos
  cmd_gateway: 'Iniciar la pasarela',
  cmd_status: 'Ver estado de la pasarela',
  cmd_onboard: 'Asistente de configuración inicial',
  cmd_logs: 'Ver registros en tiempo real',
  cmd_update: 'Actualizar OpenClaw y todos los componentes',
  cmd_install_tools: 'Añadir o eliminar herramientas opcionales',

  // Panel - acciones rápidas
  dash_quick_actions: 'Acciones rápidas',
  dash_sessions: 'Sesiones',
  dash_new_session: 'Nueva sesión',
  dash_reload_ui: 'Recargar interfaz',

  // Ajustes
  settings_title: 'Ajustes',
  settings_language: 'Idioma',
  settings_platforms: 'Plataformas',
  settings_platforms_desc: 'Gestionar plataformas instaladas',
  settings_tools: 'Herramientas adicionales',
  settings_tools_desc: 'Herramientas de terminal',
  settings_updates: 'Actualizaciones',
  settings_updates_desc: 'Buscar actualizaciones',
  settings_updates_badge: 'Actualizaciones disponibles',
  settings_keep_alive: 'Mantener activo',
  settings_keep_alive_desc: 'Evitar que Android lo detenga',
  settings_storage: 'Almacenamiento',
  settings_storage_desc: 'Gestionar uso del disco',
  settings_about: 'Acerca de',
  settings_about_desc: 'Información de la app y licencias',

  // Ajustes - Mantener activo
  ka_title: 'Mantener activo',
  ka_desc: 'Android puede detener procesos en segundo plano. Sigue estos pasos para evitarlo.',
  ka_battery: '1. Optimización de batería',
  ka_status: 'Estado',
  ka_excluded: '✓ Excluido',
  ka_request: 'Solicitar exclusión',
  ka_developer: '2. Opciones de desarrollador',
  ka_developer_desc: '• Activar opciones de desarrollador\n• Activar "Pantalla siempre encendida"',
  ka_open_dev: 'Abrir opciones de desarrollador',
  ka_phantom: '3. Eliminador de procesos fantasma (Android 12+)',
  ka_phantom_desc: 'Conecta por USB y activa la depuración ADB, luego ejecuta este comando en tu PC:',
  ka_copy: 'Copiar',
  ka_copied: '¡Copiado!',
  ka_charge: '4. Límite de carga (opcional)',
  ka_charge_desc: 'Establece el límite de carga al 80% para uso continuo. Se configura en los ajustes de batería del móvil.',

  // Ajustes - Almacenamiento
  storage_title: 'Almacenamiento',
  storage_total: 'Total usado: ',
  storage_bootstrap: 'Bootstrap (usr/)',
  storage_www: 'Interfaz web (www/)',
  storage_free: 'Espacio libre',
  storage_clear: 'Limpiar caché',
  storage_clearing: 'Limpiando...',
  storage_loading: 'Cargando información de almacenamiento...',

  // Ajustes - Acerca de
  about_title: 'Acerca de',
  about_version: 'Versión',
  about_apk: 'APK',
  about_update_available: 'Actualización disponible',
  about_package: 'Paquete',
  about_script: 'Script',
  about_runtime: 'Entorno',
  about_license: 'Licencia',
  about_app_info: 'Info de la app',
  about_made_for: 'Hecho para Android',
  about_checking_apk: 'Verificando...',
  about_check_apk: '↑ Buscar actualización APK',
  about_installation: 'Instalación',
  about_bootstrap_installed: 'Bootstrap instalado',
  about_openclaw_installed: 'OpenClaw instalado',
  about_yes: '✓ Sí',
  about_no: '✗ No',
  about_github: 'GitHub ↗',
  about_bridge_unavailable: 'Puente no disponible',
  about_running_outside: 'Ejecutándose fuera del WebView de Android',

  // Ajustes - Actualizaciones
  updates_title: 'Actualizaciones',
  updates_checking: 'Buscando actualizaciones...',
  updates_up_to_date: 'Todo está actualizado.',
  updates_updating: 'Actualizando {name}...',
  updates_update: 'Actualizar',

  // Ajustes - Plataformas
  platforms_title: 'Plataformas',
  platforms_installing: 'Instalando {name}...',
  platforms_active: 'Activa',
  platforms_install: 'Instalar y cambiar',

  // Panel - git install hint
  git_not_available: 'git no disponible',
  git_install_hint: 'Necesario para clonar repositorios',
  git_install_btn: 'Instalar git',

  // Almacenamiento adicional
  storage_payload: 'Payload extraído',
  storage_node: 'Node.js',
  storage_openclaw: 'OpenClaw',
  storage_cache: 'Caché',
  storage_total_disk: 'Almacenamiento total',
  storage_available: 'Disponible en el dispositivo',
  storage_retry: 'Reintentar',

  tools_title: 'Herramientas adicionales',
  tools_installing: 'Instalando {name}...',
  tools_installed: 'Instalado ✓',
  tools_install: 'Instalar',
  tools_uninstall: 'Desinstalar',
  tools_confirm_uninstall: '¿Desinstalar {name}?',
  tools_cat_terminal: 'Herramientas de terminal',
  tools_cat_ai: 'Herramientas de IA',
  tools_cat_network: 'Red y acceso',
  tools_cat_system: 'Sistema',

  // Ajustes - Avanzado
  settings_advanced: 'Avanzado',
  settings_advanced_desc: 'Versiones, permisos, diagnósticos',

  // Setup - modo online hint
  setup_mode_online_hint: 'Requiere conexión a internet. Instala Node.js + OpenClaw via script oficial.',

  // Setup - selección de entorno
  setup_choose_env: 'Elige tu entorno',
  setup_choose_env_desc: 'Selecciona cómo quieres ejecutar OpenClaw en tu dispositivo.',
  setup_env_termux_label: 'Termux Bootstrap',
  setup_env_termux_desc: 'Entorno nativo de Termux. Más ligero y rápido de instalar.',
  setup_env_proot_label: 'Proot + Ubuntu Linux',
  setup_env_proot_desc: 'Ubuntu completo via proot. Más compatible y resistente al Phantom Process Killer.',
  setup_env_recommended: 'Recomendado',
  setup_back: 'Volver',

  // Setup - proot step
  setup_proot_title: 'Instalar Proot + Ubuntu',
  setup_proot_desc: 'Descarga e instala un entorno Ubuntu completo usando proot. Solo requiere internet.',
  setup_proot_feat_1: 'Ubuntu 22.04 completo con apt, bash, python, git',
  setup_proot_feat_2: 'Resistente al Phantom Process Killer de Android 12+',
  setup_proot_feat_3: 'Instalación online via curl | bash',
  setup_proot_size: 'Requiere ~80 MB de descarga y ~200 MB de espacio.',
  setup_proot_btn: 'Instalar Ubuntu (Proot)',
  setup_proot_installing: 'Instalando Ubuntu...',
  setup_proot_done: '✓ Ubuntu + Proot instalado',
  setup_proot_success_title: '¡Ubuntu instalado!',
  setup_proot_success_desc: 'El entorno Ubuntu está listo. Abre el terminal para empezar.',

  // Setup - bootstrap step
  setup_bootstrap_title: 'Paso 1: Entorno base',
  setup_bootstrap_desc: 'Instala el entorno Termux (bash, apt, herramientas). Obligatorio antes de instalar OpenClaw.',
  setup_bootstrap_installing: 'Instalando Termux Bootstrap...',
  setup_bootstrap_done: '✓ Entorno base instalado',
  setup_bootstrap_btn: 'Instalar entorno base',
  setup_bootstrap_size: '~30 MB · Requiere internet',
  setup_bootstrap_optional: 'Opcional para modo offline',
  setup_bootstrap_skip: 'Omitir y continuar',
  setup_bootstrap_skipped: 'Paso 1 omitido - modo offline activo',

  // Setup - openclaw step
  setup_openclaw_title: 'Paso 2: Instalar OpenClaw',
  setup_openclaw_desc: 'Elige cómo instalar OpenClaw (Node.js + runtime).',
  setup_openclaw_locked: 'Completa el Paso 1 primero.',
  setup_openclaw_locked_or_skip: 'Completa el Paso 1 u omítelo para desbloquear la instalación de OpenClaw.',
  setup_online_needs_bootstrap: 'La instalación online requiere que el entorno Termux (Paso 1) esté instalado.',
  setup_go_install_bootstrap: 'Volver para instalar entorno base',

  // Setup - success
  setup_success_title: '¡Instalación completada!',
  setup_success_desc: 'OpenClaw está listo. Abre el terminal para empezar.',
  setup_redirecting: 'Redirigiendo al dashboard en {seconds}s...',
  setup_go_dashboard: 'Ir al Dashboard',

  // Pantalla Avanzado
  advanced_title: 'Avanzado',
  advanced_versions: 'Versiones instaladas',
  advanced_wrappers: 'Wrappers y binarios',
  advanced_paths: 'Rutas del sistema',
  advanced_permissions: 'Permisos de scripts',
  advanced_permissions_desc: 'Corrige los permisos de ejecución de todos los scripts .sh, wrappers node/npm/openclaw y binarios en el sandbox de la app. Necesario si ves errores "Permission denied".',
  advanced_fix_permissions: '🔧 Corregir permisos',
  advanced_fixing_perms: 'Corrigiendo...',
  advanced_perms_fixed: '{n} archivos corregidos',
  advanced_online_install: 'Instalación online',
  advanced_online_install_desc: 'Instala OpenClaw desde internet usando el script oficial. Se ejecuta en el terminal embebido.',
  advanced_run_install: 'Ejecutar en terminal',
  advanced_diagnostics: 'Diagnósticos',
  advanced_source: 'Fuente',
  advanced_installed_at: 'Instalado el',

  // Avanzado - Proot Linux
  advanced_proot_title: 'Linux completo (Proot)',
  advanced_proot_label: 'Ubuntu via Proot',
  advanced_proot_desc: 'Instala un entorno Ubuntu completo usando proot. Más compatible con herramientas Linux nativas y resistente al Phantom Process Killer de Android 12+.',
  advanced_proot_feat_1: 'Ubuntu 22.04 completo (~80 MB)',
  advanced_proot_feat_2: 'apt, bash, python, git incluidos',
  advanced_proot_feat_3: 'Resistente al Phantom Process Killer',
  advanced_proot_feat_4: 'Compatible con más herramientas Linux',
  advanced_proot_warning: 'Requiere ~80 MB de descarga y ~200 MB de espacio en disco.',
  advanced_proot_install_btn: 'Instalar Ubuntu (Proot)',
  advanced_proot_installing: 'Instalando Ubuntu...',
  advanced_proot_success: 'Ubuntu instalado correctamente. Abre el terminal para usarlo.',
}
