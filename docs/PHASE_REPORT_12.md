# TermBridge — Phase Report, Prompt 12

Date: 2026-10-24. Phase: 12 — RDP for Compatible Windows Endpoints.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is a POC-gated phase, and the gate FAILED. By design, that
means the deliverable is an evidence-based blocker report, not a
feature — so the change list is deliberately small, and no RDP
connection code was added anywhere.

Documentation (new):
- `docs/RDP_NOTES.md` — the full POC evaluation and blocker
  report: the gate as specified; what was verified about
  FreeRDP (maintenance, Android port, existence proof,
  no published AAR); the licence inventory status (with a
  correction to prompt 01's shorthand — see §5); the
  integration cost of a native module; the server-compatibility
  list a future feature must meet; the exact failed gate
  conditions in this environment; alternatives considered and
  rejected; and the §7 checklist that would unblock RDP.

Documentation (updated, not rewritten):
- `docs/ADR/ADR-06-rdp.md` — prompt-12 verdict appendix: gate
  failed on environment, not on library existence; licence
  status corrected to unconfirmed-mixed (see §5). The original
  ADR text is untouched.
- `docs/FEASIBILITY_AND_RISKS.md` — the supported-combinations
  line for Windows + RDP now reads BLOCKED after prompt 12,
  pointing at RDP_NOTES.md, instead of "only after prompt 12"
  (which could be misread as "coming").

UI (one screen, honesty update only):
- `ui/screens/RemoteDesktopScreen.kt` — the RDP row keeps its
  UnavailableRow, and is now followed by three text blocks
  (blocker reason, candidate-library note, what-would-unblock
  text). No connect button was added: a button that cannot
  connect would be a decorative control, which this project
  forbids. The VNC row is byte-for-byte untouched — VNC is
  prompt 13's gate, not this phase's.
- Strings: 301 + 3 new desktop strings = 304 total, 0 missing
  references in static check (only `app_name` unused in Kotlin,
  used by the manifest).

Deliberately NOT changed: no build file, no dependency, no
native module, no CMake/NDK configuration, no JNI code, no
Room schema change (database stays at version 4), no host
profile field, no credential handling, no SSH/terminal/SFTP/
buttons/env/tunnels/backup code. No third-party code or binary
was downloaded or committed. Prompt 11's export format is
unaffected: there is no remote-desktop state to export.

## 2. Commands executed and exact results

- Web re-check (2026-10-24) of FreeRDP's current state:
  upstream Android build documentation (Java GUI in
  `client/Android/Studio`, NDK/CMake native libraries, JNI
  bindings `client/Android/android_freerdp.c` +
  `LibFreeRDP.java`; build script fetches OpenSSL, OpenH264,
  libjpeg; default ABIs armeabi-v7a, arm64-v8a, x86, x86_64;
  default API 21+ debug, release configurable since 3.28);
  release history (3.26 Android overhaul, 3.27.1, 3.28 Android
  touch-pointer redesign + RISC-V 64-bit + RAIL support,
  FreeBSD ports listing 3.32.x in 2026Q3); licence labels
  (project: Apache; FreeBSD ports: APACHE20; openSUSE RPM:
  Apache-2.0); no published Android AAR found on any artifact
  repository in this re-check; aRDP 6.4.9 as an Android client
  built on FreeRDP (existence proof, GPL application, not a
  library); Windows host-edition facts (Pro/Enterprise/
  Education host; Home cannot host; NLA on Pro/Enterprise/
  Education; default port 3389).
- Environment checks (exact results): `~/workspace/tools/
  android-sdk/` contains build-tools, cmdline-tools,
  platform-tools and platforms only — no NDK directory found
  by name search; grep over the app tree finds no ndk/cmake/
  externalNativeBuild configuration and no freerdp/rdp code
  outside the desktop screen strings and navigation; no
  repository exists, so no CI run exists; no controlled
  Windows endpoint and no device/emulator is available here.
- Static verification (passed): string-reference check — 303
  Kotlin references, 0 missing (304 strings defined; the only
  Kotlin-unused one is `app_name`, used by the manifest);
  strings.xml and manifest parse as XML; grep — no PFP mention
  in any prompt-12 file and no PFP content copied anywhere, no
  AI wording, no cloud/sign-in claims, no `Log.` call and no
  `println` in new or touched code, no TODO markers in new
  files, no broad storage permission, `allowBackup` still
  false; brace/paren/bracket balance — 0 imbalance in all new
  and touched files under the corrected check (strings
  stripped before line comments).
- No Gradle build, no test run: NOT RUN, same sandbox reasons
  as prompts 02–11 (daemon protocol + Java HTTPS). CI is still
  the first real build/test gate — and for RDP specifically,
  CI is also part of the blocker (no CI job with an NDK exists
  to run the FreeRDP build script).

## 3. Tests added/run

- Added: none. There is no RDP behaviour to test: no session,
  no framebuffer, no input path, no credential flow. Writing
  unit tests for a client that does not exist would be
  decorating the blocker, not verifying it.
- Not added, deliberately: no "RDP manager" stub with mocked
  connection tests. A mocked connection test would be exactly
  the fake the pack forbids ("do not fake RDP").
- Run: none. Pass/fail: NOT RUN (all). No RDP behaviour on a
  device is claimed, and no RDP test is claimed to exist.
- The tests a future unblocked POC must add are listed in
  RDP_NOTES.md §7: lifecycle (connect, disconnect, network
  loss), error, certificate-mismatch, and credential-handling
  tests, plus a supported/unsupported edition list in docs.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–11 for the whole app). For RDP specifically: NOT BUILT
  because the native toolchain is absent here, not merely
  because the app has no CI yet.
