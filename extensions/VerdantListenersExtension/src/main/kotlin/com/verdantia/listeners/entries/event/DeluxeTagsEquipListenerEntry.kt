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
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.koin.core.component.KoinComponent

/**
 * Sets a Typewriter fact to 1 the first time a player equips a DeluxeTag.
 *
 * The patched DeluxeTags-1.8.2 build fired no custom TagEquipEvent. Instead,
 * tags are equipped via the `/tags` GUI (a chest inventory). We hook
 * `InventoryClickEvent`, detect when the player clicks inside an inventory
 * whose title contains the DeluxeTags GUI marker text, and treat any non-null
 * click in a non-bottom-inventory slot as a tag selection / equip.
 *
 * A secondary method: attempt to hook `me.clip.deluxetags.api.DeluxeTagsAPI`
 * for a `TagSelectEvent` if one exists on a newer build. Falls back to the
 * InventoryClickEvent approach automatically.
 */
@Entry(
    "deluxetags_equip_listener",
    "Set a fact (one-shot) when a player equips a tag in the /tags GUI.",
    Colors.PURPLE,
    "fa6-solid:tag",
)
class DeluxeTagsEquipListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Typewriter fact entry id to set to 1 on first tag equip.")
    val factName: String = "",
) : StaticEntry

@Singleton
class DeluxeTagsEquipListenerRegistry : Initializable, Listener, KoinComponent {

    /**
     * Known DeluxeTags custom event class names (newer / forked builds may add one).
     * If any is resolvable, we register it in addition to the GUI click fallback.
     */
    private val CUSTOM_EVENT_CLASSES = listOf(
        "me.clip.deluxetags.api.events.TagSelectEvent",
        "me.clip.deluxetags.api.events.TagEquipEvent",
        "me.clip.deluxetags.events.TagSelectEvent",
    )

    /**
     * GUI title substrings that identify the DeluxeTags inventory.
     * Checked case-insensitively; any match → treat click as potential tag equip.
     */
    private val GUI_TITLE_MARKERS = listOf("tags", "deluxetags", "your tags", "available tags")

    override suspend fun initialize() {
        val entries = Query.find<DeluxeTagsEquipListenerEntry>().toList()
        if (entries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No deluxetags_equip_listener entries found.")
            return
        }

        // Try to hook a native TagSelectEvent if available.
        var nativeHooked = false
        for (className in CUSTOM_EVENT_CLASSES) {
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
                { _, event -> if (klass.isInstance(event)) handleNativeTagEvent(event) },
                plugin,
                true,
            )
            plugin.logger.info("[VerdantiaListeners] Hooked DeluxeTags native event: $className")
            nativeHooked = true
            break
        }

        // Always register the GUI click fallback — it's low-cost and covers the patched build.
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "[VerdantiaListeners] Registered DeluxeTags equip listener (GUI click fallback" +
                if (nativeHooked) " + native event)" else " only — no native event found)" +
                        " for ${entries.size} entry(ies): ${entries.joinToString { it.factName }}",
        )
    }

    /** Called when a native TagSelectEvent fires (if available on this build). */
    private fun handleNativeTagEvent(event: org.bukkit.event.Event) {
        val player = try {
            event.javaClass.methods.firstOrNull { it.name == "getPlayer" && it.parameterCount == 0 }
                ?.invoke(event) as? org.bukkit.entity.Player
        } catch (_: Throwable) {
            null
        } ?: return
        applyToAll(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        // Only care about clicks in the top inventory (the GUI), not the player hotbar.
        if (event.clickedInventory == event.whoClicked.inventory) return
        val clickedInventory = event.clickedInventory ?: return
        if (clickedInventory == event.whoClicked.inventory) return

        // Check the inventory title against known DeluxeTags GUI markers.
        val title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(event.view.title())
        val isTagsGui = GUI_TITLE_MARKERS.any { marker -> title.contains(marker, ignoreCase = true) }
        if (!isTagsGui) return

        // A null current-item click is just nav; non-null is a tag tile click → treat as equip attempt.
        val current = event.currentItem ?: return
        if (current.type == org.bukkit.Material.AIR) return

        // Treat this as an equip — we can't reliably distinguish "equip" from "info click"
        // at this level, so we fire on any non-empty tag tile click. Worst case: fact is set
        // one click early (player browsed but then equipped) — acceptable for induction purposes.
        applyToAll(player)
    }

    private fun applyToAll(player: org.bukkit.entity.Player) {
        val entries = Query.find<DeluxeTagsEquipListenerEntry>().toList()
        for (entry in entries) {
            try {
                val current = Query.findById<ReadableFactEntry>(entry.factName)
                    ?.readForPlayersGroup(player)?.value ?: 0
                if (current >= 1) continue
                val writable = Query.findById<WritableFactEntry>(entry.factName) ?: run {
                    plugin.logger.warning(
                        "[VerdantiaListeners] deluxetags_equip_listener: fact '${entry.factName}' not writable.",
                    )
                    return
                }
                writable.write(player, 1)
                plugin.logger.fine(
                    "[VerdantiaListeners] deluxetags_equip_listener: player=${player.name} " +
                        "fact=${entry.factName} -> 1",
                )
            } catch (t: Throwable) {
                plugin.logger.warning(
                    "[VerdantiaListeners] deluxetags_equip_listener: error for ${player.name}: ${t.message}",
                )
            }
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
