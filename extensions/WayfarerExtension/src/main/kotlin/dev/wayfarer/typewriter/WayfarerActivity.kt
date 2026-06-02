package dev.wayfarer.typewriter

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.toVector
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.engine.paper.entry.entries.EntityProperty
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.isLookable
import com.typewritermc.entity.entries.activity.LookDirection
import com.typewritermc.entity.entries.activity.NavigationActivity
import com.typewritermc.entity.entries.activity.Velocity
import com.typewritermc.entity.entries.activity.getLookPitch
import com.typewritermc.entity.entries.activity.getLookYaw
import com.typewritermc.entity.entries.activity.updateLookDirection
import com.typewritermc.roadnetwork.RoadNetworkEntry
import com.typewritermc.roadnetwork.gps.PointToPointGPS

/**
 * Supplies the next destination for a wander activity. WanderLocal and
 * WanderRoad differ only in this strategy; all motion, grounding, pausing,
 * look-at and interaction handling is shared in [WayfarerActivity].
 */
internal interface WayfarerLegProvider {
    /** The next ground-snapped target, or null to just idle this leg. */
    fun nextTarget(current: Position): Position?

    /** True if the NPC has strayed far enough to force a recovery route. */
    fun isOutOfBounds(current: Position): Boolean = false

    /** Where to route on recovery / forced re-centre. */
    fun recoveryTarget(): Position? = null
}

private const val LOOK_RANGE_SQ = 10.0 * 10.0

/**
 * The shared Wayfarer activity.
 *
 * GROUNDING GUARANTEE: every movement leg is performed by upstream
 * [NavigationActivity] driven by [PointToPointGPS] over a [RoadNetworkEntry].
 * That is byte-for-byte the same engine path Typewriter core uses for
 * `patrol_activity` / `random_patrol_activity` — Hydrazine pathfinding with
 * gravity and `BlockCollision.handlePhysics`. We never hand-roll a navigator
 * and never linearly interpolate through the air (that was the #219 float
 * root cause). Targets are additionally ground-snapped by the leg providers.
 *
 * Typewriter SDK basis: core **0.9.0** — `EntityActivity<ActivityContext>`,
 * `TickResult`, `IdleActivity`, `context.isViewed/viewers/entityState`.
 */
internal class WayfarerActivity(
    private val roadNetwork: Ref<RoadNetworkEntry>,
    private val speed: Float,
    private val pause: WayfarerPause,
    private val lookAtPlayer: Boolean,
    private val pauseOnInteract: Boolean,
    private val legProvider: WayfarerLegProvider,
    startLocation: PositionProperty,
) : GenericEntityActivity {

    private sealed class State {
        data object Idle : State()
        data class Pausing(var ticks: Int) : State()
        data object Moving : State()
    }

    private var state: State = State.Idle
    private var child: EntityActivity<in ActivityContext> = IdleActivity(startLocation)
    private val yawVelocity = Velocity(0f)
    private val pitchVelocity = Velocity(0f)
    private var warnedNoNetwork = false

    override val currentPosition: PositionProperty
        get() = child.currentPosition

    override val currentProperties: List<EntityProperty>
        get() = child.currentProperties

    override fun initialize(context: ActivityContext) {
        if (roadNetwork.id.isEmpty() && !warnedNoNetwork) {
            warnedNoNetwork = true
            logger.warning(
                "[Wayfarer] No road network configured — the NPC will stand grounded & idle " +
                    "(it will NOT float). Assign a RoadNetwork covering the wander area to enable movement.",
            )
        }
        child.initialize(context)
        state = State.Idle
    }

    override fun tick(context: ActivityContext): TickResult {
        // pauseOnInteract: freeze pathing while a player talks to this NPC.
        if (pauseOnInteract && context.hasInteractingViewer()) {
            applyLook(context)
            return TickResult.CONSUMED
        }

        // Out-of-bounds recovery (teleport / shove): route straight back.
        if (state != State.Moving && legProvider.isOutOfBounds(currentPosition.toPosition())) {
            legProvider.recoveryTarget()?.let { startNavigation(context, it) }
        }

        when (val s = state) {
            is State.Idle -> {
                val p = pause.roll()
                state = if (p > 0) State.Pausing(p) else State.Idle.also { beginNextLeg(context) }
            }

            is State.Pausing -> {
                s.ticks--
                applyLook(context)
                if (s.ticks <= 0) beginNextLeg(context)
            }

            is State.Moving -> {
                val result = child.tick(context)
                if (result == TickResult.IGNORED) {
                    // Arrived (or nav gave up) — settle, then pause/next.
                    settleChildToIdle(context)
                    state = State.Idle
                }
            }
        }
        return TickResult.CONSUMED
    }

    override fun dispose(context: ActivityContext) {
        val pos = currentPosition
        child.dispose(context)
        child = IdleActivity(pos)
    }

    // ---------------------------------------------------------------------

    private fun beginNextLeg(context: ActivityContext) {
        val target = legProvider.nextTarget(currentPosition.toPosition())
        if (target == null) {
            settleChildToIdle(context)
            state = State.Idle
            return
        }
        startNavigation(context, target)
    }

    private fun startNavigation(context: ActivityContext, target: Position) {
        if (roadNetwork.id.isEmpty()) {
            // No network → cannot ground-navigate. Stay put (grounded), don't float.
            settleChildToIdle(context)
            state = State.Idle
            return
        }
        val pos = currentPosition
        child.dispose(context)
        child = try {
            NavigationActivity(
                PointToPointGPS(
                    roadNetwork,
                    { currentPosition.toPosition() },
                    { target },
                ),
                pos,
            )
        } catch (e: Exception) {
            logger.warning("[Wayfarer] GPS routing failed (${e.message}); idling this leg.")
            IdleActivity(pos)
        }
        child.initialize(context)
        state = State.Moving
    }

    private fun settleChildToIdle(context: ActivityContext) {
        val pos = currentPosition
        child.dispose(context)
        child = IdleActivity(pos)
        child.initialize(context)
    }

    /**
     * Overlay yaw/pitch toward the nearest lookable player while idle/paused.
     * Mirrors core `look_close_activity`'s smoothing. Only mutates rotation —
     * never X/Y/Z — so it can't lift the NPC off the ground.
     */
    private fun applyLook(context: ActivityContext) {
        if (!lookAtPlayer || !context.isViewed) return
        val idle = child as? IdleActivity ?: return

        val me = idle.currentPosition
        val nearest = context.viewers
            .filter { it.isLookable }
            .minByOrNull { me.distanceSqrt(it.location) ?: Double.MAX_VALUE }
            ?: return
        val dSq = me.distanceSqrt(nearest.location) ?: return
        if (dSq > LOOK_RANGE_SQ) return

        val npcEyePosition = me.add(y = context.entityState.eyeHeight)
        val dir = nearest.eyeLocation.toProperty().toVector().minus(npcEyePosition).normalize()
        val targetYaw = getLookYaw(dir.x, dir.z)
        val targetPitch = getLookPitch(dir.x, dir.y, dir.z)

        val (yaw, pitch) = updateLookDirection(
            LookDirection(me.yaw, me.pitch),
            LookDirection(targetYaw, targetPitch),
            yawVelocity,
            pitchVelocity,
        )
        idle.currentPosition = me.copy(yaw = yaw, pitch = pitch)
    }
}
