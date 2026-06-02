package com.verdantia.listeners.entries.spokenhistory

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.dialogue.currentDialogue
import com.typewritermc.engine.paper.entry.entries.WritableFactEntry
import com.typewritermc.engine.paper.events.AsyncDialogueStartEvent
import com.typewritermc.engine.paper.events.AsyncDialogueSwitchEvent
import com.typewritermc.engine.paper.plugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Hooks Typewriter's dialogue events and writes a permanent fact
 * `said_<sanitisedDialogId> = 1` whenever a tracked dialogue entry is displayed
 * to a player.
 *
 * At AsyncDialogueStartEvent time, `currentDialogue` = the entry being opened.
 * At AsyncDialogueSwitchEvent time, `currentDialogue` = the NEW entry (just
 * assigned before the event fires, per DialogueInteraction.next()). Both are
 * therefore "entries being shown right now" and should be recorded.
 *
 * Significance filter (OR logic):
 *   1. allowlist in plugins/VerdantListenersExtension/spoken-history.yml
 *   2. (future) additional programmatic allowlists can call registerAllowlisted()
 *
 * The `DialogueEntry.comment` field is defined on `FactEntry`, not `DialogueEntry`,
 * so the comment-based opt-in path is dropped per technical decision #2.
 * Authors use the allowlist only.
 *
 * The corresponding `permanent_fact` entry (id = `said_<dialogId>`) must be authored
 * in a Typewriter page before this listener can write to it. Missing entries produce
 * a logged WARNING and are skipped — no silent failure.
 *
 * FactDatabase uses ConcurrentHashMap (confirmed beta-172) — writes are safe from
 * the async event thread; no main-thread dispatch needed.
 */
@Singleton
class SpokenHistoryListener : Initializable, Listener {

    private val allowlist = mutableSetOf<String>()

    override suspend fun initialize() {
        loadAllowlist()
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("[SpokenHistory] Initialised with ${allowlist.size} allowlisted entry id(s).")
    }

    /** Allow other extensions or startup code to register ids programmatically. */
    fun registerAllowlisted(vararg ids: String) {
        allowlist.addAll(ids)
    }

    private fun loadAllowlist() {
        val configFile = plugin.dataFolder.resolve("spoken-history.yml")
        if (!configFile.exists()) {
            plugin.logger.warning(
                "[SpokenHistory] spoken-history.yml not found in ${plugin.dataFolder}. " +
                    "Allowlist will be empty. Create the file to track specific dialogue ids.",
            )
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(configFile)
        val ids = yaml.getStringList("spoken-history.allowlist")
        allowlist.addAll(ids)
        plugin.logger.info("[SpokenHistory] Loaded ${ids.size} id(s) from spoken-history.yml allowlist.")
    }

    /**
     * Fires when a new dialogue sequence begins (the very first entry).
     * At this point currentDialogue = the initial entry being shown.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDialogueStart(event: AsyncDialogueStartEvent) {
        recordCurrent(event.player)
    }

    /**
     * Fires on every entry transition. At this point DialogueInteraction.next()
     * has already assigned currentEntry = nextEntry before calling the event,
     * so currentDialogue reflects the entry NOW being shown (not the one just finished).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDialogueSwitch(event: AsyncDialogueSwitchEvent) {
        recordCurrent(event.player)
    }

    private fun recordCurrent(player: org.bukkit.entity.Player) {
        val entry = player.currentDialogue ?: return
        val entryId = entry.id

        if (entryId !in allowlist) return

        val sanitised = VerdantSpokenHistory.sanitise(entryId)
        val factName = "said_$sanitised"
        val factEntry = Query.findById<WritableFactEntry>(factName)
        if (factEntry == null) {
            plugin.logger.warning(
                "[SpokenHistory] No WritableFactEntry found for '$factName' " +
                    "(dialogue entry '$entryId'). Author a permanent_fact with this id in Typewriter.",
            )
            return
        }

        // Only write if not already set (permanent flag — never goes back to 0).
        val current = Query.findById<com.typewritermc.engine.paper.entry.entries.ReadableFactEntry>(factName)
            ?.readForPlayersGroup(player)?.value ?: 0
        if (current >= 1) return

        factEntry.write(player, 1)
        plugin.logger.fine(
            "[SpokenHistory] Recorded: player=${player.name} heard=$entryId fact=$factName",
        )
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        allowlist.clear()
    }
}
