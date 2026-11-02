package lightning.dsp.interop;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Native opt-in applies only inside user apps explicitly scoped by Vector/LSPosed. */
public final class AudioCompatibilityHook implements IXposedHookLoadPackage {
    private static native boolean nativeReady();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) {
        int appId = Process.myUid() % 100000;
        if (appId < 10000 || appId >= 20000 || info.appInfo == null ||
                (info.appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                info.packageName == null || info.packageName.equals("android") ||
                info.packageName.equals("system") || info.packageName.startsWith("com.lightning.dsp")) return;
        try {
            System.loadLibrary("ldsp_aaudio_compat");
            // Loading the library alone does not prove native_init ran.
            XposedBridge.log("LightningDSP AAudio: " + (nativeReady() ? "hook ready" : "native hook unavailable")
                    + " pid=" + Process.myPid());
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP AAudio: unavailable; original audio behavior retained");
            XposedBridge.log(error);
        }
    }
}
