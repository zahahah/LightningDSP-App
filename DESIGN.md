# Design
## Source of truth
Active, 2026-10-25. LightningDSP root mixer and floating panel.
Evidence: LightningVolumeComposeUi.kt, LightningDspVolumeCard.kt, mixer Activity,
panel theme/idle policies and existing root tests. No new device screenshots available.
## Brand
Keep the approved blue LightningDSP identity, existing assets and compact rounded tracks.
No rebrand of upstream history or new artwork.
## Product goals
Make the integrated mixer configurable without changing DSP processing or permissions.
Success: theme-readable card text, persisted display order, clear return navigation.
Non-goals: release, installation, native rebuild or audio-chain reordering.
Root startup initializes the app service silently at boot and after the module delay, without
enabling processing/mixer settings. Persistent notification taps open the main DSP page.
## Personas and jobs
Root users adjust streams and per-app volume; organize familiar controls without opening
the floating panel accidentally.
## Information architecture
Main DSP -> top-right mixer -> full mixer. Back arrow returns to main.
Backgrounding the app returns to main on re-entry; temporary permission screens remain usable.
Full mixer: status, per-app toggle, DSP card, built-in streams, search, Active/Inactive groups.
## Design principles
Preserve compact spacing. Separate display order from audio processing order.
Keep overlay interactions stable; no editing in the floating panel.
## Visual language
Existing 48dp rounded volume tracks, 42dp round effects, 58dp cells and 9sp clipped labels.
Text colors follow actual track backgrounds, not ambient theme guesses.
## Components
Full mixer Edit/Done action enables row editing. Hold and drag vertically within one group.
Active and Inactive share one saved app order. The floating panel uses the same order.
In edit mode, More options offers Pin/Unpin. Only pinned rows show a non-clickable 18dp
pin indicator to the left of the track; no extra toolbar or always-visible pin button.
Pinned rows lead each displayed group. Pinned rows can move among peers in their own Active or Inactive group. When inactive
pins are reordered, active pins are promoted ahead of inactive pins in the shared saved
order so an inactive swap cannot leapfrog an active pin. Unpinned moves never cross pins.
Eye button remains available in edit mode even when the DSP card is hidden.
Media has a right-side expand/collapse chevron. Grouping is on by default, collapsed initially;
settings may disable grouping. Saved explicit stream visibility is respected.
Effect long-press drag changes horizontal display order only in the full mixer.
Overlay effect hold still selects the parameter or opens the effect.
## Accessibility
Keep content descriptions and full effect names. Add named move actions where possible.
No drag may accidentally adjust volume or toggle an effect.
## Responsive behavior
Keep vertically scrollable groups and horizontally scrollable effects; drag must work with
off-screen items through scrolling. Overlay remains width/height bounded.
## Background startup
Reuse the existing private root DSP service. Android 34+ uses specialUse; API29–33
retains mediaPlayback. Discovery retries are finite and canceled by STOP/destruction.
Healthy global sessions are retained; stale session0 can be recovered. Module late-load
waits at least ten seconds and sequences app startup after existing guarded HAL recovery.
Service dispatch is not evidence of effect processing. No Activity is launched automatically.

## Interaction states
Persist app order by stable IDs, shared between Active/Inactive and the overlay. Legacy
Inactive ranks take priority when adopting old separate orders, retaining Active-only IDs.
Filtered, hidden and absent entries must remain in the saved order. Inactive pin edits
may change pinned ranks to preserve active-pin priority, but never discard entries.
Observe order/pins as Compose state and derive lists before the lazy content lambda.
Cancel gestures safely on removal/background. Pins default to off and do not force hidden
apps or inactive players into the floating panel.
New installations keep the manager disabled; existing choices remain unchanged.
## Content voice
Natural English labels, LightningDSP names. Preserve Bass/Stereo/Reverb aliases.
## Implementation constraints
Kotlin/Compose and existing SharedPreferences only, no dependencies.
Pure ordering/lifecycle tests plus source wiring checks and real Gradle compile.
Visual/device behavior remains pending until authorized runtime inspection.
## Open questions
- [ ] Device drag/scroll and theme visual validation, owner: runtime verification.

## Root-focused manager settings

- Stream visibility, ringer mode and Do Not Disturb are controlled in the manager, not duplicated in its settings dialog.
- The settings dialog does not expose backend selection, Shizuku permission, accessibility setup or an Open Floating Panel shortcut. The backend and accessibility implementations remain available internally; effective access is Root even when an old Shizuku preference is stored.
- Keep overlay permission, per-app processing, startup, card visibility, system-app filtering and stream grouping settings.
- End the dialog with a Floating Panel Theme section and a button showing System, Light or Dark. The local Material theme overlay shares the floating DSP card's #347FB6 accent without changing neutral app tracks or day/night surfaces.
- Runtime appearance, dragging and root permission behavior still require device verification; host tests are not visual or Android integration evidence.

- Pin indicators and Pin/Unpin actions are visible only in manager edit mode. Floating and normal views keep the pinned-first ordering without showing a pin icon.

## Full backup integration

