package com.verdantia.listeners.entries.spokenhistory

import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import org.bukkit.entity.Player

/**
 * Public API for querying which dialogue entries a player has been shown.
 *
 * Backed by Typewriter's fact system. Each tracked dialogue entry corresponds to
 * a `permanent_fact` entry in Typewriter named `said_<sanitisedDialogId>`.
 * The fact is 1 once heard, 0 (or absent) otherwise.
 *
 * Usage:
 *   `VerdantSpokenHistory.hasHeard(player, "bard_names_aelric")` — true/false
 *   `VerdantSpokenHistory.allHeard(player)` — all sanitised ids with value == 1
 *
 * [dialogId] is always the raw Typewriter entry id (e.g. `bard_names_aelric`).
 * Sanitisation is applied internally.
 */
object VerdantSpokenHistory {

    /**
     * Returns true if [player] has been shown the dialogue entry identified by [dialogId].
     *
     * [dialogId] is the raw Typewriter entry id (e.g. "bard_names_aelric").
     * Returns false if no fact entry `said_<sanitised>` exists in Typewriter pages.
     */
    fun hasHeard(player: Player, dialogId: String): Boolean {
        val factName = "said_${sanitise(dialogId)}"
        val entry = Query.findById<ReadableFactEntry>(factName) ?: return false
        return entry.readForPlayersGroup(player).value >= 1
    }

    /**
     * Returns the set of raw dialogue entry ids (after removing the `said_` prefix,
     * so values are the sanitised ids) where the corresponding fact == 1 for [player].
     *
     * Iterates all registered ReadableFactEntry instances whose id starts with `said_`.
     * Use sparingly — prefer [hasHeard] for point checks.
     */
    fun allHeard(player: Player): Set<String> {
        return Query.find<ReadableFactEntry>()
            .filter { it.id.startsWith("said_") }
            .filter { it.readForPlayersGroup(player).value >= 1 }
            .map { it.id.removePrefix("said_") }
            .toSet()
    }

    /**
     * Sanitises a dialogue entry id for use as a fact name suffix.
     * Lowercases, replaces all non-alphanumeric characters with underscores,
     * and truncates to 32 characters to stay within PAPI placeholder limits.
     */
    fun sanitise(dialogId: String): String =
        dialogId.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .take(32)
}
