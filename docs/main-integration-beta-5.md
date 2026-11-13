# Main-branch integration: 1.7.1-beta-5

This source integration preserves the experimental branch history, including
its original coauthor trailers. No history was squashed or rebased.

- Fusion Mode adds one native local-DSP relay, bounded nonblocking buffers,
  fixed DSP quanta, guarded ownership and live per-app routing exclusions.
- Global Mode and Session Mode remain separate processing choices. Scoped
  compatibility and AAudio-session reuse remain available where supported.
- Existing saved choices are retained. New root installs default to Fusion,
  Volume Manager, per-app DSP buttons and supported media play/pause controls.
- The DSP button master switch gates both on-demand and permanently visible
  buttons. Global Mode displays a disabled setting with an explanation.
- Audio Processing settings are grouped; a theme-tinted vector shortcut opens
  them directly. Both its toolbar and settings icon use the supplied artwork.
- Persistent processing notifications use Audio Processing; a running Fusion
  relay reports Fusion mode relay working. Failures retain diagnostic messages.
- Main-branch Actions uploads only the authorized owner-signed root universal
  APK, its SHA-256 and source commit; no signing credential is an artifact.

The user reported improved Fusion operation on their tested device. This does
not establish universal effect parity or latency across devices and routes.
The bundled local DSP and installed HAL driver remain distinct backends.
Existing lint errors are not claimed to be fixed by this integration.
