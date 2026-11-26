package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks only; trusted window z-order, touch and keyguard must be verified on a device. */
class SystemUiVolumeSurfaceHostContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val host get() = File(app,
        "src/root/java/lightning/dsp/volumemanager/surface/SystemUiVolumeSurfaceHost.java").readText()
    private val hook get() = File(app,
        "src/root/java/lightning/dsp/volumemanager/backend/SystemUiVolumePanelHook.java").readText()

    @Test fun windowBelongsToSystemUiAndEmbedsAppSurfaceWithoutGlobalPermission() {
        assertTrue(host.contains("Build.VERSION.SDK_INT < 30"))
        assertTrue(host.contains("\"com.android.systemui\".equals(context.getPackageName())"))
        assertTrue(host.contains("getField(\"TYPE_VOLUME_OVERLAY\")"))
        assertTrue(host.contains("displays.getDisplay(Display.DEFAULT_DISPLAY)"))
        assertTrue(host.contains("context.createDisplayContext(display).createWindowContext(type, null)"))
        assertTrue(host.contains("if (warnings++ >= 4) return"))
        assertTrue(host.contains("surface.setChildSurfacePackage(result)"))
        assertTrue(host.contains("surface.setZOrderOnTop(true)"))
        assertTrue(host.contains("VOLUME_SURFACE_HOST"))
        assertTrue(host.contains("commands, PERMISSION, main"))
        assertFalse(host.contains("TYPE_APPLICATION_OVERLAY"))
        assertFalse(host.contains("setKeyguardDisabled"))
        assertFalse(host.contains("requestDismissKeyguard"))
    }

    @Test fun readinessRequiresAttachedCurrentSurfaceAndBoundedOwnerLifecycle() {
        assertTrue(host.contains("resultGeneration != generation"))
        assertTrue(host.contains("ready = surface.isAttachedToWindow()"))
        assertTrue(host.contains("Binder.getCallingUid() == appUid"))
        assertTrue(host.contains("binder.linkToDeath(serviceDeath, 0)"))
        assertTrue(host.contains("service.release(owner, generation)"))
        assertTrue(host.contains("HEARTBEAT_MS = 2_000L"))
        assertTrue(host.contains("if (ready) {"))
        assertFalse(host.contains("heartbeat(ready && window.isAttachedToWindow())"))
        assertTrue(host.contains("REQUEST_TIMEOUT_MS = 2_000L"))
        assertTrue(host.contains("if (result != null) result.release()"))
        assertTrue(hook.contains("!SystemUiVolumeSurfaceHost.isReady()"))
        assertTrue(hook.contains("SystemUiVolumeSurfaceHost.warmFromVolumeEvent()"))
        assertTrue(host.contains("if (!host.ready && host.optedIn()) host.prepare();"))
        assertTrue(host.contains("Intent.ACTION_SCREEN_ON"))
        assertTrue(host.contains("PowerManager power = context.getSystemService(PowerManager.class)"))
        assertTrue(host.contains("requestedShow = pendingShow"))
        assertTrue(host.contains("main.postDelayed(bindRetry"))
        assertTrue(host.contains("long destroyedGeneration = generation++"))
        assertTrue(host.contains("attaching = false;\n            long destroyedGeneration"))
        assertTrue(host.contains("if (!screenOff && !userSwitch && optedIn() && window == null) prepare();"))
        assertTrue(host.contains("if (screenOff || userSwitch || configurationChanged || presentationChanged)"))
    }

    @Test fun recreatedHolderGetsItsOwnFirstFrameDeadline() {
        val created = host.substringAfter("void surfaceCreated(").substringBefore("void surfaceChanged(")
        assertTrue(created.contains("holder != surface.getHolder()"))
        assertTrue(created.contains("requestStarted = SystemClock.uptimeMillis()"))
        assertTrue(created.contains("main.postDelayed(heartbeat, HEARTBEAT_MS)"))
        val destroyed = host.substringAfter("void surfaceDestroyed(").substringBefore("private void requestSurface")
        assertTrue(destroyed.indexOf("holder != surface.getHolder()") in 0 until destroyed.indexOf("generation++"))
        assertTrue(host.contains("bindTimeout = () -> { if (service == null) fail(); }"))
    }

    @Test fun privacyTransitionsRemovePreviousContentAndUnsupportedListenersRestrictPresentation() {
        assertTrue(host.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(host.contains("android.intent.action.USER_SWITCHED"))
        assertTrue(host.contains("addKeyguardLockedStateListener"))
        assertTrue(host.contains("if (!privacyListenerReady || keyguard == null) return true"))
        assertTrue(host.contains("locked != renderingLocked || renderingLocked != presentationLocked()"))
        assertTrue(host.contains("main.removeCallbacks(heartbeat)"))
        assertTrue(host.contains("windows.removeViewImmediate(oldWindow)"))
    }

    @Test fun embeddedViewportCanGrowWhileTheSystemUiWindowTracksVisibleContent() {
        val service = File(app,
            "src/root/java/lightning/dsp/volumemanager/surface/VolumePanelSurfaceService.kt").readText()
        assertTrue(service.contains("Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart)"))
        assertTrue(service.contains("surfaceHost.setView(root, width, height)"))
        assertFalse(service.contains("surfaceHost?.relayout"))
        assertTrue(host.contains("layout.width = width;"))
        assertTrue(host.contains("windows.updateViewLayout(window, layout);"))
    }

    @Test fun failedSurfaceReplaysOnlyVerifiedStockCallbackAndDragKeepsPanelAlive() {
        assertTrue(hook.contains("backend.invokeOriginal(method, controller, originalArgs)"))
        assertTrue(hook.contains("new Handler(callbackLooper)"))
        assertFalse(host.contains("adjustStreamVolume"))
        assertFalse(host.contains("adjustVolume("))
        assertTrue(host.contains("touching || hovering || remoteInteracting"))
        assertTrue(host.contains("IDLE_MS = 3_300L"))
        assertTrue(host.contains("FADE_MS = 500L"))
        assertTrue(host.contains("layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
    }
}
