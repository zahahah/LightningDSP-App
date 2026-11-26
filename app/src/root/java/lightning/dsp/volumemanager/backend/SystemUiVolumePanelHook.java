package lightning.dsp.volumemanager.backend;

import android.os.Process;
import android.os.Build;
import android.os.SystemClock;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import lightning.dsp.xposed.HookBackend;
import lightning.dsp.xposed.HookPlatform;
import lightning.dsp.utils.logging.DiagnosticMode;
import lightning.dsp.volumemanager.surface.SystemUiVolumeSurfaceHost;

/** Keep verified SystemUI volume dispatch while a live app-owned surface is hosted in its window. */
public final class SystemUiVolumePanelHook {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String[] VOLUME_DIALOG_CONTROLLERS = {
        "com.android.systemui.volume.VolumeDialogControllerImpl",
        "com.android.systemui.volume.VolumeDialogControl"
    };
    private static final String LEASE_PROPERTY = "debug.lightningdsp.volume_panel_lease_ms";
    private static final String ENABLED_PROPERTY = "debug.lightningdsp.suppress_volume_panel";
    private static final String READY_PROPERTY = "debug.lightningdsp.volume_panel_hook_pid";
    private static final long LEASE_TTL_MS = 6_000L;
    private static final long CONTROLLER_RETRY_MS = 500L;
    // SystemUI can be relaunched after boot or under memory pressure; Samsung and
    // newer Android builds may publish its Application/controller later than 2 s.
    // Keep retries bounded to 10 s per initialization stage, then fail open to stock.
    private static final int CONTROLLER_ATTEMPTS = 20;
    private static final int FLAG_SHOW_UI = 1;
    private static final AtomicBoolean startupScheduled = new AtomicBoolean();
    private static boolean volumeHookInstalled;

    /** Only hook the AOSP contract: older releases use (int, int), newer ones add a boolean. */
    private static boolean isVolumeChangedMethod(Method method) {
        if (!"onVolumeChangedW".equals(method.getName())) return false;
        Class<?>[] parameters = method.getParameterTypes();
        return (parameters.length == 2 || parameters.length == 3)
                && parameters[0] == int.class && parameters[1] == int.class
                && (parameters.length == 2 || parameters[2] == boolean.class);
    }

    private static boolean suppressionActive() {
        try {
            if (!HookPlatform.propertyBoolean(ENABLED_PROPERTY, false))
                return false;
            String value = HookPlatform.property(LEASE_PROPERTY, "0");
            long refreshedAt = Long.parseLong(value);
            long now = SystemClock.uptimeMillis();
            return refreshedAt > 0 && now >= refreshedAt && now - refreshedAt <= LEASE_TTL_MS;
        } catch (Throwable ignored) {
            return false; // Missing/stale state preserves the native SystemUI volume panel.
        }
    }

