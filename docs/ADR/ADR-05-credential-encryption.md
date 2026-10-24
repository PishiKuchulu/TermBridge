# ADR-05 — Credential encryption: platform Keystore + AES-256-GCM, no deprecated wrapper

- Status: LOCKED direction; key lifecycle details OPEN to prompt 04.
- Decision: keys are generated in Android Keystore; secret values are
  encrypted with AES-256-GCM with a fresh random IV per encryption
  (IV stored alongside the ciphertext); decryption happens only for
  the duration of a connection attempt. Passwords/keys are held in
  wipeable buffers where the platform/library allows and are never
  logged.
- Evidence: platform Keystore APIs are the current Android
  recommendation; the Jetpack wrapper `androidx.security:security-crypto`
  (EncryptedSharedPreferences / EncryptedFile / MasterKey) is deprecated
  in 1.1.0 in favour of direct platform Keystore use, so it is NOT used
  for new code. DataStore + Tink is a known alternative, but direct
  Keystore + GCM avoids adding Tink's dependency surface for MVP;
  prompt 04 may revisit this only with a concrete reason (e.g. a
  Keystore failure mode on a target device class).
- Rejected: plaintext storage; obfuscation as "encryption"; a hardcoded
  app-wide key; storing the key next to the ciphertext in the same
  plain file.
- Open in prompt 04: key alias/versioning and rotation policy; behaviour
  when the Keystore key is lost (e.g. device restore without the key);
  auto-backup rule for encrypted blobs; biometric/user-auth binding
  (only if it does not lock users out of their own hosts — a prompt-04
  decision, not assumed here).
