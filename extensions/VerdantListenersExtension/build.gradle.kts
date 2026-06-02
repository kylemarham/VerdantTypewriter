// Matched-set traceability (Verdantia task #208): explicit version so this jar's
// extension manifest is distinguishable from the stale beta-172 build.
// task #262: wander activities (verdant_wander_anchor / verdant_wander_path)
// stripped out into the standalone Wayfarer extension. Entity/RoadNetwork deps
// dropped — no remaining VerdantListeners entry uses entity pathfinding.
version = "0.9.0-verdantia-262"

repositories {
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.lumine:Mythic-Dist:5.11.2")
}

typewriter {
    namespace = "verdantia"

    extension {
        name = "VerdantListeners"
        shortDescription = "Bump Typewriter facts from Bukkit & MythicMobs events."
        description = """
            |Static entry types and spoken-history tracking for Kingdoms of Verdantia.
            |Entry types: bukkit_event_listener (generic Bukkit event to fact bridge),
            |mythicmob_kill_listener (MythicMobDeathEvent per mob id),
            |block_interact_listener (right-click specific block coord, oneShot),
            |wg_region_fact_listener (PlayerMoveEvent + WG reflection, sets fact on region entry),
            |excellent_shop_sell_listener (ExcellentShop sell via reflection),
            |excellent_shop_buy_listener (ExcellentShop buy via reflection),
            |bentobox_island_create_listener (BentoBox IslandCreatedEvent via reflection),
            |deluxetags_equip_listener (tags GUI InventoryClickEvent + optional native event),
            |annals_open_listener (VerdantRemembers /annals GUI open by title prefix).
            |Spoken-history (VerdantSpokenHistory): hooks dialogue events, writes
            |said_<dialogId>=1 facts, PAPI expansion, allowlist yml config.
            |(Wander activities were moved out to the standalone Wayfarer extension — task #262.)
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("MythicMobs")
        }
    }
}
