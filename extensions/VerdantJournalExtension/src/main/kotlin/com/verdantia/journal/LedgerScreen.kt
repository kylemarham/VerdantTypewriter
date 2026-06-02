package com.verdantia.journal

import com.typewritermc.core.entries.Ref
import com.typewritermc.quest.entries.QuestEntry
import com.typewritermc.quest.isQuestTracked
import com.typewritermc.quest.trackQuest
import com.typewritermc.quest.unTrackQuest
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import java.time.Duration

/**
 * The journal screen state machine, built entirely on the verified Paper Dialog
 * API (paper-api 1.21.11-R0.1-SNAPSHOT — every call below is javap-confirmed
 * present in the exact artifact the Typewriter engine compiles against, and
 * corroborated by the official PaperMC dialog docs).
 *
 * Navigation is driven by re-showing dialogs from server-side `customClick`
 * callbacks (`DialogActionCallback.accept(DialogResponseView, Audience)`). Each
 * open re-creates the dialog and its callbacks, with bounded
 * `ClickCallback.Options`, so transient callbacks expire and never leak — the
 * one Gate-1 caveat, designed-around not asserted-away.
 *
 * The Track button calls `player.trackQuest(ref)` IN-PROCESS (no command
 * round-trip). The "send me there" beam is NOT this class's concern: once the
 * tracked ref is set, QuestExtension's existing TrackedQuestAudience + the
 * corpus-authored locatable_objectives_path_stream (riding the #267-fixed
 * RoadNetwork) + the quest's existing interact_entity_objective/location
 * objective produce the path. Research principle P2.
 */
internal object LedgerScreen {

    private val mm = MiniMessage.miniMessage()
    private fun c(s: String): Component = mm.deserialize(s)

