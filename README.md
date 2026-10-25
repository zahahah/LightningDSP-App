# LightningDSP

Android DSP application with distinct **LightningDSP** root and
**RootlessLightningDSP** capture-based builds. The root flavor uses the companion
[LightningDSP module](https://github.com/zahahah/JamesDSPManager); rootless
continues to use the bundled upstream DSP implementation.

LightningDSP is the rebranded name for this maintained app integration, inspired
by the original JamesDSP project. We retain the original authorship, contributor
credits, upstream references and license notices; the new name does not replace
that history.

This integration version is **1.7-beta-1** (root versionCode **65**). The root app
uses package ID `com.lightning.dsp`; the rootless package remains unchanged.
When the companion module is explicitly flashed, it removes the old `james.dsp`
package before installing LightningDSP. The old package's private data is not
migrated. This is separate from updating `com.lightning.dsp`: a signature
mismatch there still requires the existing volume-key confirmation and
best-effort backup flow. See [the compatibility inventory](REBRAND-COMPATIBILITY.md).
This is not a claim that the local source is newer than upstream ThePBone code.

## Root transport

The root app prefers `/vendor/bin/ldspctl` and the paired `ldsp.control` native
service using protocol 3 and `LDSPCTL:` replies. Grant the app itself root access;
a grant to ADB or Termux does not grant the app access. Missing or rejected native
control retains the AudioEffect/legacy path. Binder registration and accepted or
applied generations alone do not prove audible processing. The app does not
restart Audio HAL/audioserver or automatically execute recovery scripts.

## Gain and VolumeManager

**Extra Post Gain** defaults off and displays **Use it cautiously**. Root output
gain is +/-15 dB normally, +/-30 dB only after opt-in. Disabling clamps the saved
value safely. Rootless hides the option and retains +/-15 dB.

The existing opt-in [VolumeManager](https://github.com/zahahah/VolumeManager)
provider remains compatible: only `moe.chensi.volume` may call it after the user
enables **Expose the VolumeManager integration API**. Its gain getter/setter use
the currently selected range and cannot enable the extended range themselves.

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

Release outputs are unsigned until signed separately. CI preview builds use the
Android development/debug signer and are not owner-signed distribution updates. Check the package and
certificate before distribution; never automatically uninstall or delete data.
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
