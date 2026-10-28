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
| [x] | Multi-storey buildings | `-Pautotest=storeys2,storeys3` (Fabric, 2026-10-17) | Pass 3/3 each (storeys3 ~250 s, longest stall 4 s). One earlier run looped forever re-planning to a floor block from a pillar top; now capped (see next row) |
| [ ] | Going-in-circles guard | `-Pautotest=storeys3` a few times; watch `[BUILD] going round in circles` in the log | A block chased 40 s or 12 routes with nothing done is set aside ("Can't get to …, doing other blocks first"), the build goes on and comes back to it |
| [x] | Edge-of-reach blocks | any build; `[BUILD] to the middle of` in the log | Steps to the middle of its cell instead of giving up on a block just out of reach (was dozens of needless give-ups per build) |
| [x] | Multi-level presets | `-Pautotest=p-watchtower,p-machine-hall,p-iron-farm,p-starter-house,p-castle-wall,p-trading-hall,p-mob-grinder,p-smelter-room` | All pass; watchtower lantern moved under the platform (it hung from nothing) |
| [ ] | Scaffolding cleanup leftovers | `-Pautotest=sphere5,sphere5-noair,dome10` | Was 1 dirt left on each sphere (bot stood on the block it had to mine, then forgot it). Fixed after the run, **untested**: steps off it, and leftovers carry into the next cleanup round. Expect stray=0 |
| [ ] | No false "server put it back" | sphere5 / watchtower cleanup | No "The server put … back" warnings for blocks the bot re-filled itself |

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
| [ ] | Sophisticated Backpacks (Fabric port) | Bricks only in a Sophisticated backpack (inventory, then worn / Trinkets), build a box | Opens like its key, bricks come out, it closes; no walking |
| [ ] | Upgrade slots (Sophisticated) | Backpack with a crafting / smelting upgrade holding bricks in its grid | Bricks in the upgrade grid aren't counted or taken |
| [ ] | Placed backpack | Sophisticated backpack placed as a block and opened by hand | Not treated as carried |
| [ ] | No backpack | Backpack mod installed, none carried | One "didn't open" at most per session, then chests / ME as usual |
| [ ] | Upgrades untouched | Backpack with upgrades / tools in its slots | Only stored items are taken; upgrades, tools and filters stay |
| [ ] | Index | Index with a backpack | Its contents show in the item bar |
| [ ] | Full inventory (ATM10) | Inventory full of junk (not build blocks), stone only in a worn backpack, build stone | Backpack opens, junk from the main inventory goes into it (hotbar, tools and the build's own blocks stay), stone comes out; no "Inventory full" |
| [ ] | Contents changed unseen | Open the backpack once, then let a pickup upgrade collect bricks, wait 20 s, build bricks | Backpack is opened again and the bricks come out (no "Storage doesn't have") |

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
| [ ] | Hold Z in layers | Settings > Hold-Z in layers ON, 3-high box hologram, stand near the top with the bottom out of reach, hold Z | Nothing of the higher layers goes in; "the layer at y=N comes first". Move in reach of the bottom: it goes up a layer only once every block of the one below is in (also the parts out of sight). Option OFF: any height in reach, lowest first |
| [ ] | Hold-Z stairs (automated: `zstairs`) | Queue stone brick stairs facing all four ways, upright and upside down, hold Z | Every stair comes out facing the way the hologram shows, upside-down ones too; one that can't be placed right from here is skipped ("would face the wrong way"), never placed wrong |
| [ ] | Hologram shows the Z layer | Hold-Z in layers ON, queue a 3-high box, leave RTS mode | Hologram shows only the bottom layer; once it's all placed the next layer appears (about a second). Option OFF or back in RTS mode: the whole build shows |
| [ ] | Facing | Order with stairs / logs / furnaces, hold Z looking elsewhere | They come out facing the way the order wants |
| [ ] | Hand back | Let go of Z | The slot you had selected is selected again |
| [ ] | Queue running | Start the queue, hold Z | "Pause the queue to place blocks by hand", nothing placed |
| [ ] | Missing blocks | Hold Z without the order's block in the inventory | "Nothing to place: no X in your inventory" every few seconds |
| [ ] | Finished | Place the last block by hand | The order comes off the queue as finished |
| [ ] | Server-safe Z | Server-safe on: hold Z beside a hologram with stairs and a floating block | No view snaps sent, the floating block and wrong-facing stairs are skipped ("would face the wrong way"), max 5/s |
| [ ] | Server-safe X | Server-safe on: look at a block face next to a ghost, hold X | Places like a normal right-click into the ghost cell; nothing when the crosshair isn't next to a ghost |
| [ ] | Anti-cheat server | Hold Z / X on a server with Grim or NCP, server-safe on | No flags or rubber-banding |
| [ ] | Speed | Server-safe off, Hold-Z place speed 20 | About 20 blocks a second; with server-safe on, 5 |
| [ ] | Fast speed | Server-safe off, Hold-Z place speed 40 / 60 / 100 beside a big hologram | Visibly faster than 20 (2 / 3 / 5 blocks per tick); bottom layer fills first; with server-safe on, still 5 |
| [ ] | Index reads networks | Carry a wireless terminal / grid (or link an ME terminal / RS grid), press Index | "Read ME/RS network" in the queue, terminal opens and closes, item bar shows the network's items; "Indexed N container(s) and M storage network(s)" |
| [ ] | Re-index | Re-index with networks already read | Reads every reachable network again |
| [ ] | Mining range on a server | Clear order on the ATM10 server | Blocks stay mined (no popping back); if one does, "The server put X back - mining it from closer" and it's mined from close up |
| [ ] | Materials: remove a block | Materials, click the ✕ on a row twice | "Remove?" after the first click; then that block is gone from every queued order, the hologram and the list; emptied orders leave the queue; a running build restarts without it |
| [ ] | Materials: replace a block | Materials, click the ⇄ on a row (tooltip on hover) | Picker opens: "Replace X with...", your blocks first with a green dot, search finds any block; clicking one swaps it in every queued order, the hologram and the list; a running build restarts with the new block |
| [ ] | Replace keeps orientation | Queue stairs/logs, replace them with other stairs/logs | Same facing/half/axis as before; replacing stairs with a full block just drops the stair properties |
| [ ] | Materials columns | Long item names, 4-5 digit counts | NEED / HAVE / STORED, ⇄ and ✕ don't overlap; "Remove?" hides the ⇄ while armed |
| [ ] | Stairs shape | Stairs with oak planks, stone bricks, an oak log as the brush | Steps are oak / stone brick / oak stairs facing up, full blocks underneath (hollow: stairs only) |
| [ ] | Cut a doorway | Queue a hollow box (don't start), Destroy tool + Wall shape on its side over the ghost blocks | Cursor lands on the ghost blocks; "Removed N block(s) from the queued orders", the hologram has the gap; the build leaves it open |
| [ ] | Ghost + real | Destroy shape covering both ghost blocks and real ones | Ghosts removed from the orders, real blocks get a clear order |
| [ ] | Roof styles | Build a 7x9 room, Roof with oak stairs, each Style | Gable / Steep / Barn / Lean-to have closed ends (lean-to also its high side), Hip and Mansard slope on all sides (Mansard falls back to Hip on small rooms), Flat has a slab parapet |
| [ ] | Roof looks | Same room, Look = Cottage / Witch / Snowy / Medieval with Gable and Steep | Dark outline on the eaves, end rows, ridge and gable ends; Witch: flared eaves + rods on the ridge; Snowy: mixed stairs with white patches; Cottage / Medieval: chimney with a campfire through the roof |
| [ ] | Big schematic preview | Load a 50k-200k block schematic, move the cursor around, rotate (R) | Preview appears and follows the cursor straight away (no rebuild while moving); shown as real blocks (outer shell) instead of plain boxes; rotating rebuilds once |
| [ ] | Bridge styles | Bridge shape over a river, each look (Bridge button next to Mode), no brush picked | Preview shows the look; Medieval/Viaduct/Garden/Stone/Overgrown/Covered build with their own blocks, pillars reach the river bed, stairs face along the hump, the Covered roof meets at a ridge and sits high enough to walk through (5 tall walls, lantern above head height, also on the stairs) |
| [ ] | Protected clear | `protect-clear` (NeoForge): stone block, half protected, cleared | Protected half stays, the rest is mined |
| [ ] | Protect tool | Tool 8, two corners around a base, then order a big clear / quarry over it | Red outline; nothing inside is broken ("in a protected area"); click inside to remove |
| [ ] | Protect + Baritone | Same with the Baritone engine | Baritone can't break inside (blocked), may route around |
| [ ] | Survival flight item | ATM10 survival with an Angel Ring, flying, give a build order | Flies to blocks like creative; with "Creative flight" off, lands first, then walks |
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

## Blend brush (Terrain > Blend, both loaders)

- [ ] Drag paints a round patch (tinted), the ring at the cursor follows the brush size; Shift+drag erases, X / Clear forgets it
- [ ] Ctrl+scroll and -/+ change the brush size (1-25 wide); Strength -/+ (1-10) changes the preview
- [ ] Preview: red where bumps get mined, ghost blocks where dips get filled; hint line shows mine / fill counts and materials
- [x] Enter queues "Blend: mine" then "Blend: fill" (autotest `blend`: steepest step 3 -> 1, all blocks done); the result slopes into the unpainted ground with no step at the edge
- [ ] Grassy hill: fills are dirt with grass on top (Top: keep surface), plain dirt with Top: dirt; lowered columns are left dirt and green over
- [ ] Houses, trees, water inside the patch are left alone; nothing is mined in protected areas
- [ ] Ctrl+Z puts the old ground back
- [ ] Esc with paint clears it first, second Esc leaves RTS mode

## Level brush (Terrain > Level, both loaders)

- [ ] Painting sets Level Y to where the stroke starts; -/+ moves it; Edge -/+ (0 = sharp, up to 10) changes the preview
- [x] Enter queues "Level: mine" / "Level: fill"; the painted ground ends flat at Level Y (autotest `level`: 205/205 columns level, 160s, tunnelled and refilled 2 blocks)
- [ ] The edge slopes back into the land around it, no cliff at Edge 3+
- [ ] Not enough grass blocks in survival: message "topping with dirt instead", and the order doesn't get stuck

## Tunnelling (built-in engine, both loaders)

- [ ] A block it can't walk to (inside a hill, a pit with no way down): it mines through, does the block, then puts the dug blocks back
- [ ] Put-back uses cobblestone for stone and dirt for grass when that's all you have
- [ ] Never tunnels through order blocks, protected areas, chests, obsidian, or next to water / lava
- [ ] No more endless "planning" / "finding a spot to climb" with the FPS dropping

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
- [ ] Paste a schematic half into a hillside: two orders queue, "Clear for X" first (red hologram, mined top-down), then "Build X"; the build never stops to mine
- [ ] Same with a room-shaped schematic and Air: clear (now the default): the room's inside is dug out too; Air: keep leaves terrain inside the room but still clears where walls go
- [ ] Grass, flowers, snow layers where blocks go are not in the clear order (placed over directly); water and bedrock are skipped
- [ ] Undo after such a paste puts the terrain back

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
| [ ] | Apotheosis Flying potion (NeoForge) | Survival, drink a Flying potion, start a build | Flies to blocks like creative; walks again (landing first) once the effect ends |
| [ ] | Mystical Agriculture flight augment | Survival, full armor set with the Flight augment | Flies to blocks; walks after taking a piece off |

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
- [ ] RTS settings at GUI scale 2, 3, 4 and a small window: every setting down to Max shape size and the Done button is on screen; Building spills into the next column with its title repeated; columns about even
- [ ] RTS settings shrunk to fit (GUI scale 4 / tiny window): clicks hit the row under the mouse, tooltips show next to the mouse and fit

## Fabric packs

- [ ] Prominence II: no blur behind the RTS view (Blur mod), camera moves (Leawind's Third Person installed)

## New UI (neofroge-NewUI copy, NeoForge)

- [ ] Tool rail on the left: 8 pixel tool icons (hammer, pickaxe, cursor, chain, grid, mountains, ruler, shield), amber marker on the chosen one, 1-8 still switch tools
- [ ] Settings gear and ? at the rail's foot; ? / F1 opens the controls panel (key caps), any click closes it
- [ ] Inspector shows for every tool: options for Build/Destroy/Blueprint/Terrain, a description for Interact/Link/Measure/Protect
- [ ] Shape tiles (6 per row) pick shapes; height readout between - and +
- [ ] Command panel: Start / Pause / Stop / Undo always visible; ORDERS, STORAGE, SYSTEM tabs switch pages and the page is kept after reopening RTS
- [ ] Orders: cards numbered, drag to reorder, run-next / pause / cancel icons still hit, segmented progress bar
- [ ] Storage page: linked / read / types readouts, every button works, network lines (ME / RS / backpack)
- [ ] System page: engine cycle, save / load queue, settings, controls, Leave RTS
- [ ] Item dock: Carried / Stored filters, Blocks / Tools / Misc, search at the right (F focuses), page counter and scroll tick
- [ ] Quick bar under the grid behind the star: pin / unpin / Ctrl+click fetch
- [ ] Status pill (health, food, cut Y, engine) and compass at the top centre; messages drop in under them
- [ ] Small window / big GUI scale: everything still fits (scaled down) and clicks land where drawn
- [ ] Settings, Materials, Presets, Queue, Schematic and Name screens use the new amber look

## Sky island mob farm schematic (ATM10, 1.21.1)

- [ ] `sky_island_mob_farm.schem` shows in the Schematic browser, loads as 35 x 61 x 35, hologram looks like a floating island with a glass pod underneath
- [ ] Built with the pod floor at Y 240 or higher: water in all 16 trenches stops one block short of the 2x2 hole
- [ ] Standing in the pod at night: mobs drop down the shaft, die on the hoppers, loot ends up in both double chests
- [ ] Ladder runs from the pod up to the trapdoor on the grass; nothing spawns on the island top or the glass roof
- [ ] Gray carpets every third block on all four spawn floors (35 per floor); no spiders spawn in the farm, while zombies / skeletons / creepers still spawn and walk over the carpets into the trenches

## Greenhouse schematic (ATM10 To the Sky, 1.21.1)

- [ ] `greenhouse_17x17.schem` shows in the Schematic browser, loads as 17 x 16 x 17, nothing missing (no gaps where modded blocks should be)
- [ ] Modded blocks show: Chipped bundled dark oak posts, Rechiseled deepslate floor and knee wall, Connected Glass walls and roof, Farmer's Delight rich soil and crates, Supplementaries planters and green sconces, hopper botany pots along the back
- [ ] Door in the middle of the front, lanterns on chains under the ridge; four beds with an open middle (fill with water, hoe the rich soil)

## FramedBlocks presets and camo (NeoForge)

Automated: `./gradlew runClient -Pautotest=pf-cottage,pf-workshop,pf-tower -Pautoquit` in `neoforge/` (FramedBlocks in `neoforge/run/mods`).

- [ ] With FramedBlocks installed the preset list is only the framed set (cottage, workshop, storage hall, greenhouse, watchtower, and the generator / power / sieve rooms when their mods are there); without it the old presets are back; Fabric unchanged
- [ ] Pasting a framed preset queues the build, then "Camo <name>": walks to each frame and right-clicks it with its block; every frame ends up wearing it (stairs, slabs, pillars, panes, fences, cubes)
- [ ] Material list includes the camo blocks; with a camo block only in a chest, the camo pass fetches it
- [ ] Frames that already have the right camo are left alone; a missing camo block skips those frames with one warning at the end
- [ ] Doors, chests, machines, dirt, lights stay real blocks

## Climbing with scaffolding / ladders (built-in engine, both loaders)

Automated (Fabric): `wall-scaffolding`, `wall-ladders`, `sphere5-scaffolding`, `storeys2-scaffolding` - all PASS on 2026-10-20
(scaffolding wall 31 s, ladder wall 65 s, nothing left behind).

- [ ] Settings > Climb with scaffolding / ladders ON (default); carrying scaffolding, build something too tall to reach: a scaffolding column goes up from the ground (each piece clicked on the side of the column), the player climbs inside it holding jump and works from the top
- [ ] From the top of a column it lays a scaffolding walkway sideways (no more than 6 out) instead of climbing down and putting up a second column
- [ ] Support blocks (things to place against) are scaffolding when carried, dirt/cobble only where scaffolding wouldn't hold
- [ ] Afterwards: the bottom piece of each column is broken and the column comes down; nothing left floating
- [ ] Only ladders (or vines), next to a wall: ladders go up the wall as it climbs, it sneaks to hold on while placing and working, and takes them all down at the end
- [ ] Never sneaks on top of scaffolding (it would drop through), except when climbing down a column on purpose
- [ ] Routes use existing ladders / scaffolding to get up and down; a target that can't be walked to gives up within about a second and climbs
- [ ] Setting OFF: pillars up on dirt / cobble as before

## New Terrain modes (both loaders)

Automated (NeoForge): `t-road, t-room, t-farm, t-mobproof, t-restyle, t-hill, t-lake, t-river, t-terrace, t-cave, t-islandbridge, t-decorate`
(`./gradlew runClient -Pautotest=... -Pautoquit` in `neoforge/`). First run (2026-10-20, before fixes): hill, mob-proof, decorate PASS.

- [ ] Every new mode has a button with an icon in the Terrain panel; modes with a size show a -/+ row (WIDTH, HEIGHT, DEPTH, STEP, SIZE, DENSITY) and Ctrl+scroll changes it
- [ ] The hologram previews what each mode will dig (red) and build (ghost), water as light-blue glass, before Enter
- [ ] Road: two points across bumpy ground, a hill and a pond: grass/dirt/hill in the way is mined, then the road goes in; stairs where it climbs, borders, a lantern every 8; a bridge with fence railings and posts over the water
- [ ] Furnish: two floor corners inside a room: missing floor filled, lanterns in the corners, wall torches every 4, chests and a crafting table along the far wall
- [ ] Farm (hoe, seeds, 2 water buckets carried): field dug and filled to dirt, channels every 9 rows filled from the buckets (refilling from the pool), hoed, sown, fenced with a gate and corner torches
- [ ] Mob-proof: every dark spot in the area gets a slab (or the brush if it's a slab, carpet or button)
- [ ] Restyle (brush e.g. spruce planks): queued builds inside the box switch to planks / spruce stairs / spruce slabs, keeping facing; hologram updates
- [ ] Hill, Lake (2 water buckets), River (2 water buckets), Terrace (on a slope), Cave (into stone), Decorate (on grass): as described in their tooltips; with only one water bucket Lake/River say so instead of looping
- [ ] Island bridge: click two floating islands: a bridge (Build tool's Bridge look) between their nearest edges

## Mountain shape (Build tool, both loaders)

- [ ] Mountain in the shape picker (mountain icon); A = middle of its foot on the ground, B = how far it spreads; Height = main peak (1-3: about the radius)
- [ ] Every mountain different: dice button / N for a new design, a new design on every first click; preview matches the build
- [ ] Style button cycles the island styles (Grassy, Snowy, Desert...); cliffs show bare stone, snow above the snow line, trees and plants on gentle slopes below it; Hollow: only a shell
- [ ] Inverted button reads "Form: ..." with Mountain picked; [I] cycles Peaked / Rounded / Mesa / Volcano / Jagged / Ridge with a chat line
- [ ] Rounded: smooth soft hills, grass all the way up, no cliffs, no snow; Mesa: flat top, steep banded cliff sides; Volcano: cone with a crater, dark rock (blackstone, basalt, tuff) near the top, magma at the crater floor; Jagged: several sharp spires; Ridge: a long narrow range of peaks along A to B

## Giant tree shape (Build tool, both loaders)

- [ ] Giant tree in the shape picker (tree icon); A = ground at the foot of the trunk, B = how far the crown spreads; Height = trunk height (1-3: from the crown)
- [ ] Every tree different: dice button / N, and a new design on every first click; preview matches the build
- [ ] Style button cycles Oak / Birch / Spruce / Dark oak / Cherry / Jungle / Acacia / Dead with a chat line
- [ ] Thick trunk with a flared foot and roots lying on the ground (logs on their side); branches from the upper trunk ending in leaf clusters
- [ ] Spruce: a cone of needles to a point; Acacia: 2-3 bent branches with flat umbrella crowns; Dark oak: wide, flat clusters; Dead: bare branches, no leaves
- [ ] Leaves don't decay after the build (persistent)
- [ ] Random kind: each design (dice / N / new first click) picks a different kind of tree
- [ ] Within one kind, trees differ: trunks bend and taper differently, the crown's size and shape shift, and the branches grow differently every time
- [ ] Broadleaf kinds (oak, birch, dark oak, cherry, jungle, willow, dead) grow a branching crown: 2-4 thick main limbs arch out from the top of the trunk, then branches fork towards the edge, with leaf clumps round the twig ends; the trunk is solid with no diagonal breaks
- [ ] Stocky trunk (about a quarter of the crown's width), tapering, sometimes bending in an S; wood blocks (no log rings), oak and jungle with some darker spruce bark mixed in
- [ ] Buttress roots: 4-7 ridges slope from part way up the trunk down into the ground, making a star round the foot
- [ ] Wide domed canopy: leaves darker underneath (dark oak / spruce leaves under oak), azalea accents and the odd flowering azalea on oak; short strands hang under oak, cherry, dark oak and jungle
- [ ] Weeping willow: mangrove trunk, a dome with a curtain of leaf strands hanging most of the way to the ground

## Rocks shape (Build tool, both loaders)

- [ ] Rocks in the shape picker (rocks icon); A = middle on the ground, B = how far they spread; Height = tallest point (1-3: from the spread)
- [ ] Every design different: dice button / N, and a new one on every first click; preview matches the build
- [ ] Style button cycles Boulders / Arch / Sea stacks / Outcrop; Inverted button reads "Look: ..." and [I] cycles Stone / Mossy / Sandy / Dark
- [ ] Boulders: 3-8 lumpy rocks half sunk; Arch: a curved arch with thick legs you can walk under; Sea stacks: tall tapered pillars; Outcrop: one big lumpy mass
- [ ] Mossy: moss blocks on top with some moss carpet; Sandy: smooth sandstone tops; nothing is placed below the ground at A

## Maze shape (Build tool, both loaders)

- [ ] Maze in the shape picker (maze icon); A and B are opposite corners on the ground; Height = wall height (1: 3)
- [ ] A new maze every time: dice / N / every first click; preview matches the build; a way in on the west side and out on the east, and every path connects
- [ ] Style button cycles Hedge / Stone / Sandstone / Quartz / Deepslate / Nether / Prismarine / Random; Hedge mixes in azalea and flowering azalea leaves (no decay); stone-type walls get a chiseled top row and the odd lantern on a post
- [ ] Inverted button reads "Layout: ..."; [I] cycles Classic / Loops (dead ends knocked through) / Chambers (open rooms + a middle plaza) / Round (circular) / Random
- [ ] Big mazes (25+ each way) sometimes get 2-wide paths; Random style / layout picks a different one per design

## Backrooms shape (Build tool, both loaders)

- [ ] Backrooms in the shape picker; A and B opposite corners; Height = air height inside (1-2: 4); floor at A + 1, ceiling on top, walls all round with one 2-wide doorway in
- [ ] Inside splits into random rooms every design (dice / N / first click): doorways of different widths and heights, some huge openings, doorways never blocked by the wall behind
- [ ] Rooms vary: empty, pillar grids (1x1 or 2x2), bits of wall, a lower ceiling, a row of columns; lights in the ceiling at random spacing, a few missing, some rooms dark
- [ ] Style button cycles Level 0 (yellow, ochre lights) / Poolrooms (white) / Level 1 (grey concrete) / Office / Red rooms / Random

## Building on holograms (both loaders)

Automated (Fabric): `ghost-roof`.

- [ ] Queue a box (don't start), then with Build pick a point on top of the box hologram: the new shape starts on top of it, not on the ground behind
- [ ] Roof tool on the top of a queued room's hologram makes a roof sized to the room
- [ ] Blueprint paste follows the cursor onto hologram blocks; Destroy still cuts blocks out of holograms

## Modpack presets (NeoForge, ATM10 / ATM10 To the Sky)

- [ ] Blueprints > presets list Block generator room, Power generator room and Sieve station only when Cobblestone Generators Galore / Generator Galore / Ex Deorum are installed; not in a pack without them
- [ ] Block generator room: all 7 generator tiers stone to netherite along the back, a Sophisticated barrel on each, green sconces, Chipped/Rechiseled walls and floor
- [ ] Power generator room: generators face into the room, Powah cables connect them to the energy cell at the back
- [ ] Sieve station: 4 sieves, 4 compressed sieves, 2 barrels, 2 porcelain crucibles, flux sieve with a barrel on top in the middle
- [ ] All three build with the builder (every block has an item) and rotate/mirror correctly

## New bridge looks (both loaders)

- [ ] Style cycles on to Rope, Nether, Sandstone, Modern after Covered
- [ ] Rope: sags in the middle even without Sag; log towers with lanterns and a beam at both ends; chain handrails between fence posts follow the run
- [ ] Nether: blackstone arches and parapet, soul lanterns on some posts; Sandstone: smooth sandstone arches, red sandstone path, lanterns
- [ ] Modern: straight quartz deck with a grey centre line, glass pane railings, sea lantern posts every 6, quartz pylons to the ground every 12
- [ ] Older looks (Classic to Covered) unchanged

## Island shape (both loaders)

- [ ] Island in the shape picker (island icon); Height reads "Depth"; Style button next to Mode cycles Grassy / Snowy / Desert / Cherry / Mushroom / Crimson while Island is picked (and bridge looks while Bridge is picked)
- [ ] Builds with no block in hand; hologram shows the island and grows as B is dragged further out (radius 5, 15, 30)
- [ ] Every island looks different: queue one, then preview another in the same spot - new outline (stretched, lobes, 0-3 bulges), depth, hills, off-centre deepest point, tree and plant amount
- [ ] Rounded, several designs ([N]): one smooth, level bowl underneath (not lopsided); bulges have smaller, shallower bowls that blend in
- [ ] The preview doesn't change while dragging or nudging; what's built matches the preview exactly
- [ ] Dice button next to Style (only active with Island picked): "New island design", the preview changes right away, also mid-placement
- [ ] Set an island's first point, cancel (X), set it again: a different design each time
- [ ] [N] with Island picked: "New island design", preview changes; [N] in Blueprint still mirrors
- [ ] Depth 1-3: underside about as deep as the radius; Depth 20: 20 deep; Hollow: only a shell
- [ ] Every style: no plant pops off (grass/petals/mushrooms/roots/cactus/weeping vines stay), leaves don't decay, desert sand doesn't fall while building
- [ ] Fabric (1.20.1): grass plants place (block named "grass" there)
- [ ] Destroy with the Island shape removes the queued island hologram
- [ ] With Island picked the Inverted button reads "Bottom: ..." and [I] cycles Pointed / Rounded / Stepped / Torn out (default Rounded); dome/pyramid/cone still flip
- [ ] Rounded: smooth bowl, no point; Stepped: flat terraces seen from below; Torn out: lumpy dirt and rock, many roots, loose 1-4 block chunks under it that don't touch the island
- [ ] Torn out Desert: loose chunks are rock only (no sand falling)

- [ ] Floating island (Pointed, depth 10+) with the built-in engine: builds from the tip up; a tip block it can't get to first is tried again before the next layer, and upper layers aren't placed over unfinished blocks below; no blocks left under the island at the end

## Measure centre (both loaders)

- [ ] Measure: a white box marks the centre block while dragging and after the second click (odd side: one block; even side: the middle two), with a small purple dot at the exact centre
- [ ] The measure text ends with "centre X Y Z" ("4/5" on an even side), matching the white box

## Bridge sag (both loaders)

- [ ] With Bridge picked the Inverted button reads Hump / Sag; [I] toggles it with a chat line
- [ ] Sag: deck hangs down in the middle (about 1 block per 7 long, at most 10), stairs on the steps face the right way going down and back up, railings and lantern posts follow it
- [ ] Sag works for every bridge look (Classic, Medieval, Viaduct, Garden, Stone, Overgrown, Covered); pillars and piers still reach the ground
- [ ] Hump bridges look the same as before

## Pathfinding upgrades from mineflayer-pathfinder (built-in engine, both loaders)

Automated (NeoForge, 2026-10-23, all PASS): `./gradlew runClient -Pautotest=w-gap1,w-gap2,w-gap3,w-gap-up,w-gap-lava,w-gap-off,w-water-drop,w-dry-drop,w-mobs,w-reroute -Pautoquit`

| Done | Scenario | Expect | Result |
|---|---|---|---|
| [x] | `w-gap1` / `w-gap2` / `w-gap3` | Platform with a 1/2/3-block gap and a 4-block fall: one parkour jump, arrives unhurt | 5 s each, no damage |
| [x] | `w-gap-up` | 1-block gap onto a platform one higher: jumps up onto it | 5 s, no damage |
| [x] | `w-gap-lava` | Same gap with lava at the bottom: no route, doesn't jump | "No path", stays on the platform |
| [x] | `w-gap-off` | Parkour jumps off: no route | "No path" |
| [x] | `w-water-drop` | 12-high tower over a pool: jumps into the water, swims out, walks on | 7 s, no damage |
| [x] | `w-dry-drop` | Same tower, no pool: no route | "No path", stays on top |
| [ ] | `w-angle1` / `w-angle2` | 1-wide beam ending in mid-air, next beam 2 or 3 along and 2 across: jumps across at an angle, arrives unhurt | not run yet |
| [x] | `w-mobs` | Three cows in the straight line: route goes round them | Route avoided their cells |
| [x] | `w-reroute` | Wall put up across the route 2 s in: plans again and goes round | 6 s |

Note: on the unreachable gaps the player first walks the partial route to the edge of the gap, then stops with "No path".
Not covered automatically yet: partial routes on a long walk, sprint/hunger limit, the builder's partial routes.

- [ ] Engine: Built-in, walk order to a spot 150+ blocks away over hilly ground: the status reads "Walking (part way)" between plans and the player keeps going until it arrives (no "No walkable path" partway)
- [ ] Walk order to a spot inside a sealed room: still fails with "No walkable path" after a short walk at most (no endless back-and-forth)
- [ ] Cliff 6-20 blocks high over a lake: the route jumps off into the water and takes no fall damage; the same cliff over dry ground is still walked around
- [ ] Hillside of 1-block steps at an angle: the route takes diagonal steps down; it doesn't drop through a gap beside a corner
- [ ] Field full of cows/sheep: the route goes around the herd when there's room; a zombie or creeper in the way is given a wide berth
- [ ] Walking across a pond: the route prefers going round on land when that's about as short
- [ ] Builder tunnelling through a hill: it never mines a block next to water or lava; it goes through dirt rather than stone or obsidian when it has the choice
- [ ] Settings shows "Parkour jumps" (on by default) under "Climb with scaffolding / ladders"; the setting survives a restart
- [ ] Walk order across a 1-block gap: the player walks up to the edge and jumps it (no sprint); with Parkour jumps off it walks round instead
- [ ] 2- and 3-block gaps: the player lines up (backs up first for 3), sprints and jumps, lands on the far side without overshooting off a narrow landing
- [ ] Gap ending in a block one higher (1-2 wide): jumps up onto it
- [ ] Hunger 6 or lower: only 1-block gaps are jumped
- [ ] A missed jump (e.g. placed a block in the air mid-jump): the player re-plans from the bottom and doesn't try the same jump straight away
- [ ] Gap over lava: never jumped; gap over a deep ravine: only jumped when walking round is much longer
- [ ] While the player walks a route, place a block on a step a few blocks ahead (or mine the floor out): the player re-plans before reaching it
- [ ] Builder with a far-away build site (out of a single plan's range): the log shows "partial route" and it keeps going until it gets there

## Orders list (both loaders)

- [ ] Queue 8+ orders: the ORDERS section shows about 6 cards on a normal-size window (was 1), "1-6 of 8" next to the title
- [ ] A thin scrollbar on the right of the list when there are more orders than fit; scrolling over the panel moves it
- [ ] Small window / big GUI scale: cards turn into one-line rows (name, buttons, progress bar) so at least 3 show
- [ ] Pause / cancel / run-first buttons and drag-to-reorder still hit the right card, in both full and one-line modes

## Circle hole (both loaders)

- [ ] Circle, Hollow off: Ctrl+scroll up shows "Circle hole 1 wide from the centre" and the preview loses just the centre block; each step widens the hole by one ring
- [ ] The height row reads "Hole N" and its - / + buttons work; the status chips show "hole N"; scrolling down to 0 says "Circle hole off" and the disc is solid again
- [ ] A hole bigger than the circle still leaves the outer ring (same as Hollow)
- [ ] Hollow on: Ctrl+scroll says the Circle has no height, the ring is unchanged; other shapes' Height is not affected by the hole

## Flying between layers, no missed blocks (both loaders)

- [ ] Creative flight + Fly above layer, a tall build (tower, dome): when a layer finishes the status shows "flying up" and the player rises over the next layer before placing any of it; no more getting stuck between layers
- [ ] Hover height 0 and 2.5: the player rises to that height over each new layer
- [ ] Build ahead on while flying: blocks are never placed at the height the player hovers at (it isn't walled in up there)
- [ ] Under a low ceiling (no room to rise): the build carries on from where the player is instead of hanging
- [ ] Break a few blocks of an already-finished lower layer while the builder works higher up: before it says "done" it goes back and puts them back
- [ ] Order with blocks that keep failing: it sweeps again while each sweep gets more done (up to 6), then finishes with "done, N skipped"
