# RTS Client Builder (Fabric 1.20.1 / NeoForge 1.21.1, client-side)

Build from above like an RTS. Press **G** and the camera detaches into a top-down view with a mouse cursor;
your character stays in the world and does the work:

- **Walking, placing and mining** are done by Baritone when it's installed, or by the mod's own built-in
  engine (A* pathfinding, block placing and mining in reach). Switch with the **Engine** button:
  *Auto* (Baritone if installed), *Built-in*, or *Baritone*. If Baritone claims to be working but the player
  doesn't move for 10 seconds, Auto switches to the built-in engine by itself and redoes the order.
- **This mod** handles everything else: the camera, the UI, shapes, opening chests, reading and moving items.

It is client-side only and works on vanilla servers. Inspired by the gameplay of
[RTSbuilding](https://github.com/Hcrab/RTSbuilding) (NeoForge/Forge, server-side). This is an independent
implementation; no code or assets are taken from it.

## Versions

| Loader | Minecraft | Source | Jar (CI artifact) |
|---|---|---|---|
| Fabric | 1.20.1 | repository root | `rts-client-builder-fabric-1.20.1` |
| NeoForge | 1.21.1 | `neoforge/` | `rts-client-builder-neoforge-1.21.1` |

Both have the same features and controls.

## Requirements

- Fabric: Minecraft 1.20.1, Fabric Loader 0.14.21+, Fabric API
- NeoForge: Minecraft 1.21.1, NeoForge 21.1+ (Baritone's NeoForge build for 1.21 is optional, as on Fabric)
- Optional: [Baritone](https://github.com/cabaletta/baritone) for 1.20.1 (the Fabric build). It is better at
  long or awkward paths (it can bridge and pillar); without it the built-in engine is used, which walks,
  climbs single steps and drops, but doesn't build its own bridges.

## Controls (in RTS mode)

| Input | Action |
|---|---|
| **G** | Enter / leave RTS mode (Esc also leaves) |
| WASD, Space / Shift, Ctrl | Fly the camera (your movement keys), Ctrl = faster. It flies through walls. |
| Scroll | Zoom toward the cursor direction |
| Right-drag / Q | Rotate the camera |
| Middle-drag | Pan the camera |
| **Left click** | Act with the current tool (below) |
| Right click | Walk your character to that spot |
| Middle click | Pick that block as the brush |
| 1-7 | Tools: Build, Destroy, Interact, Link storage, Blueprint, Terrain, Measure |
| Ctrl+0-9 / Shift+0-9 | Save the camera position to a slot / jump back to it |
| `[` `]` | Previous / next shape |
| M | Build mode (Fill / Replace / Replace type / Cover), or Terrain mode (Flatten / Light up) |
| R / H | Rotate (blocks, or the blueprint being pasted) / toggle hollow |
| N | Mirror the blueprint being pasted |
| B, Alt+scroll | Air placement on/off, move its layer up/down |
| P | Preset buildings |
| Ctrl+scroll | Shape height (negative digs down); with Terrain, how high above the level to clear |
| Arrow keys, Shift+Up/Down | Nudge the shape one block (camera-relative) / up and down |
| Enter / X | Confirm / cancel a shape |
| V, PageUp/PageDown | Cut view: hide everything above a Y level to see inside builds; move the cut |
| Ctrl+Z | Undo the last order (queues orders that put the old blocks back) |
| F | Search items |
| Ctrl+click an item | Fetch a stack of it from storage into your inventory |
| E, T, / | Inventory, chat, command (RTS resumes when you close them) |

### Tools

- **Build**: pick a block from the item bar, then click. Shapes: Block, Line, Floor, Wall, Box, Circle,
  Cylinder, Sphere, Dome (half sphere), Pyramid, Cone, Torus, Arch (Height = how high it rises; solid makes a
  gateway), Bridge (deck with a hump and railings; Height = width), Stairs (one up per block, stair blocks face up the
  stairs; Height = width), Spiral (spiral staircase around a centre), Vein (every touching block of one type) and Hole (fills an enclosed pit or pond up to the
  level you click). Two-point shapes: click A, click B, then click again or press Enter.
  - **Modes**: *Fill* only fills empty space; *Replace* turns everything in the shape into the brush;
    *Replace type* swaps only the block type you click first; *Cover* only fills empty spaces resting on
    solid ground (carpets, paths over terrain).
  - **Block state**: buttons under the shape list set properties like slab half or stair shape (auto =
    decided by where it's placed).
  - **Shift+click** mines the clicked block without leaving the Build tool. To clear whatever is in a shape's
    way while building it, use the *Replace* build mode.
  - **Mix** (pattern brush): pick a block and press *+ Mix*, pick another and press it again (press several
    times for more of it). Shapes are then built from a random mix, e.g. 60% stone, 30% andesite, 10% cobblestone.
    The preview shows exactly the pattern that gets built. *Clear mix* goes back to one block.
    **Pattern** sets how the mix is laid out: *Random* (by the proportions), *Clusters* (blended patches, like
    weathered stone), *Gradient* (bottom to top in the order the blocks were added, with a soft blend - nice on
    domes and towers), *Layers*, *Stripes* or *Checker*.
  - **Roof** (smart roof): press *Roof*, then click the top of any wall of a room. The walls are followed round
    to find the room's size and a roof with a one-block overhang is laid on it, ready to build (Enter). *Style*
    switches between a gable roof (two slopes) and a hip roof (four slopes). Use stairs as the block for a sloped
    roof (matching slabs and planks are picked automatically for the ridge and gable ends).
- **Destroy**: the same shapes, but mines the blocks. Afterwards the character picks up the drops.
- **Interact**: click any block (chest, door, lever, crafting table...) or mob. Your character walks over and uses
  it with the item picked in the item bar: shears shear a sheep, a bucket milks a cow, a lead leashes, wheat breeds;
  a sword or axe attacks the mob (following it) until it's dead; bone meal, a hoe or flint and steel work on blocks.
  With nothing picked (X for an empty hand) it right-clicks, and attacks hostile mobs with the best weapon you carry.
  Hovering a mob shows what will happen.
- **Link storage**: click containers to add them to your storage network.
- **Blueprint**: click two corners to copy a region (saved to `config/rtsclient/blueprints/`). Saved
  blueprints are listed on the left (Shift+click deletes). **Save...** keeps the current blueprint (a copy, a
  preset, a schematic or a roof) in that list under a name of your choice. **Export...** writes it as a schematic
  file in `.minecraft/schematics/` that other tools open: WorldEdit/Sponge `.schem` (Litematica, FAWE and Axiom read
  it too), or a vanilla structure `.nbt` with Shift+click. Exported files show up under *Load schematic...* as well.
  - **Move**: select a building with two clicks; it follows the cursor (R rotates, N mirrors). Click to place it:
    the original is taken down (its drops feed the new build) and it's rebuilt at the new spot. The old spot is
    outlined in red until then.
  - **Repeat**: X / Z set how many copies are placed in a row or grid, *gap* the space between them - for rows
    of houses, farm plots or fence posts.
  - **Presets...** (P): ready-made buildings to place like a blueprint: starter house, smelter room (furnaces,
    blast furnaces, smoker, chests), storage room, enchanting room (full bookshelf ring), workshop, watchtower,
    animal pen, nether portal frame, bridge, lamp post, wheat farm, sugar cane farm, iron farm frame, mob grinder
    shell, villager trading hall, castle wall, dock and beacon pyramid. Rotate/mirror them like any blueprint; the material list
    shows what you need, and missing blocks are fetched from storage.
  - **Load schematic...** opens the files in `.minecraft/schematics/`: Litematica `.litematic`,
    WorldEdit/Sponge `.schem`, and vanilla structure `.nbt` files. Only blocks are imported (not chest
    contents or entities).
  - **Placing**: the blueprint follows the cursor, centred on it. Click to pin it, nudge it with the arrow keys
    (Shift+Up/Down for height), R to rotate, N to mirror, then click again or press Enter to build. X lets it
    follow the cursor again.
  - **Air: keep / clear**: *clear* also mines whatever is in the blueprint's empty spaces, so the result is an
    exact copy.
  - Building a schematic works like any order: layer by layer, fetching missing materials from storage, with
    the material list (need / have / stored) shown at the cursor.
- **Terrain**: click two corners of an area.
  - *Flatten* levels it to the height of the first corner: everything above (up to the "mine above" height,
    Ctrl+scroll) is mined and dips below are filled with the brush block (dirt if none is picked).
  - *Quarry* digs the area out layer by layer, from the top down to the depth you set (Ctrl+scroll or -/+),
    and stores what it digs in linked storage when the inventory fills up.
  - *Fell trees* chops every tree in the area, picks up the wood and replants saplings.
  - *Harvest* breaks ripe crops in the area, replants them and stores the produce.
  - *Plant trees* plants saplings spread out over the area (spacing -/+), on grass or dirt with room to grow and
    away from other trees: the brush sapling, or the one you have most of. Dark oak is planted 2x2.
  - *Light up* places torches on every dark floor spot in the area (spaced out, so it doesn't need hundreds).
- **Air placement** (B, or the Air button): the cursor targets a flat layer at a chosen height, so you can
  click empty space to build floating blocks and shapes. Alt+scroll (or -/+) moves the layer; a grid and a drop
  line to the ground show where it is. While drawing a shape, pointing at the sky uses the first corner's layer.
- **Measure**: click two points to see the size, the distance and how many blocks are in the box. Nothing is
  changed.

While you draw a shape or paste, a label at the cursor shows its size (e.g. `5 x 5 x 1`), how many blocks it
places or mines, and the materials it needs against what you carry and what's in storage (red when short).

Previews show the real blocks, lit like the world around them and nearly solid (opacity is a setting), up to a
size you can set; bigger shapes show as a tinted surface. With the Build tool the exact block you're about to
place is drawn under the cursor every frame, so its rotation and state (R, the state buttons, turning the
camera) update live. Queued orders keep showing their blocks until they're built.

### Building

Orders are built **one layer at a time**, bottom-up (clearing goes top-down), so every block has something to
attach to. Blocks that can't be done yet are retried later, and when an order finishes with blocks missing it
goes back for them (twice).

The built-in engine also handles shapes that overhang or float, like spheres and domes:

- **Scaffolding**: when a block has nothing to attach to, it places a few temporary blocks (dirt, cobblestone,
  netherrack... see `scaffoldBlocks` in the config) to connect it to something solid, or a column down to the
  ground.
- **Pillaring and bridging**: routes can build their own way. When a block is too high or over a drop, the path
  planner adds steps that pillar up (jump and place a block underneath), bridge across (place the block to step
  onto) and later dig back down through its own scaffolding, so it never gets stranded on top of a pillar.
- **Cleanup**: when the order is done, the scaffolding and pillars are mined out again (Settings: Remove
  scaffolding). The block underneath is never pulled out over a drop that would hurt.
- **Getting out**: if a finished dome or hollow sphere closed around the character, it opens a two-block doorway
  at the lowest point, walks out and closes it again from outside. If you run out of scaffolding blocks, it fetches more from linked storage.
- **Buildable hollow shells** (on by default): hollow spheres and circles get a slightly thicker inside where
  layers would otherwise only touch at the edges, so every block can be placed. The outside looks the same.

- **Place in mid-air** (Settings, on by default): the built-in engine places a block even when nothing is next
  to it, by clicking the empty spot itself. Domes, spheres and floating builds then need no scaffolding at all.
  This works on vanilla servers; some anti-cheat plugins don't allow it, so turn it off there. Build orders use the
  built-in engine while this is on (whatever the Engine setting), since Baritone can't do it; Baritone still walks
  and mines when selected.
- **Smooth turning** (Settings: Turn speed): the character turns its head toward each block it places or mines at a
  limited speed and only clicks once it faces it, instead of snapping to a new angle every block. *Instant* restores
  the old snapping.
- **Creative flight** (Settings, on by default): when you can fly (creative mode, or a server that allows it), the
  character flies straight to a spot within reach of each block instead of walking, pillaring or scaffolding.
- **Creative supply** (Settings, on by default): in creative mode the builder spawns the blocks and scaffolding it
  needs into your hotbar instead of fetching them from storage. Does nothing in survival.
- **Clear blocks in the way** (Settings, on by default): shapes mine whatever is in their cells (terrain, trees,
  plants) and put the brush block there, instead of skipping them.

With mid-air placement off, keep a stack of dirt or cobblestone in your inventory or storage for big domes and
floating builds.

### Interface

The top bar holds the tools (or press 1-7) and the settings. The left panel shows the options of the current
tool: shapes, build mode, hollow, height, rotation and block state for Build/Destroy; copy/cut, rotate, mirror,
air and saved blueprints for Blueprint; mode and clear height for Terrain. The right panel is the command
panel: the engine, undo, store inventory and stop, every queued order with a progress bar and the storage
summary with Scan nearby / Re-index / Sort / Smelt buttons.

- **Orders**: drag a card up or down to change the order; the buttons on each card run it next, pause/resume it,
  or cancel it.
- **Save queue / Load queue**: save every queued order under a name and add them back later (for this world and
  dimension; saved in `config/rtsclient/queues/`). Half-finished builds carry on where they were, paused orders stay
  paused. Orders still queued when you leave the world or quit are kept as *Last session*, and you're reminded of
  them when you come back.
- **Materials** lists everything the queued orders still need against what you carry and what's in storage, and
  *Fetch all* gets it in one trip.
- **Repair** checks finished builds for blocks that never got placed or were broken since, and queues orders to
  fill them in (Settings: *Auto-repair builds* does this by itself when there's nothing else to do).
- **Sort** reorganises linked chests so each item type has its own chest; with the Link tool each chest shows a
  label with what it holds. **Smelt** collects finished smelting from nearby furnaces, blast furnaces and smokers
  and loads them again with ores, sand, cobblestone or food from storage plus fuel. Messages appear as coloured toasts.

## Storage

The item bar at the bottom shows **your inventory plus everything in linked containers**. Gold counts mean the
item is only in storage.

- **Scan nearby** links every chest, barrel and shulker box within 32 blocks, then walks over and opens the new
  ones so their contents show up in the item bar. A client can only see inside a chest after opening it, so
  this read is needed once per chest. Chests you open yourself in RTS mode are re-read automatically.
- **When you build**, any blocks you don't carry are fetched from linked containers first. If Baritone runs out
  mid-build, it stops, fetches more and carries on.
- **Store inv** puts everything outside your hotbar (except pinned items) into linked storage.
- **Pin** items with right-click to keep them on the quick bar.

Storage links and contents are saved per world/server and dimension in `config/rtsclient/storage/`.

## Settings

The **Settings** button (top right in RTS mode) covers the engine, layered building, exact rotation, drop
pickup, camera collision and speed, the real-block preview limit and the max shape size. Everything is saved in
`config/rtsclient/config.json` (which also has the scan radius and max camera distance).

Cut view works with the vanilla renderer and with Sodium 0.5. In RTS mode, underwater fog and distance haze are
turned off so the view stays clear when the camera flies through water or looks down from high up.

## Building from source

The Baritone API jar isn't on a Maven repo, so put `baritone-api-fabric-<version>.jar` (1.20.1) in `libs/`,
then run:

```
./gradlew build
```

For NeoForge, put `baritone-api-neoforge-<version>.jar` (1.11.x, MC 1.21) in `neoforge/libs/` and run
`./gradlew build` inside `neoforge/` (Java 21).

CI downloads the jars automatically and uploads both mods as workflow artifacts.

### Live tests

`src/devtest` (Fabric) and `neoforge/src/devtest` (NeoForge) hold the same harness; it is only on the dev run's
classpath (never in the jar). It creates a flat survival world, builds each scenario with the real task queue and
engine, and writes the outcome (missing blocks, leftover scaffolding, time, health) to `run/autotest/results.txt`
plus a screenshot each. Testing is done on Fabric:

```
./gradlew runClient -Pautotest=dome6,sphere5,floor -Pautoquit   # or -Pautotest=all
```

(The NeoForge copy runs the same way from `neoforge/`.)
