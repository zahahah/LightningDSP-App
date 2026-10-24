# LightningDSP

Android DSP application with distinct **LightningDSP** root and
**RootlessLightningDSP** capture-based builds. The root flavor uses the companion
[LightningDSP module](https://github.com/zahahah/JamesDSPManager); rootless
continues to use the bundled upstream DSP implementation.

LightningDSP is the rebranded name for this maintained app integration, inspired
by the original JamesDSP project. We retain the original authorship, contributor
credits, upstream references and license notices; the new name does not replace
that history.

This integration version is **1.6.18** (root versionCode **63**). App and module
the root app uses the requested new package ID `com.lightning.dsp`, while the
rootless package remains unchanged. Existing installations under the previous
`james.dsp` ID are left untouched. See
[the compatibility inventory](REBRAND-COMPATIBILITY.md) before changing identities.
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

## Upstream and licensing

This is a community fork of [ThePBone's RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP),
using [JamesDSP](https://github.com/james34602). Original authorship, vendored
submodule URLs, DSP APIs and [license](LICENSE) remain unchanged. Historical
screenshots/changelogs and upstream URLs are retained for provenance, not as
current LightningDSP artwork or release claims.
