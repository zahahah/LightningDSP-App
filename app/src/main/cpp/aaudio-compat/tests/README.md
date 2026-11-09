# Native compatibility regression tests

Run from the app repository root:

```sh
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined app/src/main/cpp/aaudio-compat/tests/hook_test.cpp -o /tmp/ldsp-hook-test
/tmp/ldsp-hook-test
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined app/src/main/cpp/aaudio-compat/tests/got_rebind_test.cpp -o /tmp/ldsp-got-test
/tmp/ldsp-got-test
```

The host ELF declarations are a test-only ELF64 ABI shim for macOS. Android
builds use the NDK ELF declarations. Fixtures exercise bounds, malformed dynamic
metadata, SysV/GNU hashes, imported symbol identity, slot/page uniqueness, prior
import modifications, RELRO, writable data, protection failures, CAS conflicts,
rollback, and builder direction/performance observation guards.

The hook tests also verify the loaded-library sweep's path filtering. At runtime,
the Xposed entry performs an immediate sweep and retries every two seconds until
an AAudio import is rebound. This covers app audio libraries that were already
loaded before Vector registered its linker callback, without touching audio
buffers or changing playback from the scan thread.

An import slot may be inside a PT_LOAD whose first or last page extends beyond
the exact segment bounds. The protection transaction accepts that partial page
only when no other PT_LOAD shares it and the actual private mapping has the
expected non-executable protection. ELF metadata and slots are still read only
within the exact segment bounds. A page shared with another segment is rejected,
including the 16 KiB page layout of the examined JL Mod liboboe.so.

The direct AAudio implementation changes five import slots in an eligible user
library. An optional, separate one-slot transaction observes direct
`AAudioStreamBuilder_setSessionId` imports so explicit app IDs are not mistaken
for the default. Except for the restricted Oboe resolver described below, it
cannot observe dlsym calls or calls originating from other libraries;
missing direction/performance setter observations therefore remain unchanged.
All five eager-resolved imports must exist on one nonexecutable data page. Other
layouts and non-ARM64 ABIs intentionally do nothing. Prior GOT modifications are
rejected; this is not a general detector of third-party inline hooks.

A kernel that repeatedly refuses protection restoration cannot be made to
restore permissions by application code. Such a transaction never reports ready,
rolls back whenever possible, and permanently disables compatibility in that
process. No executable page is made writable.

Host tests do not prove emulator stability, latency, audibility, or compatibility
with every Vector/LSPosed release. Device verification is still required.

## OpenSL ES player compatibility

The same opt-in can independently rebind `slCreateEngine` in an eligible ARM64
app DSO. This is a one-slot transaction with the same ELF, imported-function
identity, page protection, CAS, rollback and unsafe-failure checks. It does not
require artificial AAudio imports and can coexist with a complete AAudio plan.

Engine object and engine interfaces are process-local proxies. Every original
method receives its genuine `self`; no platform vtable is modified. The object
callback bridge presents the caller's proxy identity and original context.
Storage for at most 32 engine proxies and 1024 callback registrations is never
freed or reused, preventing late object callbacks from observing a recycled
context. At the engine cap, new engines pass through unchanged; at the callback
cap, registration reports `SL_RESULT_RESOURCE_ERROR`. No lock is held across a
platform or user callback. Players, output mixes and recorders remain genuine
platform objects, including their PCM and buffer-queue callback identities.

For mono/stereo PCM buffer-queue sources targeting an output mix, player creation
adds Android configuration as an optional interface when it is not already
requested. Before returning a successfully created, still-unrealized player, it
requests `SL_ANDROID_PERFORMANCE_NONE`. An amended creation failure with a null
object gets exactly one retry with the caller's original arguments. A nonnull
partial object is returned with the platform failure, never destroyed or retried
by the hook. Missing configuration or a failed setter leaves the original player
usable without claiming processing success. Recorders, URI/MIME sources,
multichannel sources and unsupported descriptors are not modified.

Opt-out is checked on every player creation, including through engines proxied
while enabled. Since the player and configuration interface remain genuine, an
app's explicit performance setter after creation can override this request.
Calls through `dlsym`, non-app DSOs and independently executed subprocesses are
not promised coverage. This experimental addition requests compatible playback;
it does not itself attach DSP, prove FAST was avoided or guarantee audibility.

The OpenSL host test uses the public SLES declarations from an installed Android
NDK, after the host's standard include paths (no copied platform implementation):

