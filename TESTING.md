# Tests to run

Everything here compiles on both loaders. The automated scenarios below were run on NeoForge on 2026-10-11 (five
batches; bugs found along the way were fixed and re-run); ticked rows passed. The rest still needs trying in game.
Tick items off as they pass; note anything odd next to them.

Automated scenarios run in the NeoForge dev client (see "Live tests" in the README):

```
cd neoforge
./gradlew runClient -Pautotest=<names> -Pautoquit
```

Results land in `neoforge/run/autotest/results.txt`, screenshots in `neoforge/run/screenshots/`. A scenario passes
when every block is placed, nothing is left over (scaffolding) and the player isn't stuck inside.

## Building engine

| Done | What | How | Expect |
|---|---|---|---|
| [x] | Routes that pillar, bridge and dig down | `-Pautotest=sphere5,dome10` | Both complete (sphere 410 blocks, dome10 845 in 296 s); never stranded on a pillar |
| [x] | Shut in by a dome | `-Pautotest=dome6-inside` | Passed 3 times: doorway opened, player outside, dome complete |
| [x] | Shut in by a room with a door | `-Pautotest=p-power-room,p-storage-core` | Leaves through the door and puts it back |
| [ ] | No fall damage on round shapes | `-Pautotest=sphere5,torus` | **Known issue:** down from 2.5 hearts to 0.5-1 heart now and then (edge sneaking + steering while dropping); not zero yet |
| [ ] | Scaffolding fully removed on a floating sphere | `-Pautotest=sphere5` | **Known issue:** last run left 24 dirt; cleanup now gets 8 passes and warns about leftovers - re-run to confirm |
| [x] | Mid-air off (scaffolding path) | `-Pautotest=dome6-noair` | Passed, scaffolding removed |
| [ ] | Baritone selected + mid-air on | `-Pautotest=dome6-baritone` (Baritone jar in `run/mods`) | Built by the built-in engine, no "Starting layer 0 / Done building" loop |
| [x] | Regression | `-Pautotest=dome6,floor,line` | Pass (dome6 106 s, back near the old 97 s with smooth turning on) |

## Smooth turning (watch it, or record with another player)

- [ ] No snapping when placing plain blocks: the head turns toward each block
- [ ] Stairs / logs / furnaces still come out facing the right way (Exact rotation on)
- [ ] Mining, chests (Interact, Store inv), planting (Fell trees, Harvest) turn first, then click
- [ ] Walking turns smoothly, no sideways drift around corners
- [ ] Settings > Turn speed cycles 15 / 30 / 45 / 90 / Instant; Instant behaves like before

## New shapes

| Done | Shape | How | Check |
|---|---|---|---|
| [x] | Pyramid | `-Pautotest=pyramid` (hollow) | Passed; try solid by hand |
| [x] | Cone | `-Pautotest=cone` | Passed |
| [x] | Torus | `-Pautotest=torus` | Built (584 blocks); see the fall note above |
| [x] | Arch | `-Pautotest=arch` | Passed, round with no gaps (screenshot); try solid (gateway) and Rise by hand |
| [x] | Bridge | `-Pautotest=bridge` | Passed: wall/fence railings matching the deck, lantern posts, stairs on the hump; try A and B at different heights |
| [x] | Stairs | `-Pautotest=stairs` | Passed with stone; try stair blocks by hand |
| [x] | Spiral | `-Pautotest=spiral` | Passed (8-12 steps a turn); try stair blocks by hand |
| [ ] | Shape picker | Open Build | 4-wide icon grid, tooltips, chosen name under it, Height row says Rise / Width / Tube |

## Modpack presets

