package com.verdantia.listeners.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

private val CROWN_FRAGMENT_IDS = setOf(
    "FADED_CROWN_FRAGMENT_1",
    "FADED_CROWN_FRAGMENT_2",
    "FADED_CROWN_FRAGMENT_3",
    "FADED_CROWN_FRAGMENT_4",
)

private val CROWN_FRAGMENT_PDC_KEY: NamespacedKey? = try {
    NamespacedKey.fromString("verdantia:crown_fragment")
} catch (_: Throwable) {
    null
}

private fun mmoItemsLoaded(): Boolean =
    Bukkit.getPluginManager().getPlugin("MMOItems")?.isEnabled == true

/**
 * Reflectively reads the MMOItems item id from an ItemStack's NBT.
 * Returns the upper-cased id (e.g. "FADED_CROWN_FRAGMENT_1") or null.
 *
 * Looks at PersistentDataContainer key `mmoitems:item-id` first
 * (where MMOItems 6+ stores its data), then falls back to legacy NBT via the
 * MMOItems API by reflection.
 */
private fun mmoItemId(stack: ItemStack): String? {
    if (stack.type == Material.AIR) return null
    if (!stack.hasItemMeta()) return null
    val meta = stack.itemMeta ?: return null

    // Modern MMOItems stores its id under PDC key "mmoitems:item-id".
    val pdc = meta.persistentDataContainer
    val key = NamespacedKey.fromString("mmoitems:item-id")
    if (key != null) {
        val value = pdc.get(key, PersistentDataType.STRING)
        if (!value.isNullOrBlank()) return value.uppercase()
    }

    if (!mmoItemsLoaded()) return null

    // Legacy fallback — io.lumine.mythic.lib.api.item.NBTItem.get(stack).getString("MMOITEMS_ITEM_ID")
    return try {
        val nbtItemCls = Class.forName("io.lumine.mythic.lib.api.item.NBTItem")
        val get = nbtItemCls.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 } ?: return null
        val nbt = get.invoke(null, stack) ?: return null
        val getString = nbtItemCls.methods.firstOrNull { it.name == "getString" && it.parameterCount == 1 } ?: return null
        val raw = getString.invoke(nbt, "MMOITEMS_ITEM_ID") as? String
        raw?.takeIf { it.isNotBlank() }?.uppercase()
    } catch (_: Throwable) {
        null
    }
}

private fun isCrownFragment(stack: ItemStack?): Boolean {
    if (stack == null || stack.type == Material.AIR) return false
    // PDC tag fast-path
    val meta = stack.itemMeta
    if (meta != null && CROWN_FRAGMENT_PDC_KEY != null) {
        val tag = meta.persistentDataContainer.get(CROWN_FRAGMENT_PDC_KEY, PersistentDataType.STRING)
        if (!tag.isNullOrBlank()) return true
    }
    val id = mmoItemId(stack) ?: return false
    return id in CROWN_FRAGMENT_IDS
}

private fun crownFragmentTier(stack: ItemStack?): Int? {
    if (stack == null || stack.type == Material.AIR) return null
    val id = mmoItemId(stack) ?: return null
    return when (id) {
        "FADED_CROWN_FRAGMENT_1" -> 1
        "FADED_CROWN_FRAGMENT_2" -> 2
        "FADED_CROWN_FRAGMENT_3" -> 3
        "FADED_CROWN_FRAGMENT_4" -> 4
        else -> null
    }
}

@Entry(
    "player_holding_wheat_seeds_fact",
    "1 if the player's main hand item is WHEAT_SEEDS.",
    Colors.GREEN,
    "mdi:seed",
)
class PlayerHoldingWheatSeedsFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val item = player.inventory.itemInMainHand
        return FactData(if (item.type == Material.WHEAT_SEEDS) 1 else 0)
    }
}

@Entry(
    "player_holding_crown_fragment_fact",
    "1 if the player's main hand is a Faded Crown Fragment (MMOItem id or PDC tag verdantia:crown_fragment).",
    Colors.YELLOW,
    "mdi:crown",
)
class PlayerHoldingCrownFragmentFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        return FactData(if (isCrownFragment(player.inventory.itemInMainHand)) 1 else 0)
    }
}

@Entry(
    "player_holding_all_four_fragments_fact",
    "1 if the player has all four FADED_CROWN_FRAGMENT_n items somewhere in their inventory.",
    Colors.YELLOW,
    "mdi:crown-circle",
)
class PlayerHoldingAllFourFragmentsFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        if (!mmoItemsLoaded()) return FactData(0)
        val found = BooleanArray(4)
        for (stack in player.inventory.contents ?: emptyArray()) {
            val tier = crownFragmentTier(stack) ?: continue
            if (tier in 1..4) found[tier - 1] = true
        }
        return FactData(if (found.all { it }) 1 else 0)
    }
}
