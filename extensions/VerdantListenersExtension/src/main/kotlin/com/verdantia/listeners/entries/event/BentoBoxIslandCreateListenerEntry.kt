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
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.koin.core.component.KoinComponent

/**
 * Sets a Typewriter fact to 1 when a player creates a BentoBox skyblock island.
 *
 * Hooks `world.bentobox.bentobox.api.events.island.IslandCreatedEvent` via
 * reflection so no compile-time BentoBox dependency is required.
 *
 * BentoBox's IslandCreatedEvent extends IslandBaseEvent which extends PlayerEvent,
 * so `getPlayer(): Player` is available.
 */
@Entry(
    "bentobox_island_create_listener",
    "Set a fact (one-shot) when a player creates a BentoBox island.",
    Colors.CYAN,
    "fa6-solid:island-tropical",
)
class BentoBoxIslandCreateListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Typewriter fact entry id to set to 1 on island creation.")
    val factName: String = "",
) : StaticEntry

@Singleton
class BentoBoxIslandCreateListenerRegistry : Initializable, Listener, KoinComponent {

    private val EVENT_CLASS_NAMES = listOf(
        "world.bentobox.bentobox.api.events.island.IslandCreatedEvent",
        "world.bentobox.bentobox.api.events.island.IslandCreateEvent",
    )

    override suspend fun initialize() {
        val entries = Query.find<BentoBoxIslandCreateListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No bentobox_island_create_listener entries found.")
            return
        }

        var registered = false
        for (className in EVENT_CLASS_NAMES) {
            val klass = try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className) as Class<out org.bukkit.event.Event>
            } catch (_: Throwable) {
                continue
            }
            plugin.server.pluginManager.registerEvent(
                klass,
                this,
                EventPriority.MONITOR,
                { _, event -> if (klass.isInstance(event)) handleIslandCreate(event) },
                plugin,
                true,
            )
            plugin.logger.info(
                "[VerdantiaListeners] Hooked BentoBox island-create event: $className — " +
                    "${entries.size} entry(ies): ${entries.joinToString { it.factName }}",
            )
            registered = true
            break  // Only need one — stop at first match.
        }

        if (!registered) {
            plugin.logger.warning(
                "[VerdantiaListeners] bentobox_island_create_listener: BentoBox IslandCreatedEvent not found. " +
                    "Is BentoBox installed?",
            )
        }
    }

    private fun handleIslandCreate(event: org.bukkit.event.Event) {
        val player = extractPlayer(event) ?: return
        val entries = Query.find<BentoBoxIslandCreateListenerEntry>().toList()
        for (entry in entries) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(player)?.value ?: 0
                if (current >= 1) continue  // one-shot
                val writable = Query.findById<WritableFactEntry>(entry.factName) ?: run {
                    plugin.logger.warning(
                        "[VerdantiaListeners] bentobox_island_create_listener: fact '${entry.factName}' not writable.",
                    )
                    return
                }
                writable.write(player, 1)
                plugin.logger.fine(
                    "[VerdantiaListeners] bentobox_island_create_listener: player=${player.name} " +
                        "fact=${entry.factName} -> 1",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] bentobox_island_create_listener: error for ${player.name}: ${t.message}",
                )
            }
        }
    }

    private fun extractPlayer(event: org.bukkit.event.Event): Player? {
        return try {
            // IslandBaseEvent extends PlayerEvent — getPlayer() is on PlayerEvent.
            val m = event.javaClass.methods.firstOrNull { it.name == "getPlayer" && it.parameterCount == 0 }
            m?.invoke(event) as? Player
                ?: run {
                    // Fallback: getIsland().getOwner() → UUID → Bukkit.getPlayer(uuid)
                    val island = event.javaClass.methods.firstOrNull { it.name == "getIsland" && it.parameterCount == 0 }
                        ?.invoke(event) ?: return null
                    val uuid = island.javaClass.methods.firstOrNull { it.name == "getOwner" && it.parameterCount == 0 }
                        ?.invoke(island) as? java.util.UUID ?: return null
                    plugin.server.getPlayer(uuid)
                }
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
