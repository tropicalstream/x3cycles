# X3 Cycles 🏍️⚡

A synthwave, **isometric** take on **light cycles** for the **RayNeo X3 Pro**,
rendered in OpenGL ES 3.0 as additive neon lines on black (which on the
waveguide floats as light on the world). Ride the grid at constant speed,
leave a light wall behind you, and outlast your rivals — but don't clip a wall.

Built on the X3 suite's proven tech: 640×480-logical HUD, binocular
side-by-side (stereo viewports, auto-on for the glasses), pure-black canvas,
RayNeo detection, high-score/level persistence, and a big bank of
runtime-synthesized sound — zero vendor AARs, zero permissions, zero binary
assets.

## Controls — two, and only two

**Turn left · turn right.** Speed is constant; you only steer 90° at a time.

- Touch/trackpad: **left half = turn left, right half = turn right.**
- DPAD left/right keys also work.
- On the title or after a crash, either turn starts / retries.

## Rules

- Each cycle lays a solid light wall behind it. Hitting **any** wall (yours, a
  rival's, or the arena border) **derezzes** you.
- **Clear a level by being the last cycle riding** — outlast every rival.
- **Level 1 has one rival; each level adds one, up to five**, and every level is
  a touch faster than the last.
- After level 5 the rival count **wraps back to one** — but now **Recognizer
  tanks** join the grid: AI hunters that chase the player and fire. (Another
  tank is added on each further wrap.)
- A **5-second countdown** (with beeps) opens every level.
- **Lives.** You start with **3**. A derez spends a life and **restarts the
  current level** (your score carries over); running out ends the run. You earn
  a **bonus life every 5,000 points**, up to a cap of **5** — shown as pips in
  the bottom-left.
- **Jump power-up.** One pickup is hidden on each level (a bobbing green
  beacon). Grab it and it **auto-fires once** the next time you'd ride into a
  **light beam — enemy or your own** — hopping you clear of it. It won't save
  you from the arena wall/border.

## Sound

A generous synthesized set: engine drone, turn blips, the 5·4·3·2·1 countdown
and GO, derez shatter, rival-down confirm, recognizer cannon fire, level-up
fanfare, power-up chime, springy jump whoosh, 1-up jingle, spawn, warn, zap,
and UI blips.

## Build & install

```bash
cd ~/Projects/x3cycles
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · compileSdk 35 /
minSdk 29 · OpenGL ES 3.0.