| Done | Preset | How | Result |
|---|---|---|---|
| [x] | Machine hall | `-Pautotest=p-machine-hall` | 561 blocks, passed |
| [x] | Power room | `-Pautotest=p-power-room` | Passed, leaves through the iron door |
| [x] | Storage core | `-Pautotest=p-storage-core` | Passed (found and fixed a turning livelock) |
| [x] | Greenhouse | `-Pautotest=p-greenhouse` | Passed (found and fixed dirt-turns-to-grass loop) |
| [x] | Launch pad | `-Pautotest=p-launch-pad` | Passed |
| [x] | Rail station | `-Pautotest=p-rail-station` | Passed |
| [x] | Bee yard | `-Pautotest=p-bee-yard` | Passed |

## Materials from storage

| Done | What | How | Result |
|---|---|---|---|
| [x] | Build blocks fetched from chests | `-Pautotest=storage-box` | Scan nearby linked and read 2 chests; every brick placed came from them (192 fetched, 146 placed) |
| [x] | Scaffolding fetched from chests | `-Pautotest=storage-box,storage-dome` | Fetches dirt when it has to build its way up, removes it after |

## Patterns

- [ ] Mix 3 blocks, cycle Pattern: Random, Clusters, Gradient, Layers, Stripes, Checker each look right in the preview
- [ ] Preview matches what gets built (try Gradient on a dome)
- [ ] Pattern button greyed out with no mix

## Tree planter (Terrain > Plant trees)

- [ ] Plants on grass, skips water / stone / existing trees, spacing -/+ works (2-16)
- [ ] Uses the brush sapling; with no sapling picked, the one you have most of
- [ ] Dark oak planted in 2x2
- [ ] Missing saplings fetched from storage; creative supply spawns them

## Schematics

- [ ] Blueprint > Export... writes `.minecraft/schematics/<name>.schem`; Shift+click writes `.nbt`
- [ ] Exported file loads back with Load schematic... and matches
- [ ] `.schem` opens in WorldEdit (`//schem load`) or Litematica; `.nbt` in a structure block
- [ ] Exporting twice with the same name makes `<name>-2`, nothing overwritten

## Saved queues

- [ ] Queue a few orders (a build, a clear, a quarry, Smelt), pause one, Save queue with a name
- [ ] Load queue lists it (this world only); loading adds the orders to the end, paused one still paused
- [ ] A half-finished build carries on with only what's left
- [ ] Leave the world with orders queued, rejoin: "were still queued when you left" message; "Last session" loads and then disappears from the list
- [ ] Quit the game with orders queued: "Last session" is there next time
- [ ] Shift+click deletes a saved queue; saving with an existing name replaces it
- [ ] Other dimension / other world: their own saved queues, not this one's

## Creative flight

| Done | What | How | Expect |
|---|---|---|---|
| [ ] | Flies to blocks in creative | `-Pautotest=fly-sphere,fly-dome10` | Flies instead of pillaring, no scaffolding at all, complete; faster than the walking runs (sphere ~250-350 s walking) |
| [ ] | Toggle | Settings > Creative flight off, creative mode | Walks and climbs like survival |
| [ ] | Survival unaffected | Any build in survival | Walks as before (can't fly) |
| [ ] | Server with /fly or flight allowed | Multiplayer | Flies when the server lets you |

## Creative supply

- [ ] Creative mode, empty inventory: a build spawns its blocks into the hotbar and runs without "missing"
- [ ] Scaffolding spawned too; nothing happens in survival
- [ ] Settings toggle turns it off

## UI

- [ ] Item counts over 99 fit their slot (no "179614")
- [ ] Shape names fit; left panel not cut off at GUI scale 3 / small windows
- [ ] Air -/+ greyed while air is off; Height -/+ greyed for shapes without height
- [ ] Orders panel shows the live activity line; top bar status trims instead of vanishing
- [ ] Air mode: coordinates chip says AIR on the layer, turns gold with "ground in front" over terrain
- [ ] Air mode: the second point of a dome/sphere stays on the layer
- [ ] Bottom bar: no rows of empty frames, quick bar still pinnable

## Fabric

All of the above is also in the Fabric build, but the harness only runs on NeoForge. Spot-check on Fabric: a
dome, smooth turning, one new shape, export, the tree planter and the UI.
