# v0.8 IME Design QA

- Reference: `/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/万能语音输入法-v0.8-效果图/三栏结构-细节修正版.png`
- Rejected device capture: `/storage/emulated/0/Pictures/Screenshots/Screenshot_2026-10-13-21-06-31-25_ae1f93045b87aacac90efbb155289789.jpg`
- Candidate APK: `/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/Universal-Voice-Input-v0.8-dual-core-debug.apk`

## Rejected implementation findings

- P0: Android default Button elevation, padding and card styling dominate the keyboard.
- P0: Manual “完成” exposes the ASR engine finish phase to the user.
- P1: 336dp panel and oversized rows make the fallback keyboard visually heavy.
- P1: Live speech text is not the visual center.
- P1: T9 key labels and row structure differ from the selected reference.

## Candidate corrections

- Replaced IME keys with flat TextView controls.
- Removed elevation, default button padding and thick selected fills.
- Reduced portrait panel height to 260dp and landscape to 252dp.
- Speech finalization now starts automatically after 1.5 seconds of detected silence.
- Final speech stays in the central editable area; the right action becomes “上屏”.
- T9 uses four rows and compact two-line labels.

## Verification

The candidate builds and automated tests pass. It has not yet been installed and captured on the device, so visual comparison against the reference cannot be completed.

final result: blocked
