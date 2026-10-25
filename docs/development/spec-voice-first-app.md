---
title: Voice-first Android session and deployable voice loop
type: feature
created: 2026-10-25
status: done
baseline_commit: d7411ea36cdcd7230d9a659e933b566589222c2f
approval: User explicitly approved the UX proposal and requested implementation and an upgrade APK.
context:
  - docs/design/ux-designs/ux-sonar-2026-10-25-voice-first/EXPERIENCE.md
---

<frozen-after-approval reason="human-owned intent — approved by the user's implementation request">

## Intent

**Problem:** Starting Voxity requires choosing technical modes and managing tasks. Simulated-place users cannot run the automatic voice loop.

**Approach:** One Start enters a personal voice session. Speech expresses goals; orchestration chooses retrieval, human requests, replies and playback. Hide task management while preserving its durable state. Deliver an upgrade APK and the matching current backend.

## Boundaries & Constraints

**Always:** Preserve installed identity, encrypted Token/Linx configuration, original audio and history. Use raw AMap provider POI IDs and three real-place shortcuts. Remove local mock interfaces and demo IDs; distinguish remote selection from physical GPS. Preserve unrelated dirty work. User speech supplies public intent; normal questions remain private unless the user explicitly asks people, shares, replies, or answers an actual invitation. Model choices remain subject to deterministic identity, authorization, freshness and audio rules. Remote-place provenance stays visibly and immutably marked. Phone switching never moves Bob's Modaily pin.

**Ask First:** Destructive data reset, credential rotation, or unrelated external messaging.

**Never:** Replace the five visual modes with a spoken menu; infer continuous public sharing from a casual sentence; fake model/device/production acceptance; clear app data to pass tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected behavior | Error handling |
|---|---|---|---|
| Ask people | Explicit parking request at Modaily | Route once; original reply plays; follow-up targets heard conversation | Clarify missing place only |
| Private query | Ordinary factual question | Retrieve legal sources; offer human request only if needed | Never silently publish |
| Natural answer | Unique actually heard invitation | Plain answer becomes original response | Expired/ambiguous context stays private |
| Manage needs | Status, replay, cancel, resolve | Operate durable relevant request without task UI | Unknown result is reconciled, not resent |
| Participation | Session, today, permanent preference | Correct scoped invite eligibility; own replies unaffected | Old explicit refusals remain |
| Real places | Select three raw AMap POIs | Only caller changes remote place; shared samples require explicit listening | Never mark remote selection as GPS |
| Interruption | Speech, quiet, end, phone, disconnect | Local yielding/stop; bounded recovery; no duplicate sends | True mic closure cannot be voice-woken |
| Long speech | A coherent bounded utterance | Preserve complete semantic turn and original audio | Never publish a truncated first fragment |

</frozen-after-approval>

## Code Map

- `android/.../SonarScreen.kt`, `ExchangePanel.kt`, `MainActivity.kt`, `SonarViewModel.kt`: shell, preparation, permissions, ownership and startup.
- `android/.../ExchangeService.kt`, `SpeechSegments.kt`, `ExchangeCapture.kt`, `LocalSpeechCommands.kt`, `AgentSpeech.kt`, `LinxSession.kt`: capture/control/transcription/playback/recovery.
- `android/.../Models.kt`, `SonarApi.kt`, `TaskStore.kt`: additive session contract and preserved configuration/journal.
- `server/app/exchange.py`, `agent.py`, `remote_places.py`, `tasks.py`, `service.py`: reuse authorization, routing and requests with one real provider identity.
- `server/app/models.py`, `schema.sql`, `storage.py`, `main.py`, `audio.py`: unified contract, additive persistence and bounded audio.

## Tasks & Acceptance

**Execution:**
- [x] Server files above: unified voice-first orchestration, scoped preferences, natural context, task speech, replay and server-hosted samples; one current-version behavior.
- [x] Android shell files above: one Start; preparation keeps binding, privacy and three real-place shortcuts; no ordinary mode/task/assist UI.
- [x] Android runtime files above: voice-first startup, remote-place heartbeat/switching, result actions, invites/replay, local controls, audible failures, bounded coherent long turns.
- [x] Android build configuration: 0.2.0/code6, same package/signing identity; immutable deliverable APK with hash.
- [x] Server and Android tests: exercise matrix including negative privacy/freshness/identity cases and preservation across configuration updates.
- [x] Deployment/docs: inspected live revision, backed up mutable data/env, deployed selected files, verified real endpoints, recorded checks and remaining device acceptance.

