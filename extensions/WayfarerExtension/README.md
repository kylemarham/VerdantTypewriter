# Wayfarer

A small, open-source [Typewriter](https://docs.typewritermc.com) extension that
adds two **grounded** NPC wander activities. NPCs walk on the ground and pathfind
properly — they do not float or hover.

> MIT licensed. No coupling to any particular server or other extension.

## Activities

### `wayfarer_wander_local`
The NPC wanders to random points within a `radius` of an `anchor`, pausing
between legs. Optionally re-centres on the anchor each cycle (`returnToAnchor`).

### `wayfarer_wander_road`
The NPC walks an ordered list of `waypoints` (a road / path). `loopMode`:

- `LOOP` — restart from the first waypoint.
- `PINGPONG` — reverse direction at each end.
- `ONCE` — walk the path once, then idle at the final waypoint.

## Shared options

| Option | Meaning |
|---|---|
| `speed` | Movement speed multiplier (1.0 = normal walking pace). |
| `pause` | Idle behaviour between legs: `minTicks`, `maxTicks`, `chance` (0–1 probability of pausing at all). |
| `lookAtPlayer` | Face the nearest player (within ~10 blocks) while idle/paused. |
| `pauseOnInteract` | Halt pathing while a player is in dialogue with this NPC; resume after. |
| `returnToAnchor` | Route back to the anchor / first waypoint when a cycle finishes. |
| `radius` | (local) Wander radius in blocks. `0` = stand still. |
| `waypoints` + `loopMode` | (road) The path and how to traverse it. |

## Requirements

Wayfarer requires the **Entity** and **RoadNetwork** Typewriter extensions, and
a `RoadNetwork` that covers the wander area. All movement is delegated to
Typewriter core's own navigation + GPS layer, which is what guarantees correct
grounding (gravity + block collision). If no road network is assigned the NPC
simply stands grounded and idle — it will never float.

## Why this exists / how it grounds

A naive wander implementation that hand-rolls a navigator and linearly moves an
entity toward a target tends to make the NPC hover: with no gravity / collision
and a target Y that was never resolved to the walkable surface, the entity
slides through the air. Wayfarer avoids this entirely:

1. Every target X/Z is **ground-snapped** to the highest motion-blocking
   surface before navigation.
2. All actual movement is performed by Typewriter core's
   `NavigationActivity` + `PointToPointGPS` over a `RoadNetwork` — the same,
   proven engine path the built-in `patrol_activity` / `random_patrol_activity`
   use. Hydrazine pathfinding applies gravity and block-collision physics.

Built and verified against Typewriter core **0.9.0**.

## Building

This module lives inside a Typewriter monorepo checkout because the extension
SDK is consumed as Gradle composite-build projects. Build with:

```
./gradlew :WayfarerExtension:shadowJar
```

The shaded jar is dropped in `build/libs/`. Install it into
`<server>/plugins/Typewriter/extensions/` alongside the Entity and RoadNetwork
extension jars, then restart the server (extension changes require a restart,
not a reload).
