package com.verdantia.listeners.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.facts.FactDatabase
import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Registers a generic Bukkit event listener that increments a Typewriter fact
 * for the player that triggered the event.
 *
 * `eventClass` must be a fully-qualified Bukkit event class name. The event
 * must expose a `getPlayer(): Player` method (or be a subclass of one that
 * does — covers `PlayerEvent`, `BlockBreakEvent`, `PlayerFishEvent`, etc.).
 *
 * `factName` is the Typewriter entry id of a `WritableFactEntry`.
 */
@Entry(
    "bukkit_event_listener",
    "Increment a fact whenever a Bukkit event fires for a player.",
    Colors.GREEN,
    "mdi:bell-ring",
)
class BukkitEventListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help(
        "Fully-qualified Bukkit event class, e.g. org.bukkit.event.block.BlockBreakEvent. " +
            "Special value 'worldguard:region-enter' (or the legacy alias " +
            "io.papermc.paper.event.player.PlayerRegionEnterEvent) hooks WorldGuard region entry; " +
            "use criteria like 'region=<id>,<factId>=1,realm_time_band=night'.",
    )
    val eventClass: String = "",
    @Help("Typewriter fact entry id to increment.")
    val factName: String = "",
    @Help("Amount to add to the fact on each fire.")
    val amount: Int = 1,
    @Help("Optional filter — material id (BlockBreakEvent), entity type, fish state, etc. Leave blank for no filter.")
    val criteria: String = "",
) : StaticEntry

@Singleton
class BukkitEventListenerRegistry : Initializable, Listener, KoinComponent {
    private val factDatabase: FactDatabase by inject()
    private val registeredClasses = mutableSetOf<Class<out Event>>()
    private var regionEnterRegistered = false

    companion object {
        /**
         * Virtual event-class id for "player entered a WorldGuard region". Paper has
         * no native PlayerRegionEnterEvent; region transitions only exist via the
         * WorldGuard API. Entries may use either this id or the legacy (incorrect)
         * `io.papermc.paper.event.player.PlayerRegionEnterEvent` FQN — both resolve
         * to the WG PlayerMoveEvent-backed detector below. Region/fact/time-band
         * gating is expressed through the entry `criteria` field.
         */
        const val REGION_ENTER_VIRTUAL = "worldguard:region-enter"
        const val REGION_ENTER_LEGACY_ALIAS = "io.papermc.paper.event.player.PlayerRegionEnterEvent"

        private fun isRegionEnterClass(className: String): Boolean =
            className == REGION_ENTER_VIRTUAL || className == REGION_ENTER_LEGACY_ALIAS
    }

    override suspend fun initialize() {
        val entries = Query.find<BukkitEventListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No bukkit_event_listener entries found.")
            return
        }

