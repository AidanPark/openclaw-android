package com.openclaw.android.ui.install

import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.TerminalManager
import com.openclaw.android.ui.install.InstallOverlayController

/**
 * Maneja el flujo de instalación desde la UI de la actividad.
 *
 * Responsabilidades:
 *   - Verificar si ya está instalado
 *   - Manejar actualizaciones de APK
 *   - Iniciar instalación en modo "auto", "online", "offline", etc.
 *   - Ejecutar instalación online en el terminal
 */
internal class ActivityInstallFlow(
    private val activity: AppCompatActivity,
    private val installerManager: InstallerManager,
    private val installOverlay: InstallOverlayController,
) {

    private val TAG = "ActivityInstallFlow"

    /**
     * Verifica el estado de instalación y decide qué hacer al iniciar la app.
     * Se llama desde onStoragePermissionsGranted().
     */
    fun checkAndStartInstallation(intent: Intent?) {
        val isInstalled = installerManager.isInstalled()
        AppLogger.i(TAG, "checkAndStartInstallation: installed=$isInstalled")

        if (isInstalled) {
            checkForApkUpgrade()
        }

        when {
            !isInstalled -> {
                AppLogger.i(TAG, "Not installed - showing setup wizard")
                (activity as? MainActivity)?.showWebView()
            }
            isBootIntent(intent) -> {
                val startScript = installerManager.getRunScriptPath()
                AppLogger.i(TAG, "Boot launch - auto-starting gateway")
                (activity as? MainActivity)?.showTerminal()
                val session = (activity as? MainActivity)?.sessionManager?.createSession()
                session?.let {
                    activity.findViewById<com.termux.view.TerminalView>(com.openclaw.android.R.id.terminalView)
                        .post {
                            it.write("\"${startScript.absolutePath}\"\n")
                        }
                }
            }
            else -> {
                AppLogger.i(TAG, "Already installed - showing dashboard")
                (activity as? MainActivity)?.showWebView()
            }
        }
    }

    /**
     * Inicia la instalación desde la UI (llamado por JsBridge o botones).
     *
     * @param mode "auto", "online", "offline", "termux-bootstrap", "proot"
     * @param onComplete Callback opcional con éxito/fracaso
     */
    fun startInstallFromUi(
        mode: String = "auto",
        onComplete: ((success: Boolean) -> Unit)? = null,
    ) {
        // Modo "online": instalar bootstrap primero, luego curl | bash en terminal
        if (mode == "online") {
            // Mostrar overlay
            installOverlay.show()
            
            // Instalar bootstrap y esperar
            installOverlay.runInstall(activity, installerManager, "online", null) { bootstrapOk ->
                // Este callback se llama cuando el bootstrap TERMINA
                activity.runOnUiThread {
                    installOverlay.hide()
                    if (bootstrapOk) {
                        // AHORA sí, abrir terminal para curl | bash
                        runOnlineInstallInTerminal()
                    } else {
                        // Mostrar error
                        AppLogger.e(TAG, "Bootstrap installation failed")
                        (activity as? MainActivity)?.showToast("Falló la instalación del Bootstrap")
                    }
                }
                onComplete?.invoke(bootstrapOk)
            }
            return
        }
        installOverlay.show()
        installOverlay.runInstall(
            activity,
            installerManager,
            mode,
            (activity as? MainActivity)?.selectedPayloadUri
        ) { success ->
            if (success) (activity as? MainActivity)?.reloadWebView()
            onComplete?.invoke(success)
        }
    }

    /**
     * Ejecuta la instalación online dentro del terminal embebido.
     *
     * Flujo:
     *   1. Mostrar el terminal
     *   2. Crear sesión si no hay ninguna activa
     *   3. Inyectar entorno + ejecutar: curl -sL myopenclawhub.com/install | bash
     *   4. Si dpkg falla → ejecutar dpkg --configure -a (responde N automáticamente)
     *   5. Reintentar curl | bash
     *   6. source ~/.bashrc al finalizar
     *
     * Nota: el Termux Bootstrap debe estar instalado antes de llamar esto.
     */
    fun runOnlineInstallInTerminal() {
        AppLogger.i(TAG, "Starting online install in terminal")
        (activity as? MainActivity)?.showTerminal()

        // Asegurar que hay una sesión activa
        val session = (activity as? MainActivity)?.sessionManager?.activeSession
            ?: (activity as? MainActivity)?.sessionManager?.createSession()

        session?.let {
            // Usar TerminalManager para el flujo completo con recuperación de dpkg
            val terminalManager = TerminalManager(activity, activity.filesDir)
            terminalManager.runOnlineInstall(it) {
                AppLogger.i(TAG, "Online install commands sent to terminal session")
            }
        }
    }

    // ── Helpers privados ───────────────────────────────────────────────────

    private fun checkForApkUpgrade() {
        val prefs = activity.getSharedPreferences("openclaw", 0)
        val savedVersionCode = prefs.getInt("versionCode", 0)
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val currentVersionCode = PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        if (currentVersionCode > savedVersionCode) {
            AppLogger.i(TAG, "APK upgrade: $savedVersionCode to $currentVersionCode")
            prefs.edit { putInt("versionCode", currentVersionCode) }
        }
    }

    private fun isBootIntent(intent: Intent?): Boolean =
        intent?.getBooleanExtra("boot", false) == true ||
            intent?.action == "com.openclaw.android.BOOT"
}
