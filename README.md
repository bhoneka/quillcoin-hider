# QuillCoin Hider

The tool the official **QuillCoin** account uses to hide coins on 2b2t. It is small on purpose so anyone can read it.

## What one key press does
With a dungeon chest open and a book-and-quill in your hotbar, press the stash key:

1. A random 100-bit code is generated (`QLL-XXXXX-XXXXX-XXXXX-XXXXX`).
2. It is written into the book and the book is signed. The **server** stamps the author, so a coin book can only be created by the account that is logged in as `QuillCoin`.
3. The code is hashed (SHA-256) and the code is discarded.
4. The signed book is shift-clicked into the chest and the chest is closed.
5. `round,number,hash,unix-time,synced` is appended to `meteor-client/quillcoin-hides.txt` and posted to `POST /api/hide` on the site.

## What the hider never sees
- The code: it lives in memory between signing and hashing and is never printed, logged or rendered.
- Coin books: their tooltips are blanked and they cannot be opened while the module is on.
- Coordinates: nothing this module writes contains one. Blind flight paints the screen over while gliding and keeps F3 off. Use an instance with **no map mods**; the module warns if Xaero is loaded.

## What it refuses
- Hiding while logged in as anyone but the configured author.
- Hiding while a chest-logging module (ChestDump, StashAudit, NetWorth, LayerKit) is on.
- Hiding into a full chest.

## Numbers
Coin numbers are drawn at random from `1..numbers-per-round`, so `R1 Coin 37` says nothing about when or where it was hidden.

## Build
`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build` → `build/libs/quillcoin-hider-<version>.jar`. Requires Meteor Client for 1.21.4.
