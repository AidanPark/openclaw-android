export const en = {
  // App tab bar
  tab_terminal: '🖥 Terminal',
  tab_dashboard: '📊 Dashboard',
  tab_settings: '⚙ Settings',

  // Setup - steps
  step_platform: 'Platform',
  step_path: 'Path',
  step_tools: 'Tools',
  step_setup: 'Setup',

  // Setup - platform select
  setup_choose_platform: 'Choose your platform',
  setup_more_platforms: 'More platforms available in Settings.',
  setup_mode_title: 'Installation Mode',
  setup_mode_online_label: 'Online Installation',
  setup_mode_online_desc: 'Download components from the internet (~200MB).',
  setup_mode_offline_label: 'Offline Installation',
  setup_mode_offline_desc: 'Install from a local file. Ideal without internet.',
  setup_offline_not_found: 'Installation file not found in the app.',
  setup_offline_select: 'Select .tar.gz file',
  setup_offline_selected: 'Selected file: {name}',

  // Setup - path select
  setup_path_title: 'Install Location',
  setup_path_desc: 'Choose where to install the runtime. Use the app-local path unless you have Termux installed.',
  setup_path_local_label: 'App-local (this app)',
  setup_path_local_default: 'App internal storage',
  setup_path_termux_label: 'Use Termux',
  setup_path_recommended: 'Recommended',
  setup_next: 'Next',

  // Setup - tool select
  setup_optional_tools: 'Optional Tools',
  setup_tools_desc: 'Select tools to install alongside {platform}. You can always add more later in Settings.',
  setup_start: 'Start Setup',

  // Setup - installing
  setup_setting_up: 'Setting up...',
  setup_preparing: 'Preparing setup...',
  setup_retry: 'Retry installation',
  setup_check_connection: 'Check connection',
  setup_checking_connection: 'Checking...',
  setup_connection_ok: 'Connection OK — try retrying',
  setup_connection_failed: 'No internet connection',
  setup_install_failed: 'Installation failed',
  setup_failed_hint: 'Check your connection and try again. You can also open the terminal to see the full error log.',
  setup_open_log: 'View terminal log',
  setup_back_to_tools: 'Back to tools',

  // Setup - done
  setup_done_title: "You're all set!",
  setup_done_desc: 'The terminal will now install runtime components and your selected tools. This takes 3–10 minutes.',
  setup_open_terminal: 'Open Terminal',

  // Setup - tips
  tip_1: 'You can install multiple AI platforms and switch between them anytime.',
  tip_2: 'Setup is a one-time process. Future launches are instant.',
  tip_3: 'Once setup is complete, your AI assistant runs at full speed — just like on a computer.',
  tip_4: 'All processing happens locally on your device. Your data never leaves your phone.',

  // Setup - tool descriptions
  tool_tmux: 'Terminal multiplexer for background sessions',
  tool_ttyd: 'Web terminal — access from a browser',
  tool_ssh_server: 'OpenSSH server — remote access via SSH',
  tool_dufs: 'File server (WebDAV)',
  tool_code_server: 'VS Code in browser',
  tool_claude_code: 'Anthropic AI CLI',
  tool_gemini_cli: 'Google AI CLI',
  tool_codex_cli: 'OpenAI AI CLI',

  // Runtime environment detection
  env_not_detected: 'not found',
  env_detected: 'detected',

  // Dashboard
  dash_setup_required: 'Setup Required',
  dash_setup_desc: "The runtime environment hasn't been set up yet.",
  dash_commands: 'Commands',
  dash_runtime: 'Runtime',
  dash_management: 'Management',

  // Dashboard - commands
  cmd_gateway: 'Start the gateway',
  cmd_status: 'Show gateway status',
  cmd_onboard: 'Initial setup wizard',
  cmd_logs: 'Follow live logs',
  cmd_update: 'Update OpenClaw and all components',
  cmd_install_tools: 'Add or remove optional tools',

  // Dashboard - quick actions
  dash_quick_actions: 'Quick Actions',
  dash_sessions: 'Sessions',
  dash_new_session: 'New Session',
  dash_reload_ui: 'Reload UI',

  // Settings
  settings_title: 'Settings',
  settings_language: 'Language',
  settings_platforms: 'Platforms',
  settings_platforms_desc: 'Manage installed platforms',
  settings_tools: 'Additional Tools',
  settings_tools_desc: 'Terminal tools',
  settings_updates: 'Updates',
  settings_updates_desc: 'Check for updates',
  settings_updates_badge: 'Updates available',
  settings_keep_alive: 'Keep Alive',
  settings_keep_alive_desc: 'Prevent background killing',
  settings_storage: 'Storage',
  settings_storage_desc: 'Manage disk usage',
  settings_about: 'About',
  settings_about_desc: 'App info & licenses',

  // Settings - Keep Alive
  ka_title: 'Keep Alive',
  ka_desc: 'Android may kill background processes after a while. Follow these steps to prevent it.',
  ka_battery: '1. Battery Optimization',
  ka_status: 'Status',
  ka_excluded: '✓ Excluded',
  ka_request: 'Request Exclusion',
  ka_developer: '2. Developer Options',
  ka_developer_desc: '• Enable Developer Options\n• Enable "Stay Awake"',
  ka_open_dev: 'Open Developer Options',
  ka_phantom: '3. Phantom Process Killer (Android 12+)',
  ka_phantom_desc: 'Connect USB and enable ADB debugging, then run this command on your PC:',
  ka_copy: 'Copy',
  ka_copied: 'Copied!',
  ka_charge: '4. Charge Limit (Optional)',
  ka_charge_desc: 'Set battery charge limit to 80% for always-on use. This can be configured in your phone\'s battery settings.',

  // Settings - Storage
  storage_title: 'Storage',
  storage_total: 'Total used: ',
  storage_bootstrap: 'Bootstrap (usr/)',
  storage_www: 'Web UI (www/)',
  storage_free: 'Free Space',
  storage_clear: 'Clear Cache',
  storage_clearing: 'Clearing...',
  storage_loading: 'Loading storage info...',

  // Settings - About
  about_title: 'About',
  about_version: 'Version',
  about_apk: 'APK',
  about_update_available: 'Update available',
  about_package: 'Package',
  about_script: 'Script',
  about_runtime: 'Runtime',
  about_license: 'License',
  about_app_info: 'App Info',
  about_made_for: 'Made for Android',
  about_checking_apk: 'Checking...',
  about_check_apk: '↑ Check for APK update',
  about_installation: 'Installation',
  about_bootstrap_installed: 'Bootstrap installed',
  about_openclaw_installed: 'OpenClaw installed',
  about_yes: '✓ Yes',
  about_no: '✗ No',
  about_github: 'GitHub ↗',
  about_bridge_unavailable: 'Bridge not available',
  about_running_outside: 'Running outside Android WebView',

  // Settings - Updates
  updates_title: 'Updates',
  updates_checking: 'Checking for updates...',
  updates_up_to_date: 'Everything is up to date.',
  updates_updating: 'Updating {name}...',
  updates_update: 'Update',

  // Settings - Platforms
  platforms_title: 'Platforms',
  platforms_installing: 'Installing {name}...',
  platforms_active: 'Active',
  platforms_install: 'Install & Switch',

  // Dashboard - git install hint
  git_not_available: 'git not available',
  git_install_hint: 'Required for cloning repositories',
  git_install_btn: 'Install git',

  // Storage additional
  storage_payload: 'Extracted payload',
  storage_node: 'Node.js',
  storage_openclaw: 'OpenClaw',
  storage_cache: 'Cache',
  storage_total_disk: 'Total storage',
  storage_available: 'Available on device',
  storage_retry: 'Retry',

  tools_title: 'Additional Tools',
  tools_installing: 'Installing {name}...',
  tools_installed: 'Installed ✓',
  tools_install: 'Install',
  tools_uninstall: 'Uninstall',
  tools_confirm_uninstall: 'Uninstall {name}?',
  tools_cat_terminal: 'Terminal Tools',
  tools_cat_ai: 'AI Tools',
  tools_cat_network: 'Network & Access',
  tools_cat_system: 'System',

  // Settings - Advanced
  settings_advanced: 'Advanced',
  settings_advanced_desc: 'Versions, permissions, diagnostics',

  // Setup - online mode hint
  setup_mode_online_hint: 'Requires internet. Installs Node.js + OpenClaw via the official script.',

  // Setup - environment selector
  setup_choose_env: 'Choose your environment',
  setup_choose_env_desc: 'Select how you want to run OpenClaw on your device.',
  setup_env_termux_label: 'Termux Bootstrap',
  setup_env_termux_desc: 'Native Termux environment. Lighter and faster to install.',
  setup_env_proot_label: 'Proot + Ubuntu Linux',
  setup_env_proot_desc: 'Full Ubuntu via proot. More compatible and resistant to the Phantom Process Killer.',
  setup_env_recommended: 'Recommended',
  setup_back: 'Back',

  // Setup - proot step
  setup_proot_title: 'Install Proot + Ubuntu',
  setup_proot_desc: 'Downloads and installs a full Ubuntu environment using proot. Only requires internet.',
  setup_proot_feat_1: 'Full Ubuntu 22.04 with apt, bash, python, git',
  setup_proot_feat_2: 'Resistant to Android 12+ Phantom Process Killer',
  setup_proot_feat_3: 'Online install via curl | bash',
  setup_proot_size: 'Requires ~80 MB download and ~200 MB disk space.',
  setup_proot_btn: 'Install Ubuntu (Proot)',
  setup_proot_installing: 'Installing Ubuntu...',
  setup_proot_done: '✓ Ubuntu + Proot installed',
  setup_proot_success_title: 'Ubuntu installed!',
  setup_proot_success_desc: 'The Ubuntu environment is ready. Open the terminal to get started.',

  // Setup - bootstrap step
  setup_bootstrap_title: 'Step 1: Base Environment',
  setup_bootstrap_desc: 'Installs the Termux environment (bash, apt, tools). Required before installing OpenClaw.',
  setup_bootstrap_installing: 'Installing Termux Bootstrap...',
  setup_bootstrap_done: '✓ Base environment installed',
  setup_bootstrap_btn: 'Install base environment',
  setup_bootstrap_size: '~30 MB · Requires internet',
  setup_bootstrap_optional: 'Optional for offline mode',
  setup_bootstrap_skip: 'Skip and continue',
  setup_bootstrap_skipped: 'Step 1 skipped - offline mode active',

  // Setup - openclaw step
  setup_openclaw_title: 'Step 2: Install OpenClaw',
  setup_openclaw_desc: 'Choose how to install OpenClaw (Node.js + runtime).',
  setup_openclaw_locked: 'Complete Step 1 first.',
  setup_openclaw_locked_or_skip: 'Complete Step 1 or skip it to unlock OpenClaw installation.',
  setup_online_needs_bootstrap: 'Online installation requires the Termux environment (Step 1) to be installed.',
  setup_go_install_bootstrap: 'Go back to install base environment',

  // Setup - success
  setup_success_title: 'Installation complete!',
  setup_success_desc: 'OpenClaw is ready. Open the terminal to get started.',
  setup_redirecting: 'Redirecting to dashboard in {seconds}s...',
  setup_go_dashboard: 'Go to Dashboard',

  // Advanced screen
  advanced_title: 'Advanced',
  advanced_versions: 'Installed versions',
  advanced_wrappers: 'Wrappers & binaries',
  advanced_paths: 'System paths',
  advanced_permissions: 'Script permissions',
  advanced_permissions_desc: 'Fix executable permissions on all .sh scripts, node/npm/openclaw wrappers and binaries in the app sandbox. Use this if you see "Permission denied" errors.',
  advanced_fix_permissions: '🔧 Fix permissions',
  advanced_fixing_perms: 'Fixing...',
  advanced_perms_fixed: '{n} files fixed',
  advanced_online_install: 'Online installation',
  advanced_online_install_desc: 'Install OpenClaw from the internet using the official script. Runs in the embedded terminal.',
  advanced_run_install: 'Run in terminal',
  advanced_diagnostics: 'Diagnostics',
  advanced_source: 'Source',
  advanced_installed_at: 'Installed at',

  // Advanced - Proot Linux
  advanced_proot_title: 'Full Linux (Proot)',
  advanced_proot_label: 'Ubuntu via Proot',
  advanced_proot_desc: 'Install a full Ubuntu environment using proot. More compatible with native Linux tools and resistant to the Android 12+ Phantom Process Killer.',
  advanced_proot_feat_1: 'Full Ubuntu 22.04 (~80 MB)',
  advanced_proot_feat_2: 'apt, bash, python, git included',
  advanced_proot_feat_3: 'Resistant to Phantom Process Killer',
  advanced_proot_feat_4: 'Compatible with more Linux tools',
  advanced_proot_warning: 'Requires ~80 MB download and ~200 MB disk space.',
  advanced_proot_install_btn: 'Install Ubuntu (Proot)',
  advanced_proot_installing: 'Installing Ubuntu...',
  advanced_proot_success: 'Ubuntu installed successfully. Open the terminal to use it.',
}
