package com.verdantia.listeners.placeholders

import com.typewritermc.engine.paper.facts.factDatabase
import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * VerdantiaPlaceholderExpansion
 *
 * Registers two PAPI placeholders for use in DeluxeMenus / scoreboard / chat
 * format strings:
 *
 *   %verdantia_progress_bar_<questid>%     — 10-char filled/empty bar based on
 *                                            quest stage facts. Currently
 *                                            hard-coded to read
 *                                            "<questid>_stage" / 15. Generalise
 *                                            once a quest registry is in place.
 *
 *   %verdantia_induction_current_host%     — Returns the displayName of the
 *                                            host NPC for the player's current
 *                                            induction_stage value.
 *
 * Wire-up: this class must be instantiated and .register()ed on plugin enable.
 * The VerdantiaListeners extension currently has no central plugin entrypoint
 * (it's a Typewriter extension, not a standalone plugin) — registering this
 * expansion needs either:
 *   (a) a small bootstrap Listener (see Typewriter docs on extension lifecycle),
 *   (b) a separate standalone Paper plugin that hosts this expansion.
 *
 * For v0.1, recommend (b): pull this file out into a sibling Paper plugin
 * `VerdantiaGlue` whose only job is registering placeholder expansions.
 *
 * FLAGGED — DO NOT BUILD until lifecycle question is resolved with Kyle.
 */
class VerdantiaPlaceholderExpansion : PlaceholderExpansion() {

    override fun getIdentifier(): String = "verdantia"
    override fun getAuthor(): String = "Verdantia"
    override fun getVersion(): String = "0.1.0"
    override fun persist(): Boolean = true

    private val inductionHostByStage: Map<Int, String> = mapOf(
        1  to "Cassian, Steward of Verdantia",
        2  to "Old Goodwife Iris",
        3  to "Cartwright Ovid",
        4  to "Tomalin the Smith",
        5  to "Mirielle the Stitcher",
        6  to "Vagrant Quill",
        7  to "Farmer Roanin",
        8  to "Old Tobi the Angler",
        9  to "Goblin Hunter Vex",
        10 to "Old Granny Dust",
        11 to "Cassian, Steward of Verdantia",
        12 to "Maelis, Decree Bearer",
        13 to "The Reset Herald",
        14 to "Harbour Master Lys",
        15 to "Cassian, Steward of Verdantia",
    )

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return null

        if (params.startsWith("progress_bar_")) {
            val questId = params.removePrefix("progress_bar_")
            return renderProgressBar(player, questId)
        }

        if (params == "induction_current_host") {
            val stage = readFactInt(player, "induction_stage")
            return inductionHostByStage[stage] ?: "—"
        }

        return null
    }

    private fun renderProgressBar(player: OfflinePlayer, questId: String): String {
        // For v0.1 we only handle the induction quest's 15-stage shape.
        // Generalise once other quests adopt the pattern.
        val total = when (questId) {
            "induction" -> 15
            else -> return "—"
        }
        val stageFactName = when (questId) {
            "induction" -> "induction_stage"
            else -> return "—"
        }
        val stage = readFactInt(player, stageFactName).coerceIn(0, total)
        val filled = (stage.toDouble() / total * 10).toInt()
        return "§6" + "█".repeat(filled) + "§8" + "░".repeat(10 - filled)
    }

    private fun readFactInt(player: OfflinePlayer, factName: String): Int {
        val factEntry: ReadableFactEntry = Query.findWhere<ReadableFactEntry> { it.name == factName }
            .firstOrNull() ?: return 0
        val data = factDatabase.getCachedFact(player.uniqueId, factEntry.ref()) ?: return 0
        return data.value
    }
}
