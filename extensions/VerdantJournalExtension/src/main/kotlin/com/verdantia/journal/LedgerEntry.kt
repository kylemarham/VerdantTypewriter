package com.verdantia.journal

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger

/**
 * `ledger_open_action` — opens The Realm's Ledger for the player when this
 * action is triggered. Lets an NPC interaction, a dialogue node, or a
 * DeluxeMenus item open the journal without the player typing `/ledger`
 * (e.g. wiring the Steward to "show me my charges").
 *
 * Wiring an NPC/dialogue to this entry is corpus content and is deliberately
 * OUT of scope for this engine build (panel-clobber-gated). The entry exists so
 * that wiring is later a pure content pass with no engine change.
 */
@Entry(
    "ledger_open_action",
    "Open The Realm's Ledger (quest journal) for the player",
    Colors.RED,
    "ph:book-open-text-fill",
)
class LedgerOpenActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    @Help("Entries to trigger after the ledger is opened.")
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        LedgerScreen.openRoot(player)
    }
}
