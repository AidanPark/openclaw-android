package com.openclaw.android.ui.activity

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Maneja permisos y selección de archivos (payload, glibc).
 *
 * Responsabilidad única: registrar y responder a los ActivityResultLaunchers
 * para permisos de almacenamiento y selección de archivos.
 */
internal class ActivityPermissionHandler(
    private val activity: AppCompatActivity,
    private val onStoragePermissionsGranted: () -> Unit,
) {

    private val TAG = "ActivityPermissionHandler"

    // File pickers
    var selectedPayloadUri: Uri? = null

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        onStoragePermissionsGranted()
    }

    private val payloadFilePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            activity.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedPayloadUri = it
            // Sincronizar con MainActivity para que SetupBridge y otros lo vean
            (activity as? MainActivity)?.selectedPayloadUri = it

            AppLogger.i(TAG, "Payload file selected: $it")
            // Emitir evento al bridge (si está disponible)
            (activity as? MainActivity)?.eventBridge?.emit(
                "payload_file_selected",
                mapOf(
                    "uri" to it.toString(),
                    "name" to (it.path?.split("/")?.last() ?: "payload.tar.gz"),
                )
            )
        }
    }

    private val glibcFilePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri -> onGlibcFileSelected(selectedUri) }
    }

    /**
     * Solicita permisos de almacenamiento.
     * @deprecated Usar ModernPermissionManager.requestStorage() en su lugar.
     * Este método se mantiene solo para compatibilidad con ActivityInstallFlow.
     */
    fun requestStorage() {
        storagePermissionLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    }

    /**
     * Solicita permisos de notificaciones.
     * @deprecated Usar ModernPermissionManager.requestNotifications() en su lugar.
     */
    fun requestNotifications() {
        // No-op: delegado a ModernPermissionManager en ActivityInitializer
    }

    /**
     * Maneja el resultado de onRequestPermissionsResult.
     * @deprecated ModernPermissionManager usa ActivityResultLaunchers internamente.
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        // No-op: ModernPermissionManager maneja esto via ActivityResultLaunchers
    }

    /**
     * Abre el selector de archivos para payload.
     */
    fun pickPayloadFile() {
        payloadFilePickerLauncher.launch(arrayOf(
            "application/gzip", "application/x-gzip", "application/x-tgz",
        ))
    }

    /**
     * Abre el selector de archivos para glibc.
     */
    fun pickGlibcFile() {
        glibcFilePickerLauncher.launch(arrayOf(
            "application/x-xz", "application/x-tar", "application/octet-stream",
        ))
    }

    /**
     * Maneja la selección de un archivo glibc.
     */
    private fun onGlibcFileSelected(uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        AppLogger.i(TAG, "glibc file selected: $uri")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val homeDir = activity.filesDir.resolve("home").also { it.mkdirs() }
                val dest = homeDir.resolve("glibc-aarch64.tar.xz")
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val ok = InstallerManager(activity).installGlibcFromFile(dest)
                (activity as? MainActivity)?.eventBridge?.emit(
                    "glibc_install",
                    mapOf(
                        "success" to ok,
                        "message" to if (ok) "glibc instalado desde archivo" else "Error instalando glibc",
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "glibc file install failed: ${e.message}", e)
                (activity as? MainActivity)?.eventBridge?.emit(
                    "glibc_install",
                    mapOf(
                        "success" to false,
                        "error" to (e.message ?: "Error desconocido"),
                    )
                )
            }
        }
    }
}
