package lightning.dsp.interop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts supplement runtime policy tests without a device or root shell. */
class RebrandContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, path).readText()

    @Test fun brandingDoesNotMigrateOpaqueOrExternalIdentities() {
        val build = source("build.gradle.kts")
        assertTrue(build.contains("namespace = \"lightning.dsp\""))
        assertTrue(build.contains("applicationId = \"com.lightning.dsp\""))
        assertTrue(build.contains("applicationId = \"me.timschneeberger.rootlessjamesdsp\""))
        assertTrue(build.contains("manifestPlaceholders[\"label\"] = \"LightningDSP\""))
        assertTrue(build.contains("manifestPlaceholders[\"label\"] = \"RootlessLightningDSP\""))
        assertTrue(source("src/main/res/values/strings.xml").contains("Welcome to LightningDSP!"))
        assertFalse(File(app, "src/root/res/values/strings_brand.xml").exists())
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("me.timschneeberger.rootlessjamesdsp.SET_POWER_STATE"))
    }

    @Test fun integratedVolumeManagerDoesNotExposeTheOldExternalApi() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("VolumeManagerIntegrationProvider"))
        assertFalse(manifest.contains("\${applicationId}.volume_manager"))
        assertFalse(File(app, "src/main/java/lightning/dsp/interop/VolumeManagerIntegrationProvider.kt").exists())
        assertFalse(source("src/main/res/xml/app_misc_preferences.xml")
            .contains("key_volume_manager_integration_api"))
        assertFalse(source("src/main/java/lightning/dsp/MainApplication.kt")
            .contains("VolumeManagerIntegrationProvider"))
        assertFalse(source("src/main/java/lightning/dsp/fragment/settings/SettingsMiscFragment.kt")
            .contains("key_volume_manager_integration_api"))
    }

    @Test fun cloudCrashReportingIsNotRequiredOrPackaged() {
        val appBuild = source("build.gradle.kts")
        val rootBuild = File(app, "../build.gradle.kts").readText()
        val manifest = source("src/main/AndroidManifest.xml")
        val crashHooks = source("src/main/java/lightning/dsp/flavor/CrashlyticsImpl.kt")
        val nativeLog = source("src/main/cpp/liblightningdsp-wrapper/Log.h")
        val cmake = source("src/main/cpp/CMakeLists.txt") +
            source("src/main/cpp/liblightningdsp-wrapper/CMakeLists.txt")
        val nativeWrapper = source("src/main/cpp/liblightningdsp-wrapper/LightningDspWrapper.cpp")
        assertFalse(appBuild.contains("com.google.firebase"))
        assertFalse(appBuild.contains("com.google.gms.google-services"))
        assertFalse(rootBuild.contains("google-services"))
        assertFalse(rootBuild.contains("firebase-crashlytics-gradle"))
        assertFalse(manifest.contains("firebase_crashlytics"))
        assertFalse(crashHooks.contains("com.google.firebase"))
        assertFalse(nativeLog.contains("toCrashlytics"))
        assertFalse(cmake.contains("crashlytics-connector"))
        assertFalse(nativeWrapper.contains("firebase::crashlytics"))
    }

    @Test fun defaultThemeIsSkyBlueAndDoesNotSelectWallpaperMonet() {
        val themes = lightning.dsp.delegates.ThemingDelegate
        assertEquals(listOf(lightning.dsp.R.style.Theme_RootlessLightningDSP),
            themes.getThemeResIds(lightning.dsp.model.preference.AppTheme.DEFAULT, false))
        assertEquals(listOf(lightning.dsp.R.style.Theme_RootlessLightningDSP_Monet),
            themes.getThemeResIds(lightning.dsp.model.preference.AppTheme.MONET, false))
        val base = source("src/main/res/values/themes.xml")
        assertTrue(base.contains("<item name=\"colorPrimary\">@color/lightning_primary</item>"))
        for (qualifier in listOf("values-v31", "values-night-v31")) {
            val qualified = source("src/main/res/$qualifier/themes.xml")
            assertTrue(qualified.contains("name=\"Theme.RootlessLightningDSP.Monet\""))
            assertFalse(qualified.contains("name=\"Theme.RootlessLightningDSP\""))
        }
        val about = source("src/main/res/xml/app_about_preferences.xml")
        assertTrue(about.contains("https://github.com/thepbone/RootlessJamesDSP"))
        assertTrue(about.contains("https://play.google.com/store/apps/details?id=me.timschneeberger.rootlessjamesdsp"))
    }

    @Test fun nativeWirePrefixChangesButProtocolDoesNot() {
        assertNull(NativeControlReply.parse("JDSPCTL:OK:ready=1:protocol=3:contexts=1:writable=1:publication=3"))
        assertTrue(NativeControlReply.parse("LDSPCTL:OK:ready=1:protocol=3:contexts=1:writable=1:publication=3")!!.ready)
        val client = source("src/root/java/lightning/dsp/interop/LightningDspControlClient.kt")
        assertTrue(client.contains("/vendor/bin/ldspctl"))
        assertFalse(client.contains("/vendor/bin/jdspctl"))
        assertTrue(client.contains("ldsp.control"))
        val wrapper = source("src/main/cpp/liblightningdsp-wrapper/LightningDspWrapper.cpp")
        assertTrue(wrapper.contains("Java_lightning_dsp_interop_LightningDspWrapper_setPostGain"))
        assertTrue(wrapper.contains("JamesDSPSetPostGain")) // Vendored ABI is intentionally retained.
        val availability = source("src/main/java/lightning/dsp/interop/LightningDspRemoteEngine.kt")
        assertTrue(availability.contains("/vendor/lib64/soundfx/lightningdsplib.so"))
        assertFalse(availability.contains("libjamesdsp.so"))
        val cmake = source("src/main/cpp/CMakeLists.txt") +
            source("src/main/cpp/liblightningdsp-wrapper/CMakeLists.txt")
        assertTrue(cmake.contains("lightningdsplib"))
        assertFalse(cmake.contains("jamesdsp\n"))
    }

    @Test fun gainPolicyRunsBeforeSharedEngineDispatchAndRootlessCannotOptIn() {
        val engine = source("src/main/java/lightning/dsp/interop/LightningDspBaseEngine.kt")
        assertTrue(engine.indexOf("val outputPostGain = PostGainRangePolicy.clamp") <
            engine.indexOf("setOutputControl(limiterThreshold, limiterRelease, outputPostGain)"))
        assertTrue(engine.contains("extendedPostGain && !BuildConfig.ROOTLESS"))
        val fragment = source("src/main/java/lightning/dsp/fragment/PreferenceGroupFragment.kt")
        assertTrue(fragment.contains("isVisible = !BuildConfig.ROOTLESS"))
        assertTrue(fragment.contains("prepareRangeChange(newValue as Boolean)"))
        val preference = source("src/main/java/lightning/dsp/preference/PostGainPreference.kt")
        assertTrue(preference.contains("if (stored != target) preferences.edit().putFloat(key, target).apply()"))
        val resources = source("src/main/res/values/strings.xml")
        assertTrue(resources.contains(">Use it cautiously</string>"))
        assertTrue(source("src/main/res/xml/dsp_output_control_preferences.xml")
            .contains("android:defaultValue=\"false\""))
    }
}
