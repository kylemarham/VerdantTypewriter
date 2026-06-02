package dev.wayfarer.typewriter

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.distanceSqrt
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.engine.paper.entry.entries.GenericEntityActivityEntry
import com.typewritermc.engine.paper.logger
import com.typewritermc.roadnetwork.RoadNetworkEntry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * `wayfarer_wander_local` — the NPC wanders to random ground points within a
 * radius of a fixed anchor, pausing between legs.
 *
 * Typewriter SDK basis: core **0.9.0**. Movement is delegated to upstream
 * `NavigationActivity` + `PointToPointGPS` (the same engine path as core
 * `random_patrol_activity`), so the NPC pathfinds **on the ground** with
 * gravity + block collision. Target points are ground-snapped via
 * [snapToGround] before navigation — see WayfarerShared for the float-bug
 * root-cause writeup.
 *
 * Requires a [roadNetwork] whose graph covers the wander area. If the network
 * is missing/unconnected the NPC stays grounded & idle at the anchor (it never
 * floats) and a warning is logged.
 */
@Entry(
    "wayfarer_wander_local",
    "NPC wanders within a radius of an anchor (grounded)",
    Colors.BLUE,
    "mdi:map-marker-radius",
)
class WanderLocalEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The road network used for grounded GPS pathfinding. Must cover the wander area.")
    val roadNetwork: Ref<RoadNetworkEntry> = emptyRef(),
    @Help("Centre point the NPC wanders around. Y is auto-snapped to the ground.")
    val anchor: Position = Position.ORIGIN,
    @Help("Wander radius in blocks around the anchor. 0 = stand still at anchor.")
    @Default("8")
    val radius: Int = 8,
    @Help("Movement speed multiplier. 1.0 = normal walking pace.")
    @Default("1.0")
    val speed: Float = 1.0f,
    @Help("Idle pause behaviour between wander legs.")
    val pause: WayfarerPause = WayfarerPause(),
    @Help("Face the nearest player (within ~10 blocks) while idle.")
    @Default("true")
    val lookAtPlayer: Boolean = true,
    @Help("Halt pathing while a player is in dialogue with this NPC; resume after.")
    @Default("true")
    val pauseOnInteract: Boolean = true,
    @Help("Walk back to the anchor when wandering finishes / before idling far from it.")
    @Default("true")
    val returnToAnchor: Boolean = true,
) : GenericEntityActivityEntry {
    override fun create(
        context: ActivityContext,
        currentLocation: PositionProperty,
    ): EntityActivity<ActivityContext> {
        return WayfarerActivity(
            roadNetwork = roadNetwork,
            speed = speed.coerceIn(0.1f, 3.0f),
            pause = pause,
            lookAtPlayer = lookAtPlayer,
            pauseOnInteract = pauseOnInteract,
            startLocation = currentLocation,
            legProvider = LocalLegProvider(
                anchor = anchor,
                radius = radius.coerceAtLeast(0),
                returnToAnchor = returnToAnchor,
            ),
        )
    }
}

/**
 * Picks the next wander destination as a ground-snapped random point inside
 * [radius] of [anchor]. When [returnToAnchor] is set, every other leg routes
 * back to the (snapped) anchor so the NPC keeps re-centring instead of
 * drifting.
 */
internal class LocalLegProvider(
    private val anchor: Position,
    private val radius: Int,
    private val returnToAnchor: Boolean,
) : WayfarerLegProvider {
    private var goHomeNext = false

    override fun nextTarget(current: Position): Position? {
        if (radius == 0) return anchor.snapToGround()

        if (returnToAnchor && goHomeNext) {
            goHomeNext = false
            return anchor.snapToGround()
        }

        repeat(6) {
            val angle = Random.nextDouble() * PI * 2
            val dist = Random.nextDouble() * radius
            val tx = anchor.x + cos(angle) * dist
            val tz = anchor.z + sin(angle) * dist
            val candidate = Position(anchor.world, tx, anchor.y, tz).snapToGround()
            if (candidate != null) {
                goHomeNext = returnToAnchor
                return candidate
            }
        }
        logger.fine("[Wayfarer] wander_local: no walkable point found near anchor; idling this leg")
        return null
    }

    override fun isOutOfBounds(current: Position): Boolean {
        val d = current.distanceSqrt(anchor) ?: return false
        val limit = (radius * 3.0).coerceAtLeast(12.0)
        return d > limit * limit
    }

    override fun recoveryTarget(): Position? = anchor.snapToGround()
}
