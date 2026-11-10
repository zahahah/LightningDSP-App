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
        val guard = source.indexOf("if (!requested()) {")
        val load = source.indexOf("System.loadLibrary(\"ldsp_aaudio_compat\")")
        assertTrue("the opt-in gate must precede native library loading", guard >= 0 && load > guard)
        assertTrue(source.substring(guard, load).contains("return;"))
        assertTrue(source.contains("return \"1\".equals(XposedHelpers.callStaticMethod"))
    }

    @Test fun nativeInitAlsoRequiresOptInBeforeInstallingHooks() {
        val source = File(app, "src/main/cpp/aaudio-compat/aaudio_compat.cpp").readText()
        val init = source.substringAfter("LoadedCallback native_init(const NativeApi* api)")
        assertTrue(init.indexOf("!requested()) return nullptr;") >= 0)
        assertTrue(init.indexOf("!requested()) return nullptr;") < init.indexOf("observerReady.store("))
        assertTrue(init.contains("return libraryLoaded;"))
        assertFalse("libaaudio entry points must not be inline patched", source.contains("api->hook("))
    }

    @Test fun childLaunchAdapterPreservesTheScopedOptInAndNormalXposedChain() {
        val source = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        val entry = source.substringAfter("@Override public void handleLoadPackage(")
        assertTrue(entry.indexOf("if (!requested()) {") < entry.indexOf("installLaunchHook();"))
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
}
