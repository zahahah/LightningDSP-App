# LightningDSP

Android DSP application with distinct **LightningDSP** root and
**RootlessLightningDSP** capture-based builds. The root flavor uses the companion
[LightningDSP module](https://github.com/zahahah/LightningDSP); rootless
continues to use the bundled upstream DSP implementation.

LightningDSP is the rebranded name for this maintained app integration, inspired
by the original JamesDSP project. We retain the original authorship, contributor
credits, upstream references and license notices; the new name does not replace
that history.

The current root release is **1.7** (root versionCode **78**). The root app
uses package ID `com.lightning.dsp`; the rootless package remains unchanged.
When the companion module is explicitly flashed, it removes the old `james.dsp`
package before installing LightningDSP. The old package's private data is not
migrated. This is separate from updating `com.lightning.dsp`: a signature
mismatch there still requires the existing volume-key confirmation and
best-effort backup flow. See [the compatibility inventory](REBRAND-COMPATIBILITY.md).
This is not a claim that the local source is newer than upstream ThePBone code.

## What's new on the main branch

Stable app **1.7** and module **6.7** are still available. The main branch now
includes Fusion Mode and the newer controls below; get test builds from
[app Actions](https://github.com/zahahah/LightningDSP-App/actions/workflows/root-artifacts.yml)
or the matching [module Actions](https://github.com/zahahah/LightningDSP/actions/workflows/main-module-artifacts.yml).
The module includes the same owner-signed root universal APK. Its audio driver
is still ARM64, even though the APK includes other app architectures.

## Pick how to process audio

- **Global Mode:** effects on the output mix selected by Android.
- **Session Mode:** effects on individual sessions, with optional scanning to
  discover apps that do not announce them.
- **Fusion Mode:** one virtual audio output for apps to share, with per-app
  DSP power controls in Volume Manager. Root is required; Vector/LSPosed is not.
  The capture/playback path can add latency.

New root installs default to Fusion, Volume Manager, app DSP buttons and media
controls. Existing saved choices stay unchanged. The app DSP buttons work in
Session Mode and Fusion Mode, not on a shared Global effect.

**Include system sounds** extends Session/Fusion coverage to eligible keyboard,
notification and system audio.

**Cover additional sessions** helps eligible playback outside existing coverage.
For Global/Session emulator compatibility, the optional hook supports tested
FAST-requesting backends, including Bannerlator, Eden, NetherSX2, ARMSX2 and
RetroArch. Select affected apps in Vector/LSPosed and restart them. **Reuse AAudio
session** can keep eligible short sounds on one session and requires the same
app scope; ordinary Fusion processing does not need either hook.

Audio Processing settings are grouped, and the brain icon at the top left opens
them directly. Persistent DSP notifications use **Audio Processing** and keep
failures distinct from a working relay.

## Root transport

Global and Session processing prefer `/vendor/bin/ldspctl` and the paired
`ldsp.control` native service using protocol 3 and `LDSPCTL:` replies. Fusion
uses the bundled local DSP inside its guarded root relay. Grant the app itself root access;
a grant to ADB or Termux does not grant the app access. Missing or rejected native
control retains the AudioEffect/legacy path. Binder registration and accepted or
applied generations alone do not prove audible processing. The app does not
restart Audio HAL/audioserver or automatically execute recovery scripts.

## Presets and effect files

The app keeps its libraries under its Android app-specific external files directory,
usually `Android/data/com.lightning.dsp/files` for the root app. `Presets` contains
saved or imported `.tar` presets; it is initially empty. `Convolver`, `DDC` and
`Liveprog` contain their corresponding effect files. Use the app's **Import** action
to add files through Android's document picker, especially on devices that restrict
file-manager access to `Android/data`.

The public `/sdcard/LightningDSP` directory copied by the module installer is a
separate collection of example files, not the app's live preset library. A missing
`Presets` subdirectory there does not establish that DSP preset loading has failed.

## Gain and VolumeManager

**Extra Post Gain** defaults off and displays **Use it cautiously**. Root output
controls allow +/-15 dB normally, +/-30 dB only after opt-in; the processing
backend can still limit the applied gain. Disabling clamps the saved value
safely. Rootless hides the option and retains +/-15 dB.

LightningDSP Volume Manager is part of the root app. Stable 1.7 leaves it off by default; new main-branch installations enable it as described above. It offers per-app volume, mute and balance, active and recent apps, persistent order and pins, built-in streams, a floating panel, and an optional LightningDSP card. The root app does not require a separate VolumeManager installation. In the floating panel, tap the LightningDSP card icon to open its effects. Press and hold an effect name to select it, then use the slider to lower or raise its intensity.

I maintain LightningDSP as an audio pocket knife: DSP processing and practical audio controls in one app. The pre-rebrand JamesDSP 6.6 module and matching root app 1.6.17 remain in [JamesDSPManager-Classic](https://github.com/zahahah/JamesDSPManager-Classic) and [RootJamesDSP-Classic](https://github.com/zahahah/RootJamesDSP-Classic). The current companion module is [LightningDSP](https://github.com/zahahah/LightningDSP). Original contributors and license obligations remain credited.

Supported players can show play/pause controls. Pausing from our panel keeps
that app available for up to three minutes; pausing elsewhere does not start
the timer. Root helps grant the required notification access. Per-app DSP
buttons can stay visible or appear after tapping the LightningDSP icon, but
only while **Show app DSP power buttons** is enabled.

Edit mode supports save/cancel, mute and separate hiding from the manager and
floating panel. Inactive apps collapse; **Hide system apps** affects only the
manager page. Samsung panel suppression and KDE Connect remote-volume recovery
have received compatibility fixes without replacing non-Samsung handling.

## Module updates

The app can offer a new stable **module** release at startup. **Download** opens
GitHub; **Skip** hides that version, not future updates. Automatic checks can be
disabled in Settings. Pre-releases and app-only releases are not offered.
KernelSU Manager uses the module's stable update feed; KernelSU also needs a
mounting metamodule such as **Magic Mount** or **NoMount**.

## Optional Disable Audio Focus

The root app includes an opt-in **Disable App Audio Focus (Requires Xposed)**
switch in Volume Manager settings. To use it, install a compatible Vector/Xposed
framework, allow the LightningDSP app as a module with **System Framework** (`android`)
scope, and restart the framework as required by that manager. Grant LightningDSP
root access. The switch remains unavailable until the app verifies a PID-bound
acknowledgement from its hook in the current `system_server` process. No system
file is changed: the switch writes a volatile Android property, which the hook
checks on each focus request.
The change applies to new focus requests; an app that already paused after a
focus loss may need playback restarted.

The hook leaves voice-communication focus requests unchanged. The switch is off by
default. Hook detection and a verified property write do not prove that every
media app will continue playing.
Turn it off to restore normal handling for subsequent requests. An opted-in setting
is restored when the app starts after a reboot. The setting is intentionally not
included in Volume Manager backups, so restoring a backup cannot silently enable
this framework-level behavior. The hook is adapted from
[auag0/DisableAudioFocus](https://github.com/auag0/DisableAudioFocus); its MIT notice
is packaged in the root APK.

## Full backups

Manual and scheduled full backups include LightningDSP Volume Manager's per-app
volume, mute and balance rules, hardware-button exclusions, app order and pins,
hidden apps, stream visibility and order, effect display order, panel theme,
and enable/startup preferences. DSP effect values remain in the existing DSP
backup section. Exporting a DSP preset does not export the mixer.

A clean restore replaces portable mixer settings. A merge restore overwrites
settings present in the backup and keeps other saved rules. Backups made before
this section was added leave the current mixer untouched. Rules are saved by
package name, including apps not currently installed, rather than device UIDs.

Root and overlay permissions are not transferred. Live sessions, cleanup receipts
and current Android system-stream volume/ringer/DND state are not backed up.
After restoring, the app refreshes the mixer and uses its normal guarded
start/stop path for the saved enable preference.

## Build

Use Java 17 and an installed Android SDK:

```sh
./gradlew :app:testRootFullReleaseUnitTest :app:assembleRootFullRelease \
  :app:compileRootlessFullReleaseKotlin :app:lintRootFullRelease
```

Builds do not use Firebase or upload crash reports/symbols.

Local release outputs are unsigned until signed separately. The owner main-branch
workflow builds and verifies the zahahah-signed **root universal** APK; the module
embeds that same artifact. Other development previews can use a debug signer,
which is not interchangeable. Check the package and certificate before
distribution; never automatically uninstall or delete data.
See [root build notes](BUILD_ROOT.md) and [native integration](NATIVE-CONTROL-V4.md).

## Contributors

### App implementation

- Lightning Zahah (@zahahah)
- ThePBone (@thepbone)

### Core library implementation

- Lightning Zahah (@zahahah)
- James Fung (@james34602)

## Upstream and licensing

This is a community fork of [ThePBone's RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP),
inspired by and using [JamesDSP by James Fung (james34602)](https://github.com/james34602/JamesDSPManager).
Original authorship, vendored submodule URLs, DSP APIs and [license](LICENSE) remain unchanged. Historical
screenshots/changelogs and upstream URLs are retained for provenance, not as
current LightningDSP artwork or release claims.
