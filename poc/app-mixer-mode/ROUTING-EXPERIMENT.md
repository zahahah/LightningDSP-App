# App Mixer Mode — manual routing experiment

The manual harness remains available, and its supervised variant now backs the
opt-in experimental App Mixer Mode. The mode shares the existing app DSP power
preferences. Global and Session processing are fenced during its relay handoff.
No device validation has been performed. Build/unit tests do not prove routing,
capture eligibility, selected-only DSP, latency, or reliable cleanup on a device.

## Implemented path

Explicit app UIDs + MEDIA/GAME usage -> hidden AudioPolicy LOOP_BACK-only mix ->
AudioRecord -> bounded PCM16 stereo 48 kHz relay -> one AudioTrack/session -> sink.
The player/session and LightningDSP effect are created before policy registration.
The manual harness uses a fixed **-6 dB diagnostic output gain**. The supervised
mode instead attaches a positive-session engine and waits for the current DSP state. The harness does
not call unscoped ldsp.control commands. It does not save captured PCM to disk or
send it externally. It does not set privileged-capture, capture-policy override,
permission grants, SELinux rules, device affinities or a new preferred device.

Only LOOP_BACK is requested: LOOP_BACK|RENDER would intentionally leave the
original audible alongside the replay and is not appropriate for this experiment.
Actual policy/track coverage MUST still be checked; API success is not proof that
all requested tracks are redirected or that protection policies allow capture.
Denied/unsupported APIs fail; there is no bypass to force protected audio through.

The mix requests only the specified UIDs; actual excluded-app isolation is unverified.
The root relay's UID and system/isolated
UIDs are rejected by argument validation, avoiding capture of its own player.
The manual test accepts 1–16 explicit application UIDs and lasts 5–90 seconds.
Supervised mode accepts at most 1024 and renews its 90-second relay through a full stop/start. Stdin EOF
also stops it; use a supervised owner pipe. Nonblocking reads/writes permit
cancellation; partial writes drain before another capture block is read. Zero
reads/writes sleep rather than busy-loop. A write stalled for two seconds raises an
error and enters cleanup; progress resets the deadline. Negative statuses stop the experiment.

## Preconditions (manual gate)

Use a recoverable test device and low volume. Stop existing LightningDSP audio
processing and keep its normal audio service stopped for the entire experiment.
A bounded AudioFlinger inventory check refuses startup if existing LightningDSP
effects appear, but cannot prevent another service from starting after that check.
Thus this is NOT safe to start concurrently with normal Global/Session processing.
It never stops those services itself, changes their settings, or relaxes security.

Use the signed root APK's classpath with a separately authorized UID-0 app_process.
The process entry point is:

```
lightning.dsp.interop.appmixer.AppMixerRoutingExperiment
    --manual-routing-test --uids=APP_UID,OTHER_APP_UID --seconds=30
    --acknowledge-audio-interruption
```

No arguments print a description and return without AudioPolicy registration.
The root processing service may launch the supervised helper only for the selected experimental mode. No exported service or security-policy changes were added.
Do not feed selected UIDs from untrusted shell input. The UI's saved package list
is deliberately not sufficient to start this privileged manual test.

API choices were checked against AOSP `android16-qpr2-release` AudioManager,
AudioPolicy and AudioMixingRule sources. Hidden APIs vary by release/vendor.
`unregisterAudioPolicy` (synchronous) is resolved before registration; absent APIs
abort before any route mutation. The helper uses the existing privileged context
pattern, without changing hidden-API enforcement; inaccessible APIs abort. No platform signature or permission is
assumed merely from declaration in a manifest.

## Exit/rollback behavior

The `finally` block independently attempts player pause, recorder stop, effect
disable/release, capture/playback release, and synchronous policy unregister.
Cleanup failures are explicit and do not skip remaining cleanup attempts.
`RELEASED` means these calls returned, NOT proof that the device restored its
baseline. Process death/Binder ownership provides platform cleanup, but abrupt
termination/vendor behavior remains untested. Routing registration can briefly
interrupt source playback, including while a failure is being rolled back.

## Required device evidence before product integration

1. Baseline + simultaneous included/excluded tracks, with normal LDSP processing off.
2. Match UID, track, output and patch identities before/during/after the test.
3. Prove selected originals no longer render directly (no double audio), excluded
   playback stays on its normal path, and only the shared player has LightningDSP.
4. Confirm selected signal attenuation while excluded signal is unchanged.
5. Close stdin / allow timeout; verify the policy, capture, relay and effect are gone.
6. Measure underruns, load and added latency. Test speaker, Bluetooth and USB;
   FAST/MMAP, direct/offload, protected audio and non-MEDIA/GAME usages are not
   supported claims. Test route changes and AudioServer/HAL lifecycle separately.

The experimental controller attempts exclusivity, supervises the helper and
synchronizes current DSP preferences; none of the device checks above has been
performed. Route changes and protected, direct/offload, FAST and MMAP paths remain
unsupported or unverified. Do not treat a successful start as coverage proof.

## Supervised handoff

A root helper can also be started with `--supervised --uids=...`. It creates
its output track and prints `APP_MIXER:SESSION <id>`, then waits at most ten
seconds for an owner to attach and fully prepare the DSP effect before sending
`READY`. `STOP`, malformed input, or EOF requests shutdown. The helper remains
bounded to 90 seconds. The app-side supervisor requires an exclusive lease and
holds it until both helper exit and its cleanup acknowledgement; a missing
acknowledgement keeps the lease held rather than claiming rollback succeeded.
The root service supplies the admission lease and current-state effect preparation.
Cleanup acknowledgement is not proof of baseline routing or selected-only coverage.
Inventory failure, more than 1024 UIDs, missing authority, cleanup failure or effect
preparation failure refuses relay startup and is reported; uncertain cleanup keeps
admission fenced. Shared-UID packages cannot be separated: opting out any member
excludes the UID. Isolated/system UIDs and non-MEDIA/GAME usages are not routed. App process death and vendor rollback remain unverified.
