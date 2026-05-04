package com.openclaw.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import java.io.File

/**
 * TerminalManager — orchestrates script execution inside an existing terminal
 * session with the correct environment for install.sh to work without lock-file
 * errors.
 *
 * Root cause of the lock-file error:
 *   "E: Could not open lock file /var/lib/dpkg/lock-frontend" happens because:
 *   1. apt/dpkg look for lock files at ABSOLUTE paths hardcoded for Termux:
 *      /data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend
 *   2. When the app runs as com.openclaw.android.debug, the real PREFIX is:
 *      /data/data/com.openclaw.android.debug/files/usr
 *   3. Without the correct env vars (HOME, PREFIX, PATH), bash cannot find
 *      binaries or working directories, and apt fails trying to open lock files
 *      at paths that don't exist or lack permissions.
 *
 * Solution:
 *   This manager writes a sourced env file to TMPDIR and sources it from the
 *   shell session. This is more reliable than injecting raw export lines because
 *   it avoids the race condition where the shell hasn't finished initializing
 *   when the first bytes arrive.
 *
 * Usage:
 *   val manager = TerminalManager(context, filesDir)
 *   manager.runInstallScript(session)
 *   manager.runCommandInSession(session, "pkg update && pkg install git")
 */
class TerminalManager(
    private val context: Context,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "TerminalManager"

        // Time to wait for the shell process to initialize before writing.
        // 1200ms para asegurar que el shell esté completamente listo, especialmente después de instalar bootstrap
        private const val SHELL_INIT_DELAY_MS = 1200L

        // Extra delay between sourcing the env file and running the main command.
        // 500ms para asegurar que las variables de entorno se apliquen correctamente
        private const val ENV_APPLY_DELAY_MS = 500L

        // Name of the env file written to TMPDIR
        private const val ENV_FILE_NAME = "oca-env.sh"
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Builds the environment variable block as a shell-sourceable string.
     *
     * Critical variables:
     * - PREFIX: apt/dpkg derive ALL their paths from this. With the correct
     *   PREFIX, lock files are created at $PREFIX/var/lib/dpkg/lock-frontend
     *   (accessible) instead of /data/data/com.termux/... (inaccessible).
     * - DPKG_ADMINDIR / DPKG_ROOT: explicit overrides so dpkg never falls back
     *   to its compiled-in Termux paths.
     * - APT_CONFIG: points apt to our apt.conf with absolute Dir::State::status,
     *   which prevents the duplicated-path bug.
     */
    fun buildEnvBlock(): String {
        // Usar filesDir directamente para asegurar rutas consistentes (/data/user/0/... o /data/data/...)
        // No mezclar /data/data/ con /data/user/0/ en el mismo bloque.
        val base = filesDir.absolutePath
        val prefix = "$base/usr"
        val home = "$base/home"
        val tmpDir = "$base/tmp"
        val ocaBin = "$home/.openclaw-android/bin"
        val nodeDir = "$home/.openclaw-android/node"
        val glibcLib = "$prefix/glibc/lib"
        val certBundle = "$prefix/etc/tls/cert.pem"

        File(home).mkdirs()
        File(tmpDir).mkdirs()

        return buildString {
            appendLine("# --- OpenClaw Environment Setup ---")
            appendLine("export HOME=\"$home\"")
            appendLine("export PREFIX=\"$prefix\"")
            appendLine("export TMPDIR=\"$tmpDir\"")
            appendLine("export APP_FILES_DIR=\"$base\"")
            appendLine("export APP_PACKAGE=\"${context.packageName}\"")
            appendLine("export PATH=\"$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin\"")
            appendLine("export NPM_CONFIG_PREFIX=\"$prefix\"")
            appendLine("export npm_config_prefix=\"$prefix\"")
            // CRITICAL: Do NOT include glibc/lib in LD_LIBRARY_PATH.
            // This env block is sourced inside Bionic shells (bash/sh from Termux bootstrap).
            // If glibc/lib is in LD_LIBRARY_PATH, Android's Bionic linker finds glibc's
            // libc.so there and fails with:
            //   CANNOT LINK EXECUTABLE "sh": cannot find "libc.so" from verneed[0]
            // The glibc path is added ONLY by the node wrapper when launching node.real.
            appendLine("export LD_LIBRARY_PATH=\"$prefix/lib\"")

            // dpkg/apt explicit overrides — prevent fallback to compiled-in Termux paths
            appendLine("export DPKG_ADMINDIR=\"$prefix/var/lib/dpkg\"")
            appendLine("export DPKG_ROOT=\"$prefix\"")
            appendLine("export APT_CONFIG=\"$prefix/etc/apt/apt.conf\"")
            appendLine("export DEBIAN_FRONTEND=noninteractive")

            // Git configuration
            appendLine("export GIT_EXEC_PATH=\"$prefix/libexec/git-core\"")
            appendLine("export GIT_TEMPLATE_DIR=\"$prefix/share/git-core/templates\"")
            appendLine("export GIT_CONFIG_NOSYSTEM=1")

            // SSL and Network
            appendLine("export SSL_CERT_FILE=\"$certBundle\"")
            appendLine("export CURL_CA_BUNDLE=\"$certBundle\"")
            appendLine("export GIT_SSL_CAINFO=\"$certBundle\"")
            appendLine("export RESOLV_CONF=\"$prefix/etc/resolv.conf\"")

            // Locale and terminal
            appendLine("export LANG=en_US.UTF-8")
            appendLine("export TERM=xterm-256color")

            // Android system
            appendLine("export ANDROID_DATA=/data")
            appendLine("export ANDROID_ROOT=/system")

            // OpenClaw specific
            appendLine("export OA_GLIBC=1")
            appendLine("export CONTAINER=1")
            appendLine("export CLAWDHUB_WORKDIR=\"$home/.openclaw/workspace\"")

            // Termux compatibility (optional but kept for safety)
            appendLine("export TERMUX__PREFIX=\"$prefix\"")
            appendLine("export TERMUX_PREFIX=\"$prefix\"")
            appendLine("export TERMUX__ROOTFS=\"$base\"")

            // Bash startup suppression
            appendLine("export BASH_ENV=/dev/null")
            appendLine("export ENV=/dev/null")

            appendLine("unset LD_PRELOAD")
            appendLine("# --- End Environment Setup ---")
        }
    }

    /**
     * Writes the env block to a file in TMPDIR and returns the path.
     * Sourcing a file is more reliable than injecting raw text because the
     * shell processes it as a single atomic read rather than byte-by-byte input.
     */
    private fun writeEnvFile(): File {
        val tmpDir = filesDir.resolve("tmp").also { it.mkdirs() }
        val envFile = tmpDir.resolve(ENV_FILE_NAME)
        envFile.writeText(buildEnvBlock())
        envFile.setReadable(true)
        return envFile
    }

    /**
     * Runs install.sh inside an existing terminal session.
     *
     * The script is looked up at filesDir/<installSubdir>/install.sh.
     * Before running it, all required env vars are sourced from a temp file
     * so that apt/dpkg find their directories at the correct sandbox paths.
     *
     * @param session       Terminal session to write commands into.
     * @param installSubdir Subdirectory relative to filesDir containing install.sh.
     *                      Defaults to "install" -> filesDir/install/install.sh
     * @param onReady       Optional callback invoked after the command is sent.
     */
    fun runInstallScript(
        session: TerminalSession,
        installSubdir: String = "install",
        onReady: (() -> Unit)? = null,
    ) {
        val installDir = filesDir.resolve(installSubdir)
        val installScript = installDir.resolve("install.sh")

        if (!installScript.exists()) {
            AppLogger.e(TAG, "install.sh not found at: ${installScript.absolutePath}")
            session.write("echo '[ERROR] install.sh not found at: ${installScript.absolutePath}'\n")
            return
        }

        installScript.setExecutable(true)
        AppLogger.i(TAG, "Running install.sh from: ${installScript.absolutePath}")

        handler.postDelayed({
            val envFile = writeEnvFile()
            // Source the env file, then run install.sh in its own directory
            session.write(". \"${envFile.absolutePath}\"\n")

            handler.postDelayed({
                session.write("cd \"${installDir.absolutePath}\" && bash install.sh\n")
                onReady?.invoke()
                AppLogger.i(TAG, "install.sh command sent to terminal session")
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Runs an arbitrary command in the session with the correct environment.
     *
     * @param session    Terminal session.
     * @param command    Command to run (newline appended automatically).
     * @param injectEnv  If true (default), sources the env file before the command.
     */
    fun runCommandInSession(
        session: TerminalSession,
        command: String,
        injectEnv: Boolean = true,
    ) {
        handler.postDelayed({
            if (injectEnv) {
                val envFile = writeEnvFile()
                session.write(". \"${envFile.absolutePath}\"\n")
                handler.postDelayed({
                    session.write("$command\n")
                }, ENV_APPLY_DELAY_MS)
            } else {
                session.write("$command\n")
            }
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Ejecuta la instalación online dentro del terminal embebido.
     *
     * Flujo completo:
     *   1. Inyectar variables de entorno (PREFIX, DEBIAN_FRONTEND, etc.)
     *   2. Ejecutar: curl -sL myopenclawhub.com/install | bash
     *   3. Si falla por dpkg inconsistente → ejecutar dpkg --configure -a
     *      respondiendo "N" automáticamente al prompt de sources.list
     *   4. Reintentar: curl -sL myopenclawhub.com/install | bash
     *   5. Ejecutar source ~/.bashrc para activar el entorno instalado
     *
     * El prompt de dpkg que se maneja automáticamente:
     *   "*** sources.list (Y/I/N/O/D/Z) [default=N] ?"
     *   Respuesta: N (mantener sources.list actual de Termux — correcto)
     *
     * @param session   Sesión de terminal activa donde se ejecutarán los comandos.
     * @param onDone    Callback opcional cuando el flujo completo termina.
     */
    fun runOnlineInstall(
        session: TerminalSession,
        onDone: (() -> Unit)? = null,
    ) {
        AppLogger.i(TAG, "Starting online install flow in terminal session")

        handler.postDelayed({
            // Paso 1: Inyectar entorno
            val envFile = writeEnvFile()
            session.write(". \"${envFile.absolutePath}\"\n")

            handler.postDelayed({
                // Paso 2: Ejecutar el script de instalación online.
                // Usamos un wrapper que:
                //   a) Ejecuta curl | bash
                //   b) Si falla, corre dpkg --configure -a con "N" automático
                //   c) Reintenta curl | bash
                //   d) Hace source ~/.bashrc al final
                val installFlow = buildOnlineInstallScript()
                session.write(installFlow)
                AppLogger.i(TAG, "Online install script sent to terminal")
                onDone?.invoke()
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Construye el script de instalación online completo como string.
     *
     * El script es autocontenido y maneja:
     *   - Respuesta automática "N" al prompt de dpkg sobre sources.list
     *   - Reintentos automáticos si la primera instalación falla
     *   - source ~/.bashrc al finalizar
     *
     * Se escribe como un here-doc para evitar problemas de escape.
     */
    private fun buildOnlineInstallScript(): String {
        val prefix = "${filesDir.absolutePath}/usr"
        val home   = "${filesDir.absolutePath}/home"
        val dpkg   = "$prefix/bin/dpkg"
        val bash   = "$prefix/bin/bash"

        return buildString {
            // ── Función de reparación dpkg ────────────────────────────────────
            // Responde "N" automáticamente al prompt de sources.list
            appendLine("""
                _oca_fix_dpkg() {
                  echo '[install] Reparando dpkg --configure -a (respondiendo N)...'
                  export DEBIAN_FRONTEND=noninteractive
                  export DEBCONF_NONINTERACTIVE_SEEN=true
                  # "yes N" responde N a cualquier prompt interactivo de dpkg
                  # --force-confold: mantener archivos de config existentes sin preguntar
                  yes N | "$dpkg" --configure -a --force-confold 2>&1 || true
                  echo '[install] dpkg --configure -a completado'
                }
            """.trimIndent())

            appendLine()

            // ── Función de instalación online ─────────────────────────────────
            appendLine("""
                _oca_run_install() {
                  echo '[install] Ejecutando: curl -sL myopenclawhub.com/install | bash'
                  curl -sL myopenclawhub.com/install | bash
                  return ${'$'}?
                }
            """.trimIndent())

            appendLine()

            // ── Flujo principal ───────────────────────────────────────────────
            appendLine("""
                echo '[install] === Iniciando instalación online ==='

                # Intento 1
                _oca_run_install
                _INSTALL_EXIT=${'$'}?

                if [ "${'$'}_INSTALL_EXIT" -ne 0 ]; then
                  echo '[install] Primer intento falló (código: '"${'$'}_INSTALL_EXIT"')'
                  echo '[install] Verificando si dpkg necesita reparación...'

                  # Reparar dpkg si existe (bootstrap ya instalado)
                  if [ -x "$dpkg" ]; then
                    _oca_fix_dpkg
                  else
                    echo '[install] dpkg no encontrado, omitiendo reparación'
                  fi

                  echo '[install] Reintentando instalación...'
                  # Intento 2
                  _oca_run_install
                  _INSTALL_EXIT=${'$'}?
                fi

                if [ "${'$'}_INSTALL_EXIT" -eq 0 ]; then
                  echo '[install] === Instalación completada exitosamente ==='
                  # Activar el entorno instalado
                  if [ -f "$home/.bashrc" ]; then
                    echo '[install] Cargando ~/.bashrc...'
                    . "$home/.bashrc" 2>/dev/null || true
                  fi
                  echo '[install] Entorno listo. Puedes usar openclaw ahora.'
                else
                  echo '[install] === ERROR: Instalación falló después de 2 intentos ==='
                  echo '[install] Código de salida: '"${'$'}_INSTALL_EXIT"
                  echo '[install] Sugerencia: ejecuta manualmente: dpkg --configure -a'
                fi
            """.trimIndent())

            appendLine() // newline final para que bash ejecute el último comando
        }
    }

    /**
     * Writes diagnostic information to the terminal session.
     * Useful for debugging environment issues.
     */
    fun runDiagnostics(session: TerminalSession) {
        handler.postDelayed({
            val envFile = writeEnvFile()
            session.write(". \"${envFile.absolutePath}\"\n")
            handler.postDelayed({
                val diagScript = buildString {
                    appendLine("echo '=== OpenClaw Environment Diagnostics ==='")
                    appendLine("echo \"HOME: \$HOME\"")
                    appendLine("echo \"PREFIX: \$PREFIX\"")
                    appendLine("echo \"PATH: \$PATH\"")
                    appendLine("echo \"TMPDIR: \$TMPDIR\"")
                    appendLine("echo \"DPKG_ADMINDIR: \$DPKG_ADMINDIR\"")
                    appendLine("echo \"APT_CONFIG: \$APT_CONFIG\"")
                    appendLine("echo ''")
                    appendLine("echo '=== Directory Check ==='")
                    appendLine("ls \"\$PREFIX/bin/bash\" 2>/dev/null && echo 'bash: OK' || echo 'bash: NOT FOUND'")
                    appendLine("ls \"\$PREFIX/bin/apt\" 2>/dev/null && echo 'apt: OK' || echo 'apt: NOT FOUND'")
                    appendLine("ls \"\$PREFIX/var/lib/dpkg\" 2>/dev/null && echo 'dpkg dir: OK' || echo 'dpkg dir: NOT FOUND'")
                    appendLine("echo ''")
                    appendLine("echo '=== Lock Files ==='")
                    appendLine("ls -la \"\$PREFIX/var/lib/dpkg/lock-frontend\" 2>/dev/null || echo 'lock-frontend: not created yet (OK)'")
                    appendLine("echo '=== End Diagnostics ==='")
                }
                session.write(diagScript)
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }


}
