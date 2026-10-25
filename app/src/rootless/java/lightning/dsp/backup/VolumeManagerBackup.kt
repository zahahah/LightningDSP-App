package lightning.dsp.backup

import android.content.Context
import lightning.dsp.utils.storage.Tar
import java.io.File

/** The rootless flavor does not own a root Volume Manager or its settings. */
@Suppress("UNUSED_PARAMETER")
internal object VolumeManagerBackup {
    const val ARCHIVE_PATH = "volume_manager/preferences.properties"
    fun addTo(context: Context, composer: Tar.Composer) = Unit
    fun prepareRestore(context: Context, folder: File, dirty: Boolean): () -> Unit = {}
}