**Acceptance Criteria:**
- Given an existing installation, when upgraded and started, then Token, Linx identity/config and selected point remain usable with no reset.
- Given a prepared user, when Start is tapped, then no mode menu appears and normal request/listen/reply/follow-up/cancel operations require no touch.
- Given a registered real AMap POI, when a remote voice session runs or switches places, then only that user changes remote selection and the origin is never GPS.
- Given interruptions or unknown writes, when the session recovers, then no obsolete audio is published or replayed automatically.
- Given one current client/server version, when the protocol changes, then both adopt the new contract without maintaining old device branches.

## Spec Change Log

- User steering: no old-device compatibility, one new version only. Preserve configuration and data rather than old protocol branches.
- User steering: real AMap POI IDs are authoritative; remove local simulation/mock interfaces. Actual AMap queries resolved B0K2KHYN77 (Modaily), B0FFJSBSGR (Qiniu / AI Island building 19 address), B0FFHPHNSH (Huaxia Middle Road / Jinke Road intersection). Remote selection is not physical presence.

## Design Notes

Use one voice-first contract with per-utterance intent, rather than granting every public action indiscriminately. The accepted UX proposal supplies design approval; no repeated plan approval is needed. Backend deployment is necessary for the requested production voice loop and follows the existing authorized server scope. Implement sequentially and review actual changes against the pre-task dirty baseline.

## Verification

- `cd server && uv run pytest -q`: meaningful server regressions and new speech orchestration cases pass.
- `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: JVM, lint and packages pass.
- Isolated emulator instrumentation: render Start/preparation/active states, preserve settings, exercise real HTTP/WAV playback and local interruption paths; inspect screenshots.
- Live server: revision/manifest, authenticated health/catalog/session checks, preserved records/config and HTTP access. Do not publish test speech into real users' feeds.
- Human phone and ESP32 acoustic acceptance remain separately stated unless actually observed.

## Delivery and Review Order

Software delivery is complete: server210, JVM49, isolated Android36 tests passed; matching backend deployed, APK packaged. Start/active/quiet screenshots were inspected. Physical phone overwrite-install, actual Linx, ESP32 two-way speech and car acoustics remain user/device acceptance, not claimed as passed. Detailed results, hashes, deployment and review fixes are in [implementation and delivery](voice-first-20261025.md).

Review order:

1. [Delivery and device acceptance](voice-first-20261025.md), then the approved [experience](../design/ux-designs/ux-sonar-2026-10-25-voice-first/EXPERIENCE.md).
2. [Speech orchestration](../../server/app/voice_first.py), [exchange validation](../../server/app/exchange.py), [remote places](../../server/app/remote_places.py), [migration](../../server/app/place_migration.py).
3. [Start/preparation](../../android/app/src/main/java/com/sonar/app/SonarScreen.kt), [voice runtime](../../android/app/src/main/java/com/sonar/app/ExchangeService.kt), [configuration preservation](../../android/app/src/main/java/com/sonar/app/TaskStore.kt).
4. [Server regressions](../../server/tests/test_voice_first.py), [native runtime checks](../../android/app/src/androidTest/java/com/sonar/app/VoiceRuntimeDeviceTest.kt), [remote-place UI check](../../android/app/src/androidTest/java/com/sonar/app/RemotePlaceDeviceTest.kt).

The pre-existing working tree contains unrelated uncommitted work. It was preserved; no broad commit or reset was made. Review findings and their resolution are retained in ignored local delivery evidence `output/voice-first-20261025/review/`.

## 2026-10-25 实机排障补充

用户授权人工智能岛／七牛园区问答互通，保留原始POI ID并在回执说明园区范围。当前0.2.3与生产audio-trace-023修复写锁等待、界面／语音回执、低电平原声播放；真实手机收听通过，ESP32实际输出仍有问题，不能将played当人耳通过。详见[故障记录](audio-incident-20261025.md)与[当前ESP32交接](esp32-ai-handoff.md)。
