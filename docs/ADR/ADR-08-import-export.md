# ADR-08 — Import/export format: documented formats only, decided in prompt 11

- Status: OPEN to prompt 11.
- Direction (not a locked format): host profiles export as a versioned
  JSON document, encrypted with a user passphrase before it leaves
  private storage; keys import only from documented formats, validated
  and previewed before storage.
- Formats to evaluate in prompt 11 (support is decided there, not here):
  OpenSSH private-key formats (incl. OpenSSH-key-v1, Ed25519/RSA/ECDSA),
  PEM/PKCS#8; PuTTY (.ppk) only if a maintained parser exists and its
  licence fits — otherwise explicitly unsupported. Profile import from
  other apps' formats is NOT assumed.
- Non-negotiables for prompt 11: preview-before-import; malformed
  files fail closed; export round-trip test; secrets never leave in
  plaintext; the passphrase KDF and cipher are named in prompt 11's
  report (not guessed here).
