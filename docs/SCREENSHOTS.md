# Captured application screens

These are actual Compose test captures, not concept renders or image-model output.
Source: `d8d4484c75873382dd93e2ba24a8b4ef172c07b2`; CI run
[38991928418](https://github.com/BananaJeanss/solsleep/actions/runs/38991928418).
Labels are synthetic test data. The editor capture shows its visible viewport;
its remaining controls are reached by scrolling. The large-text test creates and
saves an alarm at 200% system text before capturing the screen.

| Screen | Capture |
| --- | --- |
| First alarm, API 37 dark | ![First alarm](screenshots/tonight-empty.png) |
| Scheduled weekday alarm, API 37 | ![Scheduled alarm](screenshots/tonight-alarm.png) |
| Editor viewport, API 37 | ![Alarm editor](screenshots/alarm-editor.png) |
| 200% text, API 37 | ![Large text](screenshots/large-text-alarm.png) |
| First alarm, API 29 light fallback | ![Light theme](screenshots/tonight-light.png) |

## Manual journal milestone

Final qualified source: `bf84199ddb61914f81adc968ac8179018cf35a49`.
[Run 39022166466](https://github.com/BananaJeanss/solsleep/actions/runs/39022166466)
contains actual final journal captures in both device-results artifacts, under
`app/build/outputs/connected_android_test_additional_output/`:

- `journal-night.png`: saved manual report after restoring an earlier correction.
- `journal-editor.png`: tag entry with fixed Save night/Cancel actions above the keyboard.
- `journal-large-text.png`: a real saved entry at 200% system text.

Labels and notes are synthetic test data. These are native app captures, not
image-model output. The earlier inline alarm images above retain their original
source attribution. Copying the latest journal PNGs into this page is pending
workspace reconnection; the authoritative captured files are already in CI.
