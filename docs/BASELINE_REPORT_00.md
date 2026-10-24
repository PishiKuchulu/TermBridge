# TermBridge — Baseline Report, Prompt 00

Date: 2026-10-24. Status: brief only. No feature code has been written,
no build has been claimed, no test has been claimed as passed.

## 1. Actual changes in this phase

- `docs/PROJECT_BRIEF.md` — concise brief derived from the prompt pack
  (vision, non-goals, architecture direction, LOCKED/OPEN/DEFERRED).
- `docs/ROADMAP.md` — phase sequence 00–18 with exit gates and milestones.
- `docs/BASELINE_REPORT_00.md` — this report.
- Private working copies (outside the product tree, never for release):
  - Prompt pack extracted to `../prompt-pack/` (21 files).
  - Internal process documents held outside the product tree
    (never for release; no content copied here).

No Android source, Gradle files, or dependencies have been created yet.
That is intentional: prompt 00 forbids jumping to implementation, and
prompt 02 owns the foundation.

## 2. Repository state (verified)

- No existing TermBridge repository, source tree, or branch was found in
  this workspace. This is a fresh project, not a migration or a fix pass.
- Nothing was overwritten. No working project existed to preserve.

## 3. Process documents (verified, private)

- Prompt pack read in full at index level: `00_INDEX.md`,
  `00_PROJECT_BRIEF_PROMPT.md`, `00_ROADMAP.md`, and prompts 01–03 in
  full; prompts 04–18 headings/roles confirmed. Usage order confirmed:
  brief + roadmap first, then one numbered prompt at a time, stopping
  at each exit gate.
- Internal process documents read: their entrypoint, manifest,
  authority matrix, and output-structure contract. They are applied
  as process only (gates, evidence labels, traceability, artifact
  hygiene). No process-document internals were copied into
  `project/docs/` or into any future app code, per the brief and
  the user's standing privacy rule for this methodology.
- Those documents stay outside the product tree and must never be
  committed to a public repository. The product tree (`project/`) is
  kept separate for exactly that reason.

(Prompt 18 note: this section previously named the private
methodology and its private directory. Both names were removed
before any public push, per audit-17 finding R16. No methodology
content was ever copied here; only the names changed.)

## 4. Environment (verified)

- JDK: OpenJDK 21.0.12.1 available in this sandbox.
- Android SDK: NOT present (`ANDROID_HOME` / `ANDROID_SDK_ROOT` empty).
- Gradle/Android build in this sandbox: known environment limitation
  from prior work in this workspace — the Gradle daemon does not stay
  alive here, so a local Android build cannot currently be verified
  here. This is recorded as a limitation, not worked around with a
  fake build claim.
- Device/emulator for manual checks: NOT available in this session.
- Consequence for prompt 02: the foundation must be built so its build
  is reproducible elsewhere (documented commands, pinned versions, CI),
  and any build claim in this sandbox must be labelled NOT RUN until
  a capable environment or CI run provides evidence.

## 5. Commands executed and results

- `unzip -l` on the user upload: OK. Prompt pack: 21 files, 52,480
  bytes listed. (Prompt 18 note: a second, private upload was
  also listed here originally; its hash and description were
  removed before any public push, per audit-17 finding R16.)
- SHA-256 of the prompt-pack upload (for traceability, not a
  release hash):
  - TermBridge pack:
    `3abd8531078f5f533882b86f535e17b9c9f961c789db5c4065452bddd66175b7`
- `java -version`: OK, OpenJDK 21.0.12.1.
- No Gradle build, no test run, no emulator run was attempted in this
  phase — therefore no build/test result is claimed.

## 6. Tests: NOT RUN (all)

No tests exist yet. Nothing is reported as passed.

## 7. Blockers, risks, and unknowns

1. **Build environment (limitation, not a product blocker):** no Android
   SDK and no reliable Gradle daemon in this sandbox. Prompt 02 must
   decide the real build route (CI and/or the user's machine) and
   document it; local sandbox claims stay NOT RUN.
2. **Library feasibility (OPEN, prompt 01):** SSH/SFTP, terminal
   rendering, RDP, and VNC libraries are all undecided. No library is
   chosen in this brief.
3. **RDP/VNC conditionality (DEFERRED, prompts 12–13):** if a protocol
   cannot pass its proof-of-concept gate, the required output is an
   evidence-based blocker report, not a fake feature.
4. **Product name/branding:** "TermBridge" is the prompt pack's working
   name. Final branding, package ID, and icon are OPEN until prompt 02.

(Prompt 18 note: a former item 4 recorded a version-label
observation about the private upload. It was removed with the
other private-upload references above, per audit-17 finding R16;
the remaining items were renumbered.)

## 8. Security implications in this phase

None introduced: no code, no credentials, no keys, no endpoints, and no
secrets were created, stored, or logged. The brief locks host-key
verification, Keystore-backed credential protection, secret redaction
in logs, and confirmation for dangerous actions as requirements for
later phases — they are requirements here, not implemented claims.

## 9. Requirements status

- Implemented: none (brief phase).
- Compiled / tested / device-tested / production-compliant: none.
- Next: prompt 01 (requirements matrix, architecture record, feasibility
  and risk register). It must resolve the OPEN library decisions with
  evidence before any protocol code is written.

## 10. Next recommended prompt

Prompt 01 — Requirements, Architecture Decisions, and Feasibility.
Awaiting the user's go-ahead to proceed to 01, per the pack's
stop-at-the-gate rule.
