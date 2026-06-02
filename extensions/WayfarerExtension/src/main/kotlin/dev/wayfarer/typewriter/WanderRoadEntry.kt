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
import com.typewritermc.roadnetwork.RoadNetwork
import com.typewritermc.roadnetwork.RoadNetworkEntry
import com.typewritermc.roadnetwork.RoadNetworkManager
import com.typewritermc.roadnetwork.RoadNodeCollectionEntry
import com.typewritermc.roadnetwork.RoadNodeId
import org.koin.java.KoinJavaComponent

/**
 * `wayfarer_wander_road` — the NPC walks a specific named **RoadNetwork**: the
 * author selects WHICH road in the panel ([roadNetwork]) and WHICH nodes on
 * that road's graph to traverse ([nodes], picked in-panel from that network),
 * with [WayfarerLoopMode] traversal (LOOP / PINGPONG / ONCE).
 *
 * This is the same road-selection mechanism Typewriter core's own
 * `patrol_activity` / `path_activity` use: it implements
 * [RoadNodeCollectionEntry], so `roadNetwork` renders as a Road Network picker
 * and `nodes` renders as the on-that-network node selector
 * (`SelectRoadNodeCollectionContentMode`). The node positions are resolved from
 * the live [RoadNetwork] graph at runtime via [RoadNetworkManager] — exactly
 * how `PatrolActivity`/`PathActivity` resolve them.
 *
 * [waypoints] is an optional raw-coordinate override/subset: if the author
 * leaves [nodes] empty but supplies waypoints, the NPC walks those instead
 * (still grounded over the selected road's GPS substrate).
 *
 * **Whole-network default**: if a [roadNetwork] is assigned but the author
 * picks NO explicit [nodes] and supplies NO [waypoints], the NPC wanders the
 * *entire* selected network — every node in its graph, visited as a
 * nearest-neighbour tour, with [loopMode] still applied. Assigning a road
 * network is enough; hand-picking nodes is purely an optional way to restrict
 * the route to a subset. The NPC only idles if there is genuinely no data at
 * all (no network, no nodes, no waypoints).
 *
 * Typewriter SDK basis: core **0.9.0**. Same grounded-navigation engine as
 * [WanderLocalEntry] (upstream `NavigationActivity` + `PointToPointGPS`).
 * Every target is ground-snapped before navigation so the NPC never floats.
 */
@Entry(
    "wayfarer_wander_road",
    "NPC walks a chosen RoadNetwork's nodes (grounded)",
    Colors.BLUE,
    "mdi:map-marker-path",
)
class WanderRoadEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The specific Road Network this NPC wanders along. Pick the named road graph here.")
    override val roadNetwork: Ref<RoadNetworkEntry> = emptyRef(),
    @Help("Ordered nodes ON the selected Road Network to walk between. Pick them in-panel from that road's graph. Need at least 2.")
    override val nodes: List<RoadNodeId> = emptyList(),
    @Help("Optional raw-coordinate override: used only if no nodes are selected above. Need at least 2. Y is auto-snapped to ground.")
    val waypoints: List<Position> = emptyList(),
    @Help("LOOP = restart at first. PINGPONG = reverse at ends. ONCE = walk once then idle at the end.")
    @Default("LOOP")
    val loopMode: WayfarerLoopMode = WayfarerLoopMode.LOOP,
    @Help("Movement speed multiplier. 1.0 = normal walking pace.")
    @Default("1.0")
    val speed: Float = 1.0f,
    @Help("Idle pause behaviour at each waypoint.")
    val pause: WayfarerPause = WayfarerPause(),
    @Help("Face the nearest player (within ~10 blocks) while paused at a waypoint.")
    @Default("true")
    val lookAtPlayer: Boolean = true,
    @Help("Halt pathing while a player is in dialogue with this NPC; resume after.")
    @Default("true")
    val pauseOnInteract: Boolean = true,
    @Help("Return to the first node/waypoint when ONCE mode completes / before idling.")
    @Default("false")
    val returnToAnchor: Boolean = false,
) : GenericEntityActivityEntry, RoadNodeCollectionEntry {
    override fun create(
        context: ActivityContext,
        currentLocation: PositionProperty,
    ): EntityActivity<ActivityContext> {
        // Genuine no-data case: nothing to walk along at all.
        if (roadNetwork.id.isEmpty() && nodes.size < 2 && waypoints.size < 2) {
            logger.warning(
                "[Wayfarer] wayfarer_wander_road '$name' has no Road Network, no >=2 nodes, " +
                    "and no >=2 override waypoints — idling. Assign a Road Network (the NPC " +
                    "will then wander its whole graph), or pick >=2 nodes / waypoints.",
            )
            return IdleActivity(currentLocation)
        }
        // A road network with no explicit nodes/waypoints is valid: the NPC
        // wanders the whole network. Only nodes/waypoints WITHOUT a network
        // would be unroutable (GPS needs the road substrate) — but the entry
        // panel only lets nodes be picked once a network is selected, and the
        // navigation layer already grounds-idles safely if the id is empty.
        return WayfarerActivity(
            roadNetwork = roadNetwork,
            speed = speed.coerceIn(0.1f, 3.0f),
            pause = pause,
            lookAtPlayer = lookAtPlayer,
            pauseOnInteract = pauseOnInteract,
            startLocation = currentLocation,
            legProvider = RoadLegProvider(
                roadNetwork = roadNetwork,
                nodes = nodes,
                waypoints = waypoints,
                loopMode = loopMode,
                returnToAnchor = returnToAnchor,
            ),
        )
    }
}

