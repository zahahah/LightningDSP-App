package lightning.dsp.interop.appmixer

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Resource/preference access belongs to the verified package. External directory
 * lookup must not make StorageManager calls attributing UID 0 to that app package. */
internal class AppMixerRelayFileContext(owner: Context, private val externalFiles: File?) : ContextWrapper(owner) {
    override fun getExternalFilesDir(type: String?): File? = if (type == null) externalFiles else null
}
