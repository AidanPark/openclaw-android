package com.openclaw.android.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.openclaw.android.AppLogger

/**
 * PermissionsController — handles all Android permission requests.
 *
 * Extracted from MainActivity to keep permission logic isolated and testable.
 * Calls back to [onGranted] once permissions are resolved (granted or denied).
 */
class PermissionsController(
    private val activity: AppCompatActivity,
    private val storagePermissionLauncher: ActivityResultLauncher<Intent>,
    private val onGranted: () -> Unit,
) {
    companion object {
        private const val TAG = "PermissionsController"
        const val REQUEST_STORAGE = 100
        const val REQUEST_NOTIFICATIONS = 102
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AppLogger.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE — showing explanation")
                showStorageExplanation()
            } else {
                onGranted()
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQUEST_STORAGE)
            } else {
                onGranted()
            }
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AppLogger.i(TAG, "Requesting POST_NOTIFICATIONS (Android 13+)")
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS,
                )
            }
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_STORAGE -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (!allGranted) {
                    AppLogger.w(TAG, "Storage permissions denied")
                    showPermissionDeniedNotification("Se requieren permisos de almacenamiento para instalar")
                }
                onGranted() // Continue regardless — show warning but don't block
            }
            REQUEST_NOTIFICATIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AppLogger.i(TAG, "POST_NOTIFICATIONS granted")
                } else {
                    AppLogger.w(TAG, "POST_NOTIFICATIONS denied — service runs but notification hidden")
                }
            }
        }
    }

    fun onStoragePermissionActivityResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AppLogger.w(TAG, "MANAGE_EXTERNAL_STORAGE denied")
            showPermissionDeniedNotification("Se requiere acceso al almacenamiento para la instalación")
        }
        onGranted()
    }

    // ── Private ────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showStorageExplanation() {
        activity.runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Permiso de Almacenamiento")
                .setMessage(
                    "OpenClaw requiere el permiso de 'Acceso a todos los archivos' para descargar " +
                    "dependencias, gestionar el entorno de la terminal y almacenar tus datos de forma local.\n\n" +
                    "Por favor, permite este acceso en la siguiente pantalla."
                )
                .setPositiveButton("Permitir") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = "package:${activity.packageName}".toUri()
                        storagePermissionLauncher.launch(intent)
                    } catch (_: Exception) {
                        storagePermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Más tarde") { _, _ ->
                    AppLogger.w(TAG, "Storage permission deferred by user")
                    onGranted()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showPermissionDeniedNotification(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, "$message\nToca para reintentar", Toast.LENGTH_LONG).apply {
                setGravity(Gravity.TOP, 0, 100)
                show()
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Permiso Requerido")
                .setMessage("$message\n\nEl entorno de la terminal y otras funcionalidades importantes podrían fallar.")
                .setPositiveButton("Reintentar") { _, _ -> requestStorage() }
                .setNegativeButton("Continuar") { _, _ -> }
                .setCancelable(false)
                .show()
        }
    }
}
