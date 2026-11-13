# Local-DSP Fusion Mode prototype (test 57)

## Intent

Avoid attaching a software session effect to the relay's return track. Process one
native DSP instance inside the authorized root helper and request low-latency PCM
playback instead. Global Mode and Session Mode keep their existing engines.

No playback-rate changes, adaptive pitch correction, periodic route rebuilds, or
intentional PCM dropping are introduced. The policy still excludes UID 0 and the
owning app, and respects the existing per-app exclusion controls.

## Preparation and lifecycle

- Verify the owner package/UID and readable credential preferences.
- Load the APK's native libraries using its own native library directory.
- Reuse the complete existing DSP preference writer, including file-backed
  effects and valid cached benchmark coefficients; reject invalid startup state.
- Warm up a constant native quantum before AudioPolicy registration.
- Publish SESSION only after preparation. The supervisor allows 30 seconds for
  local preparation and track creation, then acknowledges readiness without
  creating a duplicate framework effect.
- Poll preferences/files off the PCM thread (250 ms), using the existing native
  DSP synchronization. A rejected update stops the helper, not a silent bypass.
- Stop PCM, unregister policy, join configuration, then free native DSP.
- Resolve Live Programmable DSP JNI callbacks against their current thread,
  never retain a thread-specific JNIEnv for callbacks on another worker.

## Verification and limits

Host unit tests cover buffer bounds, partial/empty capture assembly, exact sample
ordering, cancellation, failure handling, and structural backend fencing. Android
build verifies the JNI/native sources for packaged ABIs. These do not prove that
a particular device grants FAST, that effects are acoustically equivalent, or
that latency/overflows improve.

The local DSP comes from the app's bundled DSP sources, not the installed HAL
module's binary. Effect/configuration parity (especially Convolver and Live
Programmable DSP) needs real-device testing. Configuration changes still use
native synchronization and may temporarily contend with processing; there is no
claim of lock-free live reconfiguration. The AudioPolicy capture stage remains,
so zero latency is not promised. Secondary-user owner mismatch fails closed.

The test APK is copy-only: do not uninstall, clear data, change audio settings,
or switch modes on the device without authorization.
