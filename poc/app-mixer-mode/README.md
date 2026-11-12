# App Mixer Mode — isolated POC design and blocker

**Status: an experimental supervised relay mode is implemented; device routing and effect coverage remain unproven.**

The interface is a third mutually exclusive mode in Audio Processing; existing
per-app power buttons in Volume Manager control its UID selection. This document records an earlier output-routing
hypothesis, not a claim that the mode currently works. Previously saved prototype
preferences are not deleted from user devices.

> Historical note: the analysis below concerns the earlier direct-output-mixer
> hypothesis. The implemented experiment instead uses a LOOP_BACK relay; the
> historical proposed manual gate and its acceptance matrix are not a description
> of current service behavior. Neither path has device validation.

## Question and hypothesis

Can one selected app UID be routed to a distinct AudioPolicy render output mixer on
the same physical sink, while an excluded app continues on the ordinary output; and
can the LightningDSP session-0 effect be attached to that exact render output rather
than the global output mix?

The POC passes only if it proves both *distinct output identity* and *selected-only
effect coverage*. A registered UID rule, policy-mix count, or successful effect
construction alone is not proof. Android's AudioEffect API describes session 0 as
the global output mix and says session-specific insertion is by audio session ID;
its constructor has no policy output-handle argument. [AudioEffect API](https://developer.android.com/reference/android/media/audiofx/AudioEffect)

## Hard blockers

1. **Policy authority:** Android's framework checks `MODIFY_AUDIO_ROUTING` for
   policy mixes that are not loopback/render-only; the Android permission catalog
   lists `MODIFY_AUDIO_ROUTING` as `signature|privileged`. A normal third-party app
   cannot grant itself this permission by declaring it. AOSP documents a special
   MediaProjection case for loopback/render-only policies, but the available evidence
   does not establish that the specific UID-render policy needed here qualifies or
   creates a distinct output. The exact mix flags and target image's registration
   result must be verified first. No permission or SELinux change is proposed here.
   [AOSP AudioPolicyService permission check](https://android.googlesource.com/platform/frameworks/av/+/master/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp)
   · [Android permission catalog](https://developer.android.com/reference/android/Manifest.permission#MODIFY_AUDIO_ROUTING)
2. **No effect-to-output binding contract:** session 0 denotes the global output
   mix; the documented AudioEffect insertion key is an audio session ID. No public
   AudioEffect API accepts the registered AudioPolicy output handle. A hidden/vendor
   API or platform-side effect-chain mapping might exist on a specific build, but
   that must be discovered and demonstrated on the exact target image before it is
   treated as viable. Constructing the existing session-0 LightningDSP endpoint
   would not establish attachment to the selected UID's output.
3. **Runtime evidence unavailable by policy:** this work is restricted to the
   disposable worktree and explicitly forbids installing on a device, registering
   policy, changing live routing, playing audio, attaching an effect, or restarting
   audio services. Therefore there is no safe in-session way to test selected and
   excluded concurrent playback or effect coverage. No such operation was attempted.

## Proposed minimal experiment (manual gate only; not executable here)

Do not run until an authorized owner explicitly schedules a controlled test on a
dedicated, recoverable Android test image and confirms routing/effect permissions.
The gate must remain closed in any app build; there must be no boot receiver,
background service, auto-registration, default-on path, or production integration.

1. Record build fingerprint, API level, app package/UIDs, current route, current
   policy mixes and audio effect chains. Save read-only `dumpsys media.audio_policy`
   and `dumpsys media.audio_flinger` output as baseline. Do not alter the baseline.
2. On a disposable test app / test image, construct one policy with exactly one
   selected UID render mix targeting the existing physical output and keep a second
   known playback app excluded. Registration must be an explicit, visible manual
   action after the user acknowledges the routing warning. If registration fails,
   stop and record exception/status/permission; do not try permission grants or
   fallback routing.
3. Start known, low-volume test tones in the selected and excluded apps concurrently
   only after the gate is open. Capture policy and AudioFlinger state while both are
   active. Compare the selected UID's `portId`/output handle and mix registration
   against the excluded UID and pre-test baseline. The selected path must have a
   distinct output identity on the same physical sink; mere UID matching, a mix
   record, or shared output handle is a **FAIL**.
4. Only if step 3 passes, a separately approved follow-up may attempt to apply a
   disabled/configured LightningDSP effect to the exact output identity. Record
   effect UUID, session, effect ID, control status, chain output handle/port, and
   before/after per-app signal measurements. The selected signal must change by a
   known test parameter while excluded signal and unrelated system audio remain
   unchanged. Session-0 creation without output-chain identity is **FAIL**. Do not
   enable Global or modify any Session-mode setting.
5. Repeat selected/excluded order with selection reversed. Test app-effect-power
   control only if a supported binding is found; default app state is enabled in
   the future UI proposal, but that default must never implicitly register a policy.

### Acceptance matrix

| Claim | Required evidence | Current result |
|---|---|---|
| Selected UID gets a distinct output mixer | Simultaneous selected/excluded playback; distinct active output/port handles in policy + AudioFlinger state; same physical sink | **Not tested — hard blocked** |
| Excluded UID is unaffected | Excluded output identity unchanged and signal unaffected while selected signal is processed | **Not tested — hard blocked** |
| LightningDSP binds to that exact output | Effect chain reports same output identity; selected-only signal change; effect remains disabled until explicit action | **Not tested — API mismatch / hard blocked** |
| Global and Session remain unchanged | Compare persisted settings and active effect sessions before/after; no global/session engine mutation | **Not tested — no runtime actions performed** |

The separate selection UI has been removed. The audio claims in this matrix remain unproven.

## Requirements to unblock

- Target device/image and Android API level/build fingerprint; ensure it exposes
  AudioPolicy UID render-mix routing and a distinct output path for that UID.
- A test harness authorized to register that exact render policy under the target
  image's policy rules (potentially MediaProjection for a qualifying loopback/render
  policy; otherwise platform signature/privileged `MODIFY_AUDIO_ROUTING`). Do not
  put this permission into the regular app manifest as if it were grantable to an
  ordinary install.
- A documented platform/vendor API or demonstrable effect-chain facility that binds
  the LightningDSP engine to the *policy output handle*. Session 0 by itself does
  not satisfy this requirement.
- Permission from the device owner for concurrent playback/effect instrumentation
  in a disposable environment. This artifact grants no such permission.

**API-level note:** AudioPolicy/AudioMix APIs are platform APIs with versioned
behavior; actual UID render routing and effect chain support are device/build
dependent. The app repo has rootless min SDK 29, but that is not evidence that the
required privileged permission or distinct mixer exists on API 29+. Record and
validate the exact API level on the eventual test image before claiming support.

## Rollback checklist (for the separately approved future test only)

1. Stop only the POC test tones.
2. Explicitly release/disable the test LightningDSP effect (if one was attached),
   then close its endpoint; verify its effect chain is absent.
3. Unregister the exact POC AudioPolicy object/mixes and close it; do not invoke
   global audio service restarts.
4. Re-capture `dumpsys media.audio_policy` and `dumpsys media.audio_flinger`; verify
   no POC mix/output/effect remains and routes match the saved baseline.
5. If unregister fails or baseline differs, stop testing and hand the device to its
   owner for recovery. Do not broaden recovery to permission, SELinux, or audio
   service changes without separate authorization.

## Scope and verification record

The separate Volume Manager entry point, preference model, and its unit test have
been removed. Existing per-app DSP controls and Global/Session processing are
untouched. The manual routing experiment is described separately in
`ROUTING-EXPERIMENT.md`; it has not been validated on a device.
