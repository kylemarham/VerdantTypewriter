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
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.koin.core.component.KoinComponent

/**
 * Increments a Typewriter fact whenever a specific MythicMobs mob is killed by a player.
 *
 * Set `mobInternalId` to the MythicMobs internal mob id (e.g. `goblin_whelp`).
 */
@Entry(
    "mythicmob_kill_listener",
    "Increment a fact when a player kills a specific MythicMobs mob.",
    Colors.RED,
    "fa6-solid:skull",
)
class MythicMobKillListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("MythicMobs internal mob id (e.g. goblin_whelp)")
    val mobInternalId: String = "",
    @Help("Typewriter fact entry id to increment.")
    val factName: String = "",
    @Help("Amount to add to the fact on each kill.")
    val amount: Int = 1,
    @Help("If true, fact caps at 1 (first-kill flag). Subsequent kills are no-ops.")
    val oneShot: Boolean = false,
) : StaticEntry

@Singleton
class MythicMobKillRegistry : Initializable, Listener, KoinComponent {

    override suspend fun initialize() {
        val entries = Query.find<MythicMobKillListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No mythicmob_kill_listener entries found.")
            return
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "[VerdantiaListeners] Registered MythicMobs death listener for ${entries.size} entry(ies): " +
                entries.joinToString { "${it.mobInternalId}->${it.factName}" },
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMythicMobDeath(event: MythicMobDeathEvent) {
        val killer = event.killer as? Player ?: return
        val mobId = event.mobType.internalName
        val matches = Query.find<MythicMobKillListenerEntry>()
            .filter { it.mobInternalId.equals(mobId, ignoreCase = true) }
            .toList()
        if (matches.isEmpty()) return
        for (entry in matches) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(killer)?.value ?: 0
                if (entry.oneShot && current >= 1) continue
                val writable = Query.findById<WritableFactEntry>(entry.factName)
                if (writable == null) {
                    plugin.logger.warning(
                        "[VerdantiaListeners] mythicmob_kill_listener: fact '${entry.factName}' not writable.",
                    )
                    continue
                }
                val newValue = if (entry.oneShot) 1 else current + entry.amount
                writable.write(killer, newValue)
                plugin.logger.fine(
                    "[VerdantiaListeners] mythicmob_kill_listener fired: mob=$mobId player=${killer.name} " +
                        "fact=${entry.factName} +${entry.amount}",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] Failed to bump '${entry.factName}' for ${killer.name}: ${t.message}",
                )
            }
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
