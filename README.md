# QuillCoin Hider

The tool the official **QuillCoin** account uses to hide coins on 2b2t. It is small on purpose so anyone can read it.

## What one key press does
With a dungeon chest open and a book-and-quill in your hotbar, press the stash key:

1. A random 100-bit code is generated (`QLL-XXXXX-XXXXX-XXXXX-XXXXX`).
2. The chest is closed and the book is taken in hand, because a server only signs a book the way a player can sign one. Then it is written and signed, and the same chest is opened again. The **server** stamps the author, so a coin book can only be created by the account that is logged in as `QuillCoin`.
3. The code is hashed (SHA-256) and the code is discarded.
4. The signed book is shift-clicked into the chest and the chest is closed.
5. The whole book is laid out by `meteor-client/quillcoin-book.txt` (pages split by a line `---`; `&c`-style formatting codes are turned into real ones, a line starting with `^` is centred, `>` right-aligned, `left<TAB>right` is justified, and a `║…║` line is padded out to a page-wide frame; placeholders `{code}` `{code_lines}` `{title}` `{round}` `{number}` `{hash8}` `{date}` `{time}` (UTC) `{year}` `{quote}` `{art}`; `{quote}` is a random line of `quillcoin-quotes.txt`, `{art}` a random page of `quillcoin-art.txt`), so the code can sit on any page - the site only ever cares about the code itself. A `preview-book-key` signs a sample book in singleplayer to design those pages live. The chest position is sealed: `{"x","y","z"}` encrypted with AES-256-GCM under `SHA-256("quillcoin-loc:" + code)`, so only the code that is inside the book can ever open it. The hider never sees it, the site stores it blind, and it is decrypted only when a finder redeems that code - then the found book appears on the site's map. `round,number,hash,unix-time,synced,blind,server,sealed-position` is appended to `meteor-client/quillcoin-hides.txt` and posted to `POST /api/hide` (the default site-url `https://quillcoin.gg` resolves to the QuillCoin API). Hides made in singleplayer are always round 0, the test round; the API refuses a real round number from anywhere but 2b2t.org, and refuses everything once a round is open. The hider key lives in `meteor-client/quillcoin-key.txt`, never in modules.nbt.

## What the hider never sees
- The code: it lives in memory between signing and hashing and is never printed, logged or rendered.
- Coin books: their tooltips are blanked and they cannot be opened while the module is on.
- A position on the screen: with `hide-position` on (the default), on a server, for as long as the module is on: F3 does not open, Meteor HUD texts that print a position or a biome are switched off, and so are the Waypoints, Logout Spots and Stash Finder modules. The instance that hides is not the instance that travels.
- Coordinates: nothing this module writes contains one. Blind flight paints the screen over while gliding on the run's nether leg - no peek key, no damage reveal - and keeps F3 off. Use an instance with **no map mods**; the module warns if Xaero is loaded.

## The blind run (0.2)
With `require-blind-run` on (the default), the stash key only works at a dungeon a blind run led you to:

1. In the nether, press the run key. Stopping the flight anywhere but the point (`,stop`, an emergency landing, no rockets) voids the run; the next press draws a new point. The mod picks a random point - uniform over the round's ring (overworld blocks from spawn), scaled to the nether - takes off by itself (nose up, hop, deploy, rocket - no ledge needed), then hands the goal to Baritone's elytra process through the API (nothing is printed). The black screen shows only an ETA in minutes. While gliding on this leg the screen is painted over with no peek and no exception; if you need to see, `,stop` - you land, you see, the run is void.
2. Within `arrive-radius` of the point you land; the mod says to build a portal and go through.
3. Coming out in the overworld, the mod checks you are within `exit-tolerance` of the point; farther, and the run is void.
4. In the nether after landing (and before takeoff) you see a 7x7x7 bubble around yourself - enough to build a portal or place a bed, nothing to recognise on video. Out of the portal, no block is drawn at all - sky, fluids and mobs only - until a dungeon spawner (one standing on cobblestone) is in range; then the run's dungeon is the ONLY thing drawn (x-ray style). You walk there, dig down, put your blocks back. A tracer line and box mark the run's dungeon. Block entities outside it aren't drawn. Beds, nether portals, obsidian and torches are always drawn. Every block you break during a run gets a ghost outline until the same block is back, and the HUD counts what's left to put back (positions live in memory only). F3 is dead for the whole run.
5. Blocks are already not drawn on the ground, but mobs, animals, players, item frames and dropped items still are, and those can hint at a biome or a village. So on the overworld ground phase the render distance is also pulled in to `ground-view-distance` (2 chunks = about 32 blocks): enough to see what is about to hit you, nothing further. It is restored when the run ends.
6. Within `dungeon-radius` of that spawner, with its chest open, the stash key works. The hides file records `blind=1`.
7. After the stash nothing is drawn again and everything stays locked until you are `return-distance` blocks away or in another dimension (pearl home, or die). Only then is the hash posted - a coin is never live on the site while the hider is still standing at the chest.

