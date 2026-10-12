# Tests to run

Everything here compiles on both loaders. The automated scenarios below were run on NeoForge on 2026-10-11 (five
batches; bugs found along the way were fixed and re-run); ticked rows passed. The rest still needs trying in game.
Tick items off as they pass; note anything odd next to them.

Testing is done on **Fabric** from now on. Automated scenarios run in the Fabric dev client (see "Live tests" in
the README):

```
./gradlew runClient -Pautotest=<names> -Pautoquit
```

Results land in `run/autotest/results.txt`, screenshots in `run/screenshots/`. (The ticked results below were
measured on NeoForge, before the switch; re-run on Fabric to confirm.) A scenario passes
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

## Applied Energistics 2 (dev clients: Fabric AE2 15.4.11 + ae2wtlib 15.2.1, NeoForge AE2 19.2.17 + ae2wtlib 19.5.1)

NeoForge live tests build a real network next to the arena (controller, creative energy cell, drive with a 64k
cell, boosted access point, terminal on a cable) and fill it with 448 stone bricks and 128 dirt:
`cd neoforge && ./gradlew runClient -Pautotest=me-wireless,me-terminal -Pautoquit`.

| Done | What | How | Result |
|---|---|---|---|
| [x] | Wireless terminal fetch | `me-wireless` (NeoForge) | PASS 146/146 in 48-64s; 191 bricks out of the network = 146 placed + 45 carried, client count matches the server |
| [x] | ae2wtlib terminal | `me-wtlib` (NeoForge): only an ae2wtlib wireless pattern encoding terminal | PASS 146/146 in 50s; AE2's own hotkey can't open it, ae2wtlib's does. Still to try: universal terminal, Curios/Trinkets slot |
| [ ] | Out of range | Wireless terminal out of range of the access point | Gives up quickly, falls back to a linked ME terminal or chests, no loop |
| [x] | Link an ME terminal | Link tool on a terminal or crafting terminal; `me-terminal` links a crafting terminal from a click on its rim | AE2 picks the part at the clicked point, so the rim counts (was: only the exact face, crafting terminals often failed). Cyan plate not checked yet |
| [x] | Walk to ME terminal | `me-terminal` (NeoForge) | PASS 146/146 in 57s; walks to the linked terminal, takes bricks, and later dirt for scaffolding |
| [ ] | Network + chests | Network has some of the bricks, a linked chest the rest | Takes from the network first, then the chest |
| [x] | Never opened | Every ME scenario starts with the network forgotten | Builds still go to the network first and then know its contents |
| [ ] | Item bar counts | Open any terminal by hand | Item bar and Materials screen include the network's counts; storage panel says "ME (wireless): N item types" |
| [x] | Scaffolding from ME | `me-terminal` (no dirt carried) | Fetched dirt from the network to climb to the roof centre, removed it after |
| [ ] | Inventory full | Nearly full inventory | Only asks for as many stacks as there are free slots |
| [ ] | Setting off | Settings > Use AE2 for materials off | Network ignored, chests only, no ME line in the storage panel |
| [ ] | No AE2 | Run without AE2 installed | Nothing changes, no errors in the log |
| [x] | Full inventory + Baritone | `me-terminal-full` (hotbar full of stone, tools in the main inventory) | Was stuck: Baritone's allowInventory keeps the pickaxe in hotbar slot 1 and the builder swapped blocks into that same slot, so they fought over it. Builder now swaps into slots 2-8. PASS 146/146 in 50s |
| [ ] | Terminal closed on first open | Seen once in `me-terminal-full`: "The ME terminal was closed early" right as the first fetch opened it | Retry got the items; find out what closes it |
| [ ] | NeoForge (ATM10) | Wireless terminal fetch in ATM10 | Same as the dev client |
| [ ] | Fabric | Same scenarios on Fabric (harness not ported yet) | Same as NeoForge |

## Refined Storage (NeoForge dev client has RS 2.0.9 + RS Quartz Arsenal 1.0.8)

NeoForge live tests build an RS network (creative controller, disk drive with a 64k disk, crafting grid, wireless
transmitter) and fill it with 448 stone bricks and 128 dirt: `cd neoforge && ./gradlew runClient -Pautotest=rs-wireless,rs-grid -Pautoquit`.

