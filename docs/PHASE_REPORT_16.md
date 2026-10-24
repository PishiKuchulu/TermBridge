# TermBridge — Phase Report, Prompt 16

Date: 2026-10-24. Phase: 16 — Independent Security Audit.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is an audit phase, not a feature phase and not a fix
pass: NO production code was changed, no new capability,
no new permission, no new dependency, no database version
change (DB stays v4), no RDP/VNC session work, no
UI-language change. Two documents were added:

- `docs/SECURITY_AUDIT.md` — the full audit: method, scope,
  13 findings with file-level evidence, the standing-claims
  verification table, release blockers and risk decisions,
  and the explicit untested areas.
- `docs/PHASE_REPORT_16.md` — this report.

Scope note (divergence from the pack, recorded honestly):
the pack's prompt-16 text asks for fixing verified
critical/high findings within the prompt. The tasking for
this phase overrode that to audit-only, so the audit
record is not written by the same hand that changes the
code it judges. Findings therefore carry recommended fixes
and release decisions, but the fixes themselves are NOT
applied here; the fix pass is a later, separate step that
must re-verify every finding it touches.

Findings: critical 0, high 1, medium 4, low 8 — total 13.
The high finding (H1, command-button quoting on
non-POSIX shells) is a release blocker, stated as such in
the audit (§5), not hidden. No critical finding was
confirmed in code.

Deliberately NOT changed: no production file, no test
file, no build file, no string, no manifest entry, no
Room schema, no RDP/VNC code or text, no UI-language
default (English v1; decision still Farzad's).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check —
  316 strings defined (unchanged from prompt 15; no new
  user-visible text was needed), 315 Kotlin references,
  0 missing (only `app_name` unused in Kotlin, used by
  the manifest); strings.xml and manifest parse as XML;
  grep — no PFP mention in any prompt-16 file and no PFP
  content copied anywhere, no AI wording, no cloud/sign-in
  claims, no `Log.` call and no `println` in main sources
  (verified by direct grep, not assumed), no WebView
  import, no intent filter besides MAIN/LAUNCHER, one
  exported component (MainActivity, launcher only),
  `allowBackup` still false, permissions still INTERNET +
  ACCESS_NETWORK_STATE only, cleartext still forbidden
  (manifest + network security config: system anchors).
- Full-read audit: the security-critical files listed in
  `SECURITY_AUDIT.md` §1 were read line-by-line where it
  matters (crypto, transport, trust policy, button
  execution, backup codec/service, RFB core, repositories,
  paste paths, manifest); the rest was grep-verified.
- Claims verification: 15 standing claims re-checked
  against code (see audit §4): 11 CONFIRMED, 2 QUALIFIED
  (paste confirmation, risky heuristic coverage), 1
  REFUTED for non-POSIX shells (parameter quoting — see
  H1), 1 CONFIRMED with a low gap (SFTP traversal, L3).
- No Gradle build, no test run, no device run, no
  penetration test: NOT RUN / NOT PERFORMED, same
  sandbox reasons as prompts 02–15 (daemon protocol +
  Java HTTPS). No dependency-vulnerability scan was run
  (see M4); no git history exists to audit (no
  repository created).

## 3. Tests added/run

- Added: none. This phase adds no tests, because it
  changes no code; the regression tests for the findings
  are specified per finding in the audit (H1 shell
  quoting tests, M2 trust-deletion test, M3 replace
  atomicity test, L1 paste-dialog test, L2 tunnel
  removal test, L3 rename normalisation test, L4 delete
  transaction test, L7 cause-stripping test, L8
  preview-wipe test) and belong to the fix pass, not to
  this audit.
- Run: none. Pass/fail: NOT RUN (all). No test is claimed
  to have passed, and no audit claim rests on an
  executed test — every finding cites code that was read.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–15 for the whole app).
- Audit-specific limitations: without a build, this audit
  cannot confirm that any audited file compiles (the
  accumulated CI compile risks from prompts 08–15 still
  stand); without a device, Keystore/OEM behaviour, DES
  availability on API 26+, and PBKDF2 cost are still
  assumed; without a server fixture, handshake and
  forwarding behaviour are still assumed. These are
  recorded as untested areas (audit §6), not as findings
  with invented evidence.
- Previews: none changed. No preview signature changed.

## 5. Security properties: proven vs assumed

