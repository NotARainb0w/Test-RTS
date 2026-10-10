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
| WASD, Space / Shift, Ctrl | Fly the camera (your movement keys), Ctrl = faster |
| Scroll | Zoom toward the cursor direction |
| Right-drag / Q, E | Rotate the camera |
| Middle-drag | Pan the camera |
| **Left click** | Act with the current tool (below) |
| Right click | Walk your character to that spot |
| Middle click | Pick that block as the brush |
| 1-4 | Tools: Build, Destroy, Interact, Link storage |
| `[` `]` | Previous / next shape |
| R / H | Rotate directional blocks / toggle hollow |
| Ctrl+scroll | Shape height (negative digs down) |
| Enter / X | Confirm / cancel a shape |
| Ctrl+Z | Undo the last order (queues orders that put the old blocks back) |
| F | Search items |
| E, T, / | Inventory, chat, command (RTS resumes when you close them) |

### Tools

- **Build**: pick a block from the item bar, click to place. With a shape selected (Line, Floor, Wall, Box,
  Circle, Cylinder, Sphere) click point A, then point B, then click again or press Enter. The hint line shows
  how many blocks the shape needs and how many you have (in inventory and in storage).
- **Destroy**: same shapes, but clears blocks.
- **Connected** shape: one click selects every touching block of the clicked type: mine a whole ore vein or
  tree with Destroy, or replace it with your brush block with Build.
- **Interact**: click any block (chest, door, lever, crafting table...). Your character walks over and uses it.
- **Link storage**: click containers to add them to your storage network.

Chat (including Baritone's messages) is mirrored above the item bar, since RTS mode hides the normal HUD.

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

`config/rtsclient/config.json`: camera speed, max camera distance, max shape size, scan radius,
`strictOrientation` (force exact stair/log directions), and `manageBaritoneSettings` (lets the mod turn on
Baritone's `allowPlace`, `allowBreak`, `allowInventory`).

## Building from source

The Baritone API jar isn't on a Maven repo, so put `baritone-api-fabric-<version>.jar` (1.20.1) in `libs/`,
then run:

```
./gradlew build
```

CI downloads the jar automatically; the built mod is uploaded as a workflow artifact.
