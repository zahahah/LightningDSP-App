# Root integration iteration v4

Branch: ldsp-rebrand. The app prefers the named global
ldsp.control service when the module's `/vendor/bin/ldspctl` reports a single
writable context. The helper is built with the same vendor-native generated
AIDL V3 binding as the DSP library and runs only through the existing root
shell. No app_process Java bridge, raw transaction codes, Binder stability
relaxation, effect-bound parameter protocol, or new dependency is used.

Native enable is independent from AudioFlinger enable: the app always maintains
AudioEffect.enabled. Prepared FIR and post-gain use global controls where
supported; limiter settings and other effects retain their legacy path.
Accepted, pending and applied are distinct in logs; none proves audibility.
Missing helper/service, root denial, ambiguous contexts and rejected controls
use the legacy fallback. Rootless/plugin variants have no native root client.

Preference writes are serialized on the native worker, never rejected simply
because the caller is Android's UI thread. A queued request is not reported as
applied; the helper's accepted/applied generations provide that distinction.
Rejected writes fall back without replaying superseded queued values. Root
denials are surfaced to the user, and cached non-root shells can be reacquired
after approval in the root manager. The app cannot grant itself root access.

Integration build versionName is 1.6.18; root versionCode is 63 so an
owner-signed update can preserve the existing app data. This version label is
not a claim that the local source tree is a newer upstream ThePBone release.
Never uninstall automatically or clear data to resolve signer differences.

Build and unit-test:

    ./gradlew :app:testRootFullReleaseUnitTest :app:assembleRootFullRelease \
      :app:compileRootlessFullReleaseKotlin

Read the signing instructions outside the repo; verify package and certificate
before distributing. Lint currently reports upstream translation/resource
errors despite a successful lint task; do not describe that report as clean.
This rebrand requires a paired native/module build exposing the new service,
helper and LDSPCTL reply prefix. Protocol 3 and DSP UUIDs remain unchanged.
Do not ship this app with an old helper as though the wire identity were unchanged. Framework attachment remains responsible for the
audio stream lifecycle; seeing an AudioFlinger effect alone does not identify
whether parameter writes used the global service or the legacy fallback.
