package lightning.dsp.volumemanager.backend;
import android.os.ParcelFileDescriptor;
interface IVolumePlaybackService {
    void start(in ParcelFileDescriptor commands, in ParcelFileDescriptor responses, in ParcelFileDescriptor receipt, String nonce, IBinder owner) = 0;
    boolean isRunning() = 1;
    void stop() = 2;
    void destroy() = 16777114;
}
