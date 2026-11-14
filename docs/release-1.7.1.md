# LightningDSP app 1.7.1

LightningDSP is my JamesDSP-based audio pocket knife for Android. This release brings together the profile and preset fixes from the pre-releases, new listening paths, and more useful per-app controls.

### Changes

#### Audio processing
- Add **Fusion Mode**, a virtual audio output with a local DSP relay. Included apps share processing; Volume Manager's DSP buttons let you leave individual apps without effects. Fusion needs root, not Vector/LSPosed.
- Keep Global Mode and Session Mode as separate choices. The three modes are mutually exclusive.
- Add **Cover additional sessions** for eligible playback outside the output hosting the global effect. Improve Session Mode discovery, mono voice-message coverage and simultaneous WhatsApp/music playback. Cover DuckStation on separate outputs without forcing everything into the global mixer.
- Add **Include system sounds** for eligible keyboard, notification and system audio in Session/Fusion. Android capture and routing restrictions still apply.
- Reduce Fusion relay overhead and latency with bounded buffers, local processing, safer recovery and in-place effect/exclusion updates. Remove the need to restart the whole relay for routine per-app changes.
- Improve inactive-session cleanup and active-app reporting. Keep processing bypassed until the full DSP state is ready, and reject stale work.
- Group Audio Processing settings, add a theme-aware shortcut and simplify persistent processing notifications.
- New root installs default to Fusion, Volume Manager, app DSP buttons and media controls. Existing saved choices stay unchanged.

#### Emulator compatibility
- Add opt-in support for emulators that request **FAST** audio (reported working: **Bannerlator, Eden, NetherSX2, ARMSX2 and RetroArch**).
- Support eligible AAudio, OpenSL ES and DirectAudio compatibility paths, including static/dynamic Oboe loading. This does not make arbitrary FAST/RAW tracks directly processable by the driver.
- Select affected apps in **Vector/LSPosed**, enable compatibility and restart those apps. Ordinary Fusion processing does not need these hooks.
- Add optional **Reuse AAudio session** for scoped apps that create a session for each sound. Respect explicit app session IDs and retain the existing ownership, cleanup and duplicate-effect guards.
- **JL Mod is not declared fixed:** custom soundbanks and repeated short sounds can still have delayed effects, cuts or inconsistent coverage.

#### Volume Manager
- Add per-app DSP power buttons for Session and Fusion, with optional always-visible controls. Global Mode cannot exclude one app from its shared effect.
- Add play/pause controls for supported media sessions; configure notification-listener access with root when needed.
- Keep a paused app in the panel for up to three minutes **only when paused with our button**. Resuming cancels retention; pausing elsewhere does not start the timer.
- Add edit-mode cancel alongside save, working mute controls, collapsible inactive apps and correctly anchored menus.
- Separate hiding from the manager and floating panel. **Hide system apps** filters only the manager page.
- Improve themed power/media buttons, panel spacing and expanded effect-card alignment. Honor the DSP-button master switch and remove per-app power-change toasts.
- Improve panel recovery after long suspensions and helper loss.
- Improve Samsung stock-panel suppression and recovery after KDE Connect remote-volume requests, while preserving non-Samsung behavior. Firmware and hook requirements still apply.

#### Device profiles and presets
- Restore profiles after output changes, with startup/route reconciliation, complete DSP reapplication, desired-state generations and bounded retries.
- Parse preset XML with Android-compatible, secure parsing. Restore embedded scripts and initialize missing storage directories.
- Save presets atomically and keep the previous archive if a save fails.
- Isolate each preset load and clean temporary state on success or failure. Prevent interrupted imports from contaminating the next preset/profile.
- Save loaded preference values, including disabled effects and default namespaces. Reset missing namespaces in older presets; validate before applying and attempt rollback on failure.
- Stop profile switching when outgoing settings cannot be saved; preserve an existing destination when the active-profile marker is missing.

#### Updates and diagnostics
- Check for new **stable module releases** at startup. Download opens GitHub; Skip hides only that release. Automatic checks can be disabled. Pre-releases and app-only releases are excluded.
- Add the stable update feed for KernelSU Manager. On KernelSU, a mounting metamodule such as **Magic Mount** or **NoMount** is required.
- Add **Enable diagnostic mode**, off by default. Detailed app/relay/hook logs and optional module traces require opt-in; essential warnings, errors and functional safety receipts remain available.
- Remove raw root-command arguments and native pointer values from diagnostic messages. Logs stay local; no audio recording or automatic upload.
- Keep shell traces private without changing mount/recovery safety checks or global overlay permissions.

### Module and app versions

Module **6.7.1/code 212**, including app **1.7.1/code 145**, package com.lightning.dsp, signed with the existing zahahah owner certificate. The standalone APK is identical to the one in the module.

The module is root-only with an **ARM64** audio-effect driver. Its universal APK includes arm64-v8a, armeabi-v7a, x86 and x86_64 app libraries; this does not make the module driver universal. The native HAL driver and ldspctl helper are unchanged from 6.7.

### Migration from JamesDSP

Export presets before migrating. During an explicit module flash, the installer verifies and removes the old ainur_jamesdsp module and james.dsp app. Their private app data is not migrated.

Existing com.lightning.dsp installations signed with the same key update in place and retain their data. Do not uninstall or clear data to resolve a certificate mismatch; the existing confirmation flow still applies.

### Compatibility notes

Fusion can add latency because it uses a capture/playback path. Device firmware, audio routes and app backends still affect behavior; calls, protected audio and every system sound are not universally covered. Reported emulator results are not a universal device guarantee. JL Mod short-sound/soundbank behavior remains a known limitation.
