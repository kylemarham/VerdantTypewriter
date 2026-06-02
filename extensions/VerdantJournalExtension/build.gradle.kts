// VerdantJournal — Kingdoms of Verdantia's quest journal ("The Realm's Ledger").
//
// Verdantia-coupled by design (the "Ledger" framing, Cassian-voice strings, and
// zone/track grouping are server identity — unlike the brand-clean Wayfarer
// extension, this one is NOT meant to be standalone-publishable). It is built
// inside the Typewriter monorepo because the extension SDK is consumed as
// composite-build projects (:engine:engine-paper, :QuestExtension,
// :EntityExtension, :RoadNetworkExtension).
//
// Renders a native minecraft:dialog journal over the verified Paper Dialog API
// (paper-api 1.21.11-R0.1-SNAPSHOT, present 1.21.6+). Reads the Typewriter Quest
// API only (activeQuests/inactiveQuests/completedQuests/questShowingObjectives/
// trackQuest) — no parallel state, no fact scraping. The Track button leans on
// QuestExtension's existing TrackedQuestAudience + the corpus
// locatable_objectives_path_stream for the "send me there" beam.
//
// Engine API target: Typewriter core 0.9.0 (../../version.txt) — matched exactly
// to the live server's Typewriter.jar.
version = "0.1.0"

repositories {}

dependencies {
    // Quest API (activeQuests/trackQuest/questShowingObjectives/QuestEntry) and
    // the locatable objective types live in these extensions; provided at
    // runtime by the installed Typewriter extensions.
    compileOnly(project(":QuestExtension"))
    compileOnly(project(":EntityExtension"))
    compileOnly(project(":RoadNetworkExtension"))
}

typewriter {
    // Verdantia-owned namespace. Entry/command ids are exposed as `ledger*`.
    namespace = "verdantjournal"

    extension {
        name = "VerdantJournal"
        shortDescription = "The Realm's Ledger — a native-dialog quest journal."
        description = """
            |VerdantJournal renders Kingdoms of Verdantia's quest journal ("The
            |Realm's Ledger") as a native minecraft:dialog screen.
            |
            |- /ledger (alias /charges): opens the journal root.
            |- Three views: Open Charges (active), Charges Unclaimed
            |  (available), The Chronicle (completed) — mapped 1:1 onto the
            |  Typewriter Quest API's own tri-state.
            |- Per-quest detail: lore (the matter) separated from the LIVE
            |  current objective (your charge) and the promised Tribute.
            |- Take up this charge: one click -> player.trackQuest(ref); the
            |  existing QuestExtension tracked-quest path-stream then beams the
            |  Citizen to the quest. Reversible (Set this charge aside).
            |
            |Reads only the Typewriter Quest API (no parallel state). Optional
            |ledger_open_action @Entry lets an NPC/dialogue/menu open it.
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        dependencies {
            dependency("typewritermc", "Quest")
            dependency("typewritermc", "Entity")
            dependency("typewritermc", "RoadNetwork")
        }

        paper()
    }
}
