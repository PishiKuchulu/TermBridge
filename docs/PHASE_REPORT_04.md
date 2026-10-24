# TermBridge — Phase Report, Prompt 04

Date: 2026-10-24. Phase: 04 — Host Profiles and Credential Security.

## 1. Actual changes and touched files

New dependencies (all justified by this phase, versions in
`gradle/libs.versions.toml`): Room 2.6.1 (runtime + ktx, compiler via
KSP 2.0.21-1.0.25 — compiler is a build-time tool, never a runtime
dependency), `lifecycle-viewmodel-ktx`, `kotlinx-coroutines-test`
(unit + instrumented tests), `androidx.test:core` 1.6.1 (instrumented
tests). No SSH, network, or crypto library was added — the Keystore
protection uses the platform API directly, per ADR-05 (the deprecated
`androidx.security` wrapper is deliberately not used).

Domain (`domain/`, pure Kotlin, no Android dependency):
- `HostProfile.kt` — profile without any secret value; `AuthType`
  (PASSWORD / PRIVATE_KEY); `CredentialInput` (wipeable buffers).
- `HostValidator.kt` — shape validation only (blank fields, port
  1–65535, hostname characters, username spaces, PEM shape). It
  never checks reachability and never touches a credential value.
- `HostKeyTrust.kt` — pure UNKNOWN / MATCH / CHANGED comparison of
  fingerprint strings. Fingerprint computation belongs to the SSH
  transport (prompt 05); this phase only defines what a stored vs
  presented pair means.
- `SecretRedactor.kt` — redaction helper + `wipe()` extensions.

Security (`security/`):
- `SecretCrypto.kt` — encrypt/decrypt/deleteKey boundary, blob
  treated as opaque by callers.
- `AndroidKeystoreSecretCrypto.kt` — AES-256-GCM, alias
  `termbridge_master_key`, fresh 12-byte IV per encryption, blob =
  IV || ciphertext, no user-auth binding (see lifecycle doc for the
  rationale), no exported key, no disk copy by the app.

Data (`data/`):
- `HostEntity` (`hosts` table) — plaintext non-secret columns +
  `credentialBlob` (encrypted, nullable). `TrustedHostKeyEntity`
  (`trusted_host_keys`, endpoint primary key) — fingerprint strings
  only, no key material.
- `HostDao`, `TrustedHostKeyDao`, `TermBridgeDatabase` v1
  (exportSchema = false, no migration yet — fresh database).
- `HostRepository` — the rule enforcer (rules live here, not in the
  UI): validation before any write; credential encrypted before it
  reaches the database and plaintext buffers wiped in finally
  blocks; passphrase NEVER persisted (only the key PEM is stored,
  Keystore-encrypted, and the passphrase is asked again at
  connection time); edit without a new credential keeps the existing
  blob; changing auth type without a new credential is rejected
  (caught during review — a password blob must never be kept under
  a key auth type); duplicate copies non-secret fields only, with
  no credential; delete removes the profile and its blob, while
  trust records (endpoint-scoped, not secrets) intentionally survive
  profile deletion and are cleared only by an explicit action.

UI (`ui/`):
- `HostsViewModel` / `HostFormViewModel` + factories (manual DI, no
  framework — prompt 02 decision). `TermBridgeApplication` now owns
  the lazy Room database and repository.
- `HostsScreen` — real list from Room (no seed/sample hosts), edit,
  immediate duplicate (name gains "(copy)", no credential), delete
  with an AlertDialog confirmation. The shell banner is gone from
  this screen because CRUD is real; the session-preview and advanced
  entries below the list stay labelled shells.
- `HostFormScreen` — real add/edit form: name/host/port/username,
  auth-type radio (password vs private key), clearly separated
  credential fields (password field / PEM field + passphrase field
  with "never stored" note), notes/tags, per-field validation
  errors, Save writes through the repository and navigates back on
  success. On edit, blank credential fields keep the stored secret.
- Navigation: edit route `host_form/{hostId}` added alongside the
  add route; top-bar title distinguishes Add vs Edit.
- Strings: 78 total, 0 missing references in static check (the only
  Kotlin-unused string, `app_name`, is used by the manifest).

Documentation:
- `docs/SECURITY_KEY_LIFECYCLE.md` — what is stored where, key
  lifecycle (no rotation yet, invalidation behaviour), backup
  (`allowBackup = false`, deliberate) and migration consequences
  (reinstall/new device loses stored credentials; prompt 11 export
  is the future migration path, and no other migration is claimed).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 77 Kotlin
  references, 0 missing; XML parse of strings.xml and manifest —
  OK; grep checks — no shell banner left in Hosts/Form, no
  trust-all/accept-all code in main sources, no hardcoded secret
  (the single grep hit is `password = ""`, the field-clearing line).
