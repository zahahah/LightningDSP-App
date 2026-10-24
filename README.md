# RootJamesDSP

JamesDSP on Android, with a Root build, a Rootless build, and an optional bridge to VolumeManager. This fork keeps the upstream app and adds the integration work described below. No magic audio fairy required, just the right build and setup.

## Pick your build

- **Root** (`james.dsp`) uses root access and the companion JamesDSPManager module. This is the path for the system-wide Pixel setup.
- **Rootless** (`me.timschneeberger.rootlessjamesdsp`) does not need root or the module. It uses Android's audio capture, so apps that block capture can stay outside the effect chain, and some extra latency is expected.

## Root setup

The Root build works with the companion module in [zahahah/JamesDSPManager](https://github.com/zahahah/JamesDSPManager). JamesDSPManager 6.6 supplies one ARM64 `libjamesdsp.so` with AIDL effect entrypoints and a legacy adapter for supported HIDL effect factories. It uses a systemless mount overlay, so it doesn't write permanent changes to the physical `/system` or `/vendor` partitions.

The Pixel configuration tested so far is a **Pixel 10 Pro XL (`mustang`) on Android 17, KernelSU Next, and Magic Mount-rs 4.0.11**. The module stayed active across several soft reboots in that setup. That's useful evidence, not a promise that every Pixel build or audio HAL will behave identically. Android audio stacks enjoy keeping a few surprises in reserve.

### Native control first

Our modified Root app **1.6.17 (versionCode 62)** prefers **`jdsp.control`**, the global Binder service supplied by the companion native library. The app uses the root-native `jdspctl` helper for power, post-gain and FIR EQ controls. Android's `AudioEffect` mechanism still attaches the effect to the audio chain; other controls use the existing effect-parameter path, with legacy fallback when native control is unavailable.

The native service accepts writes from root, so grant **JamesDSP itself** root access. A root grant for ADB or Termux doesn't cover the app.

Both this app and the module carry that integration on **`master`**, promoted
from `pixel-test-new-aidl`. This app release is the same owner-signed APK already
included in **JamesDSPManager 6.6**, built from app commit
`94a22048108097d379b60d817e6bee8a6753be55`. Later release documentation does not
require rebuilding it.

The latest app changes handle late factory registration and recreate a missing
Global mode attachment without changing the user's mode selection. The error
screen's **Retry** action requests the module's root-authorized, guarded recovery
path off the UI thread. The module validates the firmware handler and lifecycle
before recovery; firmware actions can also restart audioserver indirectly.

For that setup:

1. Download the matching module and Root APK from the [JamesDSPManager release](https://github.com/zahahah/JamesDSPManager/releases/tag/v6.6). The module includes the APK too.
2. Install the module using your root manager. For normal-boot root, follow its reboot procedure. For temp-root/late-load, follow its activation flow; do not assume reboot preserves root.
3. Grant JamesDSP root access, enable processing and play some music.

The module does not need PIXAML for JamesDSP's basic effect path. PIXAML provides a separate AML service, which can still matter for other AML-based effects or device-specific compatibility, but installing PIXAML by itself won't make JamesDSP available.

## VolumeManager integration

This fork includes an opt-in ContentProvider API for [VolumeManager](https://github.com/zahahah/VolumeManager). It lets VolumeManager read and change JamesDSP's post gain, power state, supported effect switches, and a few effect parameters. The API checks that calls come from the VolumeManager package, `moe.chensi.volume`, and remains unavailable until you enable it.

To connect the apps:

1. Install JamesDSP and VolumeManager.
2. In JamesDSP, open **Settings → Miscellaneous → Integration** and enable **Expose the VolumeManager integration API**.
3. In VolumeManager, enable JamesDSP controls in its settings.

These are controls for the shared JamesDSP engine, not per-app DSP settings. A change made from the volume panel affects JamesDSP globally. The post-gain control ranges from −15 to +15 dB and starts at 0 dB.

## A couple of limits

The Root build depends on the device's audio HAL and the root manager's module-mount behavior. The Rootless build has different tradeoffs: Android capture restrictions can leave some apps unprocessed, and capture adds latency. If something doesn't work, include the device model, Android build, root manager/module versions, and logs. “It broke” is a start, but the audio stack usually needs a little more gossip.

## Downloads

- [JamesDSP root app 1.6.17 / code 62](https://github.com/zahahah/RootJamesDSP/releases/tag/v1.6.17), the same signed APK bundled in the 6.6 module.
- [Current module and matching Root APK](https://github.com/zahahah/JamesDSPManager/releases/tag/v6.6).
- [Historical RootJamesDSP Pixel test builds](https://github.com/zahahah/RootJamesDSP/releases/tag/pixel-test): choose **Root** when using the module, or **Rootless** if you don't want root.
- [JamesDSPManager module](https://github.com/zahahah/JamesDSPManager)
- [VolumeManager](https://github.com/zahahah/VolumeManager)

This is a community fork of [ThePBone's RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP), which uses [JamesDSP](https://github.com/james34602). Please check the upstream project and this repository's [license](LICENSE) for the original credits and terms.
