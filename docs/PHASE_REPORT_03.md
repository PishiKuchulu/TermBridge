# TermBridge — Phase Report, Prompt 03

Date: 2026-10-24. Phase: 03 — Navigation, Design System, and Screen Shell.

## 1. Actual changes and touched files

- `gradle/libs.versions.toml` + `app/build.gradle.kts` — one new
  dependency, justified by this phase: Material Icons Extended, for
  navigation/screen icons. No protocol, persistence, or network
  library was added.
- `ui/theme/Spacing.kt` — single spacing scale (8/16/24dp, 48dp
  minimum touch target) used by every screen.
- `ui/theme/Theme.kt`, `Type.kt` — unchanged from prompt 02 (neutral
  Material 3 defaults). A brand/terminal palette is deliberately NOT
  invented here; prompt 14 owns visual refinement with device checks.
- `ui/components/ShellComponents.kt` — `ShellBanner` (honesty banner
  that must stay until a feature is real), `EmptyState`,
  `PrimaryAction`/`SecondaryAction` (48dp minimum), `UnavailableRow`.
- `ui/navigation/Routes.kt` — 10 routes, all unique; bottom-bar set
  is a subset of the registered set.
- `ui/navigation/TermBridgeNavGraph.kt` — one Scaffold (top bar with
  back when a previous destination exists, bottom bar with 5 primary
  destinations), one NavHost, start destination Hosts. Content uses
  Scaffold padding (safe insets) and vertical scrolling per screen,
  so portrait and landscape show full content without clipping.
- Screens (each with a Compose `@Preview`, each carrying its shell
  banner where its feature is unimplemented):
  - `HostsScreen` — empty state only, no fake rows; Add → form,
    session preview → session shell, advanced → advanced shell.
  - `HostFormScreen` — name/host/port/username fields with
    in-memory typing only; Save disabled until prompt 04 storage.
  - `SessionScreen` — dark terminal placeholder, Connect disabled.
  - `FilesScreen`, `CommandsScreen` — empty states, nothing runs or
    transfers.
  - `AdvancedScreen` — tunnels/jump/proxy, all marked Unavailable.
  - `RemoteDesktopScreen` — RDP/VNC prerequisites explained, both
    marked Unavailable.
  - `SettingsScreen` — navigation only (Diagnostics, About); no
    toggles, because a non-persisting toggle would be decorative.
  - `DiagnosticsScreen` — only real device-readable values (model,
    Android version/SDK, app version string, "sessions: none").
  - `AboutScreen` — independence statement + foundation libraries
    only (Compose, Navigation, Coroutines — Apache 2.0).
- `MainActivity.kt` — now hosts `TermBridgeApp`; the prompt-02
  foundation screen was deleted (no stale reference remains).
- `res/values/strings.xml` — all user-visible text via string
  resources (57 strings, 0 missing references in static check).
  UI language decision for v1: English, because this is a technical
  server tool and English strings keep the domain vocabulary exact;
  localisation is OPEN to prompt 14, and centralised strings make
  that a translation pass, not a rewrite. If the user wants Persian
  first, that decision should be made before prompt 14, not after.
- Tests: `RoutesTest` (JVM: uniqueness, start destination
  registered, bottom-bar subset) and an updated instrumented smoke
  test (Hosts shell visible). The old foundation-screen test was
  updated, not left asserting deleted UI.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 56 Kotlin
  string references, 0 missing (the 1 "unused" string, `app_name`,
  is used by the manifest, which the script did not scan);
  route-reference check — all 10 routes appear in the graph;
  wording scan — no AI wording, no cloud-sync claim, no sign-in
  requirement (the only "sign-in" hit is the sentence saying no
  sign-in is required); stale-reference scan — no FoundationScreen
  or foundation_note remains.
- No Gradle build, no unit test run, no instrumented run, no
  device/emulator screenshot check: all NOT RUN, for the same
  sandbox reasons recorded in prompt 02 (daemon protocol + Java
  HTTPS). Previews exist in code but were not rendered here.

## 3. Tests added/run

- Added: `RoutesTest` (3 assertions groups), updated smoke test.
- Run: none (see §2). Pass/fail: NOT RUN.
- Previews: 10 screen previews + component previews in code,
  NOT RENDERED here.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompt 02; the CI
  gate still awaits a repository + push decision).
- Device checks: NOT RUN (no device/emulator here). Overflow,
  clipping, and vertical-text checks therefore remain open until
  prompt 14's device run, even though the shell is built to avoid
  them (scrollable content, wrapping text, safe insets, 48dp targets).

## 5. Security implications and dependency/licence decisions

- One dependency added: Material Icons Extended (Apache 2.0, same
  family as the rest of the Compose stack). No network, storage,
  crypto, or protocol dependency was added.
- No secret, hostname, credential, or command text appears in any
  screen, preview, or diagnostics output. Diagnostics shows only
  device/app facts.

## 6. Requirements implemented vs partial vs blocked

- Implemented (shell scope): navigation graph, design tokens,
  shared shell components, all 10 destinations reachable, back
  navigation via top bar + bottom bar, empty/loading-free honest
  states (no fake loading spinners for features that do not exist).
- Partial: the shell is written but uncompiled/unrendered here —
  its exit gate ("screens navigate, UI responsive, no overflow")
  is NOT marked passed until a build + device run proves it.
- Blocked: build, test execution, device screenshot checks (see §4).
- Not implemented, correctly: every protocol feature, host storage,
  and any settings behaviour — all still behind their banners or
  disabled controls.

## 7. Evidence for claimed capabilities

- No transport or feature capability is claimed. The only claims
  with evidence are static: file list, string/route reference
  checks, and the absence of forbidden wording in the scanned
  sources. Navigation behaviour itself is claimed only as code
  structure, not as a verified run.

## 8. Remaining issues and next prompt

- Prompt 04 — Host Profiles and Credential Security: host CRUD,
  Keystore-backed credential model, host-key trust design, and
  persistence tests. This is what turns the Hosts shell and the
  disabled Save button into a real feature.
- Open decisions carried forward: repository + CI push (first real
  build proof), application ID confirmation, UI-language
  confirmation (English v1 vs Persian first, before prompt 14).
- Session-aware back behaviour (do not kill a live session with a
  plain back press) is a prompt-06 requirement, noted in the
  NavGraph comment; it is not needed while no session exists.
- Awaiting the user's go-ahead for prompt 04, per the pack's
  stop-at-the-gate rule.
