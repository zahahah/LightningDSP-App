# RootJamesDSP

JamesDSP on Android, with a Root build, a Rootless build, and an optional bridge to VolumeManager. This fork keeps the upstream app and adds the integration work described below. No magic audio fairy required, just the right build and setup.

## Pick your build

- **Root** (`james.dsp`) uses root access and the companion JamesDSPManager module. This is the path for the system-wide Pixel setup.
- **Rootless** (`me.timschneeberger.rootlessjamesdsp`) does not need root or the module. It uses Android's audio capture, so apps that block capture can stay outside the effect chain, and some extra latency is expected.

## The Pixel setup

The Root build works with the companion module in [zahahah/JamesDSPManager](https://github.com/zahahah/JamesDSPManager). The module supplies `libjamesdsp.so` and registers the effect with Android's `IFactory/default` audio factory. It uses a systemless mount overlay, so it doesn't write permanent changes to the physical `/system` or `/vendor` partitions.

The Pixel configuration tested so far is a **Pixel 10 Pro XL (`mustang`) on Android 17, KernelSU Next, and Magic Mount-rs 4.0.11**. The module stayed active across several soft reboots in that setup. That's useful evidence, not a promise that every Pixel build or audio HAL will behave identically. Android audio stacks enjoy keeping a few surprises in reserve.

For that setup:

1. Install the RootJamesDSP **Root** APK from the [Pixel test pre-release](https://github.com/zahahah/RootJamesDSP/releases/tag/pixel-test).
2. Install the JamesDSPManager module from [its repository](https://github.com/zahahah/JamesDSPManager) using your root manager, then reboot.
3. Grant root access to JamesDSP when prompted and start the app.

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

- [RootJamesDSP Pixel test builds](https://github.com/zahahah/RootJamesDSP/releases/tag/pixel-test): choose **Root** when using the module, or **Rootless** if you don't want root.
- [JamesDSPManager module](https://github.com/zahahah/JamesDSPManager)
- [VolumeManager](https://github.com/zahahah/VolumeManager)

This is a community fork of [ThePBone's RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP), which uses [JamesDSP](https://github.com/james34602). Please check the upstream project and this repository's [license](LICENSE) for the original credits and terms.
