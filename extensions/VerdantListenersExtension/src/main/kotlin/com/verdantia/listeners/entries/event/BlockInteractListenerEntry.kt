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
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.koin.core.component.KoinComponent

/**
 * Fires once-per-player when a player right-clicks a specific block coordinate.
 *
 * Used for static "shrine chest" / "loose flagstone" style triggers where the
 * fact must flip the first time the precise block is interacted with. The
 * registry caps the fact at 1 when `oneShot = true` (default) so subsequent
 * right-clicks are no-ops.
 */
@Entry(
    "block_interact_listener",
    "Set or increment a fact when a player right-clicks a specific block coord.",
    Colors.ORANGE,
    "mdi:hand-pointing-right",
)
class BlockInteractListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Bukkit world name, e.g. world")
    val world: String = "",
    @Help("Block X coordinate (integer).")
    val x: Int = 0,
    @Help("Block Y coordinate (integer).")
    val y: Int = 0,
    @Help("Block Z coordinate (integer).")
    val z: Int = 0,
    @Help("Typewriter fact entry id to write/increment.")
    val factName: String = "",
    @Help("Amount to add on fire. Ignored when oneShot=true (always sets to 1).")
    val amount: Int = 1,
    @Help("If true, fact caps at 1 and re-interacts do nothing. If false, behaves as a counter.")
    val oneShot: Boolean = true,
) : StaticEntry

@Singleton
class BlockInteractListenerRegistry : Initializable, Listener, KoinComponent {

    override suspend fun initialize() {
        val entries = Query.find<BlockInteractListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No block_interact_listener entries found.")
            return
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "[VerdantiaListeners] Registered block-interact listener for ${entries.size} entry(ies): " +
                entries.joinToString { "${it.world}(${it.x},${it.y},${it.z})->${it.factName}" },
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player: Player = event.player
        val worldName = block.world.name
        val bx = block.x
        val by = block.y
        val bz = block.z

        val matches = Query.find<BlockInteractListenerEntry>()
            .filter {
                it.world.equals(worldName, ignoreCase = true) &&
                    it.x == bx && it.y == by && it.z == bz
            }
            .toList()
        if (matches.isEmpty()) return

        for (entry in matches) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(player)?.value ?: 0
                if (entry.oneShot && current >= 1) continue
                val writable = Query.findById<WritableFactEntry>(entry.factName)
                if (writable == null) {
                    plugin.logger.warning(
                        "[VerdantiaListeners] block_interact_listener: fact '${entry.factName}' not writable.",
                    )
                    continue
                }
                val newValue = if (entry.oneShot) 1 else current + entry.amount
                writable.write(player, newValue)
                plugin.logger.fine(
                    "[VerdantiaListeners] block_interact_listener fired: " +
                        "world=$worldName pos=($bx,$by,$bz) player=${player.name} " +
                        "fact=${entry.factName} -> $newValue",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] Failed to set fact '${entry.factName}' for ${player.name}: ${t.message}",
                )
            }
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
