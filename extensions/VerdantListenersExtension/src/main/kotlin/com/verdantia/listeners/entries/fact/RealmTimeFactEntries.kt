package com.verdantia.listeners.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player

/**
 * Helper — returns the in-game hour (0..23) of the main overworld, or null
 * if no world is loaded. Minecraft tick 0 = 06:00, tick 18000 = 00:00.
 */
private fun realmHour(): Int? {
    val world: World = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NORMAL }
        ?: Bukkit.getWorlds().firstOrNull()
        ?: return null
    val time = world.time // 0..23999
    return (((time + 6000) / 1000) % 24).toInt()
}

@Entry(
    "realm_time_after_22_fact",
    "1 if overworld hour is 22, 23, 0, 1, 2, 3, 4 or 5 (late-night/pre-dawn).",
    Colors.BLUE,
    "mdi:weather-night",
)
class RealmTimeAfter22FactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val hour = realmHour() ?: return FactData(0)
        return FactData(if (hour >= 22 || hour < 6) 1 else 0)
    }
}

@Entry(
    "realm_time_after_23_fact",
    "1 if overworld hour is 23, 0, 1, 2, 3 or 4 (deep night).",
    Colors.BLUE,
    "mdi:weather-night",
)
class RealmTimeAfter23FactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val hour = realmHour() ?: return FactData(0)
        return FactData(if (hour >= 23 || hour < 5) 1 else 0)
    }
}

@Entry(
    "realm_time_after_02_before_04_fact",
    "1 if overworld hour is 2 or 3 (witching hour window).",
    Colors.BLUE,
    "mdi:ghost-outline",
)
class RealmTimeAfter02Before04FactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val hour = realmHour() ?: return FactData(0)
        return FactData(if (hour in 2..3) 1 else 0)
    }
}
