package lightning.dsp.volumemanager.focus;

import android.media.AudioAttributes;
import android.media.AudioManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Set;

/** Optional system_server hook. The runtime property defaults to off. */
public final class DisableAudioFocusHook implements IXposedHookLoadPackage {
    private static final String PROPERTY = "debug.lightningdsp.disable_audio_focus";
    private static final String READY = "debug.lightningdsp.audio_focus_hook_pid";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) {
        if (!DisableAudioFocusTarget.isFrameworkPackage(packageInfo.packageName)) return;
        Class<?> focusControl = XposedHelpers.findClassIfExists(
                "com.android.server.audio.MediaFocusControl", packageInfo.classLoader);
        if (focusControl == null) {
            XposedBridge.log("LightningDSP AudioFocus: MediaFocusControl missing in " + packageInfo.packageName);
            return;
        }
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    focusControl, "requestAudioFocus", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    boolean enabled;
                    try {
                        enabled = (boolean) XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.os.SystemProperties", null),
                                "getBoolean", PROPERTY, false);
                    } catch (Throwable ignored) {
                        return; // Unknown framework behavior: retain the original focus policy.
                    }
                    if (!enabled) return;
                    // Android 17 places an int before AudioAttributes; older releases
                    // place AudioAttributes first. Resolve by type, not argument index.
                    AudioAttributes attributes = null;
                    for (Object argument : param.args) {
                        if (argument instanceof AudioAttributes) {
                            attributes = (AudioAttributes) argument;
                            break;
                        }
                    }
                    if (attributes == null || attributes.getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        return;
                    param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                }
            });
            if (hooks.isEmpty()) {
                XposedBridge.log("LightningDSP AudioFocus: requestAudioFocus method missing in " + packageInfo.packageName);
                return;
            }
            // A volatile, PID-bound acknowledgement lets the app distinguish an active
            // hook from mere installation or a stale marker after system_server restarts.
            XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null),
                    "set", READY, Integer.toString(android.os.Process.myPid()));
            String readyPid = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null), "get", READY, "");
            if (!Integer.toString(android.os.Process.myPid()).equals(readyPid)) {
                XposedBridge.log("LightningDSP AudioFocus: readiness property rejected in " + packageInfo.packageName);
                return;
            }
            XposedBridge.log("LightningDSP AudioFocus hook ready in " + packageInfo.packageName
                    + " pid=" + readyPid + " hooks=" + hooks.size());
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP AudioFocus: hook setup failed in " + packageInfo.packageName);
            XposedBridge.log(error);
        }
    }

}
