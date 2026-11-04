# Proposal R-09 — UI language default (owner decision)

**Status: PROPOSAL ONLY. The one-line decision is at the
top; the reasoning and the "support both fully" plan
follow.**

## The one-line decision requested

**Default: follow the system locale; the app ships English
(strings.xml) and Persian (values-fa) resources, and an
in-app picker (System / English / فارسی) overrides it.**

## Where things stand

- English is the source language (values/strings.xml).
- Persian (values-fa) exists and grows with every feature
  since the keychain work; item Q-05 completes the full
  Persian pass, after which both resource sets are total.
- Android falls back per string, so a partial translation
  never breaks the UI — but Q-05 removes the partial state.
- The app currently has NO language picker and no
  per-app-locale wiring: the UI language is whatever
  Android resolves from the system locale.

## What "support both fully" costs

- **Picker + persistence:** a Settings choice stored like
  any other preference; applying it via AndroidX AppCompat
  per-app locales (`AppCompatDelegate.setApplicationLocales`)
  or, without AppCompat, a manual locale wrap in
  `attachBaseContext`. The AppCompat route is the
  supported one and also surfaces the choice in Android
  13+'s system per-app language settings. ~1–2 days,
  including recreation behaviour tests.
- **RTL:** Compose handles layout direction from the
  locale; the screens were built direction-agnostic, and
  Q-04/Q-05 verification includes an RTL pass with
  screenshots.
- **No content exceptions:** dates/numbers in the UI use
  locale-aware formatters (already the case for the
  last-connected stamp); log files, exported JSON keys and
  protocol text stay English always, by design.

## Options

1. **System default + picker (recommended, above).**
2. **English default + picker.** Every first run is
   English until changed; simpler support story, worse
   first impression for Persian users.
3. **Persian default + picker.** Mirrors option 2 for the
   other audience.

## Recommendation

Option 1: the audience is bilingual in practice, the
system already knows the user's preference, and the
picker covers the exceptions (a Persian system with an
English work context, and vice versa).

## What approval unlocks

The chosen line is recorded in the traceability file and
the picker lands with Q-05 (the full Persian pass) in the
release group — the two are natural partners.
