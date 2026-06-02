// Wayfarer — a standalone, open-source Typewriter extension that adds grounded
// NPC wander activities (local-radius wander + waypoint road wander).
//
// This module is intentionally brand-clean: no Verdantia coupling, MIT-licensed,
// publishable on its own. It is developed inside the Typewriter monorepo only
// because the Typewriter extension SDK is consumed as composite-build projects
// (`:engine:engine-paper`, `:EntityExtension`, `:RoadNetworkExtension`).
//
// Engine API target: Typewriter core 0.9.0 (see ../../version.txt) — matched
// exactly to the live server's Typewriter.jar. Building against a different
// core version is what broke the previous wander implementation.
version = "1.0.2"

repositories {}

dependencies {
    // Upstream activity/navigation SDK. compileOnly: these are provided by the
    // installed Typewriter Entity + RoadNetwork extensions at runtime.
    compileOnly(project(":EntityExtension"))
    compileOnly(project(":RoadNetworkExtension"))
}

typewriter {
    // Brand-clean namespace — NO "verdantia". Entry ids are exposed as
    // `wayfarer_wander_local` / `wayfarer_wander_road`.
    namespace = "wayfarer"

    extension {
        name = "Wayfarer"
        shortDescription = "Grounded NPC wander activities for Typewriter."
        description = """
            |Wayfarer adds two NPC movement activities that pathfind on the
            |ground (no floating) using Typewriter's own navigation + RoadNetwork
            |GPS layer:
            |
            |- wayfarer_wander_local: wander randomly within a radius of an anchor.
            |- wayfarer_wander_road: walk an ordered list of waypoints (LOOP /
            |  PINGPONG / ONCE).
            |
            |Shared options: speed, pause (idle min/max + chance), lookAtPlayer,
            |pauseOnInteract, returnToAnchor. Requires a RoadNetwork covering the
            |wander area — navigation, gravity and block-collision are delegated
            |to Typewriter core so the entity always grounds correctly.
            |
            |MIT licensed. https://github.com/ (open-sourceable standalone).
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        dependencies {
            dependency("typewritermc", "Entity")
            dependency("typewritermc", "RoadNetwork")
        }

        paper()
    }
}
