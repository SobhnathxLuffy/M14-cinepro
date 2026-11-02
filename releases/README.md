# M14 CinePro — Release Artifacts

| File | Build | Commit | Date |
|---|---|---|---|
| `M14CinePro-v3.1-r2-debug.apk` | debug (minSdk 28, targetSdk 34), `assembleDebug` | `fd5149e` (QA Round-2 remediation on top of V3 `46e1cab`) | 2026-11-02 |

**Install:** `adb install -r M14CinePro-v3.1-r2-debug.apk`
(or: `git pull && cd m14_cinepro && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`)

**This build contains the QA Round-2 remediation** — see `QA_ROUND2_REPORT.md`:
- HS-session failure now always falls back to a working regular session, and the encoder
  timeline is re-stamped so fallback takes are real-time (no more 2x-fast clips).
- Anamorphic 1.33x/1.55x/2.0x de-squeeze now visibly stretches the GL preview immediately
  and keeps the true factor (uniform fit).
- Gyroflow `.gcsv` logs are in deg/s (stabilization actually works now).
- Slider drags no longer die mid-gesture; every panel has a reset; media pool is
  thread-safe on large folders.
- Full list: `R2-H1..H7`, `R2-M1..M17`, `R2-L1..L28` in the QA report.