- Proven by code inspection (not by execution): the
  host-key policy (decision required, post-auth trust
  persistence); Keystore AES-256-GCM with fresh IVs;
  fixed, secret-free error messages across SSH, SFTP,
  tunnel, backup, and RFB paths; fail-closed backup
  parsing (unknown fields, caps, version pinning);
  POSIX button guarantees (mode separation in
  `CommandRunner`, single-quote escaping, secret-scan
  blocking, per-host scoping); env-value encryption and
  write-only handling; tunnel loopback default with an
  explicit exposure flag; RFB no-silent-downgrade rules;
  SFTP path clamping; import never trusting host keys
  or executing commands.
- Proven false / qualified by code inspection: parameter
  quoting on non-POSIX shells (H1, refuted); paste
  confirmation on both paths (qualified, L1); risky
  heuristic coverage on Windows shells (qualified, H1);
  trust survival across host deletion (M2, by design,
  now challenged); import replace atomicity (M3).
- Assumed, not proven: that sshj 0.40.0 negotiates
  strict key exchange by default (M4); that Room
  generates the expected wrappers and schemas (carried
  from prompts 10/15); that DES/ECB/NoPadding resolves
  on API 26+ (prompt 13); that the emulator/CI jobs run
  (prompt 15); that no audited file has a compile error
  (whole-app build never run).
- Documented non-claims and limits: no penetration
  testing performed or claimed; no dependency scan run;
  no git history audited (none exists); no dynamic
  behaviour observed; no finding invented without a
  code location.

## 6. Requirements implemented vs partial vs blocked

- Implemented (docs scope): the full audit record
  (method, scope, findings with evidence, fixes
  recommended, residual risk, untested areas) and this
  phase report — the pack's acceptance criteria for the
  audit document itself.
- Partial, explicitly: the pack also asks for fixing
  verified critical/high findings within the prompt.
  That half is NOT done here, by the tasking override
  recorded in §1: fixes are recommended, not applied,
  and H1/M1/M2/M3/M4 carry explicit release decisions
  instead of silent deferrals.
- Blocked: build, test execution, fixture, device
  checks, dependency scan, dynamic verification (same
  standing sandbox limits; no device/emulator here).
- Not implemented, correctly: any production code
  change; any RDP/VNC session UI; any language default
  switch or values-fa (UI-language decision STILL OPEN
  and still Farzad's — prompt 16 assumes no answer, as
  instructed); any test weakening; any claim of a
  penetration test.

## 7. Evidence for claimed capabilities

- No capability requiring a run is claimed. Claims with
  evidence are: the static checks (§2), the files read
  (audit §1 lists them), the per-finding code citations
  (file + function + quoted behaviour in audit §3), and
  the claims table (audit §4, each row tied to code).
  Everything needing a run is labelled NOT RUN,
  NOT PERFORMED, or VERIFICATION GAP.
- The strongest evidence in this phase is negative and
  checkable: zero logging calls, zero WebView/deep-link
  surface, zero PFP/AI wording, zero accept-all paths —
  each verified by grep over the tree, with the commands
  and outcomes in §2.

## 8. Remaining issues and next prompt

- Prompt 17 — Capability & Claim Audit: its own phase,
  not prejudged here. It inherits this audit's
  refuted/qualified claims (H1 quoting, L1 paste paths,
  risky-heuristic coverage) as items its own verdict
  must not contradict, and the standing open decisions
  (repo + CI push, application ID confirmation,
  UI-language decision, SSH/SFTP fixture, schema-export
  enablement).
- Fix pass (after prompt 17, or as its own step if
  Farzad directs): H1 (blocker), M1, M2, M3, M4
  verification, L1–L8 — each with the regression test
  specified in the audit. The fix pass must not edit
  this audit document's findings; it should add a
  dated remediation note referencing the finding IDs.
- Open decisions carried forward (unchanged):
  repository + CI push, application ID confirmation,
  UI-language decision (still Farzad's), SSH/SFTP
  integration fixture, schema-export enablement for
  future migration tests (prompt-15 decision).
- Continuing autonomously to prompt 17 per the user's instruction.

## Addendum (2026-10-24, prompt 17 — original text above untouched)

Correction to §8: the security fix pass did NOT come
"after prompt 17". It ran BEFORE prompt 17, as its own
step, and is recorded in `docs/SECURITY_FIXES_16.md`
(H1 per-shell quoting + cmd.exe gate, M1 FLAG_SECURE,
M2 trust deletion with the host, M3 import transaction,
M4 sshj strict-kex verified default-on from the 0.40.0
jar, lows L1–L7 fixed and L8 mitigated-in-part).
`docs/SECURITY_AUDIT.md` itself was left untouched by
that pass, so the audit record remains independent of
the code it judged. Prompt 17 audited claims against
the FIXED code.
