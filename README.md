# QuillCoin Hider

The tool the official **QuillCoin** account uses to hide coins on 2b2t. It is small on purpose so anyone can read it.

## What one key press does
With a dungeon chest open and a book-and-quill in your hotbar, press the stash key:

1. A random 100-bit code is generated (`QLL-XXXXX-XXXXX-XXXXX-XXXXX`).
2. It is written into the book and the book is signed. The **server** stamps the author, so a coin book can only be created by the account that is logged in as `QuillCoin`.
3. The code is hashed (SHA-256) and the code is discarded.
4. The signed book is shift-clicked into the chest and the chest is closed.
5. `round,number,hash,unix-time,synced,blind` is appended to `meteor-client/quillcoin-hides.txt` and posted to `POST /api/hide` on the site.

## What the hider never sees
- The code: it lives in memory between signing and hashing and is never printed, logged or rendered.
- Coin books: their tooltips are blanked and they cannot be opened while the module is on.
- Coordinates: nothing this module writes contains one. Blind flight paints the screen over while gliding and keeps F3 off. Use an instance with **no map mods**; the module warns if Xaero is loaded.

## The blind run (0.2)
With `require-blind-run` on (the default), the stash key only works at a dungeon a blind run led you to:

1. In the nether, press the run key. The mod picks a random point - uniform over the ring between `min-ow-radius` and `max-ow-radius` (overworld blocks), scaled to the nether - hands it to Baritone's elytra process through the API (nothing is printed), and you fly. While gliding the screen is painted over.
2. Within `arrive-radius` of the point you land; the mod says to build a portal and go through.
3. Coming out in the overworld, the mod checks you are within `exit-tolerance` of the point; farther, and the run is void.
4. You fly around; the first dungeon spawner the mod sees (a spawner standing on cobblestone) becomes the run's dungeon. A HUD line gives relative guidance only - "dungeon ahead-left, 140m, 38 down" - never a coordinate.
5. Within `dungeon-radius` of that spawner, with its chest open, the stash key works. The hides file records `blind=1`.

The point, the spawner's position and your position never leave memory. Any dimension change other than the expected nether-to-overworld voids the run.

## Tamper handling
The mod cannot stop the person running it from removing it - nothing on your own computer can. What it does is make every deviation
visible: `meteor-client/quillcoin-audit.txt` is an append-only log (no coordinates) of `module on`, `run started`, `arrived after N peeks`,
`dungeon designated`, `stashed R1 Coin 37 at the end of a blind run` / `WITHOUT a blind run`, and every reason a run was voided
(module switched off, blind flight turned off, portal exit too far, dimension change). It is published with each round next to the hash list.
The hider instance should carry nothing but Meteor, Baritone, performance mods and this addon - no map mods, no HUDs that print coordinates.

## What it refuses
- Hiding while logged in as anyone but the configured author.
- Hiding while a chest-logging module (ChestDump, StashAudit, NetWorth, LayerKit) is on.
- Hiding into a full chest.

## Numbers
Coin numbers are drawn at random from `1..numbers-per-round`, so `R1 Coin 37` says nothing about when or where it was hidden.

## Build
`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build` → `build/libs/quillcoin-hider-<version>.jar`. Requires Meteor Client for 1.21.4.