        val byClass = entries.groupBy { it.eventClass }
        for ((className, group) in byClass) {
            if (isRegionEnterClass(className)) {
                registerRegionEnter(group)
                continue
            }
            val klass = try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className) as Class<out Event>
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] Could not resolve eventClass='$className' (entries: ${group.map { it.id }}): ${t.message}",
                )
                continue
            }
            if (registeredClasses.add(klass)) {
                registerEventClass(klass)
                plugin.logger.info(
                    "[VerdantiaListeners] Registered listener for $className — ${group.size} entry(ies): ${group.joinToString { it.factName }}",
                )
            }
        }
    }

    private fun registerRegionEnter(group: List<BukkitEventListenerEntry>) {
        val wgPresent = try {
            Class.forName("com.sk89q.worldguard.WorldGuard"); true
        } catch (_: Throwable) {
            false
        }
        if (!wgPresent) {
            plugin.logger.info(
                "[VerdantiaListeners] region-enter listener: WorldGuard not present — " +
                    "${group.size} entry(ies) inert: ${group.map { it.id }}",
            )
            return
        }
        if (!regionEnterRegistered) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            regionEnterRegistered = true
        }
        plugin.logger.info(
            "[VerdantiaListeners] Registered WG region-enter listener — ${group.size} entry(ies): " +
                group.joinToString { "${it.factName}[${it.criteria}]" },
        )
    }

    /**
     * WG region-enter detector. Fires on block-boundary crossings, resolves the
     * region set at the destination, and increments matching entries whose
     * `criteria` (region + optional fact-equality + optional time-band) passes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRegionMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val player = event.player
        val fromRegions = getRegionsAt(from) ?: emptySet()
        val toRegions = getRegionsAt(to) ?: return
        // Only treat regions newly entered this move as an "enter".
        val entered = toRegions - fromRegions
        if (entered.isEmpty()) return

        val matches = Query.find<BukkitEventListenerEntry>().filter { entry ->
            isRegionEnterClass(entry.eventClass) &&
                passesRegionCriteria(entry.criteria, player, entered)
        }.toList()
        for (entry in matches) {
            applyIncrement(player, entry, event)
        }
    }

    /**
     * Region-enter criteria grammar (comma-separated clauses):
     *   region=<wgRegionId>            required — must match a newly-entered region
     *   <factId>=<int>                 Typewriter fact must equal this value
     *   realm_time_band=<band>         overworld time band: night|morning|day|evening
     */
    private fun passesRegionCriteria(criteria: String, player: Player, entered: Set<String>): Boolean {
        if (criteria.isBlank()) return true
        var regionOk = true
        for (raw in criteria.split(",")) {
            val clause = raw.trim()
            if (clause.isEmpty()) continue
            val eq = clause.indexOf('=')
            if (eq <= 0) continue
            val key = clause.substring(0, eq).trim()
            val value = clause.substring(eq + 1).trim()
            when {
                key.equals("region", ignoreCase = true) ->
                    regionOk = entered.any { it.equals(value, ignoreCase = true) }
                key.equals("realm_time_band", ignoreCase = true) ->
                    if (!currentTimeBand().equals(value, ignoreCase = true)) return false
                else -> {
                    // Treat as a Typewriter fact equality gate.
                    val expected = value.toIntOrNull() ?: continue
                    val current = readFactValue(player, key)
                    if (current != expected) return false
                }
            }
        }
        return regionOk
    }

    /** night: 19–4, morning: 5–9, day: 10–16, evening: 17–18 (overworld hour). */
    private fun currentTimeBand(): String {
        val world: World = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NORMAL }
            ?: Bukkit.getWorlds().firstOrNull() ?: return "day"
        val hour = (((world.time + 6000) / 1000) % 24).toInt()
        return when {
            hour >= 19 || hour < 5 -> "night"
            hour < 10 -> "morning"
            hour < 17 -> "day"
            else -> "evening"
        }
    }

    /** Region ids at a location via WorldGuard reflection (no compile-time WG dep). */
    private fun getRegionsAt(location: Location): Set<String>? {
        return try {
            val wg = Class.forName("com.sk89q.worldguard.WorldGuard")
                .getMethod("getInstance").invoke(null)
            val platform = wg.javaClass.getMethod("getPlatform").invoke(wg)
            val regionContainer = platform.javaClass.getMethod("getRegionContainer").invoke(platform)
            val adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter")
            val localWorld = adapter.getMethod("adapt", World::class.java).invoke(null, location.world)
            val localVector = adapter.getMethod("asBlockVector", Location::class.java).invoke(null, location)
            val manager = regionContainer.javaClass
                .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(regionContainer, localWorld) ?: return null
            @Suppress("UNCHECKED_CAST")
            val regionMap = manager.javaClass
                .getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                .invoke(manager, localVector) as? Iterable<*> ?: return null
            regionMap.mapNotNull { r -> r?.javaClass?.getMethod("getId")?.invoke(r) as? String }.toSet()
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerEventClass(klass: Class<out Event>) {
        plugin.server.pluginManager.registerEvent(
            klass,
            this,
            EventPriority.MONITOR,
            { _, event ->
                if (!klass.isInstance(event)) return@registerEvent
                handleEvent(event)
            },
            plugin,
            true,
        )
    }

    private fun handleEvent(event: Event) {
        val player = extractPlayer(event) ?: return
        val eventClassName = event.javaClass.name
        val matches = Query.find<BukkitEventListenerEntry>().filter { entry ->
            entryMatches(entry, event, eventClassName)
        }.toList()
        if (matches.isEmpty()) return
        for (entry in matches) {
            applyIncrement(player, entry, event)
        }
    }

    private fun entryMatches(entry: BukkitEventListenerEntry, event: Event, eventClassName: String): Boolean {
        // Accept exact match or subclass match — entries configured with a parent class still fire on subclasses.
        val configured = try {
            Class.forName(entry.eventClass)
        } catch (_: Throwable) {
            return false
        }
        return configured.isInstance(event) && passesCriteria(entry.criteria, event)
    }

    /**
     * Best-effort criteria filter. Supported expression shapes:
     *   material=WHEAT             (looks at event.block.type, event.item.type, etc.)
     *   entity=ZOMBIE
     *   state=CAUGHT_FISH          (PlayerFishEvent#getState)
     *   (blank)                    no filter
     */
    private fun passesCriteria(criteria: String, event: Event): Boolean {
        if (criteria.isBlank()) return true
        val parts = criteria.split("=", limit = 2).map { it.trim() }
        if (parts.size != 2) return true
        val (key, expected) = parts
        return when (key.lowercase()) {
            "material" -> readMaterial(event)?.equals(expected, ignoreCase = true) == true
            "entity" -> readEntityType(event)?.equals(expected, ignoreCase = true) == true
            "state" -> readState(event)?.equals(expected, ignoreCase = true) == true
            else -> true
        }
    }

    private fun readMaterial(event: Event): String? {
        return tryInvoke<Any?>(event, "getBlock")?.let { tryInvoke<Any?>(it, "getType")?.toString() }
            ?: tryInvoke<Any?>(event, "getItem")?.let { tryInvoke<Any?>(it, "getType")?.toString() }
    }

    private fun readEntityType(event: Event): String? =
        tryInvoke<Any?>(event, "getEntity")?.let { tryInvoke<Any?>(it, "getType")?.toString() }

    private fun readState(event: Event): String? = tryInvoke<Any?>(event, "getState")?.toString()

    @Suppress("UNCHECKED_CAST")
    private fun <T> tryInvoke(target: Any, methodName: String): T? {
        return try {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?: return null
            method.invoke(target) as T?
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractPlayer(event: Event): Player? {
        val direct = tryInvoke<Any?>(event, "getPlayer")
        if (direct is Player) return direct
        val killer = tryInvoke<Any?>(event, "getEntity")?.let { tryInvoke<Any?>(it, "getKiller") }
        if (killer is Player) return killer
        return null
    }

    private fun applyIncrement(player: Player, entry: BukkitEventListenerEntry, event: Event) {
        try {
            val current = readFactValue(player, entry.factName)
            writeFactValue(player, entry.factName, current + entry.amount)
            plugin.logger.fine(
                "[VerdantiaListeners] bukkit_event_listener fired: event=${event.javaClass.simpleName} " +
                    "player=${player.name} fact=${entry.factName} +${entry.amount}",
            )
        } catch (t: Throwable) {
            plugin.logger.warning(
                "[VerdantiaListeners] Failed to increment fact '${entry.factName}' for ${player.name}: ${t.message}",
            )
        }
    }

    // #266: factName lookups need to support both the entry ID (`fact-foo-bar`)
    // and the human-readable entry NAME (`foo_bar`) — most authored pages
    // reference facts by NAME, which Query.findById() can't resolve. Mirrors
    // the upstream TypewriterDsl pattern (findById ?: findByName).
    private fun readFactValue(player: Player, factId: String): Int {
        val klass = com.typewritermc.engine.paper.entry.entries.ReadableFactEntry::class
        val entry = (Query.findById(klass, factId) ?: Query.findByName(klass, factId)) ?: return 0
        return entry.readForPlayersGroup(player).value
    }

    private fun writeFactValue(player: Player, factId: String, value: Int) {
        val klass = com.typewritermc.engine.paper.entry.entries.WritableFactEntry::class
        val entry = (Query.findById(klass, factId) ?: Query.findByName(klass, factId))
            ?: error("Fact '$factId' is not writable or not found (looked up by id and by name)")
        entry.write(player, value)
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        registeredClasses.clear()
    }
}