The point, the spawner's position and your position never leave memory. Any dimension change other than the expected nether-to-overworld voids the run.

## The ring (0.6)
Every round publishes its ring on the site before it opens (`GET /api/board?round=N` → `ring_min`, `ring_max`), and the site refuses to change it once the round is open.
For a real round the mod reads that ring when the run key is pressed and draws its point inside it. It never uses local numbers for a real round:

- no answer from the site, or no sane ring in it: the run does not start;
- the round is already open: the run does not start, and the stash key refuses too, so no book is ever left in a chest without a coin behind it;
- the round already holds all of its books (`planned` on the same board, counting books hidden here whose fingerprint has not reached the site yet): the run does not start, and the stash key refuses too. The site's database refuses a book too many as well;
- `test-distance` is refused for a real round.

**Overworld ring, nether flight.** A ring is given in overworld blocks. The point is drawn from it evenly by area and then divided by eight: a ring of 15,000 to 50,000 is a flight to somewhere 1,875 to 6,250 blocks from the nether's 0,0.

**The book ends up inside the ring, not just the point.** The landing and the portal can be a long way off the point, so the draw keeps `exit-tolerance` blocks away from both edges of the ring. On the ground, a dungeon outside the ring is never chosen as the run's dungeon, and for a real round the stash key checks the chest's own distance from spawn and refuses outside the ring.

**Never a short flight.** The point is at least `min-flight` nether blocks (1,000 by default) from where the run starts. The black screen shows how long the flight takes; a flight of a few seconds would tell the hider that the book is next door.

`min-ow-radius` and `max-ow-radius` only apply to round 0, which is for trying things in singleplayer. On a server, round 0 is refused once the site has closed its test round: a book hidden under it would belong to no round at all. The ring that was used is written to the audit log (`run started (ring 15000-50000 from the site)`); a ring is public, a point never is.

## Tamper handling
The mod cannot stop the person running it from removing it - nothing on your own computer can. What it does is make every deviation
visible: `meteor-client/quillcoin-audit.txt` is an append-only log (no coordinates) of `module on`, `run started`, `arrived after N peeks`,
`dungeon designated`, `stashed R1 Coin 37 at the end of a blind run` / `WITHOUT a blind run`, and every reason a run was voided
(module switched off, blind flight turned off, portal exit too far, dimension change). It is published with each round next to the hash list.
While a run is live or the screen is covered, Meteor's GUI (click GUI, HUD editor, settings) cannot be opened and Meteor's whole HUD is switched off (any element - Position, Waypoints - can be added in two clicks otherwise) and restored afterwards. The hider instance should carry nothing but Meteor, Baritone, performance mods and this addon - no map mods, no HUDs that print coordinates.

## The recording (0.7)
Every run records itself. Nobody presses record, so nobody chooses what gets filmed.

- **What is recorded:** the game's own window and the game's own sound. Not the screen, not other windows, not notifications, not the microphone. The recorder is a small program of its own (`recorder/quillcoin-recorder.swift`, macOS 15 or newer, ScreenCaptureKit) that the mod starts with the game's process id; it can only film a window of that process.
- **From when to when:** half a second after the run starts, until the hider is away from the chest (the pearl home, or a run that was voided). The world is hidden *before* the recording begins and stays hidden until the recorder has ended: the screen goes black and reads `SAVING THE RECORDING` for that moment. So a recording holds neither the place a run started from nor the place the hider went to.
- **On screen:** a red `● REC` at the top for as long as it records.
- **No recording, no hide:** with `require-recording` on, a run of a real round does not start without the recorder, and is void if the recording fails half way.
- **Where it goes:** `meteor-client/quillcoin-recordings/r1-coin-37.mp4`, with notes next to it (`.json`: round, number, the server, and how many seconds into the recording the book went in and the run ended - never a position). A run that hid no book is kept as `no-book-<time>.mp4`.
- **Publishing:** with `publish` on, the mod then starts `meteor-client/quillcoin-publish <recording> <notes>`, a program of your own that puts the recording on the site (the site's `tools/publish_hide.py` does it), and says in chat how it went.
- **A check before any run:** when you join a server the mod records two seconds, checks that picture and sound are there, throws them away, and tells you. The first time, macOS has to be told that the game may be recorded: System Settings > Privacy & Security > Screen & System Audio Recording.

Build the recorder with `recorder/build.sh` and put it at `meteor-client/quillcoin-recorder`.

## What it refuses
- Hiding while logged in as anyone but the configured author.
- Hiding while a chest-logging module (ChestDump, StashAudit, NetWorth, LayerKit) is on.
- Hiding into a full chest.
- A run of a real round that cannot be recorded.

## Numbers
Coin numbers are drawn at random from `1..numbers-per-round`, so `R1 Coin 37` says nothing about when or where it was hidden.

## Build
`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build` → `build/libs/quillcoin-hider-<version>.jar`. Requires Meteor Client for 1.21.4.
