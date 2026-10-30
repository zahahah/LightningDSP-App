package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Static guardrails; actual Xposed activation needs device verification. */
class DisableAudioFocusContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }

    @Test fun hookIsRootOnlyAndScopedToFramework() {
        val manifest = File(app, "src/root/AndroidManifest.xml").readText()
        val entry = File(app, "src/root/assets/xposed_init").readText().trim()
        val hook = File(app, "src/root/java/lightning/dsp/volumemanager/focus/DisableAudioFocusHook.java").readText()
        assertTrue(manifest.contains("android:name=\"xposedscope\" android:value=\"android\""))
        assertEquals("lightning.dsp.volumemanager.focus.DisableAudioFocusHook", entry)
        assertTrue(hook.contains("DisableAudioFocusTarget.isFrameworkPackage(packageInfo.packageName)"))
        val target = File(app, "src/root/java/lightning/dsp/volumemanager/focus/DisableAudioFocusTarget.kt").readText()
        assertTrue(target.contains("packageName == \"android\" || packageName == \"system\""))
        assertTrue(hook.contains("USAGE_VOICE_COMMUNICATION"))
        assertTrue(hook.contains("for (Object argument : param.args)"))
        assertTrue(hook.contains("argument instanceof AudioAttributes"))
        assertFalse(hook.contains("param.args[0] instanceof AudioAttributes"))
        assertTrue(hook.contains("param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)"))
    }

    @Test fun preferenceDefaultsOffAndRootWriteIsVerified() {
        val option = File(app, "src/root/java/lightning/dsp/volumemanager/focus/DisableAudioFocusOption.kt").readText()
        val hook = File(app, "src/root/java/lightning/dsp/volumemanager/focus/DisableAudioFocusHook.java").readText()
        assertTrue(option.contains("getBoolean(KEY, false)"))
        assertTrue(option.contains("RootShellImpl.hasRootShell()"))
        assertTrue(option.contains("result.out.lastOrNull()?.trim() != value"))
        assertTrue(option.contains("marker == server"))
        assertTrue(option.contains("if (!available()) return false"))
        assertTrue(option.contains("debug.lightningdsp.disable_audio_focus"))
        assertTrue(hook.contains("debug.lightningdsp.disable_audio_focus"))
        assertTrue(hook.contains("debug.lightningdsp.audio_focus_hook_pid"))
        val settings = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt").readText()
        assertTrue(settings.contains("val available = DisableAudioFocusOption.available()"))
        assertTrue(settings.contains("audioFocusSwitch.isEnabled = available"))
        assertTrue(settings.contains("audioFocusStatus.visibility = if (active) View.GONE else View.VISIBLE"))
        assertTrue(settings.contains("audioFocusStatus.visibility = if (success && checked) View.GONE else View.VISIBLE"))
    }
}
