# TermBridge — Architecture (Prompt 01)

## Module boundaries (logical first, Gradle modules only if justified)

Prompt 02 must not create Gradle modules merely to look modular. The
starting shape is a single Android app module with strict internal
package boundaries. A boundary becomes a Gradle module only when prompt
02/05 evidence shows it reduces coupling or build cost (for example, a
native RDP/VNC module in prompts 12–13, which must stay isolated so a
native failure cannot destabilise the SSH core).

| Layer | Responsibility | Must NOT do |
|---|---|---|
| `ui` (Compose) | Screens, navigation, design system, state holders for display only | Talk to a transport directly; hold secrets in saved state or logs |
| `domain` | Use cases, session/command/file rules, validation, confirmation policy for risky actions | Know about Room, Keystore internals, or a specific SSH library |
| `data` | Host/command repositories, persistence, import/export codecs (prompt 11) | Store a secret in plaintext; log a secret |
| `security` | Key management, encryption/decryption, host-key store, passphrase handling, redaction | Decide product policy (e.g. which actions need confirmation) |
| `transport` | Protocol interfaces + implementations behind them: `SshTransport`, `SftpTransport`, `TerminalSession`, `RemoteDesktopTransport` (RDP/VNC, later) | Be referenced by UI directly; silently bypass host-key verification |
| `diagnostics` | Connection diagnostics, non-sensitive error reporting | Include hostnames/credentials/command text in crash reports by default |

## Data flow

1. UI collects host/auth input → domain use case validates it.
2. `security` unwraps the credential only for the duration of the
   connection attempt (passwords/keys held as wipeable buffers where the
   platform and library allow; never written to logs or disk plaintext).
3. `transport` connects through the SSH interface, verifies the host key
   via the `security` host-key store, and only then opens a shell or SFTP
   channel.
4. Terminal bytes flow: transport → terminal engine (prompt 06) → Compose
   renderer. Input flows the reverse way. No terminal content is logged.
5. SFTP bytes flow through streams with progress/cancellation tokens;
   local files go through Storage Access Framework, never through a
   blanket storage permission the app does not need.
6. Sessions own their lifecycle: connect, disconnect, reconnect, and
   background/foreground transitions are owned by the session object,
   not by a screen. A screen disappearing must not silently kill a
   session, and a dead session must not keep a screen claiming it is live.

## Protocol abstraction

Every protocol sits behind an interface with at least: connect, disconnect,
liveness/error state, cancellation, and timeout configuration. The SSH
implementation (sshj, ADR-03) is one implementation of `SshTransport`;
SFTP is obtained from the same authenticated session, not from a second
unauthenticated connection. RDP and VNC each get their own interface
implementation behind `RemoteDesktopTransport` only after their POC gates
pass — until then, their UI entry points are labelled unavailable, never
wired to a stub that pretends to connect.

## Security boundaries

- Keystore holds keys, never ciphertext blobs as a substitute for
  encryption: the master/data key lives in Android Keystore; secret
  values are AES-256-GCM ciphertext with a fresh IV per encryption
  (ADR-05). Key rotation is a prompt-04 design item, not an afterthought.
- Host-key store is separate from credential storage: a host key is
  trust data, not a secret, but changing it is a security event (FR-03).
- Import files are untrusted input: parsed, validated, and previewed
  before anything is stored (prompt 11). A malformed file must fail
  closed, never partially import secrets.
- Backups/exports are encrypted before they leave the app's private
  storage; Android auto-backup must not restore encrypted blobs without
  their Keystore key and silently present them as usable (prompt 04/11
  must decide the backup rule explicitly and document it).

## Lifecycle ownership

- One `SessionManager` owns active sessions; screens observe, they do
  not own.
- Transport work runs off the main thread (coroutines, structured
  concurrency, cancellation propagated to the transport).
- On process death, sessions are dead: the app must re-authenticate and
  re-verify, never resume a session from a saved secret in plain state.

## Build / dependency rules for later prompts

- Every dependency added in prompts 02+ must record: purpose, license,
  maintenance evidence, Android/ABI impact, and size impact. A dependency
  without that record is not added.
- No dependency may introduce an AI/LLM/agent capability, a cloud
  account requirement, or a network call outside the user's explicit
  connection (plus the user's own configured servers).