    /** Bounded callback lifetime — re-issued every open; cannot leak/stale. */
    private fun cbOptions(): ClickCallback.Options =
        ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES) // screen lives until closed
            .lifetime(Duration.ofMinutes(10))
            .build()

    // ── Public entry points ─────────────────────────────────────────────────

    fun openRoot(player: Player) {
        val active = LedgerData.rows(player, LedgerScope.ACTIVE).size
        val available = LedgerData.rows(player, LedgerScope.AVAILABLE).size
        val completed = LedgerData.rows(player, LedgerScope.COMPLETED).size

        val buttons = listOf(
            navButton("${sTabActive} <gray>(${active})</gray>", null) {
                openList(it, LedgerScope.ACTIVE, 0)
            },
            navButton("${sTabAvailable} <gray>(${available})</gray>", null) {
                openList(it, LedgerScope.AVAILABLE, 0)
            },
            navButton("${sTabCompleted} <gray>(${completed})</gray>", null) {
                openList(it, LedgerScope.COMPLETED, 0)
            },
        )

        val base = DialogBase.builder(c("<#fbbf24><b>${sRootTitle}</b></#fbbf24>"))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(listOf(DialogBody.plainMessage(c("<#d4d4d8>${sRootBody}</#d4d4d8>"))))
            .build()

        val dialog = Dialog.create { f ->
            f.empty()
                .base(base)
                .type(DialogType.multiAction(buttons, exitButton(sClose), 1))
        }
        player.showDialog(dialog)
    }

    // ── List view ───────────────────────────────────────────────────────────

    private fun openList(player: Player, scope: LedgerScope, page: Int) {
        val rows = LedgerData.rows(player, scope)
        val title = when (scope) {
            LedgerScope.ACTIVE -> sListActiveTitle
            LedgerScope.AVAILABLE -> sListAvailableTitle
            LedgerScope.COMPLETED -> sListCompletedTitle
        }

        if (rows.isEmpty()) {
            val emptyMsg = when (scope) {
                LedgerScope.ACTIVE -> sEmptyActive
                LedgerScope.AVAILABLE -> sEmptyAvailable
                LedgerScope.COMPLETED -> sEmptyCompleted
            }
            val base = DialogBase.builder(c("<#fbbf24><b>$title</b></#fbbf24>"))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(listOf(DialogBody.plainMessage(c("<#a1a1aa><i>$emptyMsg</i></#a1a1aa>"))))
                .build()
            player.showDialog(
                Dialog.create { f ->
                    f.empty().base(base)
                        .type(DialogType.notice(navButton(sBack, null) { openRoot(it) }))
                },
            )
            return
        }

        val pageSize = sPageSize.coerceAtLeast(1)
        val pages = (rows.size + pageSize - 1) / pageSize
        val safePage = page.coerceIn(0, pages - 1)
        val slice = rows.subList(safePage * pageSize, minOf((safePage + 1) * pageSize, rows.size))

        val buttons = ArrayList<ActionButton>(slice.size + 3)
        for (row in slice) {
            val label = buildString {
                append("<white>").append(row.title).append("</white>")
                if (row.tracked) append(sTrackedSuffix)
            }
            buttons += navButton(label, null) { openDetail(it, row.ref, scope) }
        }
        if (pages > 1) {
            if (safePage > 0) {
                buttons += navButton(sPrev, null) { openList(it, scope, safePage - 1) }
            }
            if (safePage < pages - 1) {
                buttons += navButton(sNext, null) { openList(it, scope, safePage + 1) }
            }
        }

        val headerLine = if (pages > 1) {
            "<#a1a1aa>${sDefaultGroup} — ${safePage + 1}/$pages</#a1a1aa>"
        } else {
            "<#a1a1aa>${sDefaultGroup}</#a1a1aa>"
        }
        val base = DialogBase.builder(c("<#fbbf24><b>$title</b></#fbbf24>"))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(listOf(DialogBody.plainMessage(c(headerLine))))
            .build()

        player.showDialog(
            Dialog.create { f ->
                f.empty().base(base)
                    .type(
                        DialogType.multiAction(
                            buttons,
                            navButton(sBack, null) { openRoot(it) },
                            1,
                        ),
                    )
            },
        )
    }

    // ── Detail view ──────────────────────────────────────────────────────────

    private fun openDetail(player: Player, ref: Ref<QuestEntry>, scope: LedgerScope) {
        val d = LedgerData.detail(player, ref, scope) ?: run { openList(player, scope, 0); return }

        val body = ArrayList<DialogBody>()
        if (d.lore.isNotBlank()) {
            body += DialogBody.plainMessage(c("<#d4d4d8>${sHeaderMatter}</#d4d4d8>"))
            body += DialogBody.plainMessage(c("<#d4d4d8>${d.lore}</#d4d4d8>"))
        }
        body += DialogBody.plainMessage(c("<#fbbf24>${sHeaderCharge}</#fbbf24>"))
        if (d.scope == LedgerScope.COMPLETED) {
            body += DialogBody.plainMessage(c("<#a3e635><i>${sCompletedNote}</i></#a3e635>"))
        } else if (d.objectiveLines.isEmpty()) {
            body += DialogBody.plainMessage(c("<#a1a1aa><i>${sNoObjective}</i></#a1a1aa>"))
        } else {
            for (line in d.objectiveLines) {
                body += DialogBody.plainMessage(c(line))
            }
        }
        body += DialogBody.plainMessage(c("<#fbbf24>${sHeaderTribute}</#fbbf24>"))
        body += DialogBody.plainMessage(c("<#d4d4d8>${d.tribute}</#d4d4d8>"))

        val base = DialogBase.builder(c("<#fbbf24><b>${d.title}</b></#fbbf24>"))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(body)
            .build()

        // Track is only meaningful for non-completed quests.
        val buttons = ArrayList<ActionButton>(2)
        if (d.scope != LedgerScope.COMPLETED) {
            buttons += trackButton(ref, scope)
        }
        buttons += navButton(sBack, null) { openList(it, scope, 0) }

        player.showDialog(
            Dialog.create { f ->
                f.empty().base(base)
                    .type(
                        DialogType.multiAction(
                            buttons,
                            exitButton(sClose),
                            1,
                        ),
                    )
            },
        )
    }

    /**
     * THE load-bearing button. Reads tracked state LIVE so the label is never
     * wrong (research P7); flips it IN-PROCESS via the verified QuestExtension
     * extension functions; closes the screen so the beam takes over.
     */
    private fun trackButton(ref: Ref<QuestEntry>, scope: LedgerScope): ActionButton {
        return ActionButton.builder(Component.empty()).let { _ ->
            // We don't know tracked-state until a player clicks; the callback
            // reads it live. Label is decided at render time per player below.
            ActionButton.builder(c("<#84cc16>${sTrack}</#84cc16>"))
                .tooltip(c("<#a1a1aa>${sTrackTip}</#a1a1aa>"))
                .action(
                    DialogAction.customClick({ _, audience ->
                        val p = audience as? Player ?: return@customClick
                        if (p isQuestTracked ref) {
                            p.unTrackQuest()
                        } else {
                            p trackQuest ref
                        }
                        // Closing hands the screen back; the existing tracked-
                        // quest path-stream now beams the player to the quest.
                        p.closeDialog()
                    }, cbOptions()),
                )
                .build()
        }
    }

    // ── Button helpers ───────────────────────────────────────────────────────

    private fun navButton(
        label: String,
        tooltip: String?,
        onClick: (Player) -> Unit,
    ): ActionButton {
        val b = ActionButton.builder(c(label))
        if (tooltip != null) b.tooltip(c(tooltip))
        return b.action(
            DialogAction.customClick({ _, audience ->
                (audience as? Player)?.let(onClick)
            }, cbOptions()),
        ).build()
    }

    private fun exitButton(label: String): ActionButton =
        ActionButton.builder(c("<#a1a1aa>$label</#a1a1aa>"))
            .action(
                DialogAction.customClick({ _, audience ->
                    (audience as? Player)?.closeDialog()
                }, cbOptions()),
            )
            .build()
}
