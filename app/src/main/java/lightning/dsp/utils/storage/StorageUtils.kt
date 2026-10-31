package lightning.dsp.utils.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.InputStream


object StorageUtils {

    fun importFile(context: Context, targetDir: String, uri: Uri): File? {
        return try {
            val name = queryName(context, uri) ?: return null
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { LibraryStorage.importStream(File(targetDir), name, it) }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to import library file")
            null
        }
    }

    fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            null
        }
    }

    fun queryName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || !cursor.moveToFirst()) null else cursor.getString(index)
        }
    }
}
