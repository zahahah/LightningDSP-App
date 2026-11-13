package lightning.dsp.interop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioCompatibilityLoadGateTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }

    @Test fun disabledOptionDoesNotLoadTheNativeHook() {
        val source = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        val handle = source.substringAfter("@Override public void handleLoadPackage(")
        val guard = handle.indexOf("if (!aaudioRequested && !sessionReuseEnabled && !sessionPreparationEnabled)")
        val load = handle.indexOf("System.loadLibrary(\"ldsp_aaudio_compat\")")
        assertTrue("the opt-in gate must precede native library loading", guard >= 0 && load > guard)
        assertTrue(handle.substring(guard, load).contains("return;"))
        assertTrue(source.contains("return \"1\".equals(XposedHelpers.callStaticMethod"))
    }

    @Test fun sessionPreparationHooksAudioTrackBeforePlaybackWithoutLoadingTheNativePatch() {
        val source = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        val handle = source.substringAfter("@Override public void handleLoadPackage(")
        assertTrue(handle.indexOf("if (sessionPreparationEnabled) installAudioTrackPreparationHook();") <
            handle.indexOf("System.loadLibrary(\"ldsp_aaudio_compat\")"))
        assertTrue(source.contains("findAndHookMethod(\"android.media.AudioTrack\", null, \"play\""))
        assertTrue(source.contains("beforeHookedMethod(MethodHookParam param)"))
        assertTrue(source.contains("\"prepare_session\""))
        assertTrue(source.contains("Looper.myLooper() == Looper.getMainLooper()"))
    }

    @Test fun nativeInitAlsoRequiresOptInBeforeInstallingHooks() {
        val source = File(app, "src/main/cpp/aaudio-compat/aaudio_compat.cpp").readText()
        val init = source.substringAfter("LoadedCallback native_init(const NativeApi* api)")
        assertTrue(init.indexOf("!hookRequested()) return nullptr;") >= 0)
        assertTrue(init.indexOf("!hookRequested()) return nullptr;") < init.indexOf("observerReady.store("))
        assertTrue(init.contains("return libraryLoaded;"))
        assertFalse("libaaudio entry points must not be inline patched", source.contains("api->hook("))
    }

    @Test fun childLaunchAdapterPreservesTheScopedOptInAndNormalXposedChain() {
        val source = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        val entry = source.substringAfter("@Override public void handleLoadPackage(")
        assertTrue(entry.indexOf("if (!aaudioRequested && !sessionReuseEnabled && !sessionPreparationEnabled)") < entry.indexOf("installLaunchHook();"))
        assertTrue(entry.indexOf("info.appInfo.flags & ApplicationInfo.FLAG_SYSTEM") < entry.indexOf("installLaunchHook();"))
        assertTrue(source.contains("findAndHookMethod(\"java.lang.ProcessImpl\", null, \"start\","))
        assertTrue(source.contains("String[].class, Map.class, String.class, ProcessBuilder.Redirect[].class,"))
        assertTrue(source.contains("boolean.class, new XC_MethodHook(Integer.MIN_VALUE)"))
        assertTrue(source.contains("() -> new ProcessBuilder().environment()"))
        assertTrue(source.contains("param.args[1] = adapted"))
        assertFalse(source.contains("param.args ="))
        val launch = source.substringAfter("private static void installLaunchHook()")
            .substringBefore("@Override public void handleLoadPackage")
        assertFalse(launch.contains("afterHookedMethod"))
        assertFalse(source.contains("invokeOriginalMethod"))
        assertFalse(source.contains("XC_MethodReplacement"))
    }

    @Test fun loadedLibrarySweepStopsAfterAnyCompatibleBackendIsRebound() {
        val source = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        val scan = source.substringAfter("private static void scanLoadedAudioLibraries()")
            .substringBefore("private static void startLoadedLibraryScan()")
        val start = source.substringAfter("private static void startLoadedLibraryScan()")
            .substringBefore("/** Do not even load the native library")

        assertTrue("the sweep must stop on OpenSL as well as AAudio", scan.contains("if (nativeReady())"))
        assertFalse("OpenSL-only apps must not be polled forever waiting for AAudio", scan.contains("if (nativeAAudioReady())"))
        assertTrue("the initial sweep should avoid scheduling retries after a successful backend bind", start.contains("if (nativeReady() || !libraryScanScheduled"))
        assertTrue("the retry loop should stop once a backend bind is reported", start.contains("!aaudioHookRequested() || nativeReady()"))
        val native = File(app, "src/main/cpp/aaudio-compat/aaudio_compat.cpp").readText()
        assertTrue("later dlopen operations remain covered by the registered linker callback", native.contains("return libraryLoaded;"))
    }
}
