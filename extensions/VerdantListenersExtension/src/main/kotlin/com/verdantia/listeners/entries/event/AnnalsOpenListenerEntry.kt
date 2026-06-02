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
import org.bukkit.event.inventory.InventoryOpenEvent
import org.koin.core.component.KoinComponent

/**
 * Sets a Typewriter fact to 1 the first time a player opens the Annals GUI
 * (`/annals` command in VerdantRemembers / RealmRemembers).
 *
 * Detection: the inventory title begins with "Annals of Verdantia"
 * (set in `VerdantRemembers.GUI_TITLE_PREFIX`). We match via plain-text
 * serialisation of the Adventure Component title.
 *
 * This does NOT require any VerdantRemembers API dependency — it's purely
 * inventory-title-based, so it works regardless of plugin version.
 */
@Entry(
    "annals_open_listener",
    "Set a fact (one-shot) when a player opens the Annals (/annals) GUI.",
    Colors.ORANGE,
    "fa6-solid:book-open",
)
class AnnalsOpenListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Typewriter fact entry id to set to 1 on first Annals open.")
    val factName: String = "",
    @Help("GUI title prefix to match. Default: 'Annals of Verdantia'. Change if VerdantRemembers was reconfigured.")
    val titlePrefix: String = "Annals of Verdantia",
) : StaticEntry

@Singleton
class AnnalsOpenListenerRegistry : Initializable, Listener, KoinComponent {

    override suspend fun initialize() {
        val entries = Query.find<AnnalsOpenListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No annals_open_listener entries found.")
            return
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "[VerdantiaListeners] Registered Annals-open listener for ${entries.size} entry(ies): " +
                entries.joinToString { "'${it.titlePrefix}'->${it.factName}" },
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(event.view.title())

        val matching = Query.find<AnnalsOpenListenerEntry>()
            .filter { title.startsWith(it.titlePrefix, ignoreCase = true) }
            .toList()
        if (matching.isEmpty()) return

        for (entry in matching) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(player)?.value ?: 0
                if (current >= 1) continue  // one-shot
                val writable = Query.findById<WritableFactEntry>(entry.factName) ?: run {
                    plugin.logger.warning(
                        "[VerdantiaListeners] annals_open_listener: fact '${entry.factName}' not writable.",
                    )
                    return
                }
                writable.write(player, 1)
                plugin.logger.fine(
                    "[VerdantiaListeners] annals_open_listener: player=${player.name} " +
                        "fact=${entry.factName} -> 1 (title matched '${entry.titlePrefix}')",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] annals_open_listener: error for ${player.name}: ${t.message}",
                )
            }
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