- Manual and scheduled full backups include a versioned optional `volume_manager/preferences.properties` section. DSP presets stay DSP-only.
- Preserve package-keyed volume/mute/balance/button exclusions/history, enable/startup settings, app/stream/effect order, pins, hide/group/card options and panel theme. Never export runtime ownership or cleanup receipts, UIDs, permissions or Android's current system-volume/ringer/DND state.
- Validate before destructive clean restoration. Old backups without this section do not modify the mixer. Clean replaces portable keys only; merge overwrites supplied keys. Current cleanup guards remain local in both cases.
- Use SharedPreferences.Editor to update live objects, serialize with pending per-app writes, hide the old panel and reconcile enable/start/stop plus refreshed rules through the normal backend path, even if storage reports a partial failure.
- Host codec, Editor and compressed-TAR tests supplement root/rootless compilation. SAF and device service/overlay restoration still require Android validation.

## Floating-panel refinements

- No second surface behind the LightningDSP card: each slider/button paints only its own bounds. Collapsed rows have no extra vertical padding; expanded effects add 8dp below the buttons.
- Optional `media_icon_hold_expand`, off by default, applies only to grouped floating controls. Tap the left Media icon to toggle expansion; while collapsed omit the right expansion chevron, and show the up/collapse chevron when expanded. Preserve the ordinary chevron in the full manager and when Media is hidden (fallback access).
- The icon's click handler is installed once on the base content, not on both contrast-painted layers. Clicking the icon does not seek volume. Include this preference in full backups.
- Root controller registration reports adjustment readiness for its ownership lifetime, rather than tying Android's hardware-key adjustment gate to transient panel visibility. Android performs the step; callbacks must not synthesize a second one. Preserve SystemUI forwarding, safety warnings and owner-checked cleanup.
- ADB currently reports no attached device. Visual comparison and hardware-key behavior require firmware runtime verification; host tests do not prove them.

## Status icon

- Use a 24dp monochrome lightning silhouette as the small service notification icon while DSP power is enabled (or rootless capture is active). Keep disabled-state and warning/backup icons distinct; launcher artwork is unchanged.
- The Volume Manager notification follows the DSP power preference too: lightning while enabled, a crossed-out lightning bolt while DSP power is disabled. Observe/unregister the preference listener with the service lifecycle.
- Root status channels default to silent LOW importance for new installations. Retain the existing channel ID and all existing Android notification choices; never reset a blocked channel or grant notification permissions. Preserve rootless channel defaults.
- Status-bar visibility and rendering depend on Android permissions/channel settings. An enabled icon is not proof of processing frames; device appearance remains unverified.

- The stored `media_icon_hold_expand` key is retained for compatibility, but its visible setting and behavior now use a single tap to expand AND collapse. The expanded up-chevron remains a second collapse method.
- Floating idle timeout is 3.3 seconds after the last interaction (paused while interacting); fade remains 0.5 seconds. Existing focus/touch/hover/accessibility and explicit-close guards are unchanged.

- Floating DSP header uses a 10dp start inset for its 44dp button, aligning its center (32dp) with the other 32dp icons inside 16dp-padded tracks. The button size, expanded effects row and right slider edge are unchanged. Device visual confirmation is pending.

## Manager footer

- Reuse the home page's Material BottomAppBar cradle and FloatingToggleButton in a fixed manager footer, including existing power icon/state selectors. This control changes only the manager opt-in through its existing overlay-permission path, not DSP power or the per-app switch.
- Bind appearance to reported manager state, never an optimistic local toggle while permission is pending. Use current state/callback references across recomposition and reserve navigation-bar space.
- Show the top explanatory enable card only while disabled. When enabled, retain connection/error feedback without duplicating the enable switch. Keep the footer out of the floating panel.
- Android view rendering/cradle sizing and notification/status-bar grouping still require device validation.

## Combined status notifications

- When both foreground services publish notifications, group their original rows under one silent summary icon. Preserve each row's content intent: DSP opens home; Volume Manager opens its own manager page. A single running service remains ungrouped.
- Use the current DSP power preference for both enabled and crossed-out summary icons. Keep channel IDs and user channel settings intact. Rootless behavior is unchanged.
- Reconcile actual Android notification snapshots immediately and after bounded 150/650ms settling delays. Coalesce refreshes; never self-poll. Explicit stopped-service tombstones prevent stale snapshots from republishing a stopped foreground notification, and an actual restart clears its tombstone.
- Host tests cover icon selection, grouping transitions, lifecycle wiring and navigation contracts. SystemUI rendering, grouping and PendingIntent behavior require device verification.

## Manager presentation settings

- Keep the LightningDSP track blue in light and dark themes. An off-by-default monochrome bar preference affects only this track, not effect buttons, DSP power or other app streams. Use theme-aware neutral tracks when opted in, choose readable label contrast separately for each painted region, observe preference changes live, and include the choice in full backups.
- Keep the manager's floating-panel shortcut in the bottom bar's right action slot, preserving the central power button and its permission-aware manager-only state. The floating panel itself has no footer.
- Settings use compact titled sections, a wider bounded window, scrollable content and at most 80% of the current app content height, centered with at least 10% clear above and below.
- No device is attached; actual light/dark screenshots, dialog geometry with Android font/display scaling, FAB cradle and right action spacing remain pending visual verification.
