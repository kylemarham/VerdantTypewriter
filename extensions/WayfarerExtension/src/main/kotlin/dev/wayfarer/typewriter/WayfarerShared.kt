package dev.wayfarer.typewriter

import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.dialogue.speakersInDialogue
import com.typewritermc.engine.paper.entry.entity.ActivityContext
import com.typewritermc.engine.paper.entry.entity.PositionProperty
import com.typewritermc.engine.paper.entry.entity.toProperty
import com.typewritermc.engine.paper.utils.toBukkitWorld
import org.bukkit.HeightMap
import kotlin.random.Random

/**
 * Wayfarer — shared option blocks + the ground-snap helper.
 *
 * Typewriter extension SDK basis: Typewriter core **0.9.0** (engine-paper
 * `com.typewritermc.engine.paper.entry.entity.*`, `EntityActivity`,
 * `ActivityContext`, `TickResult`, `IdleActivity`) + EntityExtension
 * `NavigationActivity` + RoadNetworkExtension `PointToPointGPS`. Verified
 * directly against the matched-set source tree whose `Typewriter.jar` is
 * byte-identical (sha256) to the live server jar. Context7 `/gabber235/typewriter`
 * corroborates the `@Entry` + `GenericEntityActivityEntry` shape (docs v0.8.0;
 * API surface unchanged for activities in 0.9.0).
 */

/**
 * Idle/pause behaviour between movement legs. A field block so both
 * `WanderLocalEntry` and `WanderRoadEntry` expose the same option group.
 */
data class WayfarerPause(
    @Help("Minimum idle ticks to wait between legs (20 ticks = 1s).")
    @Default("40")
    val minTicks: Int = 40,
    @Help("Maximum idle ticks to wait between legs. Coerced to >= min.")
    @Default("200")
    val maxTicks: Int = 200,
    @Help("Probability (0.0–1.0) that the NPC pauses at all before the next leg. 1.0 = always pause.")
    @Default("1.0")
    val chance: Double = 1.0,
) {
    /** Rolls a pause length in ticks, or 0 if [chance] fails this roll. */
    fun roll(): Int {
        if (chance < 1.0 && Random.nextDouble() > chance.coerceIn(0.0, 1.0)) return 0
        val lo = minTicks.coerceAtLeast(0)
        val hi = maxTicks.coerceAtLeast(lo)
        return if (hi > lo) Random.nextInt(lo, hi + 1) else lo
    }
}

/** Traversal mode for [WanderRoadEntry]. */
enum class WayfarerLoopMode {
    /** Walk to the last waypoint, then jump back to the first and repeat. */
    LOOP,

    /** Walk to the last waypoint, then reverse and walk back, repeating. */
    PINGPONG,

    /** Walk the path once, then idle at the final waypoint. */
    ONCE,
}

/**
 * THE GROUNDING FIX.
 *
 * Root cause of the previous floating bug (#219): the old implementation drove
 * a hand-rolled navigator toward a target whose Y was simply the anchor's Y and
 * only tested `!block.isSolid`. When unviewed it linearly interpolated through
 * the air (no gravity, no collision). The target was never resolved to the
 * actual walkable surface, so the NPC hovered.
 *
 * Wayfarer instead:
 *  1. snaps every target X/Z to the highest *motion-blocking* surface Y
 *     (`HeightMap.MOTION_BLOCKING_NO_LEAVES`) before handing it to navigation,
 *     mirroring how Typewriter core's own `FakeNavigation.standingPosition`
 *     places an NPC on `blockY + collisionShape.maxY`; and
 *  2. delegates all actual movement to upstream `NavigationActivity` +
 *     `PointToPointGPS`, which run Hydrazine pathfinding with gravity and
 *     `BlockCollision.handlePhysics`. That is the exact, proven mechanism behind
 *     core `patrol_activity` / `random_patrol_activity` — both of which ground
 *     correctly on this build.
 *
 * Returns null if the world is not loaded.
 */
fun Position.snapToGround(): Position? {
    // toBukkitWorld() is declared non-null but resolves by UUID; if the world
    // isn't loaded this throws — treat that as "can't snap" rather than crash.
    val bukkitWorld = runCatching { world.toBukkitWorld() }.getOrNull() ?: return null
    // Block column must be the FLOORED coordinate, not a toward-zero truncation.
    // `Double.toInt()` truncates toward zero, so for any negative X/Z it lands one
    // block too far toward the origin (e.g. a node `center()`-stored at x=-10.5 →
    // (-10.5).toInt() == -10, but the real block column is floor(-10.5) == -11).
    // That sampled `getHighestBlockYAt` from the wrong column, giving negative-
    // coordinate road nodes a bad ground Y so GPS either failed (NPC idled) or the
    // NPC popped to a wrong height — invisible on the positive side where
    // truncation == floor. Reuse core Typewriter's own `Point.blockX/blockZ`
    // (`floor(x).toInt()`, engine-core Point.kt) — the exact, sign-safe block
    // conversion core uses everywhere, so we never re-derive node identity from
    // rounded coords.
    val surfaceY = bukkitWorld.getHighestBlockYAt(
        blockX,
        blockZ,
        HeightMap.MOTION_BLOCKING_NO_LEAVES,
    ) + 1.0
    // Preserve the Typewriter World (not the org.bukkit.World).
    return copy(y = surfaceY)
}

/** Convenience: snap then convert to a [PositionProperty], or null. */
fun Position.snapToGroundProperty(): PositionProperty? = snapToGround()?.toProperty()

/**
 * True while any viewer is currently in a Typewriter dialogue whose speaker is
 * this NPC's instance/definition. Same detection upstream `in_dialogue_activity`
 * uses (`Player.speakersInDialogue` vs `instanceRef` / its definition), so
 * `pauseOnInteract` halts pathing exactly while the player is talking to *this*
 * NPC and resumes when the dialogue ends.
 */
internal fun ActivityContext.hasInteractingViewer(): Boolean {
    val instance = instanceRef
    val definition = instanceRef.get()?.definition
    return viewers.any { viewer ->
        viewer.speakersInDialogue.any { it == instance || (definition != null && it == definition) }
    }
}
