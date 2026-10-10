# RTS Client Builder (Fabric 1.20.1, client-side)

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

## Requirements

- Minecraft 1.20.1, Fabric Loader 0.14.21+, Fabric API
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
| 1-5 | Tools: Build, Destroy, Interact, Link storage, Blueprint |
| `[` `]` | Previous / next shape |
| M | Build mode (Fill / Replace / Replace type / Cover) |
| R / H | Rotate (blocks, or the blueprint being pasted) / toggle hollow |
| Ctrl+scroll | Shape height (negative digs down) |
| Arrow keys, Shift+Up/Down | Nudge the shape one block (camera-relative) / up and down |
| Enter / X | Confirm / cancel a shape |
| V, PageUp/PageDown | Cut view: hide everything above a Y level to see inside builds; move the cut |
| Ctrl+Z | Undo the last order (queues orders that put the old blocks back) |
| F | Search items |
| E, T, / | Inventory, chat, command (RTS resumes when you close them) |

### Tools

- **Build**: pick a block from the item bar, then click. Shapes: Block, Line, Floor, Wall, Box, Circle,
  Cylinder, Sphere, Vein (every touching block of one type) and Hole (fills an enclosed pit or pond up to the
  level you click). Two-point shapes: click A, click B, then click again or press Enter.
  - **Modes**: *Fill* only fills empty space; *Replace* turns everything in the shape into the brush;
    *Replace type* swaps only the block type you click first; *Cover* only fills empty spaces resting on
    solid ground (carpets, paths over terrain).
  - **Block state**: buttons under the shape list set properties like slab half or stair shape (auto =
    decided by where it's placed).
- **Destroy**: the same shapes, but mines the blocks. Afterwards the character picks up the drops.
- **Interact**: click any block (chest, door, lever, crafting table...). Your character walks over and uses it.
- **Link storage**: click containers to add them to your storage network.
- **Blueprint**: click two corners to copy a region (saved to `config/rtsclient/blueprints/`). Saved
  blueprints are listed on the left (Shift+click deletes).
  - **Load schematic...** opens the files in `.minecraft/schematics/`: Litematica `.litematic`,
    WorldEdit/Sponge `.schem`, and vanilla structure `.nbt` files. Only blocks are imported (not chest
    contents or entities).
  - **Placing**: the blueprint follows the cursor, centred on it. Click to pin it, nudge it with the arrow keys
    (Shift+Up/Down for height), R to rotate, then click again or press Enter to build. X lets it follow the
    cursor again.
  - **Air: keep / clear**: *clear* also mines whatever is in the blueprint's empty spaces, so the result is an
    exact copy.
  - Building a schematic works like any order: layer by layer, fetching missing materials from storage, with
    the material list (need / have / stored) shown at the cursor.

While you draw a shape or paste, a label at the cursor shows its size (e.g. `5 x 5 x 1`), how many blocks it
places or mines, and the materials it needs against what you carry and what's in storage (red when short).

Previews show the real blocks, see-through, up to a size you can set; bigger shapes show as a tinted surface.
Queued orders keep showing their ghost blocks until they're built.

### Building

Orders are built **one layer at a time**, bottom-up (clearing goes top-down), so every block has something to
attach to. Blocks that can't be done yet are retried later, and when an order finishes with blocks missing it
goes back for them (twice).

The built-in engine also handles shapes that overhang or float, like spheres and domes:

- **Scaffolding**: when a block has nothing to attach to, it places a few temporary blocks (dirt, cobblestone,
  netherrack... see `scaffoldBlocks` in the config) to connect it to something solid, or a column down to the
  ground.
- **Pillaring**: when a block is too high to reach from the ground, it walks next to it, jumps and places blocks
  under itself until it can reach.
- **Cleanup**: when the order is done, the scaffolding and pillars are mined out again (Settings: Remove
  scaffolding). If you run out of scaffolding blocks, it fetches more from linked storage.
- **Buildable hollow shells** (on by default): hollow spheres and circles get a slightly thicker inside where
  layers would otherwise only touch at the edges, so every block can be placed. The outside looks the same.

Keep a stack of dirt or cobblestone in your inventory or storage for big domes and floating builds.

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

Cut view works with the vanilla renderer and with Sodium 0.5.

## Building from source

The Baritone API jar isn't on a Maven repo, so put `baritone-api-fabric-<version>.jar` (1.20.1) in `libs/`,
then run:

```
./gradlew build
```

CI downloads the jar automatically; the built mod is uploaded as a workflow artifact.