| Done | What | How | Result |
|---|---|---|---|
| [x] | Wireless crafting grid | `rs-wireless`: only a bound creative wireless crafting grid carried | PASS 146/146 in 76s; opened twice (bricks, then scaffolding dirt), 192 bricks out = 146 placed + 46 carried, client count matches |
| [x] | Linked grid | `rs-grid`: crafting grid linked, no wireless | PASS 146/146 in 58s; walks to the grid, 192 bricks out = 146 placed + 46 carried |
| [ ] | Plain wireless grid | RS's own wireless grid instead | Opens and fetches the same way |
| [ ] | Curios slot | Wireless grid in a Curios slot (ATM10) | Found and opened |
| [ ] | Out of range | No wireless transmitter in range | Gives up quickly, falls back to a linked grid or chests |
| [ ] | AE2 + RS | Both networks, items split between them | AE2 first, then RS, then chests |
| [ ] | ATM10 | Real RS network in ATM10 | Same as the dev client |

## Backpacks

| Done | What | How | Result |
|---|---|---|---|
| [ ] | Sophisticated Backpacks (ATM10) | Bricks only in a backpack (inventory, then worn / Curios), build a box | Backpack opens for a moment, bricks come out, it closes, the build goes on; no walking |
| [ ] | Traveler's Backpack (Prominence) | Bricks only in a worn Traveler's Backpack, then one carried in the inventory | Same; worn one opens like its key, carried one by using it |
| [ ] | No backpack | Backpack mod installed, none carried | One "didn't open" at most per session, then chests / ME as usual |
| [ ] | Upgrades untouched | Backpack with upgrades / tools in its slots | Only stored items are taken; upgrades, tools and filters stay |
| [ ] | Index | Index with a backpack | Its contents show in the item bar |

## Modded storage (ATM10)

| Done | What | How | Result |
|---|---|---|---|
| [ ] | Link modded storage | Link tool on Sophisticated Storage chest/barrel, Iron Chests, crates | Links; reading/fetching/storing works through its screen |
| [ ] | Scan nearby | Scan in a base with modded chests, drawers and machines | Links chests/barrels/crates, not drawers, furnaces, pipes or AE2 blocks |
| [ ] | Drawers refused | Link tool on a Storage Drawers / Functional Storage drawer | "That's not a storage block" (a click would insert the held item) |

## Start / Pause, hologram, controls panel

