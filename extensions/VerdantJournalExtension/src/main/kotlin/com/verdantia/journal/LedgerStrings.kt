package com.verdantia.journal

import com.typewritermc.engine.paper.snippets.snippet

/**
 * All player-facing strings, snippet-backed so a content pass can override them
 * in `snippets.yml` without a rebuild. Defaults are written in Cassian's regal
 * register (voice glossary: quest = Decree/Trial/Errand; reward = Tribute;
 * tutorial-of-record framing) so nothing ships off-brand even un-wired.
 *
 * These are top-level `val`s (NOT object members): `snippet()` returns a
 * `ReadOnlyProperty<Nothing?, T>`, so it can only delegate a top-level/local
 * property — the exact pattern Wayfarer/QuestExtension use
 * (`private val displayTemplate by snippet(...)` at file scope).
 *
 * Lore-safety: these are *frame* strings only. No narrative about the throne /
 * the Blight / the unnamed kings — the journal renders corpus quest strings
 * that already passed canon review; it owns no story text of its own.
 */

internal val sRootTitle by snippet("verdantjournal.root.title", "The Realm's Ledger")
internal val sRootBody by snippet(
    "verdantjournal.root.body",
    "The Steward keeps a record of every charge you carry, Citizen. Read it well.",
)

internal val sTabActive by snippet("verdantjournal.tab.active", "Open Charges")
internal val sTabAvailable by snippet("verdantjournal.tab.available", "Charges Unclaimed")
internal val sTabCompleted by snippet("verdantjournal.tab.completed", "The Chronicle")
internal val sClose by snippet("verdantjournal.button.close", "Close the Ledger")

internal val sListActiveTitle by snippet("verdantjournal.list.active.title", "Open Charges")
internal val sListAvailableTitle by snippet("verdantjournal.list.available.title", "Charges Unclaimed")
internal val sListCompletedTitle by snippet("verdantjournal.list.completed.title", "The Chronicle")
internal val sBack by snippet("verdantjournal.button.back", "Back to the Ledger")
internal val sNext by snippet("verdantjournal.button.next", "Further pages")
internal val sPrev by snippet("verdantjournal.button.prev", "Earlier pages")
internal val sTrackedSuffix by snippet("verdantjournal.list.tracked_suffix", " <#fbbf24>✦ borne</#fbbf24>")

internal val sEmptyActive by snippet(
    "verdantjournal.empty.active",
    "The ledger holds no open charges, Citizen. The Realm's folk have work — go and ask.",
)
internal val sEmptyAvailable by snippet(
    "verdantjournal.empty.available",
    "Nothing waits unclaimed. You carry the Realm's burdens already.",
)
internal val sEmptyCompleted by snippet(
    "verdantjournal.empty.completed",
    "The Chronicle is yet unwritten. Earn your first line.",
)

internal val sHeaderMatter by snippet("verdantjournal.detail.header.matter", "── The matter ──")
internal val sHeaderCharge by snippet("verdantjournal.detail.header.charge", "── Your charge ──")
internal val sHeaderTribute by snippet("verdantjournal.detail.header.tribute", "── Tribute promised ──")
internal val sNoObjective by snippet(
    "verdantjournal.detail.no_objective",
    "The path is not yet drawn. Seek the one who set this charge.",
)
internal val sNoLore by snippet("verdantjournal.detail.no_lore", "")
internal val sNoTribute by snippet("verdantjournal.detail.no_tribute", "Its own reward, Citizen.")
internal val sCompletedNote by snippet(
    "verdantjournal.detail.completed_note",
    "This charge is discharged. The Realm remembers.",
)

internal val sTrack by snippet("verdantjournal.button.track", "Take up this charge")
internal val sTrackTip by snippet(
    "verdantjournal.button.track.tip",
    "The Realm will draw you a path to it.",
)
internal val sUntrack by snippet("verdantjournal.button.untrack", "Set this charge aside")
internal val sUntrackTip by snippet(
    "verdantjournal.button.untrack.tip",
    "No path will be drawn for it.",
)

/** Default single group label (rich zone/track grouping is a content pass). */
internal val sDefaultGroup by snippet("verdantjournal.group.default", "Charges")

/** Max quest rows per list page before Next/Prev paginate (anti choice-overload). */
internal val sPageSize by snippet("verdantjournal.list.page_size", 6)