```sh
# Set this to your installed NDK's sysroot/usr/include directory.
NDK_INCLUDE=/path/to/ndk/toolchains/llvm/prebuilt/HOST/sysroot/usr/include
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -idirafter "$NDK_INCLUDE" app/src/main/cpp/aaudio-compat/tests/opensl_test.cpp \
  -o /tmp/ldsp-opensl-test
/tmp/ldsp-opensl-test
```

Mocks verify supported/unsupported source gating, optional-interface preservation,
clean-null versus dirty creation fallback, missing configuration, live opt-out,
real player/configuration identity and explicit later override, engine `self`
forwarding, callback identity/context, callbacks during destruction, and bounded
proxy exhaustion. They do not emulate the Android framework's thread scheduling
or prove every possible concurrently reentrant engine callback. The platform's
normal object-lifetime and caller synchronization requirements still apply.

Android reference: https://developer.android.com/ndk/guides/audio/opensl/opensl-prog-notes

## Oboe dynamic AAudio lookup

For eligible app-local audio DSOs, the opt-in compatibility hook can rebind
their `dlsym` import. This includes `liboboe.so`, JL Mod's TinySoundFont and
Sonivox backend DSOs (`libmmapi_tsf.so` and `libmmapi_eas.so`), and ARMSX2's
`libemucore_4k.so` / `libemucore_16k.so`, which statically link Oboe and its dynamic AAudio loader. It substitutes
the existing AAudio construction wrappers only when both the requested symbol
and the resolved address match the expected platform function. Other results,
errors and explicit opt-out are preserved. Caller-relative `RTLD_DEFAULT` and
`RTLD_NEXT` lookups are forwarded with a mandatory tail call to preserve the
original caller address. No system library or global resolver is patched.

The host test checks matching and nonmatching symbols, real `dlerror` behavior,
opt-out, eligible Oboe/JL Mod/ARMSX2 backend DSO names, and special-handle forwarding.
An Android ARM64 build must also verify that the special-handle path compiles to
a tail branch. These checks do not prove that a particular backend resolves its
functions after the hook is installed, that its stream avoids FAST, or that DSP
is audible; device testing is required.

## Pre-start AAudio preparation experiment

Scoped, opted-in low-latency AAudio opens can request session preparation before
returning the newly opened stream. Only an observed NONE session request is
temporarily changed to ALLOCATE; observed positive IDs are preserved and builder
state is restored before fallback. The optional session setter is intercepted
through the existing dynamic resolver, not added as a required GOT import.

The root-flavor provider captures Binder UID and only hints to the existing
controller. Inactive admission requires that exact UID/session in both diagnostic
sources and retains all format, reserved-output, duplicate-effect and control
guards. Deadlines travel across IPC and are rechecked after suspensions.
Caller wait is bounded to 2.2 seconds; unavailable/expired preparation releases
playback rather than blocking indefinitely. This can delay stream startup.
Never-started prewarmed owners expire after 10 seconds. Runtime provider access,
inactive-track visibility, responsiveness and audible JL Mod results require
device testing; host tests do not establish first-frame coverage.

## Experimental process-local AAudio session reuse

An independent, default-off `debug.ldsp.aaudio_reuse_session` option is exposed
under the existing root AAudio compatibility setting. In explicitly scoped
Vector/LSPosed user apps, it pins streams whose effective session request is
`AAUDIO_SESSION_ID_NONE` and whose low-latency request is already converted by
the compatibility hook. AAudio specifies NONE as the default even when the app
does not call `setSessionId`; the host test covers both implicit-default and
explicit-NONE callers. The first successful stream allocates a session; later
eligible streams in that process temporarily request the same ID. Explicit app
session IDs, input streams and normal/untracked builders pass through unchanged.
Builder state is restored, the first allocation is serialized across concurrent
opens, and pre-start preparation is requested once for the shared ID rather
than on every short sound. The older pre-start path remains restricted to apps
that explicitly called the session setter, unless the separate reuse option is
enabled. Direct setter imports are observed through their own optional safe GOT
transaction; if a present import cannot be safely observed, session reuse is
disabled for that process. A failed reused-session open clears the pin and
receives one original request retry.

The purpose is to avoid rebuilding a per-session effect chain when an app closes
and reopens short-lived AAudio streams. This can change how streams from one
process share effects, and Android documents that allocated or specified AAudio
session IDs may increase latency. Keep the option off except for targeted
testing; host tests prove only wrapper decisions, not device latency, sound
quality, safe behavior for every app, or JL Mod stability.
