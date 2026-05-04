package com.openclaw.android.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.openclaw.android.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

/**
 * ModernPermissionManager — manejo de permisos con corutinas y CompletableDeferred.
 * 
 * Mejoras sobre PermissionsController:
 * - API suspend para requestStorage(), requestNotifications(), requestManageExternalStorage()
 * - Manejo automático de shouldShowRequestPermissionRationale
 * - Diálogos de justificación integrados
 * - Verificación de permisos antes de cada operación
 */
class ModernPermissionManager(
    private val activity: AppCompatActivity,
) {
    companion object {
        private const val TAG = "ModernPermissionManager"
        const val REQUEST_STORAGE_LEGACY = 100
        const val REQUEST_NOTIFICATIONS = 102
    }

    // Launchers registrados
    private lateinit var storageTreeLauncher: ActivityResultLauncher<Intent>
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>
    private lateinit var legacyStorageLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>

    // Deferred para resultados asíncronos
    private val storageDeferred = CompletableDeferred<Boolean>()
    private val notificationDeferred = CompletableDeferred<Boolean>()
    private val manageStorageDeferred = CompletableDeferred<Boolean>()

    // Callbacks de rationale
    var onStorageRationale: (() -> Unit)? = null
    var onNotificationRationale: (() -> Unit)? = null

    /**
     * Inicializa los ActivityResultLaunchers.
     * Debe llamarse desde onCreate() de la Activity.
     */
    fun initialize() {
        // Launcher para ACTION_OPEN_DOCUMENT_TREE (Android 5+)
        storageTreeLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
            storageDeferred.complete(granted)
        }

        // Launcher para MANAGE_EXTERNAL_STORAGE (Android 11+)
        manageStorageLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
            manageStorageDeferred.complete(granted)
        }

        // Launcher para permisos legacy (Android < 11)
        legacyStorageLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            storageDeferred.complete(allGranted)
        }

        // Launcher para notificaciones (Android 13+)
        notificationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            notificationDeferred.complete(granted)
        }
    }

    /**
     * Solicita permiso de almacenamiento.
     * @return true si el permiso fue concedido, false en caso contrario.
     */
    suspend fun requestStorage(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    return true
                }

                // Mostrar rationale si el usuario rechazó anteriormente
                if (activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    onStorageRationale?.invoke()
                }

                val shouldRequest = showStorageExplanationAndWait()
                if (shouldRequest) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = "package:${activity.packageName}".toUri()
                        storageTreeLauncher.launch(intent)
                    } catch (_: Exception) {
                        storageTreeLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                    storageDeferred.await()
                } else {
                    false
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ no necesita permisos de almacenamiento para acceder a archivos del app
                true
            }

            else -> {
                val permissions = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    true
                } else {
                    if (activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        onStorageRationale?.invoke()
                    }
                    legacyStorageLauncher.launch(missing.toTypedArray())
                    storageDeferred.await()
                }
            }
        }
    }

    /**
     * Solicita permiso de notificaciones (Android 13+).
     * @return true si el permiso fue concedido, false en caso contrario.
     */
    suspend fun requestNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            onNotificationRationale?.invoke()
        }

        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return notificationDeferred.await()
    }

    /**
     * Solicita permiso MANAGE_EXTERNAL_STORAGE (Android 11+).
     * @return true si el permiso fue concedido, false en caso contrario.
     */
    suspend fun requestManageExternalStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }

        if (Environment.isExternalStorageManager()) {
            return true
        }

        manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        return manageStorageDeferred.await()
    }

    /**
     * Verifica si tenemos permiso de almacenamiento.
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                activity, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Verifica si tenemos permiso de notificaciones.
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Obtiene el estado de todos los permisos relevantes.
     */
    fun getPermissionsStatus(): Map<String, Boolean> = mapOf(
        "storage" to hasStoragePermission(),
        "notifications" to hasNotificationPermission(),
    )

    // ── Private helpers ───────────────────────────────────────────────────

    private suspend fun showStorageExplanationAndWait(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        
        activity.runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Permiso de Almacenamiento")
                .setMessage(
                    "OpenClaw requiere el permiso de 'Acceso a todos los archivos' para descargar " +
                    "dependencias, gestionar el entorno de la terminal y almacenar tus datos de forma local.\n\n" +
                    "Por favor, permite este acceso en la siguiente pantalla."
                )
                .setPositiveButton("Permitir") { _, _ -> deferred.complete(true) }
                .setNegativeButton("Más tarde") { _, _ -> deferred.complete(false) }
                .setCancelable(false)
                .show()
        }
        
        return deferred.await()
    }
}