    public static void install(HookBackend backend, String packageName, String processName) {
        // SystemUI also runs auxiliary processes (for example :screenshot).
        // Only its main process owns the volume dialog and may host our window.
        if (!SYSTEM_UI.equals(packageName) || !SYSTEM_UI.equals(processName)) return;
        if (Build.VERSION.SDK_INT < 30) return;
        // Package-ready can be dispatched again as its splits are loaded.
        // Do not clear a live readiness receipt or spawn a second startup chain.
        if (!startupScheduled.compareAndSet(false, true)) return;
        clearReadinessMarker();
        // A framework can load this hook after SystemUI's Application.onCreate.
        // A hook placed on onCreate would then never run, leaving the stock dialog forever.
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> initializeSystemUi(backend, 0), CONTROLLER_RETRY_MS);
    }

    /** Do not leave a dead SystemUI PID advertised while this process reinitializes. */
    private static void clearReadinessMarker() {
        try {
            HookPlatform.setProperty(READY_PROPERTY, "0");
        } catch (Throwable error) {
            HookPlatform.log("LightningDSP SystemUI volume panel: could not clear stale readiness marker");
        }
    }

    private static void initializeSystemUi(HookBackend backend, int attempt) {
        if (volumeHookInstalled) return;
        Application application = null;
        try {
            application = HookPlatform.currentApplication();
        } catch (Throwable ignored) { /* ActivityThread may not publish the Application yet. */ }
        if (application == null) {
            if (attempt + 1 < CONTROLLER_ATTEMPTS) {
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> initializeSystemUi(backend, attempt + 1), CONTROLLER_RETRY_MS);
            } else {
                HookPlatform.log("LightningDSP SystemUI volume panel: application unavailable after startup retries; native behavior retained");
            }
            return;
        }
        SystemUiVolumeSurfaceHost.install(application);
        installVolumeController(backend, application.getClassLoader(), 0);
    }

    /** SystemUI's concrete controller is not reliably resolvable during package loading. */
    private static void installVolumeController(HookBackend backend, ClassLoader loader, int attempt) {
        if (volumeHookInstalled) return;
        try {
            boolean samsung = "samsung".equalsIgnoreCase(Build.MANUFACTURER);
            Class<?> control = null;
            int candidateCount = samsung ? VOLUME_DIALOG_CONTROLLERS.length : 1;
            for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                String name = VOLUME_DIALOG_CONTROLLERS[candidateIndex];
                Class<?> candidate = HookPlatform.findClassIfExists(name, loader);
                if (candidate == null) continue;
                for (Method method : candidate.getDeclaredMethods()) {
                    if (isVolumeChangedMethod(method)) { control = candidate; break; }
                }
                if (control != null) break;
            }
            if (control == null) {
                if (attempt + 1 < CONTROLLER_ATTEMPTS) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                        () -> installVolumeController(backend, loader, attempt + 1), CONTROLLER_RETRY_MS);
                } else {
                    HookPlatform.log("LightningDSP SystemUI volume panel: controller unavailable after startup retries; native behavior retained");
                }
                return;
            }
            int hooks = 0;
            for (Method method : control.getDeclaredMethods()) {
                if (!isVolumeChangedMethod(method)) continue;
                backend.before(method, HookBackend.DEFAULT_PRIORITY, param -> {
                    if (Build.VERSION.SDK_INT < 30) return;
                    if (param.args == null || param.args.length < 2 || !(param.args[1] instanceof Integer)) return;
                    // Capture the exact platform callback, not a second volume adjustment. The
                    // root proxy may already have stripped SHOW_UI before SystemUI receives it.
                    Looper callbackLooper = Looper.myLooper();
                    if (callbackLooper != null) {
                        final Handler callbackHandler = new Handler(callbackLooper);
                        final Object controller = param.receiver;
                        final Object[] originalArgs = param.args.clone();
                        originalArgs[1] = ((Integer) originalArgs[1]) | FLAG_SHOW_UI;
                        SystemUiVolumeSurfaceHost.setStockFallback(() -> callbackHandler.post(() -> {
                            try { backend.invokeOriginal(method, controller, originalArgs); }
                            catch (Throwable ignored) { /* Unsupported native callback remains untouched. */ }
                        }));
                    }
                    int flags = (Integer) param.args[1];
                    if (!suppressionActive()) {
                        if (!SystemUiVolumeSurfaceHost.isReady() && (flags & FLAG_SHOW_UI) != 0)
                            SystemUiVolumeSurfaceHost.warmFromVolumeEvent();
                        return;
                    }
                    if (!SystemUiVolumeSurfaceHost.isReady()) {
                        if ((flags & FLAG_SHOW_UI) != 0) {
                            // Queue this request through the bounded surface attach. On
                            // failure the host replays this exact stock callback once.
                            SystemUiVolumeSurfaceHost.showFromVolumeEvent();
                            param.args[1] = flags & ~FLAG_SHOW_UI;
                        }
                        return;
                    }
                    if ((flags & FLAG_SHOW_UI) != 0) {
                        SystemUiVolumeSurfaceHost.showFromVolumeEvent();
                        param.args[1] = flags & ~FLAG_SHOW_UI;
                    }
                });
                hooks++;
            }
            if (hooks == 0) {
                HookPlatform.log("LightningDSP SystemUI volume panel: supported onVolumeChangedW signature not found; native behavior retained");
                return;
            }
            // Only advertise readiness after a real hook was installed. The app compares
            // this PID with the live SystemUI process before allowing the option to be enabled.
            String pid = Integer.toString(Process.myPid());
            try {
                HookPlatform.setProperty(READY_PROPERTY, pid);
            } catch (Throwable ignored) {
                // SystemUI (platform_app) may lack SELinux permission to set debug_prop
            }
            volumeHookInstalled = true;
            if (DiagnosticMode.isEnabled()) HookPlatform.log("LightningDSP SystemUI volume panel hook ready pid=" + Process.myPid() + " hooks=" + hooks);
        } catch (Throwable error) {
            HookPlatform.log("LightningDSP SystemUI volume panel hook unavailable; native behavior retained");
            if (DiagnosticMode.isEnabled()) HookPlatform.log(error);
        }
    }
}
