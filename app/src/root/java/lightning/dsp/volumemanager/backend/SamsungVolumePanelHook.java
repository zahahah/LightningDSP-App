package lightning.dsp.volumemanager.backend;

import android.os.Process;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** One UI dispatches its volume panel from SystemUI even when a controller proxy is installed. */
public final class SamsungVolumePanelHook implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String[] VOLUME_DIALOG_CONTROLLER_CLASSES = {
        "com.android.systemui.volume.VolumeDialogControllerImpl",
        "com.android.systemui.volume.VolumeDialogControl"
    };
    private static final String LEASE_PROPERTY = "debug.lightningdsp.volume_panel_lease_ms";
    private static final long LEASE_TTL_MS = 6_000L;
    private static final int FLAG_SHOW_UI = 1;

    private static boolean suppressionActive() {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            String value = (String) XposedHelpers.callStaticMethod(properties, "get", LEASE_PROPERTY, "0");
            long refreshedAt = Long.parseLong(value);
            long now = SystemClock.uptimeMillis();
            return refreshedAt > 0 && now >= refreshedAt && now - refreshedAt <= LEASE_TTL_MS;
        } catch (Throwable ignored) {
            return false; // Missing/stale state preserves Samsung's normal volume panel.
        }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) {
        if (!SYSTEM_UI.equals(info.packageName)) return;
        try {
            Class<?> control = null;
            for (String targetClass : VOLUME_DIALOG_CONTROLLER_CLASSES) {
                control = XposedHelpers.findClassIfExists(targetClass, info.classLoader);
                if (control != null) break;
            }
            if (control == null) {
                XposedBridge.log("LightningDSP Samsung volume panel: VolumeDialogControllerImpl not found; native behavior retained");
                return;
            }
            int hooks = XposedBridge.hookAllMethods(control, "onVolumeChangedW", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!suppressionActive() || param.args == null || param.args.length < 2 || !(param.args[1] instanceof Integer))
                        return;
                    int flags = (Integer) param.args[1];
                    if ((flags & FLAG_SHOW_UI) != 0) param.args[1] = flags & ~FLAG_SHOW_UI;
                }
            }).size();
            if (hooks == 0) {
                XposedBridge.log("LightningDSP Samsung volume panel: onVolumeChangedW not found; native behavior retained");
                return;
            }
            XposedBridge.log("LightningDSP Samsung volume panel hook ready pid=" + Process.myPid() + " hooks=" + hooks);
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP Samsung volume panel hook unavailable; native behavior retained");
            XposedBridge.log(error);
        }
    }
}