- No Gradle build, no test run: NOT RUN, same sandbox reasons as
  prompts 02–03 (daemon protocol + Java HTTPS). CI is still the
  first real build/test gate and still awaits the repository + push
  decision.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally): `HostValidatorTest`
  (7 cases), `HostKeyTrustTest` (verifier 3 cases + redactor 2),
  `HostRepositoryTest` (9 cases: encryption round-trip + blob !=
  plaintext, invalid-port rejection without secret leakage,
  duplicate without credential, delete removes credential,
  edit-keeps-blob, edit-replaces-credential, PEM stored without
  passphrase, auth-type-change rejection, trust round-trip + clear),
  `RoutesTest` (unchanged, still valid with the new edit route).
  Test doubles (`FakeSecretCrypto` — real AES-GCM, in-memory key;
  `FakeDaos`) live in test sources only and are labelled as such.
- Instrumented tests (written, NOT RUN): `KeystoreAndRoomTest`
  (Keystore round-trip, tampered-blob failure, Room host CRUD, Room
  trust upsert/delete) + updated smoke test (Hosts empty state).
  The Keystore test must also run on a real device class before
  prompt 05 relies on it, because Keystore behaviour varies by OEM.
- Run: none. Pass/fail: NOT RUN.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts 02–03).
- Known compile risks to watch on the first CI run (not claims):
  KSP + Room code generation, the new `host_form/{hostId}` route
  pattern vs the plain `host_form` route, and Compose
  `collectAsState` on the ViewModel StateFlows. If CI flags any of
  these, the fix belongs in this phase's files, not in a later
  prompt's scope.
- Previews for Hosts/Form were removed with the shell: they now
  require a ViewModel, and faking a repository in main-source
  preview code would be worse than no preview. Their states are
  covered by the smoke test + repository tests instead.

## 5. Security properties: proven vs assumed

- Proven by construction (code, not by a run): no plaintext secret
  column exists in the schema; the only credential path goes through
  SecretCrypto; duplicate/delete/edit rules are enforced in the
  repository, not the UI; no trust-all verifier exists in main code.
- Proven by tests (once CI runs them): encryption round-trip,
  blob != plaintext, duplicate/delete/edit semantics, auth-type
  confusion rejection, redaction, trust comparison.
- Assumed, not proven: that the platform Keystore on the user's
  device behaves as documented (needs the instrumented run);
  that Room generates the DAOs correctly (needs the CI build);
  that GCM tampering fails on device (instrumented test, not run).
- Documented residual limitation: Compose text fields hold Strings,
  which cannot be wiped; field strings are cleared on save, but a
  String copy may linger until garbage collection. The repository
  wipes its own CharArray/ByteArray copies. This is stated in the
  lifecycle doc, not hidden behind "all secrets are wiped".

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): host CRUD persisting via Room (schema
  + repository + UI), password + private-key credential references
  with encrypted-key passphrase handling (not persisted), host-key
  trust records + verification semantics, edit/duplicate/delete
  with confirmation, per-host credential isolation (no shared
  credential object; each host has its own blob), validation with
  per-field errors, redaction.
- Partial: persistence "across app restart" is implemented by Room
  but not demonstrated by a run (no CI/device yet). The trust flow
  UI (first-connection fingerprint display + accept/reject) is
  prompt 05, not this phase — this phase stores and compares
  fingerprints; it does not present them.
- Blocked: build, test execution, device verification (see §2/§4).
- Not implemented, correctly: any network connection, any
  connection test, any host-key fingerprint computation, any export
  (prompt 11), any key rotation.

## 7. Evidence for claimed capabilities

- No connection capability is claimed. The only claims with
  evidence are static (file/schema/code checks listed in §2) and
  the test list in §3. Everything that needs a run is labelled
  NOT RUN.

## 8. Remaining issues and next prompt

- Prompt 05 — SSH Connection and Session: sshj integration, first-
  connection trust flow UI (UNKNOWN / MATCH / CHANGED presentation),
  session lifecycle, and the terminal placeholder becoming a real
  terminal input/output surface (terminal engine itself is prompt
  06 per the pack's split — confirm against prompt 05's text at
  the gate).
- Open decisions carried forward: repository + CI push, application
  ID confirmation, UI-language confirmation (before prompt 14).
- Awaiting the user's go-ahead for prompt 05, per the pack's
  stop-at-the-gate rule.