| Done | What | How | Result |
|---|---|---|---|
| [ ] | Orders wait | Queue a shape (Enter or click) | "queued - press Start", ORDERS says "Waiting - press Start", the character doesn't move |
| [ ] | Start | Press Start | The queue runs; button shows "Running" |
| [ ] | Pause | Press Pause mid-build | The character stops at once (no half-mined block, no walking); Start carries on from there |
| [ ] | Queue empties | Let the queue finish, queue another order | It waits for Start again |
| [ ] | Start right away | Setting on | Orders start as soon as they're queued (old behaviour) |
| [ ] | Fetch / helpers | A build that has to fetch materials | The fetch runs inside the started queue, no extra Start needed |
| [ ] | Auto-repair | Auto-repair on, break a block of a finished build | The repair order starts by itself |
| [ ] | Hologram | Queue a build, leave RTS mode | Ghost blocks stay visible in normal play; H hides/shows them |
| [ ] | Manual placing | Place a few of the ghost's blocks by hand | They drop out of the ghost within a second |
| [ ] | ATM10 render | Hologram on in ATM10 (Embeddium / Oculus) | No player, mob or portal drawn in the wrong place (the old bug) |
| [ ] | Next to a chest / crafting table | Hologram block whose only neighbour is a chest, crafting table, smithing table, modded machine; hold Z | Block placed, no screen opens (sneak-click). Same for the builder |
| [ ] | Screen opens anyway | A modded block that ignores sneaking | Screen closes at once, that spot is skipped, no getting stuck |
| [ ] | Hold-Z range | Settings > Hold-Z place range = 2 blocks, hold Z beside a big hologram | Only blocks within 2 blocks of your eyes go in; Full reach goes back to normal |
| [ ] | Hold X (aimed) | Look at a ghost block, hold X; sweep the crosshair over a wall of ghosts | Places only the ghost under the crosshair; real blocks stop the look |
| [ ] | Hold Z to place | Queue a build, leave RTS mode, stand near it and hold Z | Blocks in reach go in, lowest first, about 5 a second; the view doesn't move |
| [ ] | Facing | Order with stairs / logs / furnaces, hold Z looking elsewhere | They come out facing the way the order wants |
| [ ] | Hand back | Let go of Z | The slot you had selected is selected again |
| [ ] | Queue running | Start the queue, hold Z | "Pause the queue to place blocks by hand", nothing placed |
| [ ] | Missing blocks | Hold Z without the order's block in the inventory | "Nothing to place: no X in your inventory" every few seconds |
| [ ] | Finished | Place the last block by hand | The order comes off the queue as finished |
| [ ] | Server-safe Z | Server-safe on: hold Z beside a hologram with stairs and a floating block | No view snaps sent, the floating block and wrong-facing stairs are skipped ("would face the wrong way"), max 5/s |
| [ ] | Server-safe X | Server-safe on: look at a block face next to a ghost, hold X | Places like a normal right-click into the ghost cell; nothing when the crosshair isn't next to a ghost |
| [ ] | Anti-cheat server | Hold Z / X on a server with Grim or NCP, server-safe on | No flags or rubber-banding |
| [ ] | Speed | Server-safe off, Hold-Z place speed 20 | About 20 blocks a second; with server-safe on, 5 |
| [ ] | Index reads networks | Carry a wireless terminal / grid (or link an ME terminal / RS grid), press Index | "Read ME/RS network" in the queue, terminal opens and closes, item bar shows the network's items; "Indexed N container(s) and M storage network(s)" |
| [ ] | Re-index | Re-index with networks already read | Reads every reachable network again |
| [ ] | Mining range on a server | Clear order on the ATM10 server | Blocks stay mined (no popping back); if one does, "The server put X back - mining it from closer" and it's mined from close up |
| [ ] | Materials: remove a block | Materials, click the ✕ on a row twice | "Remove?" after the first click; then that block is gone from every queued order, the hologram and the list; emptied orders leave the queue; a running build restarts without it |
| [ ] | Stairs shape | Stairs with oak planks, stone bricks, an oak log as the brush | Steps are oak / stone brick / oak stairs facing up, full blocks underneath (hollow: stairs only) |
| [ ] | Cut a doorway | Queue a hollow box (don't start), Destroy tool + Wall shape on its side over the ghost blocks | Cursor lands on the ghost blocks; "Removed N block(s) from the queued orders", the hologram has the gap; the build leaves it open |
| [ ] | Ghost + real | Destroy shape covering both ghost blocks and real ones | Ghosts removed from the orders, real blocks get a clear order |
| [ ] | Roof styles | Build a 7x9 room, Roof with oak stairs, each Style | Gable / Steep / Barn / Lean-to have closed ends (lean-to also its high side), Hip and Mansard slope on all sides (Mansard falls back to Hip on small rooms), Flat has a slab parapet |
| [ ] | Roof looks | Same room, Look = Cottage / Witch / Snowy / Medieval with Gable and Steep | Dark outline on the eaves, end rows, ridge and gable ends; Witch: flared eaves + rods on the ridge; Snowy: mixed stairs with white patches; Cottage / Medieval: chimney with a campfire through the roof |
| [ ] | Bridge styles | Bridge shape over a river, each look (Bridge button next to Mode), no brush picked | Preview shows the look; Medieval/Viaduct/Garden/Stone/Overgrown/Covered build with their own blocks, pillars reach the river bed, stairs face along the hump, the Covered roof meets at a ridge |
| [ ] | Protected clear | `protect-clear` (NeoForge): stone block, half protected, cleared | Protected half stays, the rest is mined |
| [ ] | Protect tool | Tool 8, two corners around a base, then order a big clear / quarry over it | Red outline; nothing inside is broken ("in a protected area"); click inside to remove |
| [ ] | Protect + Baritone | Same with the Baritone engine | Baritone can't break inside (blocked), may route around |
| [ ] | Survival flight item | ATM10 survival with an Angel Ring, flying, give a build order | Lands first, then walks; with "Fly with flight items" on, flies to blocks |
| [ ] | Removed storage | Link a chest / ME terminal / RS grid, break it | "... are gone - unlinked" within a few seconds; the builder never walks to it |
| [ ] | Cursor offset | RTS mode while flying / sprinting / with Speed | Cursor highlight stays under the mouse (view uses the plain FOV setting) |
| [ ] | Inverted shapes | Dome / pyramid / cone, I or the Inverted button | Built upside down in the same space (funnel, bowl); preview updates |
| [ ] | Inverted digging | Destroy tool, cut view down, inverted dome / cone clicked at the deepest point of a pit | Red preview widening upward from the click to the surface; clearing digs the pit |
| [ ] | Forget all | Storage panel, click twice | Every link, ME/RS terminal and remembered content forgotten |
| [ ] | Item bar kinds | Item bar: Blocks / Tools / Misc, combined with Inventory / Storage and search | Blocks only placeable blocks, Tools pickaxes/swords/armour/modded tools, Misc the rest; clicking the selected kind again shows all |
| [ ] | Controls panel | F1, or the ? button in RTS mode | Panel with every control, two columns on small screens; click or F1/Esc closes it |

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

- [x] Blueprint > Export... writes `.minecraft/schematics/<name>.schem` (dev client: copy_1.schem); Shift+click writes `.nbt` (not tried)
- [x] Exported file loads back with Load schematic... and matches (23 x 34 x 12, 1,605 blocks)
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

## Camera (rebuilt: RTS mode changes no game settings)

| Done | What | How | Expect |
|---|---|---|---|
| [ ] | Leaving RTS restores the view | First person, G in and out; then F5 back view, G in and out; then front view | Always back in the same view as before, HUD and hand as before |
| [x] | Normal third person after RTS | Use RTS, leave, F5 and walk/turn around | Fine in the NeoForge dev client; confirm in ATM10 (the player model was drawn in the wrong place by the old render hook) |
| [ ] | F1 untouched | F1 (HUD off), G in and out | HUD still off after; with HUD on, still on |
| [ ] | RTS view itself | WASD, scroll, right-drag, middle-drag, Q | Moves as before; your character visible, no block outline where it looks |
| [ ] | Through other screens | E / T / chest from RTS mode, close it | Back in RTS view where you were |
| [ ] | Death / dimension change in RTS mode | Die, or go through a portal | RTS closes, normal view, nothing stuck |
| [ ] | Packs with Leawind's Third Person / Blur | Prominence II | Camera moves, no blur, normal third person fine after |
| [ ] | Baritone mining in RTS mode | Destroy order with Engine: Baritone | Still mines (it reads what the player looks at) |

## Interact with items (Fabric)

| Done | What | How | Expect |
|---|---|---|---|
| [ ] | Shear a sheep | Interact tool, pick Shears in the item bar, click a sheep | Walks over (following it), shears it once |
| [ ] | Attack a mob | Pick a sword (or axe), click a zombie | Follows and hits it on cooldown until it's dead |
| [ ] | Empty hand on a hostile mob | X for empty hand, click a zombie | Attacks with the best sword/axe you carry |
| [ ] | Empty hand on a villager | Click a villager | Opens trading |
| [ ] | Other items on mobs | Bucket on a cow, lead on a horse, wheat on cows, name tag | Milks / leashes / breeds / names |
| [ ] | Items on blocks | Bone meal on a sapling, hoe on dirt, flint and steel on a portal frame | Uses the item on the clicked face |
| [ ] | Hover label | Hover a mob with the Interact tool | "Shear Sheep" (blue) / "Attack Zombie" (red) next to the cursor |
| [ ] | Blocks in front | A mob behind a wall | The wall gets the click, not the mob |

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

## Fabric packs

- [ ] Prominence II: no blur behind the RTS view (Blur mod), camera moves (Leawind's Third Person installed)
