# ADR-01 — Language and UI: Kotlin + Jetpack Compose

- Status: LOCKED (direction from the brief; no existing repository
  contradicts it).
- Decision: Kotlin with Jetpack Compose for all new UI; single-activity,
  Compose Navigation for the destinations in prompt 03.
- Rationale: brief direction; Compose is the current Android UI toolkit;
  Kotlin coroutines fit the transport lifecycle in ARCHITECTURE.md.
- Alternatives rejected: Views/XML (more code for the same shell, no
  benefit for a new app); Flutter/React Native (would add a second
  runtime and complicate native RDP/VNC JNI later).
- Verification in prompt 02: Compose BOM + Kotlin version pinned only
  after the build environment can resolve them; versions are NOT locked
  in this ADR until that check runs.
- Open: exact Kotlin/Compose/AGP versions, minSdk/targetSdk (prompt 02).
