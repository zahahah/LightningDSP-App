# LightningDSP app 1.7.2-beta-3

This pre-release adds Android 14/15 processing-mode defaults and updates the Fusion Mode description. It also carries forward the work-profile, volume-panel and audio-recovery changes, plus an optional build for testing the modern libxposed API. The usual APK still uses the legacy Xposed entry point to preserve compatibility with existing setups.

### Changes

#### Work profiles and app controls
- Show work-profile apps distinctly in Volume Manager, with a briefcase badge and independent per-app volume and DSP controls.
- Enable work-profile DSP by default only when Android exposes an eligible work profile. Keep existing saved choices, and let users change the setting in **Volume Manager → Settings → App controls**.
- Route eligible work-profile audio in Session and Fusion while preserving full Android UIDs, personal/work separation, and the relay when package inventory is restricted.
- Keep app rows stable when DSP power changes, including while the panel is visible on the lock screen.

#### SystemUI volume panel
- Use the SystemUI volume-window path for the optional replacement, while retaining stock controls until the trusted LightningDSP surface is ready.
- Improve the panel's initialization after Vector injects late and after SystemUI services become available.
- Keep the panel centered and sized to its content; expand its media controls without turning the panel into a scrolling viewport.
- Preserve app rows and media grouping through lock-screen, wake and unlock transitions, and allow touches outside the visible panel to reach the underlying screen.
- Keep the stock path safe during startup and for Samsung devices unless the replacement is explicitly ready and enabled.

#### Audio and hook reliability
- Recover Session Mode when AudioFlinger rejects the first effect attachment, without accepting stale work.
- Keep app-compatibility hooks away from SystemUI and the system framework, where app-specific audio hooks do not belong.
- Keep Fusion responsive without reducing its playback headroom; improve work-profile routing without disabling the relay when Android's package inventory is incomplete.
- Add repeatable OpenSL route checks for diagnosis. These checks do not change the APK's audio behavior.

#### Processing-mode defaults
- Fresh root installs on Android 14/API 34 and Android 15/API 35 start in Global Mode. Other Android versions retain the existing Fusion default.
- Saved processing choices are preserved on updates, and users can still choose Fusion Mode on Android 14/15.
- Describe Fusion as offering per-app effect control and potentially better playback stability, while warning that its extra audio path can add delay.

#### Optional libxposed API 102 candidate
- The release also provides a separately named root universal APK built against libxposed API 102 for testing with a compatible modern hook framework.
- The normal APK remains on the legacy Xposed API path. The two APKs are alternatives with the same package and signing certificate; do not install or distribute them as separate apps.
- The API 102 candidate has not been validated across Vector/LSPosed versions or on a full device matrix. Use the legacy APK if the modern framework does not expose the module or its hooks.

### Versions and compatibility

Root app **1.7.2/code 153**, package `com.lightning.dsp`, signed with the existing owner certificate. The universal APK includes arm64-v8a, armeabi-v7a, x86 and x86_64 app libraries. This does not make the module's ARM64 audio driver universal.

Work-profile routing depends on Android exposing the profile and an eligible package inventory through the supported root path. The SystemUI replacement requires its app scope and hook enabled in a compatible Vector/LSPosed setup; stock controls remain the fallback when the replacement is unavailable. Global Mode still cannot exclude one app from a shared output mix.

### Corrections in progress before stable 6.7.2

- We are investigating intermittent Fusion distortion reported with SBC Bluetooth audio. LDAC has been stable in the reports so far; the cause is not confirmed and no fix is claimed in this beta.
- We are continuing work on the hooked SystemUI volume panel for Samsung One UI, where it can remain unreliable. It has been stable in testing on the project's Pixel 10 Pro XL, but that does not establish Samsung or device-wide compatibility.
- We are investigating late-mounted audio APEX availability in temp-root environments. The guarded Exynos late-load path in this beta does not yet establish that this separate timing case is fixed.

Unit tests and both Xposed build variants are checked in CI. The modern API candidate still needs real-device testing; framework and firmware behavior can vary.
