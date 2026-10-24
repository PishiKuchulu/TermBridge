# ADR-04 — Terminal rendering: own Kotlin VT engine (scoped), termlib only after a licence check

- Status: OPEN — recommended direction below; final choice in prompt 06
  after a licence/ABI check and a scripted byte-stream test.
- Recommended direction: a purpose-built Kotlin terminal engine in the
  app — VT parser + screen grid + scrollback + Compose Canvas renderer —
  scoped to the VT100/xterm subset real shells and TUIs need (text,
  colours incl. 256-colour, cursor movement, erase, scroll, alternate
  screen, resize). This keeps the core pure-Kotlin (no native ABI risk)
  and keeps the licence surface clean.
- Evidence: no mature, maintained, pure-Compose VT terminal library
  was found as a drop-in in the prompt-01 search. ConnectBot's `termlib`
  exists (libvterm native + Kotlin + Compose layers) and is a real,
  proven terminal stack — but its licence and native build cost were
  NOT verified in this phase, so it is NOT adopted here. Prompt 06 must
  check its licence, NDK/ABI build, and size before it may be used;
  if its licence would force the whole app's licence terms to change,
  it stays rejected and the own-engine path stands.
- Alternatives rejected for MVP: embedding a WebView + xterm.js
  (adds a web runtime, keyboard/input complexity, and a second
  rendering path for a phone terminal); a View-based terminal widget
  (fights the Compose shell for no benefit).
- Verification in prompt 06: scripted VT byte-stream tests (cursor,
  colours, scroll, alt screen, resize), plus a real shell session test;
  a renderer that only handles plain text is MOCK ONLY, not a terminal.
