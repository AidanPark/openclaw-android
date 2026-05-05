package com.openclaw.android.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openclaw.android.*
import com.openclaw.android.bridge.JsBridgeFacade
import com.openclaw.android.databinding.ActivityMainBinding
import com.openclaw.android.ui.install.InstallOverlayController
import com.openclaw.android.ui.permissions.ModernPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.Manifest

/**
 * Inicializa todos los managers y controllers de la actividad.
 *
 * Responsabilidad única: crear y configurar los componentes principales
 * en onCreate() y limpiarlos en onDestroy().
 */
internal class ActivityInitializer(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val terminalSessionClient: TerminalSessionClient,
    private val terminalViewClient: TerminalViewClient,
) {

    private val TAG = "ActivityInitializer"

    // Managers públicos (accesibles desde MainActivity)
    lateinit var sessionManager: TerminalSessionManager
    lateinit var installerManager: InstallerManager
    lateinit var eventBridge: EventBridge
    lateinit var jsBridge: JsBridgeFacade

    // Controllers
    lateinit var installOverlay: InstallOverlayController
    lateinit var permissionManager: ModernPermissionManager

    /**
     * Configura todos los componentes en onCreate().
     */
    fun onCreate(savedInstanceState: Bundle?) {
        setupBackPressedHandler()
        initializeManagers()
        initializeControllers()
        startOpenClawService()
    }

    /**
     * Limpia recursos en onDestroy().
     */
    fun onDestroy() {
        jsBridge.cancel()
    }

    private fun setupBackPressedHandler() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.terminalContainer.isVisible -> {
                        // Delegar a ActivityViewSwitcher
                        (activity as? MainActivity)?.showWebView()
                    }
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun initializeManagers() {
        installerManager = InstallerManager(activity)
        eventBridge = EventBridge(binding.webView)
        sessionManager = TerminalSessionManager(activity, terminalSessionClient, eventBridge)
        // Usar JsBridgeFacade directamente — JsBridge.kt era un shim puro
        jsBridge = JsBridgeFacade(activity as MainActivity, sessionManager, installerManager, eventBridge)
    }

    private fun initializeControllers() {
        installOverlay = InstallOverlayController(
            binding, activity, terminalSessionClient, terminalViewClient,
        ) {
            // Callback de recuperación de error
            (activity as? MainActivity)?.showTerminal()
            val session = sessionManager.createSession()
            session.write("echo '=== Terminal de recuperacion ==='\n")
            session.write("echo 'Ejecuta comandos para corregir el error.'\n")
        }

        // Inicializar ModernPermissionManager
        permissionManager = ModernPermissionManager(activity)
        permissionManager.initialize()
        
        // Configurar callbacks de rationale
        permissionManager.onStorageRationale = {
            activity.runOnUiThread {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Permiso Necesario")
                    .setMessage("Se requiere acceso al almacenamiento para instalar el entorno de OpenClaw.")
                    .setPositiveButton("Entendido") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }

        installOverlay.setupErrorButton()
        sessionManager.onSessionsChanged = {
            (activity as? MainActivity)?.updateSessionTabs()
        }
    }

    private fun startOpenClawService() {
        activity.startService(Intent(activity, OpenClawService::class.java))
    }

    /**
     * Solicita permisos iniciales de forma segura.
     * Usa ModernPermissionManager con corutinas.
     */
    fun requestInitialPermissions() {
        // Solicitar permisos de almacenamiento - ejecutar en contexto de corutina
        CoroutineScope(Dispatchers.Main).launch {
            permissionManager.requestStorage()
            permissionManager.requestNotifications()
            
            // Notificar que los permisos están listos
            (activity as? MainActivity)?.onStoragePermissionsGranted()
        }
    }
}
