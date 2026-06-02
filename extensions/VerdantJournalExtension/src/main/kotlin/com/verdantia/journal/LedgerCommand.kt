package com.verdantia.journal

import com.typewritermc.core.extension.annotations.TypewriterCommand
import com.typewritermc.engine.paper.command.dsl.CommandTree
import com.typewritermc.engine.paper.command.dsl.*

/**
 * `/ledger` (alias `/charges`) — opens The Realm's Ledger.
 *
 * Mirrors QuestExtension's `QuestCommand.kt` idiom exactly (the documented
 * Typewriter command pattern: `@TypewriterCommand fun CommandTree.x() =
 * literal(...) { withPermission(...); executePlayer { ... } }`). This also
 * closes the long-standing UX gap that bare `/quest` errors (QuestExtension
 * ships only `track`/`untrack`, no root, no list) — `/ledger` is the real
 * player-facing entry to quest state.
 */
@TypewriterCommand
fun CommandTree.ledgerCommand() = literal("ledger") {
    withPermission("typewriter.ledger")
    executePlayer { player ->
        LedgerScreen.openRoot(player)
    }
}

@TypewriterCommand
fun CommandTree.chargesCommand() = literal("charges") {
    withPermission("typewriter.ledger")
    executePlayer { player ->
        LedgerScreen.openRoot(player)
    }
}
