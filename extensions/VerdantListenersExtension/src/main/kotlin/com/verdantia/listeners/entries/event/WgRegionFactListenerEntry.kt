package com.verdantia.listeners.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.WritableFactEntry
import com.typewritermc.engine.paper.plugin
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.koin.core.component.KoinComponent

/**
 * Sets a Typewriter fact (to 1, one-shot) when a player enters a specific
 * WorldGuard region. Uses PlayerMoveEvent + reflection against the WG API so
 * the extension does not need a compile-time WorldGuard dependency.
 *
 * `region` is the WG region id (e.g. `market_zone`). The fact is only written
 * once per player (`oneShot=true` semantics — subsequent entries are no-ops).
 */
@Entry(
    "wg_region_fact_listener",
    "Set a fact (one-shot) when a player enters a named WorldGuard region.",
    Colors.YELLOW,
    "fa6-solid:map-location-dot",
)
class WgRegionFactListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("WorldGuard region id to watch, e.g. market_zone")
    val region: String = "",
    @Help("Typewriter fact entry id to set to 1 on region entry.")
    val factName: String = "",
    @Help("World name to limit the check, e.g. world. Leave blank for all worlds.")
    val world: String = "",
) : StaticEntry

@Singleton
class WgRegionFactListenerRegistry : Initializable, Listener, KoinComponent {

    override suspend fun initialize() {
        val entries = Query.find<WgRegionFactListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No wg_region_fact_listener entries found.")
            return
        }

        // Check WorldGuard is on the server — bail gracefully if not.
        val wgPresent = try {
            Class.forName("com.sk89q.worldguard.WorldGuard")
            true
        } catch (_: Throwable) {
            false
        }
        if (!wgPresent) {
            plugin.logger.warning(
                "[VerdantiaListeners] wg_region_fact_listener: WorldGuard not found on server — " +
                    "${entries.size} entries will not function.",
            )
            return
        }

        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "[VerdantiaListeners] Registered WG region-fact listener for ${entries.size} entry(ies): " +
                entries.joinToString { "${it.region}->${it.factName}" },
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        // Only proceed if the player actually crossed a block boundary.
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val player = event.player
        val worldName = to.world.name

        // Resolve WorldGuard region set at destination via reflection.
        val regions = getRegionsAt(to) ?: return
        if (regions.isEmpty()) return

        val entries = Query.find<WgRegionFactListenerEntry>()
            .filter { entry ->
                (entry.world.isBlank() || entry.world.equals(worldName, ignoreCase = true)) &&
                    regions.any { it.equals(entry.region, ignoreCase = true) }
            }
            .toList()

        for (entry in entries) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(player)?.value ?: 0
                if (current >= 1) continue  // already set
                val writable = Query.findById<WritableFactEntry>(entry.factName)
                if (writable == null) {
                    plugin.logger.warning(
                        "[VerdantiaListeners] wg_region_fact_listener: fact '${entry.factName}' not writable.",
                    )
                    continue
                }
                writable.write(player, 1)
                plugin.logger.fine(
                    "[VerdantiaListeners] wg_region_fact_listener: region=${entry.region} " +
                        "player=${player.name} fact=${entry.factName} -> 1",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] wg_region_fact_listener: error for ${player.name}: ${t.message}",
                )
            }
        }
    }

    /**
     * Returns the set of region ids at the given location via WorldGuard reflection.
     * Returns null if WorldGuard is unavailable or lookup fails.
     */
    private fun getRegionsAt(location: org.bukkit.Location): Set<String>? {
        return try {
            val wg = Class.forName("com.sk89q.worldguard.WorldGuard")
                .getMethod("getInstance").invoke(null)
            val platform = wg.javaClass.getMethod("getPlatform").invoke(wg)
            val regionContainer = platform.javaClass.getMethod("getRegionContainer").invoke(platform)

            // Convert Bukkit world to WG LocalWorld via BukkitAdapter
            val adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter")
            val localWorld = adapter.getMethod("adapt", org.bukkit.World::class.java).invoke(null, location.world)
            val localVector = adapter.getMethod("asBlockVector", org.bukkit.Location::class.java).invoke(null, location)

            val manager = regionContainer.javaClass.getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(regionContainer, localWorld) ?: return null

            @Suppress("UNCHECKED_CAST")
            val regionMap = manager.javaClass.getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                .invoke(manager, localVector) as? Iterable<*> ?: return null

            regionMap.mapNotNull { r ->
                r?.javaClass?.getMethod("getId")?.invoke(r) as? String
            }.toSet()
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
