package lightning.dsp.volumemanager.focus;

import android.media.AudioAttributes;
import android.media.AudioManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Optional system_server hook. The runtime property defaults to off. */
public final class DisableAudioFocusHook implements IXposedHookLoadPackage {
    private static final String PROPERTY = "debug.lightningdsp.disable_audio_focus";
    private static final String READY = "debug.lightningdsp.audio_focus_hook_pid";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) {
        if (!"android".equals(packageInfo.packageName)) return;
        Class<?> focusControl = XposedHelpers.findClassIfExists(
                "com.android.server.audio.MediaFocusControl", packageInfo.classLoader);
        if (focusControl == null) return;
        XposedBridge.hookAllMethods(focusControl, "requestAudioFocus", new XC_MethodHook() {
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
        // A volatile, PID-bound acknowledgement lets the app distinguish an active hook
        // from mere installation or a stale marker after system_server restarts.
        try {
            XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null),
                    "set", READY, Integer.toString(android.os.Process.myPid()));
        } catch (Throwable ignored) {
            // If this firmware disallows the property, the app must fail closed.
        }
    }
}
