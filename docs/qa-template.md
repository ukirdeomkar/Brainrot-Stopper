# QA verification template

Copy this block into the QA issue. Fill every field with real values from the run. A pass
without a command, a commit, and an evidence link is not a pass.

````markdown
## QA pass — <feature / issue>

**Verdict:** PASS / FAIL / BLOCKED
**Repo:** https://github.com/ukirdeomkar/Brainrot-Stopper
**Branch / PR:** `<branch>` / `<PR link>`
**Head commit:** `<full sha>`
**APK:** `<artifact name or path>` (sha256: `<hash>`)
**Environment:** CI API 31 / CI API 36 / VPS KVM API 31
**Emulator image:** `system-images;android-<level>;google_apis;x86_64`
**Test command / CI run:** `<command and/or run URL>`
**Evidence:** `<artifact names, attachment links, or qa-evidence/... paths>`

### What was tested

| # | Flow | Steps | Expected | Actual | Result |
|---|---|---|---|---|---|
| 1 | App launch | Install APK, `am start -W -n dev.brainrotstopper/.app.MainActivity` | Main screen renders, no crash | <observed> | PASS/FAIL |
| 2 | <feature flow> | <adb steps / UI steps> | <expected> | <observed> | PASS/FAIL |
| 3 | Boundary case | <null / empty / zero / wrong type / repeat> | <expected> | <observed> | PASS/FAIL |
| 4 | Error path | <permission denied / invalid input> | <expected> | <observed> | PASS/FAIL |

### Automated checks

- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew :app:connectedDebugAndroidTest` — API 31
- [ ] `./gradlew :app:connectedDebugAndroidTest` — API 36
- [ ] `APK=<path> sg kvm -c './scripts/qa-vps-emulator.sh'` — API 31

Paste the exact result lines, for example:

```text
OK (1 test)          # instrumentation on API 31
BUILD SUCCESSFUL     # unit tests / assembleDebug
```

### Evidence

- Screenshots: `<links or paths>`
- Instrumentation output: `<link or path>`
- Logcat (offending window): `<link or path>`
- Device/build info: `<getprop / CI image>`

### Residual risks

- <e.g. emulator only, no OEM device; permission flows not scripted; feature X untested>
- <e.g. CI emulator flake re-run: first run failed, re-run passed>

### Next action

- <owner and exact next step: fix, re-run, approve, or close>
````

## Rules for the QA issue

1. Run server-side only (CI emulator or VPS KVM emulator). Never ask the owner to test on a
   phone.
2. Reproduce a bug with the smallest deterministic case before the fix, and record the failing
   command output.
3. Keep the QA issue open until the verdict and evidence are recorded. A feature issue is not
   done while its QA child is pending or failed.
4. Attach real evidence; `qa-evidence/` is git-ignored and must not be committed.
5. State residual risks even on PASS. Mobile QA on emulators cannot prove OEM behaviour.
