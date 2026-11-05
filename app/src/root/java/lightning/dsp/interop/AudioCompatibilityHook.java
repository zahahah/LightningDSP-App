package lightning.dsp.interop;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Native opt-in applies only inside user apps explicitly scoped by Vector/LSPosed. */
public final class AudioCompatibilityHook implements IXposedHookLoadPackage {
    private static final String PROPERTY = "debug.ldsp.aaudio_compat";
    private static final AtomicBoolean launchHookInstalled = new AtomicBoolean();
    private static final AtomicInteger launchDiagnostics = new AtomicInteger();
    private static native boolean nativeReady();
    private static native boolean nativeObserverReady();

    /** Do not even load the native library into selected apps while disabled. */
    private static boolean requested() {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            return "1".equals(XposedHelpers.callStaticMethod(properties, "get", PROPERTY, ""));
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP AAudio: opt-in unavailable; original audio behavior retained");
            return false;
        }
    }

    private static void installLaunchHook() {
        if (!launchHookInstalled.compareAndSet(false, true)) return;
        try {
            // ProcessImpl consumes an Android-specific environment map. Adapt the
            // argument, not the caller's ProcessBuilder or its mutable environment.
            XposedHelpers.findAndHookMethod("java.lang.ProcessImpl", null, "start",
                    String[].class, Map.class, String.class, ProcessBuilder.Redirect[].class,
                    boolean.class, new XC_MethodHook(Integer.MIN_VALUE) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[1] instanceof Map)) return;
                    @SuppressWarnings("unchecked")
                    Map<String, String> original = (Map<String, String>) param.args[1];
                    Map<String, String> adapted = DirectAudioLaunchEnvironment.adapt(original,
                            requested(), () -> new ProcessBuilder().environment());
                    if (adapted != original) {
                        // Vector forwards this same argument array; replace its map
                        // element in place without changing the receiver or result.
                        param.args[1] = adapted;
                        if (launchDiagnostics.getAndIncrement() < 12)
                            XposedBridge.log("LightningDSP DirectAudio: normal mode requested for child launch");
                    }
                }
            });
        } catch (Throwable unavailable) {
            launchHookInstalled.set(false);
            XposedBridge.log("LightningDSP DirectAudio: launch adapter unavailable; original launch retained");
        }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) {
        int appId = Process.myUid() % 100000;
        if (appId < 10000 || appId >= 20000 || info.appInfo == null ||
                (info.appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                info.packageName == null || info.packageName.equals("android") ||
                info.packageName.equals("system") || info.packageName.startsWith("com.lightning.dsp")) return;
        if (!requested()) {
            XposedBridge.log("LightningDSP AAudio: disabled; native library not loaded");
            return;
        }
        installLaunchHook();
        try {
            System.loadLibrary("ldsp_aaudio_compat");
            // Registration, patched imports and audio processing are distinct states.
            String state = !nativeObserverReady() ? "native observer unavailable"
                    : nativeReady() ? "imports rebound" : "observer ready; waiting for app audio library";
            XposedBridge.log("LightningDSP AAudio: " + state
                    + " pid=" + Process.myPid());
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP AAudio: unavailable; original audio behavior retained");
            XposedBridge.log(error);
        }
    }
}
