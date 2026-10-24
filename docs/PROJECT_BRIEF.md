# TermBridge — Project Brief (Prompt 00 baseline)

Working product name: **TermBridge**. Independent Android app for managing
remote servers. Not a Termius clone, copy, or rebrand: no proprietary code,
branding, assets, or undocumented internals are used. Capabilities are built
from documented protocols, compatible libraries, and original product decisions.

## What it is

A phone-first, security-focused workspace for everyday remote-server work:

- Manage multiple server (host) profiles and store their credentials securely.
- Connect over SSH to Linux/Unix and to Windows endpoints where an SSH
  server is configured.
- Provide a real interactive terminal, not a one-shot command runner.
- Support password and SSH-key authentication, key import, passphrase
  handling, and host-key / fingerprint verification on first connection.
- Transfer files both ways with SFTP where the server offers a compatible
  SFTP subsystem.
- Let users choose local/remote working directories and configure
  environment variables only where technically valid for the login mode.
- Custom command buttons / snippets with three clearly separate behaviours:
  insert text into the terminal, execute one command, or run a multi-step
  sequence — with declared variables/parameters and confirmation for risky
  actions.
- Advanced SSH (tunnels, jump hosts, proxy) only where reliable and testable.
- Remote desktop to compatible Windows systems via RDP and to compatible
  Linux desktops via VNC or another suitable protocol — only after a
  feasibility proof in prompts 12–13. No universal-support claim is made.

## What it is not

- Not an AI / LLM / agent app. No AI features, dependencies, UI wording,
  or roadmap items are to be added.
- Not a cloud product. No mandatory cloud account, mandatory sync, or
  paywall for core functions. Optional sync may be considered later and
  must never become mandatory.
- Not a root tool. Routine supported operations must not require root.
- Not a universal remote-access promise. SSH, SFTP, RDP, and VNC are
  different protocols with different server-side prerequisites:
  - SFTP needs a compatible SFTP subsystem on the server.
  - Windows SSH needs a configured SSH server; RDP needs a compatible
    Windows edition/configuration with remote access enabled.
  - Linux desktop access needs a compatible remote-desktop server/session
    and network configuration.
  - Android cannot arbitrarily set a remote shell's environment for all
    login modes; environment settings are scoped and documented, never faked.

## Architecture direction

- Language/UI: Kotlin + Jetpack Compose (direction from the brief; no
  existing repository contradicts it — see baseline report).
- Keep protocol engines behind interfaces so SSH / SFTP / RDP / VNC
  implementations can be tested and replaced independently.
- Separate UI, domain/use cases, persistence, security/credential handling,
  and transport implementations. Do not create modules merely to inflate
  the architecture.
- Credential protection: Android Keystore-backed encryption where
  appropriate, with a documented threat model and safe key lifecycle
  (to be locked in prompt 04, not invented here).
- Local files via Android's normal permission and Storage Access
  Framework flows.
- No hardcoded credentials, tokens, private keys, endpoints, or secrets.
  Never log secrets or put them in ordinary crash reports.
- Host-key verification is enforced; first-connection trust is an explicit,
  safe flow — never a silent bypass of host-key/certificate checks.
- Destructive, privilege-escalating, or dangerous commands require clear
  user awareness and confirmation.
- Every UI control must be wired to real logic. A screen, placeholder, or
  mock transport is never reported as a working feature.

## Decisions (LOCKED / OPEN / DEFERRED)

LOCKED (implementation-ready, from the brief):

- Platform: Android, phone-first; Kotlin + Jetpack Compose direction.
- No AI/LLM/agent features. No mandatory cloud account or sync.
- No routine root requirement.
- Host-key verification enforced; no silent bypass.
- Secrets never stored in plaintext, never logged, never hardcoded.
- Core functions (hosts, SSH terminal, SFTP, command buttons, scoped
  environment/paths) are the MVP core; RDP/VNC are conditional.

OPEN (must be resolved with evidence in prompt 01, not guessed here):

- SSH/SFTP library, terminal rendering/input approach, credential
  storage implementation, and persistence choice.
- RDP library and VNC library (or alternative Linux-desktop protocol).
- Import/export formats (documented formats only, with preview and
  validation — prompt 11).
- minSdk / targetSdk, dependency versions, and CI setup (prompt 02).

DEFERRED:

- Optional sync design — after core, and only as non-mandatory.
- RDP implementation — prompt 12, only if its feasibility gate passes.
- VNC / Linux-desktop implementation — prompt 13, only if its gate passes.
- Final release packaging — prompt 18.

## Process

Development follows internal process guidelines held outside the
product tree. Those guidelines are private methodology: they are
**never copied into the app, never copied into product documentation,
and never committed to a public repository**. Only a project-facing
compliance summary (if any) may leave the private tree, and only in
the form those guidelines allow.

(Prompt 18 note: this section previously named the private
methodology. The name was removed before any public push, per
audit-17 finding R16. No methodology content was ever copied here;
only the name changed.)

Evidence labels used in every phase report (never interchangeable):

- Implemented / Compiled / Unit-tested / Integration-tested /
  Device-tested / Security-reviewed / Production-compliant.
- If a test cannot run: `NOT RUN` with the reason.
- If a capability is only mocked: `MOCK ONLY`, never advertised as done.

## Phase order

Prompt 00 (this brief + baseline) → 01 requirements/feasibility →
02 foundation → 03 navigation/UI shell → 04 hosts/credentials →
05 SSH → 06 terminal → 07 SFTP → 08 command buttons →
09 environment/paths → 10 advanced SSH → 11 import/export →
12 RDP → 13 VNC → 14 mobile UX → 15 integration/regression →
16 security audit → 17 capability audit → 18 release/handoff.

One numbered prompt at a time. Each phase stops at its exit gate with a
report; the next prompt starts only after that gate is verified.
Prompts 12 and 13 require an evidence-based blocker report instead of a
fake feature if their gate cannot pass.
