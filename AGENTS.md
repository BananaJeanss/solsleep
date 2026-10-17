# SolSleep implementation rules

- Read PLAN.md and STATUS.md before choosing the next milestone.
- No image-generation models or AI-generated raster imagery in any project asset.
  The user explicitly requires a cute sleeping Android robot logo. Keep artwork
  editable as SVG/code/Android vectors, with attribution and monochrome icon.
- Code is GPL-3.0-or-later; brand artwork has separate attribution in NOTICE.
  Do not add proprietary SDKs without resolving license compatibility.
- Saved alarms/actions must survive process death. Never replace exact scheduling
  with WorkManager or a network-dependent wake-up path.
- Preserve original deadlines and ringing starts in recovery. Stale/repeated
  callbacks and actions must be harmless.
- Never silence an alarm until a requested snooze is durably scheduled.
- Never destroy/reset an alarm or future health database to recover from an error.
- Keep alarms independent of future credential-protected health initialization.
- No pretend tracking, fabricated sensor data, inactive controls, or unsupported
  claims. Never call estimated stages physiological measurements.
- Use current Android/Compose APIs, edge-to-edge/adaptive layouts, externalized
  strings, accessible controls, and useful missing-permission states.
- Add meaningful domain/storage/platform regression tests for behavior changes.
  Do not hide failures with broad lint suppression, disabled tests, or fake APIs.
- Run appropriate checks. Record commands, counts, device/API, limitations, and
  remaining physical-device gates in STATUS.md.
