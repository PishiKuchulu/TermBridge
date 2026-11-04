# Proposal T-17 — Active-connection notifications (owner decision)

**Status: PROPOSAL ONLY. No code. This item needs a NEW
PERMISSION, so under the work order's rule 3 it requires
the owner's written approval plus README / DEVELOPMENT /
CAPABILITY_MATRIX updates in the same change.**

## The question

Should a live session (and a running file transfer) be
visible and controllable from a notification while the app
is in the background?

## What it actually takes on Android

- A session that must survive backgrounding needs a
  **foreground service**; Android requires that service to
  show a persistent notification. There is no
  notification-free way to keep the promise "your session
  stays alive".
- On Android 13+ the app must also hold
  **POST_NOTIFICATIONS** (runtime permission) for the
  notification to be visible; the foreground service can
  run without it, but then the user cannot see or control
  it — the worst of both worlds.
- A manifest permission (`POST_NOTIFICATIONS`), a service
  declaration (`foregroundServiceType="dataSync"` or
  `remoteMessaging`-adjacent typing, with the Play-style
  declarations that follow), and a `FOREGROUND_SERVICE`
  permission are the full new-permission surface.

## The justification text (rule 3, ready to reuse)

> TermBridge keeps SSH sessions and SFTP transfers alive
> only while the user is connected. A foreground service
> with a visible notification is Android's mechanism for
> work the user started and expects to continue in the
> background: it shows the connected host, lets the user
> disconnect without reopening the app, and is the only
> thing standing between a backgrounded session and the OS
> killing it silently. The notification shows the host
> NAME only — never terminal content, file names, or
> credentials.

## Options

1. **Decline.** Sessions remain foreground-only in
   practice; the app keeps its two-permission posture
   (INTERNET + ACCESS_NETWORK_STATE). Backgrounding risks
   the OS reaping the process — the current honest state,
   already implied by the keep-awake setting.
2. **Session-only foreground service.** Service runs only
   while ≥1 session/transfer is active, stops with the
   last one. Notification: host name + Disconnect action.
   ~4–5 days including the permission flow and device
   verification (this cannot be proven on an emulator
   alone — it needs the F-04 device plan).
3. **Full status centre.** Per-forward and per-transfer
   notifications too. Scope creep; not recommended as a
   first step.

## Recommendation

**Option 2, if background survival matters to you;
option 1 if the permission posture matters more.** Both
are defensible — this is a product-identity call, which
is why it is yours. What is NOT recommended: shipping the
service without the notification permission flow (an
invisible keep-alive the user cannot audit).

## What approval unlocks

Option 2 gets a mini-plan (service lifecycle tied to the
existing connection holders, notification content rules,
permission-request copy) and the rule-3 doc updates are
drafted in the same PR.
