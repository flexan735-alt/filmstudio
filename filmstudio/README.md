# Film Studio (Fabric, Minecraft 1.21.11)

A complete in-game film studio: record actors, edit scenes, and play them back as real player-like entities
with physics, combat, mobs, drops, custom skins and imported 3D models. Everything is driven from a dashboard UI.

## Build

Requirements: **JDK 21**, internet access (Gradle downloads Minecraft, Fabric Loom and Fabric API).

```
./gradlew build          # Windows: gradlew.bat build
```

The mod jar is `build/libs/filmstudio-1.0.0.jar`. Put it in `mods/` together with **Fabric API** (0.141.x for 1.21.11).
To test directly: `./gradlew runClient`.

Config comes from the official `fabric-example-mod` (branch 1.21.11): Mojang mappings, Loader 0.20.1, Loom 1.19-SNAPSHOT.
If Gradle cannot resolve `1.19-SNAPSHOT`, set `loom_version=1.14.5` in `gradle.properties` (the version the reference mod was built with).

## Using it

* **K** opens the dashboard (or type `/film`). **J** starts/stops recording the selected actor. **L** stops playback.
* Singleplayer/LAN: everybody can use it. Dedicated server: run `/film allow <player>` from the **server console**.
* Data lives in `<game folder>/filmstudio/` (films, recordings `.fsr`, imported skins and models).

Workflow:
1. **Films** tab: create a film.
2. **Actors** tab: add actors, set name / name tag / colour / skin / size / model / equipment. Press *Apply changes*.
3. **Record** tab: select an actor, press *Start recording*. After the countdown, play the scene. Press **J** to stop and save.
   Other actors that already have recordings play along in sync while you record (toggle *Play others*).
4. Record more actors the same way; hit each other, fight, build, mine.
5. **Playback** tab: tick actors, shift them in time (delay), choose *Loop* / *Restore world* / *At my position*, press *Play*.
6. **Assets** tab: import skins (`.png`, or drag & drop) and models (`.bbmodel`).

## What is implemented

| # | Requirement | How |
|---|---|---|
| 1 | Dashboard UI | 5-tab screen (Films, Actors, Record, Playback, Assets) + status bar + timeline lanes; keybinds; `/film` only opens it |
| 2 | Films | Each film = actors, per-actor recordings, playback settings, notes; stored on disk |
| 3 | Realistic recording | Per-tick pose, look direction, sneak/sprint/swim/fly/glide, inferred movement keys, equipment (full item data), swings, item use/release, block use, dig start/finish, attacks, entity interaction, drops, hurt, death, chat |
| 4 | Customisation | Skin (default / Minecraft username / imported PNG, slim arms), size, name, prefix/suffix, colour, name-tag visibility, equipment override per slot, pose (crouch/crawl/glide), glow, invisible, damage mode, physics mode, start delay |
| 5 | Custom 3D models | Blockbench `.bbmodel`: bones, cubes, UVs (box + per-face), embedded texture, keyframe animations (`idle`, `walk`, `attack`, `death`), fallback limb swing |
| 6 | Physics | Actors are real server-side `ServerPlayer`s driven by the *recorded inputs*, so gravity, collisions, water, holes and block changes work. A soft correction keeps them on the path; if thrown off they walk back (`recover` mode, with auto-jump), and are snapped back if stuck for 6 s |
| 7 | Knockback / damage | Real hits, real knockback and hurt animation, then recovery to the recorded path. Damage modes: normal / cannot die / invulnerable-but-pushable |
| 8 | Mobs | Mobs near the recording (all / only interacted / none) are tracked. On playback the original is adopted if alive, otherwise a copy is spawned; it follows its recorded path with AI paused, takes real hits, and dies on cue |
| 9 | Gameplay | Block break/place/use, container opening, tools, bows/food/throwables, sounds and particles come from the real actions being replayed |
| 10 | Drops / pickup | Q-drops are replayed as real item entities; mined blocks drop normally; players can pick them up; actors never vacuum items or XP |
| 11 | Multiple actors | One synchronised timeline, per-actor delay, record-while-others-play, actors can target each other (attack, knockback, damage) |

Extras: world restore (revert/re-apply the blocks a recording changed, automatically before each play), trimming a take,
duplicating actors, looping, playing at your current position.

## Known limits (honest list)

* Actors appear in the tab list, count as players for mob spawning/chunk loading, and hostile mobs can target them.
* To damage actors with your own hits, PvP must be on (default in singleplayer).
* Riding vehicles/boats, villager trading and complex GUIs are not recorded. Elytra/creative flight are replayed by exact position.
* Mob puppets restore: type, baby, custom name, sheep colour. Other variants are not preserved. Their AI is off during playback.
* Model actors: walk, jump, knockback, damage, attack and block *breaking* work; they do not render held items/armour and cannot place blocks.
* Movement keys are inferred from how the player moved (the server does not expose raw key state reliably).

## Project layout

```
src/main   server + shared: data/ (films, recordings), rec/ (Recorder), play/ (ActorPlayer, ActorRuntime, MobPuppet, sessions),
           net/ (protocol), model/ (ModelDef parser, ModelActorEntity), mixin/, util/
src/client dashboard (client/ui), skin + model caches, model renderer, key bindings, skin mixin
```

## Verification status

This was written without access to Mojang's/Fabric's Maven servers, so **it has not been compiled or run**.
What was done instead: every API name was cross-checked against the real 1.21.11 Fabric API sources, the
reference mod's source, and Yarn mappings; the code was syntax-checked with `javac`. The few spots I could not
confirm (most likely places for a first-build error) are:

1. `client/ModelActorRenderer.java`: `RenderTypes.entityCutoutNoCull(...)` and the `submitCustomGeometry` lambda
2. `client/ui/DashboardScreen.java`: slider widget overrides (`AbstractSliderButton`)
3. `play/ActorRuntime.java`: a few `LivingEntity`/`Player` method names (`setZza`, `setXxa`, `startFallFlying`, `animateHurt`)
4. `mixin/LivingEntityMixin.java` / `PlayerMixin.java`: injection descriptors
5. `gradle.properties`: `loom_version`

If the build prints errors, they will be in one of these files. Send me the Gradle output and they are quick to fix.
