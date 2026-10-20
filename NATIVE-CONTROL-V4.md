# Root integration iteration v4

Branch: pixel-test-native-control-v4. The app prefers the named global
jdsp.control service when the module's `/vendor/bin/jdspctl` reports a single
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

Integration build versionName remains 1.6.15; root versionCode is 58 so an
owner-signed update can preserve the existing app data. This version label is
not a claim that the local source tree is a newer upstream ThePBone release.
Never uninstall automatically or clear data to resolve signer differences.

Build and unit-test:

    ./gradlew :app:testRootFullReleaseUnitTest :app:assembleRootFullRelease \
      :app:compileRootlessFullReleaseKotlin -x :app:uploadCrashlyticsSymbolFileRootFullRelease

Read the signing instructions outside the repo; verify package and certificate
before distributing. Lint currently reports upstream translation/resource
errors despite a successful lint task; do not describe that report as clean.
A new native v4 .so AND jdspctl must be compiled/imported into the module before
this client can be validated. No device installation/restart is part of the
source change; no device functionality has been demonstrated with v4.