- Compile risks to watch on the first CI run: none new from
  this phase. The only Kotlin change is a screen that uses
  existing components (`ShellBanner`, `UnavailableRow`) and
  three new string resources; the string-reference check in §2
  covers it. No new dependency, no native module, no schema
  change — so there is no new CI surface from prompt 12 at all.
- Previews: the existing `RemoteDesktopScreenPreview` still
  compiles against the updated screen (same composable
  signature, no new parameters); it was not run (no build).
- Database stays at version 4 with MIGRATION_1_2/2_3/3_4
  untouched: no RDP host field or table was added, because
  adding schema for a blocked feature would imply it exists.

## 5. Security properties: proven vs assumed

- Proven by construction: there is no RDP code path that could
  mishandle a credential, accept a certificate, or open a
  network connection — the attack surface added by this phase
  is zero. No secret appears in any new text (the screen and
  docs contain no credential, address, or key material).
- Proven by the re-check (documentary evidence, §2): FreeRDP
  is actively maintained; its Android port is real and
  recently improved; it ships no Android library artifact.
- Corrected, not assumed: prompt 01's "mixed (Apache + LGPL
  components)" licence note, attributed to Fedora packaging,
  could NOT be reproduced in this re-check — Fedora queries
  returned generic packaging guidelines, not the freerdp
  spec's licence field. The honest status is: core Apache-2.0
  per the project and multiple distributors; the full
  inventory for the actually-linked set (OpenSSL, OpenH264
  including its patent-licence distinction, libjpeg-turbo,
  optional Kerberos variants, optional FFmpeg LGPL/GPL codec
  path) is OPEN and must come from the pinned source tree at
  integration time. No licence statement for a shipped RDP
  build may be written until then.
- Assumed, not proven: that the upstream Android build script
  works for a pinned 3.x version on a CI machine with the NDK
  (upstream documents it; nobody on this project has run it);
  that the JNI layer from FreeRDP's own Android app adapts
  cleanly to TermBridge's Compose UI (it is app code, not a
  library API, so adaptation cost is unmeasured); that
  FreeRDP's certificate-verification callback can be wired to
  a user-visible trust decision without weakening its defaults
  (a design requirement for integration, not a verified fact).
- Documented non-claims and limits: no RDP connection, no
  desktop rendering, no keyboard/pointer input, no clipboard
  or file redirection, no gateway support, no smart-card
  logon, no Windows Home hosting (impossible by Microsoft's
  edition restriction, not by this app's choice).

## 6. Requirements implemented vs partial vs blocked

- Implemented (code + docs scope): the POC evaluation itself;
  the blocker report (RDP_NOTES.md); the honest Desktop screen
  update (blocker reason + candidate note + unblock checklist
  in the UI, not just in docs); the ADR-06 verdict appendix;
  the feasibility-doc correction.
- Partial: none — a "partial RDP" would be the fake this phase
  exists to prevent. The evaluation is complete; the feature
  is not partial, it is blocked.
- Blocked: the RDP feature in full (build, connection, input,
  rendering, lifecycle, credential handling, tests) — blocked
  by the four environment conditions in RDP_NOTES.md §6, each
  independently sufficient: no NDK here, no CI build, no
  Windows endpoint, no native infrastructure in the app.
- Not implemented, correctly: any RDP client code, any native
  module skeleton, any Kotlin-only "RDP manager", any connect
  button, any mocked connection test, any RDP host-profile
  field, any licence statement for a shipped RDP build.

## 7. Evidence for claimed capabilities

- No RDP capability is claimed. Claims with evidence are the
  static checks (§2), the environment checks (§2), and the
  documentary re-check (§2, sources: FreeRDP's Android build
  documentation, release notes for 3.26/3.27.1/3.28, FreeBSD
  ports listings, distributor licence labels, aRDP release
  notes). Everything needing a run is labelled NOT RUN, and
  the feature itself is labelled BLOCKED in both docs and UI.

## 8. Remaining issues and next prompt

- Prompt 13 — VNC (Linux remote desktop): its own POC gate,
  not prejudged by this verdict. The VNC row on the Desktop
  screen is unchanged and still awaits prompt 13. The same
  discipline applies: a failed VNC gate yields a blocker
  report, not a fake feature.
- RDP unblock checklist (full text in RDP_NOTES.md §7): NDK
  CI job building pinned FreeRDP 3.x for the target ABIs with
  per-ABI size measured; licence inventory from the exact
  source tree with attribution; a minimal JNI bridge +
  framebuffer view demonstrating a real connection to a
  controlled Pro/Enterprise/Education endpoint with NLA and
  certificate-mismatch behaviour; lifecycle/error/credential
  tests; only then a real feature phase.
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH/SFTP integration fixture, connection
  unification (prompt 15), Room transaction for button-child
  replacement (prompt 08 item), form-dialog-level save-error
  surfacing (prompt 08 item), room-testing for the v2->v3 and
  v3->v4 migrations, ViewModel-message localisation (prompt
  14), upload picker starting in the local folder (blocked on
  the OpenDocument contract), on-device SAF picker + encrypted
  round-trip run (prompt 11 item).
- Continuing autonomously to prompt 13 per the user's instruction.
