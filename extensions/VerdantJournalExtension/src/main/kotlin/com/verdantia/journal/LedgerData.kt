package com.verdantia.journal

import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.ref
import com.typewritermc.quest.entries.QuestEntry
import com.typewritermc.quest.entries.questShowingObjectives
import com.typewritermc.quest.activeQuests
import com.typewritermc.quest.completedQuests
import com.typewritermc.quest.inactiveQuests
import com.typewritermc.quest.isQuestTracked
import org.bukkit.entity.Player

/**
 * Read-only projection of the Typewriter Quest API into view models the screen
 * renders. Every value is read LIVE off the quest API at call time — nothing is
 * cached — so objective text can never go stale (research principle P3) and the
 * journal inherits all corpus `q_<id>_state` / `completable_objective`
 * correctness for free (it reads the quest API; the corpus drives the API).
 *
 * All Quest API entry points used here are source-verified
 * (QuestExtension QuestTracker.kt / QuestEntry.kt):
 *  - Player.activeQuests()/inactiveQuests()/completedQuests(): List<Ref<QuestEntry>>
 *  - Player.questShowingObjectives(ref): Sequence<ObjectiveEntry>  (.display(player))
 *  - Player.isQuestTracked(ref) / Player.trackedQuest()
 *  - QuestEntry.display(player): String  (PAPI-parsed displayName)
 */
internal enum class LedgerScope { ACTIVE, AVAILABLE, COMPLETED }

internal data class QuestRow(
    val ref: Ref<QuestEntry>,
    val title: String,
    val tracked: Boolean,
    val group: String,
)

internal data class QuestDetail(
    val ref: Ref<QuestEntry>,
    val scope: LedgerScope,
    val title: String,
    val lore: String,
    val objectiveLines: List<String>,
    val tribute: String,
    val tracked: Boolean,
)

internal object LedgerData {

    fun rows(player: Player, scope: LedgerScope): List<QuestRow> {
        val refs: List<Ref<QuestEntry>> = when (scope) {
            LedgerScope.ACTIVE -> player.activeQuests()
            LedgerScope.AVAILABLE -> player.inactiveQuests()
            LedgerScope.COMPLETED -> player.completedQuests()
        }
        return refs.mapNotNull { ref ->
            val quest = ref.get() ?: return@mapNotNull null
            QuestRow(
                ref = ref,
                title = quest.display(player),
                tracked = player isQuestTracked ref,
                group = groupOf(quest),
            )
        }.sortedWith(
            // tracked first, then by group, then title — research P5/P7.
            compareByDescending<QuestRow> { it.tracked }
                .thenBy { it.group }
                .thenBy { it.title },
        )
    }

    fun detail(player: Player, ref: Ref<QuestEntry>, scope: LedgerScope): QuestDetail? {
        val quest = ref.get() ?: return null
        val objectives: List<String> = if (scope == LedgerScope.ACTIVE) {
            player.questShowingObjectives(ref)
                .map { it.display(player) }
                .filter { it.isNotBlank() }
                .toList()
        } else {
            emptyList()
        }
        return QuestDetail(
            ref = ref,
            scope = scope,
            title = quest.display(player),
            lore = loreOf(quest),
            objectiveLines = objectives,
            tribute = tributeOf(quest),
            tracked = player isQuestTracked ref,
        )
    }

    fun resolve(id: String): Ref<QuestEntry>? =
        (Query.findById(QuestEntry::class, id))?.ref()

    /**
     * Grouping key. v1 ships a single safe group (compiles & runs un-wired).
     * Rich zone/track grouping is a deliberately-deferred content pass (the
     * panel-clobber-gated wiring) — the hook exists so adding it later is purely
     * additive and needs no engine change.
     */
    private fun groupOf(@Suppress("UNUSED_PARAMETER") quest: QuestEntry): String =
        sDefaultGroup

    /**
     * Lore blurb. The stock QuestEntry exposes no dedicated lore field, so v1
     * renders the safe empty default; a content pass can surface corpus lore via
     * a snippet or a future quest-metadata field without an engine change. The
     * journal owns NO story text (lore-safety by construction).
     */
    private fun loreOf(@Suppress("UNUSED_PARAMETER") quest: QuestEntry): String =
        sNoLore

    /** Reward preview source intersects ohso's open reward economy-gate call;
     *  v1 renders the safe default until that decision + metadata exist. */
    private fun tributeOf(@Suppress("UNUSED_PARAMETER") quest: QuestEntry): String =
        sNoTribute
}