/**
 * Walks the selected Road Network's [nodes] in [loopMode] order. Node IDs are
 * resolved to their live positions from the [roadNetwork] graph (via
 * [RoadNetworkManager], the same lookup `PatrolActivity`/`PathActivity` use)
 * on first use; until the network loads the provider yields no leg (NPC idles
 * grounded — never floats). If [nodes] is empty, falls back to the optional raw
 * [waypoints] override. Each target is ground-snapped. Snaps the start index to
 * the nearest point so a dialogue-interrupted NPC resumes sensibly.
 */
internal class RoadLegProvider(
    private val roadNetwork: Ref<RoadNetworkEntry>,
    private val nodes: List<RoadNodeId>,
    private val waypoints: List<Position>,
    private val loopMode: WayfarerLoopMode,
    private val returnToAnchor: Boolean,
) : WayfarerLegProvider {
    private var points: List<Position>? = null
    private var index = -1
    private var direction = 1
    private var finishedOnce = false
    private var initialised = false

    /**
     * Resolves the ordered target list once. Precedence:
     *  1. explicit selected road [nodes] (>=2) — resolved against the live
     *     [RoadNetwork];
     *  2. else the optional raw-coordinate [waypoints] override (>=2);
     *  3. else, if a [roadNetwork] is assigned, the **whole network**: every
     *     node in its graph, ordered as a nearest-neighbour tour.
     *
     * Node positions come from `RoadNetworkManager.getNetworkOrNull(ref)` →
     * `RoadNetwork.nodes` (each `RoadNode.id` / `RoadNode.position`) — the exact
     * enumeration `PatrolActivity` uses on core 0.9.0. Returns null while the
     * network is still loading so we can retry.
     */
    private fun resolvePoints(): List<Position>? {
        points?.let { return it }

        // (1) Explicit author-picked nodes on the selected road graph.
        if (nodes.isNotEmpty()) {
            val network: RoadNetwork = KoinJavaComponent
                .get<RoadNetworkManager>(RoadNetworkManager::class.java)
                .getNetworkOrNull(roadNetwork)
                ?: return null // network not loaded yet — retry next leg
            val byId = network.nodes.associateBy { it.id }
            val resolved = nodes.mapNotNull { byId[it]?.position }
            if (resolved.size < 2) {
                logger.warning(
                    "[Wayfarer] wander_road: < 2 of the selected nodes exist on road " +
                        "'${roadNetwork.id}' — idling.",
                )
                points = emptyList()
                return points
            }
            logger.info(
                "[Wayfarer] wander_road '${roadNetwork.id}': using ${resolved.size} " +
                    "explicitly-selected node(s).",
            )
            points = resolved
            return points
        }

        // (2) No nodes selected, but raw waypoint override supplied.
        if (waypoints.size >= 2) {
            logger.info(
                "[Wayfarer] wander_road '${roadNetwork.id}': using ${waypoints.size} " +
                    "override waypoint(s).",
            )
            points = waypoints
            return points
        }

        // (3) Whole-network default: no explicit nodes, no waypoints, but a
        // road network is assigned — wander every node on its graph.
        if (roadNetwork.id.isNotEmpty()) {
            val network: RoadNetwork = KoinJavaComponent
                .get<RoadNetworkManager>(RoadNetworkManager::class.java)
                .getNetworkOrNull(roadNetwork)
                ?: return null // network not loaded yet — retry next leg
            val all = network.nodes.map { it.position }
            if (all.size < 2) {
                logger.warning(
                    "[Wayfarer] wander_road: road '${roadNetwork.id}' has < 2 nodes in its " +
                        "graph — nothing to wander, idling. Add nodes to the network.",
                )
                points = emptyList()
                return points
            }
            val tour = nearestNeighbourTour(all)
            logger.info(
                "[Wayfarer] wander_road '${roadNetwork.id}': no explicit nodes/waypoints — " +
                    "wandering the WHOLE network (${tour.size} node(s), nearest-neighbour tour).",
            )
            points = tour
            return points
        }

        // No data at all (defensive — create() already guards this).
        points = emptyList()
        return points
    }

    /**
     * Orders the full node set into a stable, sensible visiting sequence: a
     * greedy nearest-neighbour tour starting from the first node. GPS
     * ([PointToPointGPS]) still routes each leg along the actual road edges; the
     * tour only decides the *order* nodes are visited so the NPC doesn't
     * teleport-feel across the map between far-apart graph entries.
     */
    private fun nearestNeighbourTour(all: List<Position>): List<Position> {
        if (all.size <= 2) return all
        val remaining = all.toMutableList()
        val ordered = ArrayList<Position>(all.size)
        var current = remaining.removeAt(0)
        ordered.add(current)
        while (remaining.isNotEmpty()) {
            val nextIdx = remaining.indices.minByOrNull { i ->
                remaining[i].distanceSqrt(current) ?: Double.MAX_VALUE
            } ?: 0
            current = remaining.removeAt(nextIdx)
            ordered.add(current)
        }
        return ordered
    }

    override fun nextTarget(current: Position): Position? {
        val pts = resolvePoints() ?: return null // network still loading
        if (pts.size < 2) return null

        if (!initialised) {
            // Resume at the point closest to current position.
            index = pts.indices.minByOrNull { i ->
                pts[i].distanceSqrt(current) ?: Double.MAX_VALUE
            } ?: 0
            initialised = true
            return pts[index].snapToGround()
        }

        if (finishedOnce) {
            return if (returnToAnchor) pts.first().snapToGround() else null
        }

        when (loopMode) {
            WayfarerLoopMode.LOOP -> index = (index + 1) % pts.size
            WayfarerLoopMode.PINGPONG -> {
                var next = index + direction
                if (next >= pts.size) {
                    direction = -1
                    next = index - 1
                } else if (next < 0) {
                    direction = 1
                    next = index + 1
                }
                index = next.coerceIn(0, pts.lastIndex)
            }
            WayfarerLoopMode.ONCE -> {
                if (index >= pts.lastIndex) {
                    finishedOnce = true
                    return if (returnToAnchor) pts.first().snapToGround() else null
                }
                index += 1
            }
        }
        return pts[index].snapToGround()
    }

    override fun isOutOfBounds(current: Position): Boolean {
        val pts = points ?: return false
        val nearest = pts.minOfOrNull { it.distanceSqrt(current) ?: Double.MAX_VALUE }
            ?: return false
        return nearest > 50.0 * 50.0
    }

    override fun recoveryTarget(): Position? {
        val pts = points ?: return null
        if (pts.isEmpty()) return null
        val idx = index.coerceIn(0, pts.lastIndex)
        return pts[idx].snapToGround()
    }
}
