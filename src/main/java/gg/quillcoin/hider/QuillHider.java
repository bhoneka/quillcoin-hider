package gg.quillcoin.hider;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import java.util.Map;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hides one QuillCoin: with a dungeon chest open and a book-and-quill in the hotbar, one key press
 * <ol>
 *   <li>makes a random 100-bit code,</li>
 *   <li>writes it into the book and signs it - the SERVER stamps the author, so a coin book can only ever be made by the official account,</li>
 *   <li>hashes the code with SHA-256 and forgets the code,</li>
 *   <li>shift-clicks the signed book into the chest and closes it,</li>
 *   <li>appends "round,number,hash,time,synced" to meteor-client/quillcoin-hides.txt and posts the same to the site.</li>
 * </ol>
 * The person at the keyboard never sees the code: it exists in memory for the few ticks between signing and hashing,
 * is never printed, never logged, never rendered. Tooltips of coin books are blanked and coin books cannot be opened
 * while this module is on. Nothing this module writes contains a coordinate.
 *
 * <p>Blind flight: while gliding, the whole screen is painted over with "FLYING" (peek key to look; damage lifts it
 * for a while) and F3 is forced off, so the hider learns as little as possible about where they are going.
 */
public class QuillHider extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlind = settings.createGroup("Blind flight");

    private final Setting<String> author = sgGeneral.add(new StringSetting.Builder()
        .name("author").description("The official account. The module refuses to hide unless you are logged in as it.").defaultValue("QuillCoin").build());
    private final Setting<Integer> round = sgGeneral.add(new IntSetting.Builder()
        .name("round").description("Round number written into the book title (R1 Coin 37). Use 0 for tests - the site ignores round 0.").defaultValue(1).min(0).sliderMax(50).build());
    private final Setting<Integer> numbersPerRound = sgGeneral.add(new IntSetting.Builder()
        .name("numbers-per-round").description("Coin numbers are drawn at random from 1..this, so the number never reveals the hiding order.").defaultValue(1000).min(10).sliderMax(100000).build());
    private final Setting<String> siteUrl = sgGeneral.add(new StringSetting.Builder()
        .name("site-url").description("Where hashes are posted (POST /api/hide). Empty = keep them in the local file only.").defaultValue("https://quillcoin.gg").build());
    private final Setting<String> apiKey = sgGeneral.add(new StringSetting.Builder()
        .name("api-key").description("Bearer token for the site's hider endpoint. Stored in plain text in modules.nbt.").defaultValue("").build());
    private final Setting<Keybind> stashKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("stash-key").description("With a chest open: sign the book in your hotbar, hash it, put it in the chest.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_P)).build());

    private final Setting<Boolean> blind = sgBlind.add(new BoolSetting.Builder()
        .name("blind").description("Paint the screen over while gliding and keep F3 off.").defaultValue(true).build());
    private final Setting<Keybind> peekKey = sgBlind.add(new KeybindSetting.Builder()
        .name("peek-key").description("Hold to see the screen while gliding.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT)).build());
    private final Setting<Integer> maxPeeks = sgBlind.add(new IntSetting.Builder()
        .name("max-peeks").description("Peeks allowed per run (for emergencies). One more than this voids the run.").defaultValue(3).min(0).sliderMax(20).build());
    private final Setting<Boolean> feed = sgBlind.add(new BoolSetting.Builder()
        .name("debug-feed").description("Show the last few things the mod did, bottom right, even over the dark screen. Never a coordinate.").defaultValue(true).build());
    private final Setting<Integer> damageReveal = sgBlind.add(new IntSetting.Builder()
        .name("damage-reveal").description("Ticks the screen stays visible after you take damage, so you can deal with it.").defaultValue(200).min(0).sliderMax(1200).build());

    private final SettingGroup sgRun = settings.createGroup("Blind run");
    private final Setting<Boolean> requireRun = sgRun.add(new BoolSetting.Builder()
        .name("require-blind-run").description("The stash key only works at the dungeon a blind run led you to. Off = any chest (testing only).").defaultValue(true).build());
    private final Setting<Keybind> runKey = sgRun.add(new KeybindSetting.Builder()
        .name("run-key").description("In the nether: pick a random point you will never be shown and fly there with Baritone. Press again to resume a stalled run.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_O)).build());
    private final Setting<Integer> minRadius = sgRun.add(new IntSetting.Builder()
        .name("min-ow-radius").description("Closest the random point may be to 0,0 - in OVERWORLD blocks.").defaultValue(15000).min(0).sliderMax(1000000).build());
    private final Setting<Integer> maxRadius = sgRun.add(new IntSetting.Builder()
        .name("max-ow-radius").description("Farthest the random point may be from 0,0 - in OVERWORLD blocks (nether flight is an eighth of it).").defaultValue(100000).min(1000).sliderMax(5000000).build());
    private final Setting<Integer> arriveRadius = sgRun.add(new IntSetting.Builder()
        .name("arrive-radius").description("Nether blocks from the point that count as arrived.").defaultValue(300).min(50).sliderMax(2000).build());
    private final Setting<Integer> exitTolerance = sgRun.add(new IntSetting.Builder()
        .name("exit-tolerance").description("If you come out of the portal farther than this (overworld blocks) from the run's point, the run is void.").defaultValue(2500).min(200).sliderMax(20000).build());
    private final Setting<Integer> groundView = sgRun.add(new IntSetting.Builder()
        .name("ground-view-distance").description("Render distance (chunks) while you are on the ground in the overworld part of a run: enough to fight and dig, not enough to recognise the area. Restored when the run ends.").defaultValue(2).min(2).sliderMax(8).build());
    private final Setting<Integer> dungeonRadius = sgRun.add(new IntSetting.Builder()
        .name("dungeon-radius").description("You must be within this many blocks of the run's spawner to stash.").defaultValue(10).min(4).sliderMax(24).build());

    /** NONE -> FLYING (nether, Baritone) -> ARRIVED (build a portal) -> OVERWORLD (fly, find a spawner) -> DESIGNATED (stash allowed here) */
    private enum RunStage { NONE, FLYING, ARRIVED, OVERWORLD, DESIGNATED }
    private RunStage run = RunStage.NONE;
    private int targetNX, targetNZ;                                   // the run's point, nether coords - lives here and nowhere else
    private BlockPos spawner;                                         // the run's dungeon
    private RegistryKey<World> lastDim;
    private long lastScan;

    private final java.util.ArrayDeque<String> feedLines = new java.util.ArrayDeque<>();
    private void feed(String s) { feedLines.addLast(s); while (feedLines.size() > 8) feedLines.removeFirst(); }
    private void say(String fmt, Object... a) { String m = String.format(fmt, a); info(m); feed(m); }
    private void warn(String fmt, Object... a) { String m = String.format(fmt, a); warning(m); feed("! " + m); }
    private void fail(String fmt, Object... a) { String m = String.format(fmt, a); error(m); feed("X " + m); }

    private enum Stage { IDLE, SIGNING, STASHING, RETRY }
    private Stage stage = Stage.IDLE;
    private long tick, stageSince, lastDamageTick = -100000;
    private float lastHealth;
    private int pendingSlot = -1, pendingNumber;
    private String pendingHash, pendingTitle;
    private boolean warnedMaps, peeking, hudWasActive = true, hudSuppressed, elytraWasActive;
    private int savedView = -1;
    private int peeks;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // Crockford base32: no I, L, O, U

    public QuillHider() {
        super(QuillHiderAddon.CATEGORY, "quill-hider", "Signs a coin book with a random code, hashes it, stashes it in the open chest, and never shows you the code.");
    }

    @Override
    public void onDeactivate() {
        if (hudSuppressed) { Hud.get().active = hudWasActive; hudSuppressed = false; }
        restoreView();
        restoreAutoJump();
        if (run != RunStage.NONE) audit("module switched off during a run (" + run.name().toLowerCase() + ") - run void");
        run = RunStage.NONE;
        spawner = null;
        if (stage != Stage.IDLE) { audit("module switched off mid-stash (" + stage.name().toLowerCase() + ")"); stage = Stage.IDLE; }
    }

    @Override
    public void onActivate() {
        audit("module on");
        stage = Stage.IDLE;
        run = RunStage.NONE;
        spawner = null;
        lastDim = mc.world == null ? null : mc.world.getRegistryKey();
        warnedMaps = false;
        lastHealth = mc.player == null ? 20 : mc.player.getHealth();
        new Thread(this::resync, "quillcoin-resync").start();
    }

    private void shrinkView() {
        if (savedView != -1) return;
        savedView = mc.options.getViewDistance().getValue();
        mc.options.getViewDistance().setValue(groundView.get());
        feed("view pulled in to " + groundView.get() + " chunks");
    }

    private void restoreAutoJump() {
        if (savedAutoJump == null) return;
        try { BaritoneAPI.getSettings().elytraAutoJump.value = savedAutoJump; } catch (Throwable ignored) { }
        savedAutoJump = null;
    }

    private void restoreView() {
        if (savedView == -1) return;
        mc.options.getViewDistance().setValue(savedView);
        savedView = -1;
        feed("view distance restored");
    }

    private static File hidesFile() { return new File(MeteorClient.FOLDER, "quillcoin-hides.txt"); }
    private static File auditFile() { return new File(MeteorClient.FOLDER, "quillcoin-audit.txt"); }

    /** Integrity events, append-only, published with the round. Never a coordinate. */
    private void audit(String event) {
        try {
            Files.writeString(auditFile().toPath(), (System.currentTimeMillis() / 1000) + "," + event + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
        feed(event);
    }

    // ------------------------------------------------------------------ the key

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press || mc.player == null) return;
        if (runKey.get().matches(true, event.key, event.modifiers)) { event.cancel(); startOrResumeRun(); return; }
        if (!stashKey.get().matches(true, event.key, event.modifiers)) return;
        event.cancel();
        if (stage == Stage.RETRY) { retryStash(); return; }
        if (stage != Stage.IDLE) { say("Busy (%s).", stage.name().toLowerCase()); return; }
        stash();
    }

    private void stash() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !(hs.getScreenHandler() instanceof GenericContainerScreenHandler handler)) {
            fail("Open the dungeon chest first, then press the key."); return;
        }
        String me = mc.getSession() == null ? "" : mc.getSession().getUsername();
        if (!me.equals(author.get())) { fail("You are %s, not %s - not hiding.", me, author.get()); return; }
        if (requireRun.get()) {
            if (run != RunStage.DESIGNATED || spawner == null) { fail("No blind run brought you here. Press %s in the nether first.", runKey.get()); return; }
            if (!mc.player.getBlockPos().isWithinDistance(spawner, dungeonRadius.get())) { fail("This isn't the run's dungeon. Follow the arrow."); return; }
        }
        for (String logger : new String[]{"chest-dump", "stash-audit", "net-worth", "layer-kit"}) {
            Module m = Modules.get().get(logger);
            if (m != null && m.isActive()) { fail("%s is on - it logs chest contents. Turn it off first.", m.title); return; }
        }
        if (!warnedMaps && (FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaeroworldmap"))) {
            warnedMaps = true;
            warn("This instance has Xaero maps installed. Blind flight can't cover them - the hider instance should have no map mods.");
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(Items.WRITABLE_BOOK)) { slot = i; break; }
        if (slot == -1) { fail("No book-and-quill in your hotbar."); return; }
        Inventory chest = handler.getInventory();
        boolean room = false;
        for (int i = 0; i < chest.size(); i++) if (chest.getStack(i).isEmpty()) { room = true; break; }
        if (!room) { fail("That chest is full."); return; }

        int number = drawNumber();
        if (number == -1) { fail("Every number in this round is used - raise numbers-per-round."); return; }
        String code = makeCode();
        String title = "R" + round.get() + " Coin " + number;
        List<String> pages = List.of(
            code,
            "This book is one QuillCoin.\n\nRedeem the code on the first page at\nquillcoin.gg\n\nFirst redemption wins."
        );
        pendingHash = sha256(code);
        code = null;                                                  // the code's whole life: made, written, hashed, gone
        pendingNumber = number;
        pendingTitle = title;
        pendingSlot = slot;
        mc.getNetworkHandler().sendPacket(new BookUpdateC2SPacket(slot, pages, Optional.of(title)));
        feed("code made + hashed, asking the server to sign");
        stage = Stage.SIGNING;
        stageSince = tick;
    }

    private void retryStash() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !(hs.getScreenHandler() instanceof GenericContainerScreenHandler handler)) {
            fail("Open a chest, then press the key to stash the signed book."); return;
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (isCoinBook(mc.player.getInventory().getStack(i))) { slot = i; break; }
        if (slot == -1) { stage = Stage.IDLE; fail("No signed coin book in the hotbar any more."); return; }
        pendingSlot = slot;
        quickMove(handler, slot);
        stage = Stage.STASHING;
        stageSince = tick;
    }

    private void quickMove(GenericContainerScreenHandler handler, int hotbarSlot) {
        int idx = handler.getInventory().size() + 27 + hotbarSlot;     // container slots, then 27 main, then the 9 hotbar
        mc.interactionManager.clickSlot(handler.syncId, idx, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    // ------------------------------------------------------------------ every tick

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tick++;
        if (mc.player == null) return;
        float h = mc.player.getHealth();
        if (h < lastHealth - 0.01f) lastDamageTick = tick;
        lastHealth = h;
        if (blind.get() && mc.getDebugHud().shouldShowDebugHud()) mc.getDebugHud().toggleDebugHud();
        if (run != RunStage.NONE && !blind.get()) { audit("blind flight turned off during a run - run void"); run = RunStage.NONE; spawner = null; warn("Blind flight was turned off - the run is void."); }
        // Meteor's HUD draws after everything else and any element (Position, Waypoints...) can be added in two clicks:
        // while a run is live or the screen is covered, the HUD is simply off. It comes back when the run ends.
        if (run != RunStage.FLYING && savedAutoJump != null) restoreAutoJump();
        boolean onGroundPhase = run == RunStage.OVERWORLD || run == RunStage.DESIGNATED;
        if (onGroundPhase && savedView == -1) shrinkView();
        else if (!onGroundPhase && savedView != -1) restoreView();
        boolean wantHudOff = run != RunStage.NONE;
        Hud hud = Hud.get();
        if (wantHudOff && !hudSuppressed) { hudWasActive = hud.active; hud.active = false; hudSuppressed = true; }
        else if (!wantHudOff && hudSuppressed) { hud.active = hudWasActive; hudSuppressed = false; }
        else if (wantHudOff && hud.active) hud.active = false;                       // someone toggled it back on mid-run
        if (run == RunStage.FLYING && mc.player.isGliding() && peekKey.get().isPressed() && !peeking) {
            peeking = true; peeks++;
            feed("peek " + peeks + "/" + maxPeeks.get());
            if (peeks > maxPeeks.get()) { audit("peeked more than " + maxPeeks.get() + " times - run void"); run = RunStage.NONE; spawner = null; warn("Too many peeks - the run is void."); }
        }
        if (!peekKey.get().isPressed()) peeking = false;
        runTick();

        switch (stage) {
            case SIGNING -> {
                ItemStack st = mc.player.getInventory().getStack(pendingSlot);
                if (isCoinBook(st)) {
                    feed("server signed the book");
                    if (mc.currentScreen instanceof HandledScreen<?> hs && hs.getScreenHandler() instanceof GenericContainerScreenHandler handler) {
                        quickMove(handler, pendingSlot);
                        feed("moving it into the chest");
                        stage = Stage.STASHING;
                        stageSince = tick;
                    } else {
                        stage = Stage.RETRY;
                        warn("Book signed but the chest closed. Open a chest and press the key - and do not open the book.");
                    }
                } else if (tick - stageSince > 60) {
                    stage = Stage.IDLE;
                    fail("The server didn't sign the book. Nothing was hidden; the number is free again.");
                }
            }
            case STASHING -> {
                ItemStack st = mc.player.getInventory().getStack(pendingSlot);
                if (!isCoinBook(st)) {
                    stage = Stage.IDLE;
                    record();
                    if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
                } else if (tick - stageSince > 40) {
                    stage = Stage.RETRY;
                    warn("The signed book is still in your hotbar (chest full?). Open a chest with room and press the key. Do not open the book.");
                }
            }
            default -> {}
        }
    }

    private boolean isCoinBook(ItemStack st) {
        if (!st.isOf(Items.WRITTEN_BOOK)) return false;
        WrittenBookContentComponent c = st.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        return c != null && author.get().equals(c.author());
    }

    // ------------------------------------------------------------------ never show a coin

    @EventHandler
    private void onTooltip(ItemStackTooltipEvent event) {
        if (!isCoinBook(event.itemStack())) return;
        event.list().clear();
        event.list().add(Text.literal("QuillCoin book - contents hidden"));
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;
        if (event.screen instanceof WidgetScreen && run != RunStage.NONE) {
            event.cancel();
            audit("meteor gui blocked during the run");
            warn("Meteor's GUI is locked while a run is live.");
            return;
        }
        if (!(event.screen instanceof BookScreen)) return;
        if (isCoinBook(mc.player.getMainHandStack()) || isCoinBook(mc.player.getOffHandStack())) {
            event.cancel();
            warn("That's a coin book. Not showing it.");
        }
    }

    // ------------------------------------------------------------------ blind flight

    /**
     * Meteor posts Render2DEvent under an UNSCALED projection: one unit is one framebuffer pixel, not a GUI unit. So the
     * cover is drawn in pixels, and text is drawn under a pushed matrix scaled back up to GUI size.
     */
    @EventHandler(priority = EventPriority.LOWEST - 1000)
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null) return;
        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        int sw = Math.max(1, mc.getWindow().getScaledWidth()), sh = Math.max(1, mc.getWindow().getScaledHeight());
        float sx = fw / (float) sw, sy = fh / (float) sh;
        boolean covered = blind.get() && run == RunStage.FLYING && mc.player.isGliding() && tick - lastDamageTick >= damageReveal.get() && !peekKey.get().isPressed();
        if (covered) event.drawContext.fill(-fw, -fh, fw * 3, fh * 3, 0xFF000000);       // belt and braces: whatever the projection, it's black
        event.drawContext.getMatrices().push();
        event.drawContext.getMatrices().scale(sx, sy, 1f);
        if (covered) {
            int rockets = 0;
            for (int i = 0; i < 36; i++) { ItemStack s = mc.player.getInventory().getStack(i); if (s.isOf(Items.FIREWORK_ROCKET)) rockets += s.getCount(); }
            int cx = sw / 2, cy = sh / 2;
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "FLYING", cx, cy - 10, 0xFFFFFFFF);
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "rockets " + rockets + "   hp " + (int) mc.player.getHealth(), cx, cy + 4, 0xFFAAAAAA);
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "hold " + peekKey.get() + " to peek", cx, cy + 16, 0xFF666666);
        } else {
            String top = run == RunStage.DESIGNATED ? arrow()
                : run == RunStage.FLYING && !mc.player.isGliding() ? "blind run: take off (" + runKey.get() + " to resume)"
                : run == RunStage.ARRIVED ? "blind run: build a portal here and go through"
                : run == RunStage.OVERWORLD ? "blind run: fly around until a dungeon is found" : "";
            if (!top.isEmpty()) event.drawContext.drawTextWithShadow(mc.textRenderer, top, 6, 6, 0xFFE6C85A);
        }
        if (feed.get()) {
            int y = 6;                                                                 // top right: the HUD is off during runs, so the corner is free
            String head = "quill " + run.name().toLowerCase() + " / " + stage.name().toLowerCase();
            event.drawContext.drawTextWithShadow(mc.textRenderer, head, sw - 6 - mc.textRenderer.getWidth(head), y, 0xFFE6C85A);
            y += 12;
            for (String l : feedLines) {
                String t = l.length() > 42 ? l.substring(0, 41) + "…" : l;
                event.drawContext.drawTextWithShadow(mc.textRenderer, t, sw - 6 - mc.textRenderer.getWidth(t), y, 0xFFBBBBBB);
                y += 10;
            }
        }
        event.drawContext.getMatrices().pop();
    }

    // ------------------------------------------------------------------ the blind run

    private void startOrResumeRun() {
        if (mc.world == null || mc.world.getRegistryKey() != World.NETHER) { fail("Blind runs start in the nether."); return; }
        String me = mc.getSession() == null ? "" : mc.getSession().getUsername();
        if (!me.equals(author.get())) { fail("You are %s, not %s.", me, author.get()); return; }
        try { Class.forName("baritone.api.BaritoneAPI"); } catch (Throwable t) { fail("Baritone isn't installed."); return; }
        if (run == RunStage.FLYING) {
            if (!elytraWasActive) { fly(); info("Handing the goal to Baritone again. Take off."); feed("retrying takeoff"); }
            else info("A run is in the air. Land somewhere that isn't the point (,stop) to void it, or let it finish.");
            return;
        }
        if (run != RunStage.NONE) { say("A run is already in progress (%s).", run.name().toLowerCase()); return; }
        // uniform over the ring between min and max overworld radius, then scaled to the nether
        double a = RNG.nextDouble() * Math.PI * 2;
        double lo = minRadius.get(), hi = Math.max(minRadius.get() + 1000, maxRadius.get());
        double r = Math.sqrt(lo * lo + RNG.nextDouble() * (hi * hi - lo * lo));
        targetNX = (int) Math.round(r * Math.cos(a) / 8.0);
        targetNZ = (int) Math.round(r * Math.sin(a) / 8.0);
        spawner = null;
        peeks = 0;
        elytraWasActive = false;
        run = RunStage.FLYING;
        audit("run started");
        fly();
        info("Blind run started - Baritone has a point you will never be shown. Take off; the screen goes dark while you glide.");
        feed("blind run started");
    }

    private Boolean savedAutoJump;

    private void fly() {
        try {
            if (savedAutoJump == null) savedAutoJump = BaritoneAPI.getSettings().elytraAutoJump.value;
            BaritoneAPI.getSettings().elytraAutoJump.value = true;                 // Baritone jumps and deploys on its own
            GoalXZ g = new GoalXZ(targetNX, targetNZ);
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(g);
            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(g);
        } catch (Throwable t) { fail("Couldn't hand the goal to Baritone: %s", t.getMessage()); run = RunStage.NONE; }
    }

    private boolean elytraActive() {
        try { return BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive(); } catch (Throwable t) { return false; }
    }

    private void runTick() {
        RegistryKey<World> dim = mc.world.getRegistryKey();
        if (lastDim != null && dim != lastDim) onDimensionChange(lastDim, dim);
        lastDim = dim;
        switch (run) {
            case FLYING -> {
                if (dim != World.NETHER) return;
                double d = Math.hypot(mc.player.getX() - targetNX, mc.player.getZ() - targetNZ);
                boolean active = elytraActive();
                if (d <= arriveRadius.get() && !mc.player.isGliding()) {
                    run = RunStage.ARRIVED;
                    elytraWasActive = false;
                    audit("arrived at the point after " + peeks + " peek(s)");
                    say("Arrived. Make a portal here and go through it - the run continues in the overworld.");
                } else if (elytraWasActive && !active && !mc.player.isGliding()) {
                    // ,stop, an emergency landing, out of rockets: the flight ended somewhere that isn't the point
                    run = RunStage.NONE;
                    elytraWasActive = false;
                    audit("flight ended before the point - run void");
                    warn("The flight ended before the point - the run is void. Press %s for a new one.", runKey.get());
                } else if (!active && !mc.player.isGliding() && !elytraWasActive && tick % 600 == 0) {
                    info("Baritone hasn't taken off. Press %s to try again.", runKey.get());
                    feed("baritone idle - press " + runKey.get());
                }
                if (active) elytraWasActive = true;
            }
            case OVERWORLD -> {
                if (dim != World.OVERWORLD || tick - lastScan < 20) return;
                lastScan = tick;
                BlockPos found = nearestDungeonSpawner();
                if (found != null) {
                    spawner = found;
                    run = RunStage.DESIGNATED;
                    audit("dungeon designated");
                    say("Dungeon found. Follow the arrow, dig down, put the blocks back, and stash there.");
                }
            }
            default -> {}
        }
    }

    private void onDimensionChange(RegistryKey<World> from, RegistryKey<World> to) {
        if (run == RunStage.ARRIVED && from == World.NETHER && to == World.OVERWORLD) {
            double d = Math.hypot(mc.player.getX() - targetNX * 8.0, mc.player.getZ() - targetNZ * 8.0);
            if (d > exitTolerance.get()) {
                run = RunStage.NONE;
                audit("portal exit too far from the point - run void");
                warn("You came out of the portal too far from the run's point - the run is void. Start another.");
            } else {
                run = RunStage.OVERWORLD;
                say("In the overworld at the run's point. Fly around; the first dungeon the mod sees becomes yours.");
            }
        } else if ((run == RunStage.OVERWORLD || run == RunStage.DESIGNATED) && to == World.OVERWORLD) {
            // back in the overworld after a portal / bed / respawn dance: still fine as long as you are near the point
            double d = Math.hypot(mc.player.getX() - targetNX * 8.0, mc.player.getZ() - targetNZ * 8.0);
            if (d > exitTolerance.get()) { run = RunStage.NONE; spawner = null; warn("You're back in the overworld far from the run's point - the run is void."); }
            else feed("back in the overworld near the point - run continues");
        } else if ((run == RunStage.OVERWORLD || run == RunStage.DESIGNATED) && to == World.NETHER) {
            feed("in the nether - run paused until you're back in the overworld");
        } else if (run == RunStage.FLYING && to != World.NETHER) {
            run = RunStage.NONE;
            spawner = null;
            warn("Left the nether mid-flight - the run is void.");
        }
    }

    /** A dungeon spawner: sits on cobblestone/mossy floor. Mineshaft and fortress spawners don't. */
    private BlockPos nearestDungeonSpawner() {
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        for (int cx = pcx - 8; cx <= pcx + 8; cx++) for (int cz = pcz - 8; cz <= pcz + 8; cz++) {
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            WorldChunk chunk = mc.world.getChunk(cx, cz);
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                if (!(e.getValue() instanceof MobSpawnerBlockEntity)) continue;
                BlockPos p = e.getKey();
                var below = mc.world.getBlockState(p.down());
                if (!below.isOf(Blocks.COBBLESTONE) && !below.isOf(Blocks.MOSSY_COBBLESTONE)) continue;
                double d = p.getSquaredDistance(mc.player.getPos());
                if (d < bestD) { bestD = d; best = p.toImmutable(); }
            }
        }
        return best;
    }

    /** Relative guidance only: bearing words, distance, depth. Never a coordinate. */
    private String arrow() {
        if (spawner == null || mc.player == null) return "";
        double dx = spawner.getX() + 0.5 - mc.player.getX(), dz = spawner.getZ() + 0.5 - mc.player.getZ();
        double dist = Math.hypot(dx, dz);
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));                 // minecraft yaw convention
        double rel = ((bearing - mc.player.getYaw()) % 360 + 540) % 360 - 180;   // -180..180, 0 = straight ahead
        String dir = Math.abs(rel) < 22 ? "ahead" : Math.abs(rel) > 158 ? "behind" : rel > 0 ? (rel < 68 ? "ahead-right" : rel < 112 ? "right" : "behind-right") : (rel > -68 ? "ahead-left" : rel > -112 ? "left" : "behind-left");
        int dy = spawner.getY() - mc.player.getBlockPos().getY();
        return String.format("dungeon %s, %dm, %s", dir, (int) dist, dy < -1 ? (-dy) + " down" : dy > 1 ? dy + " up" : "level");
    }

    // ------------------------------------------------------------------ code, hash, record, sync

    private static String makeCode() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder("QLL");
        long acc = 0; int bits = 0, out = 0;
        for (byte x : b) {
            acc = (acc << 8) | (x & 0xFF); bits += 8;
            while (bits >= 5 && out < 20) {
                if (out % 5 == 0) sb.append('-');
                sb.append(ALPHABET.charAt((int) ((acc >> (bits - 5)) & 31)));
                bits -= 5; out++;
            }
        }
        return sb.toString();                                         // QLL-XXXXX-XXXXX-XXXXX-XXXXX, 100 bits
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private Set<Integer> usedNumbers() {
        Set<Integer> used = new HashSet<>();
        try {
            if (hidesFile().exists()) for (String line : Files.readAllLines(hidesFile().toPath())) {
                String[] p = line.split(",");
                if (p.length >= 2 && Integer.parseInt(p[0].trim()) == round.get()) used.add(Integer.parseInt(p[1].trim()));
            }
        } catch (Exception ignored) { }
        return used;
    }

    private int drawNumber() {
        Set<Integer> used = usedNumbers();
        if (used.size() >= numbersPerRound.get()) return -1;
        for (int tries = 0; tries < 100000; tries++) {
            int n = 1 + RNG.nextInt(numbersPerRound.get());
            if (!used.contains(n)) return n;
        }
        return -1;
    }

    /** round,number,hash,unix-seconds,synced - and nothing else, ever. */
    private void record() {
        long now = System.currentTimeMillis() / 1000;
        boolean blindRun = run == RunStage.DESIGNATED && spawner != null;
        audit("stashed " + pendingTitle + (blindRun ? " at the end of a blind run" : " WITHOUT a blind run"));
        String line = round.get() + "," + pendingNumber + "," + pendingHash + "," + now + ",0," + (blindRun ? 1 : 0);
        run = RunStage.NONE;
        spawner = null;
        try {
            Files.writeString(hidesFile().toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { fail("Couldn't write quillcoin-hides.txt: %s", e.getMessage()); }
        say("%s hidden%s. Hash %s… recorded. Pearl home.", pendingTitle, blindRun ? " at the end of a blind run" : "", pendingHash.substring(0, 10));
        final int r = round.get(), n = pendingNumber; final String hsh = pendingHash;
        pendingHash = null;
        new Thread(() -> { if (post(r, n, hsh, now)) { markSynced(hsh); say("Hash posted to the site."); } else say("Site unreachable - the hash is saved locally and will be posted next time the module turns on."); }, "quillcoin-post").start();
    }

    private boolean post(int r, int n, String hash, long ts) {
        String url = siteUrl.get().trim();
        if (url.isEmpty() || apiKey.get().isEmpty()) return false;
        try {
            String body = String.format("{\"round\":%d,\"number\":%d,\"hash\":\"%s\",\"ts\":%d}", r, n, hash, ts);   // blind flag rides in the file; the site learns it on sync v2
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/api/hide"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.get())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) { return false; }
    }

    private synchronized void markSynced(String hash) {
        try {
            if (!hidesFile().exists()) return;
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(hidesFile().toPath())) {
                String[] p = line.split(",");
                if (p.length >= 5 && p[2].equals(hash)) { p[4] = "1"; out.add(String.join(",", p)); } else out.add(line);
            }
            Files.write(hidesFile().toPath(), out, StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private void resync() {
        try {
            if (!hidesFile().exists() || apiKey.get().isEmpty()) return;
            int sent = 0;
            for (String line : Files.readAllLines(hidesFile().toPath())) {
                String[] p = line.split(",");
                if (p.length < 5 || !p[4].trim().equals("0")) continue;
                if (post(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), p[2].trim(), Long.parseLong(p[3].trim()))) { markSynced(p[2].trim()); sent++; }
            }
            if (sent > 0) say("Posted %d hash%s that were waiting.", sent, sent == 1 ? "" : "es");
        } catch (Exception ignored) { }
    }
}
