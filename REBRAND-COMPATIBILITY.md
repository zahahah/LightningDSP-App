# LightningDSP compatibility boundary

The first-party namespace, Kotlin/Java classes, JNI bindings and wrapper target
are `lightning.dsp`, `LightningDsp*` and `lightningdsp-wrapper`. The root label is
**LightningDSP**; the separate rootless label is **RootlessLightningDSP**. Shared
in-app body copy uses the LightningDSP product name for both variants rather
than duplicating localized strings in flavor overlays.
This fork is version **1.6.18**, shared code **59**, root code **63**. This is a
local integration version, not a claim of newer upstream ThePBone source.

## Identity migration decisions

The root package identity is `com.lightning.dsp`; the rootless package remains
`me.timschneeberger.rootlessjamesdsp`. During an explicit flash of the companion
module, its installer removes the old `james.dsp` package for all Android users
before installing the new root app. The old package's private data is not
migrated. This is intentional and distinct from a same-package signature
mismatch, which still requires explicit volume-key confirmation and uses a
best-effort backup/restore flow. The module also identity-checks, disables, and
deletes the previous `ainur_jamesdsp` module directory; the normal reboot after
installation replaces any active mounts.

## Explicitly retained identities

| Retained entry | Reason |
| --- | --- |
| `com.lightning.dsp` root and `me.timschneeberger.rootlessjamesdsp` rootless application IDs | The root app uses the rebranded package; rootless remains distinct. The companion module performs the documented legacy-package migration during an explicit module flash. |
| `${applicationId}.volume_manager`, provider method/extra names and authorized `moe.chensi.volume` caller | Existing VolumeManager contract; opt-in gate and authorization unchanged. |
| `me.timschneeberger.rootlessjamesdsp.SET_POWER_STATE`, `rootlessjamesdsp.enabled` and `rootlessjamesdsp.ignore` | External broadcast/intent extras remain stable. |
| Old `me.timschneeberger.rootlessjamesdsp.backup.BackupCreatorJob` worker class | WorkManager stores this FQCN in queued/manual/periodic WorkSpecs; a narrow forwarding subclass keeps reflection resolution and original parameters intact without cancellation/rescheduling. |
| Old notification-listener, Quick Settings tile, processor/backup/installer service and receiver component FQCNs | Android persists component grants, tiles and pending intents; small compatibility entrypoints delegate to `lightning.dsp` implementations. Listener registration and settings use the same stable component. |
| Old activity component FQCNs, including main launcher | Manifest aliases target new activities. The launcher filter stays on the old main alias so existing launcher pins survive without duplicate icons. |
| Historical notification channel ID `JamesDSP` | Retain as a deletion target; active channel IDs are unchanged. `LightningDSP` is also removed for intermediate-build cleanup. |
| `me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult` | AutoEQ saves this Serializable array in activity instance state. Preserve the original class shape/FQCN exactly; new code uses a `lightning.dsp` typealias. Profile results themselves are Strings. |
| Existing preference keys, preference-file namespaces and `AppTheme` enum names | Existing DSP settings and explicit theme choices remain readable. |
| `jamesdsp_backup_*` export/import filenames | Existing backup discovery remains compatible. |
| Legacy `libjamesdsp.so` discovery paths and `jamesdsp` CMake backend target | Existing AudioEffect fallback and unmodified bundled rootless backend. New `lightningdsplib.so` paths are probed first. |
| Vendored `JamesDSP*` C ABI, `jdsp_*`, headers and `app/src/main/cpp/libjamesdsp` submodule | Third-party DSP ABI/source and legal provenance are not rebranded. |
| Upstream project/repository/update/store/Crowdin URLs, Google-services package configuration and historical changelogs | Existing endpoints/provenance are not invented or silently redirected. |

Effect UUIDs and native protocol number **3** are unchanged. New root transport
requires the paired `/vendor/bin/ldspctl`, `ldsp.control`, and `LDSPCTL:` helper
reply. Old `JDSPCTL:` replies are rejected, not interpreted as new transport.
Service absence, denied root and invalid replies continue to use the established
legacy fallback. Acceptance/application generations never prove audible DSP.
The bundled rootless backend retains its existing DSP ABI and +/-15 dB clamp.

## Extra Post Gain

Default is off. The exact warning is **Use it cautiously**. Root output gain is
+/-15 dB normally and +/-30 dB only after explicit opt-in. Rootless hides the
switch and always enforces +/-15 dB. Shared engine ingress clamps restored
values before either legacy or native dispatch; provider ingress rejects invalid
values and cannot enable extended mode. Enabling preserves the previous effective
gain; disabling persists the reduced-range gain before the preference broadcast.
Opening preferences does not rewrite gain. No global-mode/device-volume/power
state is altered by this option.

## Validation limits

Source/JVM contracts do not establish on-device audio processing. Validate the
paired native/module contracts separately before distribution. Verify the final
APK package ID and authorized signing certificate; never uninstall or clear data
automatically to resolve incompatible signers. Serialized activity/service/receiver component identities are preserved at the
manifest boundary while implementations use the new namespace. No notification
access grant or tile is reset or recreated automatically. There are no appwidget
providers or custom ShortcutManager definitions in this source; existing launcher
pins resolve through the stable main activity alias. WorkManager rows retain their
IDs, inputs, constraints, tags and scheduling state; the legacy worker class forwards
WorkManager Context/WorkerParameters unchanged to the renamed implementation.
A narrow keep rule protects this serialized worker FQCN if shrinking is enabled.
On-device upgrade/grant/tile behavior still requires separate validation.

## Build signing boundary

Release outputs are unsigned for separate authorized owner signing. Preview
builds explicitly use the Android development/debug signer so CI previews remain
installable; this is not the authorized distribution identity and must not be
presented as an owner-signed compatible update.

The retained upstream update/store endpoints are compatibility/provenance entries,
not approved LightningDSP fork-distribution channels. The fork release/update
endpoint decision remains pending before publication.
