# Building LightningDSP root

Use Java 17, the configured Android SDK/NDK and the Gradle wrapper. Build without
uploading symbols:

```sh
./gradlew :app:testRootFullReleaseUnitTest :app:assembleRootFullRelease \
  :app:compileRootlessFullReleaseKotlin \
  -x :app:uploadCrashlyticsSymbolFileRootFullRelease
```

The root output is LightningDSP 1.6.18, versionCode 63; rootless is a separate
application, versionCode 59. Application IDs remain `james.dsp` and
`me.timschneeberger.rootlessjamesdsp` pending the user's identity decision.

Use a matching, separately validated companion module. Root access and a
successfully discovered Binder service do not establish applied/audible audio.
Do not install, restart audio services or run module scripts as build validation.

Read the signing credentials/instructions outside the repository before signing;
never copy secrets into the tree, logs or artifacts. Verify final package and
certificate before distribution. Never uninstall or clear data automatically to
resolve signature incompatibility: scoped-storage data can be lost on uninstall.
The [compatibility inventory](REBRAND-COMPATIBILITY.md) records retained identities.
