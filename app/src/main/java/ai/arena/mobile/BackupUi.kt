package ai.arena.mobile

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Экспорт и импорт списка профилей через системный выбор файла.
 * Лаунчеры регистрируются в onCreate — создавайте помощник сразу после super.onCreate().
 */
class BackupUi(private val activity: AppCompatActivity) {

    private val exportLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
            if (uri == null) return@registerForActivityResult
            writeTo(uri)
        }

    private val importLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            readFrom(uri)
        }

    fun export() {
        try {
            exportLauncher.launch(BackupManager.DEFAULT_FILE_NAME)
        } catch (t: Throwable) {
            Toast.makeText(activity, R.string.backup_error_io, Toast.LENGTH_SHORT).show()
        }
    }

    fun import() {
        try {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        } catch (t: Throwable) {
            Toast.makeText(activity, R.string.backup_error_io, Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeTo(uri: Uri) {
        try {
            val text = BackupManager.exportText(activity)
            activity.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(activity, R.string.backup_exported, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(activity, R.string.backup_error_io, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readFrom(uri: Uri) {
        val json = try {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
        } catch (t: Throwable) {
            ""
        }

        if (json.isBlank()) {
            Toast.makeText(activity, R.string.backup_error_io, Toast.LENGTH_SHORT).show()
            return
        }

        val result = BackupManager.importText(activity, json)
        val message = if (result.isSuccess) {
            activity.getString(R.string.backup_imported, result.profilesRestored)
        } else {
            result.error ?: activity.getString(R.string.backup_error_format)
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

        if (result.isSuccess) {
            (activity as? MainActivity)?.refreshAfterImport()
            AppShortcuts.syncIfChanged(activity)
        }
    }
}
