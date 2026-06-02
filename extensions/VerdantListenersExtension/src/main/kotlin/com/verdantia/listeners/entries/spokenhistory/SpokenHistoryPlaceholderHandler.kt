package com.verdantia.listeners.entries.spokenhistory

import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.extensions.placeholderapi.PlaceholderHandler
import org.bukkit.entity.Player

/**
 * Registers PAPI placeholder `%typewriter_verdant_spoken_<dialogId>%` that returns
 * "true" or "false" indicating whether the requesting player has been shown the
 * named dialogue entry.
 *
 * The placeholder is namespaced under `typewriter` (Typewriter's PAPI expansion
 * identifier) and uses the `verdant_spoken_` prefix to avoid collisions.
 *
 * Example: `%typewriter_verdant_spoken_bard_names_aelric%` returns "true" once
 * the player has been shown the dialogue entry with id `bard_names_aelric`.
 *
 * Registered automatically via Koin's PlaceholderHandler binding — no explicit
 * registration call needed.
 */
@Singleton
class SpokenHistoryPlaceholderHandler : PlaceholderHandler {

    private val prefix = "verdant_spoken_"

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        if (!params.startsWith(prefix)) return null

        val rawId = params.removePrefix(prefix)
        if (rawId.isBlank()) return null

        return if (VerdantSpokenHistory.hasHeard(player, rawId)) "true" else "false"
    }
}
