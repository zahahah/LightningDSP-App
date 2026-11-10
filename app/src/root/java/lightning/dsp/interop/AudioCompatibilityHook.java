package lightning.dsp.interop;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.Bundle;
import android.os.SystemClock;
import android.app.Application;
import android.net.Uri;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final AtomicInteger preparationDiagnostics = new AtomicInteger();
    private static final AtomicInteger launchDiagnostics = new AtomicInteger();
    private static final AtomicInteger libraryScanDiagnostics = new AtomicInteger();
    private static final AtomicBoolean libraryScanScheduled = new AtomicBoolean();
    private static native boolean nativeReady();
    private static native boolean nativeAAudioReady();
    private static native void nativeSetPreparationCallback();
    private static native int nativeScanLoadedAudioLibraries();
    private static final ScheduledExecutorService libraryScanWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LDSP-library-scan");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile ScheduledFuture<?> libraryScanTask;
    private static final ThreadPoolExecutor preparationWorker = new ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "LDSP-session-prepare");
                thread.setDaemon(true);
                return thread;
            });

    /** Called only after open, before the caller can start its stream. Never on a PCM callback. */
    private static final String OWNER_DESCRIPTOR = "lightning.dsp.UnifiedSessionOwner";
    private static final int OWNER_CODE = android.os.IBinder.FIRST_CALL_TRANSACTION + 731;
    private static final android.os.Binder reuseOwner = new android.os.Binder() {
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code != OWNER_CODE) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(OWNER_DESCRIPTOR);
            android.os.IBinder ack = data.readStrongBinder();
            if (ack == null) return false;
            android.os.Parcel confirmation = android.os.Parcel.obtain();
            try {
                confirmation.writeInterfaceToken(OWNER_DESCRIPTOR);
                return ack.transact(OWNER_CODE, confirmation, null, android.os.IBinder.FLAG_ONEWAY);
            } finally { confirmation.recycle(); }
        }
    };
    private static volatile ScheduledFuture<?> reuseHeartbeat;
    private static volatile Future<?> reuseRenewal;
    private static volatile int unifiedSessionId;

    private static synchronized void retainUnifiedSession(int sessionId) {
        unifiedSessionId = sessionId;
        if (reuseHeartbeat != null && !reuseHeartbeat.isDone()) return;
        reuseHeartbeat = libraryScanWorker.scheduleWithFixedDelay(() -> {
            if (!requested() || !reuseRequested()) {
                ScheduledFuture<?> task = reuseHeartbeat;
                if (task != null) task.cancel(false);
                return;
            }
            Future<?> pending = reuseRenewal;
            if (pending != null && !pending.isDone()) return;
            try {
                reuseRenewal = preparationWorker.submit(() -> {
                    Application app = (Application) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    if (app == null) return;
                    Bundle extras = new Bundle();
                    extras.putLong("deadline", SystemClock.elapsedRealtime() + 1500);
                    extras.putBoolean("unified", true);
                    extras.putBinder("owner", reuseOwner);
                    app.getContentResolver().call(Uri.parse("content://com.lightning.dsp.compatibility"),
                            "retain", Integer.toString(unifiedSessionId), extras);
                });
            } catch (Throwable ignored) { /* Bounded worker busy: lease expires, never block audio. */ }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private static boolean reuseRequested() {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", null);
            return "1".equals(XposedHelpers.callStaticMethod(props, "get", "debug.ldsp.aaudio_reuse_session", "0"));
        } catch (Throwable unavailable) { return false; }
    }

    private static void prepareSession(int sessionId, boolean unified) {
        if (sessionId <= 0 || !requested()) return;
        if (unified) retainUnifiedSession(sessionId);
        Future<?> pending = null;
        AtomicReference<String> phase = new AtomicReference<>("worker_dispatch");
        try {
            final long deadline = SystemClock.elapsedRealtime() + 2000;
            pending = preparationWorker.submit(() -> {
                phase.set("application_lookup");
                Application app = (Application) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                Bundle extras = new Bundle();
                extras.putLong("deadline", deadline);
                if (unified) {
                    extras.putBoolean("unified", true);
                    extras.putBinder("owner", reuseOwner);
                }
                if (app == null || SystemClock.elapsedRealtime() >= deadline) return false;
                phase.set("provider_call");
                Bundle result = app.getContentResolver().call(
                        Uri.parse("content://com.lightning.dsp.compatibility"),
                        "prepare", Integer.toString(sessionId), extras);
                return result != null && result.getBoolean("prepared", false);
            });
            Object prepared = pending.get(2200, TimeUnit.MILLISECONDS);
            if (preparationDiagnostics.getAndIncrement() < 24)
                android.util.Log.i("LightningDSP_AAudio", "preparation ready=" + Boolean.TRUE.equals(prepared)
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - (deadline - 2000)));
        } catch (Throwable unavailable) {
            if (pending != null) pending.cancel(true);
            if (preparationDiagnostics.getAndIncrement() < 24) {
                Throwable cause = unavailable;
                for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++)
                    cause = cause.getCause();
                // ExecutionException alone hides provider, attribution and class-loading failures.
                // Bounded, local diagnostics; never log the audio dump or preference payload.
                String category = cause instanceof IllegalArgumentException && cause.getMessage() != null
                        && cause.getMessage().startsWith("Unknown authority ") ? "provider_unresolved" : "exception";
                android.util.Log.w("LightningDSP_AAudio", "preparation unavailable phase=" + phase.get()
                        + " cause=" + cause.getClass().getSimpleName() + " category=" + category);
            }
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            // Missing provider, rejected request or timeout never prevents playback.
        }
    }
    private static native boolean nativeObserverReady();

    /** Catch app audio DSOs loaded before Vector registered its linker callback. */
    private static void scanLoadedAudioLibraries() {
        try {
            int scanned = nativeScanLoadedAudioLibraries();
            if (nativeAAudioReady()) {
                ScheduledFuture<?> task = libraryScanTask;
                if (task != null) task.cancel(false);
                libraryScanScheduled.set(false);
                XposedBridge.log("LightningDSP AAudio: loaded audio imports rebound pid=" + Process.myPid());
            } else if (scanned > 0 && libraryScanDiagnostics.getAndIncrement() < 4) {
                XposedBridge.log("LightningDSP AAudio: scanned " + scanned
                        + " loaded app libraries; compatibility imports not ready pid=" + Process.myPid());
            }
        } catch (Throwable error) {
            if (libraryScanDiagnostics.getAndIncrement() < 4)
                XposedBridge.log("LightningDSP AAudio: loaded-library scan unavailable; waiting for linker callback");
        }
    }

    private static void startLoadedLibraryScan() {
        if (!nativeObserverReady()) return;
        scanLoadedAudioLibraries();
        if (nativeAAudioReady() || !libraryScanScheduled.compareAndSet(false, true)) return;
        libraryScanTask = libraryScanWorker.scheduleWithFixedDelay(() -> {
            if (!requested() || nativeAAudioReady()) {
                ScheduledFuture<?> task = libraryScanTask;
                if (task != null) task.cancel(false);
                libraryScanScheduled.set(false);
                return;
            }
            scanLoadedAudioLibraries();
        }, 1, 2, TimeUnit.SECONDS);
    }

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
            nativeSetPreparationCallback();
            startLoadedLibraryScan();
            // Registration, patched imports and audio processing are distinct states.
            String state = !nativeObserverReady() ? "native observer unavailable"
                    : nativeAAudioReady() ? "AAudio imports rebound"
                    : nativeReady() ? "other audio imports rebound; waiting for AAudio library"
                    : "observer ready; waiting for app audio library";
            XposedBridge.log("LightningDSP AAudio: " + state
                    + " pid=" + Process.myPid());
        } catch (Throwable error) {
            XposedBridge.log("LightningDSP AAudio: unavailable; original audio behavior retained");
            XposedBridge.log(error);
        }
    }
}
