# UI/UX audit (item Q-04)

**Method (2026-11-04):** the design tokens were audited
where they live (`ui/theme/`), not screenshot by screenshot:
WCAG contrast ratios were COMPUTED from the exact hex
values in `Theme.kt` and `domain/TerminalTheme.kt`
(relative-luminance formula), touch targets were checked
against the component defaults and the spacing token, and
the token inventory was verified as the single source every
screen consumes. The look is the app's own (deep slate +
teal) — nothing here imitates any other product.

## Design tokens (verified present, single source)

- Colours: `DarkScheme` / `LightScheme` in `ui/theme/Theme.kt`.
- Spacing: `Spacing` (small 8 / medium 16 / large 24 /
  minTouch 48 dp) in `ui/theme/Spacing.kt`.
- Shapes: `TermBridgeShapes` (6–28 dp radii).
- Typography: `TermBridgeTypography` in `ui/theme/Type.kt`.

## Contrast — app schemes (all text pairs ≥ 4.5:1)

Dark: onBackground/background 15.26, onSurface/surface
14.23, onSurfaceVariant/surfaceVariant 7.17, onPrimary/
primary 7.63, onPrimaryContainer pair 8.05, onSecondary
pair 6.82, onError pair 6.38, primary on background 9.76.

Light: onBackground/background 16.14, onSurface/surface
17.16, onSurfaceVariant pair 6.52, onPrimary pair 5.32,
onPrimaryContainer pair 13.72, onSecondary pair 6.50,
onError pair 6.54, primary on background 5.00.

**One fix:** `outline` in the dark scheme measured 2.31:1
on surface — below the 3:1 guidance for meaningful
non-text boundaries. Raised 0xFF4A5570 → 0xFF64708C
(3.46:1 on surface). The light outline (3.71:1) already
passed and is unchanged.

## Contrast — terminal themes (foreground on background)

Classic 16.83, Solarized 5.61, Light 15.24, Dracula 13.36,
Nord 9.25, Gruvbox 10.75, Monokai 13.94, High contrast
21.00 — all ≥ 4.5:1 for body text. (Individual ANSI colours
are content, not UI chrome; several are intentionally dimmer
in their upstream palettes.)

## Touch targets

Material 3 components carry the 48 dp minimum by default
(buttons, IconButtons, switches, chips). The spacing token
names it explicitly (`minTouch = 48.dp`). A source sweep
for sub-48 explicit sizes found only non-interactive
decoration: colour swatches (28 dp / 14 dp) inside rows
whose whole row is the target, and a 28 dp section icon.

## Not covered here

Before/after screenshots and TalkBack traversal order are
device work; they are part of `docs/DEVICE_TEST_PLAN.md`
(sections 4 and 9 exercise the affected screens in both
themes and in Persian/RTL).
