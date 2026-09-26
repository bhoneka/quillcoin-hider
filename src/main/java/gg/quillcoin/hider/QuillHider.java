package gg.quillcoin.hider;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
import meteordevelopment.meteorclient.events.render.TooltipDataEvent;
import meteordevelopment.meteorclient.events.render.RenderBlockEntityEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import java.util.LinkedHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.Hand;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
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
import net.minecraft.client.network.ServerInfo;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
        .name("site-url").description("Where hashes are posted (POST <url>/api/hide). https://quillcoin.gg resolves to the QuillCoin API. Empty = keep them in the local file only.").defaultValue("https://quillcoin.gg").build());
    private final Setting<String> apiKey = sgGeneral.add(new StringSetting.Builder()
        .name("api-key").description("Bearer token for the site's hider endpoint. Leave empty and put it in meteor-client/quillcoin-key.txt instead, so it never lands in modules.nbt.").defaultValue("").build());
    private final Setting<Keybind> previewKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("preview-book-key").description("Singleplayer only: signs a SAMPLE book (fake code, nothing recorded or posted) from the book-and-quill in your hotbar, so the page layout in meteor-client/quillcoin-book.txt can be designed live.").defaultValue(Keybind.none()).build());
    private final Setting<Keybind> stashKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("stash-key").description("With a chest open: sign the book in your hotbar, hash it, put it in the chest.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_P)).build());

    private final Setting<Boolean> blind = sgBlind.add(new BoolSetting.Builder()
        .name("blind").description("Paint the screen over - no exceptions - while gliding on the nether leg of a run, and keep F3 off.").defaultValue(true).build());
    private final Setting<Boolean> feed = sgBlind.add(new BoolSetting.Builder()
        .name("debug-feed").description("Show the last few things the mod did, bottom right, even over the dark screen. Never a coordinate.").defaultValue(true).build());

    private final SettingGroup sgRun = settings.createGroup("Blind run");
    private final Setting<Boolean> requireRun = sgRun.add(new BoolSetting.Builder()
        .name("require-blind-run").description("The stash key only works at the dungeon a blind run led you to. Off = any chest (testing only).").defaultValue(true).build());
    private final Setting<Keybind> runKey = sgRun.add(new KeybindSetting.Builder()
        .name("run-key").description("In the nether: pick a random point you will never be shown and fly there with Baritone. Press again to resume a stalled run.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_O)).build());
    private final Setting<Integer> minRadius = sgRun.add(new IntSetting.Builder()
        .name("min-ow-radius").description("Closest the random point may be to 0,0 - in OVERWORLD blocks.").defaultValue(15000).min(0).sliderMax(1000000).build());
    private final Setting<Integer> maxRadius = sgRun.add(new IntSetting.Builder()
        .name("max-ow-radius").description("Farthest the random point may be from 0,0 - in OVERWORLD blocks (nether flight is an eighth of it).").defaultValue(100000).min(1000).sliderMax(5000000).build());
    private final Setting<Integer> testRadius = sgRun.add(new IntSetting.Builder()
        .name("test-distance").description("TESTING ONLY: 0 = off. Otherwise the point is drawn this many NETHER blocks (give or take 20%) from where you stand instead of the spawn-centred ring. Logged in the audit as a test run.").defaultValue(0).min(0).sliderMax(5000).build());
    private final Setting<Keybind> devSkipKey = sgRun.add(new KeybindSetting.Builder()
        .name("dev-skip-key").description("Singleplayer test worlds only: teleports you to the run's point in the nether with command feedback muted, so the coordinates never appear in chat. Logged as a dev skip.").defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_J)).build());
    private final Setting<Integer> arriveRadius = sgRun.add(new IntSetting.Builder()
        .name("arrive-radius").description("Nether blocks from the point that count as arrived.").defaultValue(300).min(50).sliderMax(2000).build());
    private final Setting<Integer> exitTolerance = sgRun.add(new IntSetting.Builder()
        .name("exit-tolerance").description("If you come out of the portal farther than this (overworld blocks) from the run's point, the run is void.").defaultValue(2500).min(200).sliderMax(20000).build());
    private final Setting<Integer> groundView = sgRun.add(new IntSetting.Builder()
        .name("ground-view-distance").description("Render distance (chunks) on the overworld ground phase. Blocks are not drawn anyway; this limits how far mobs, players, item frames and drops are drawn, since those can hint at a biome. Restored when the run ends.").defaultValue(2).min(2).sliderMax(8).build());
    private final Setting<Integer> returnDistance = sgRun.add(new IntSetting.Builder()
        .name("return-distance").description("After the stash the screen stays dark and locked until you are this far (blocks) from the chest, or in another dimension. Only then is the hash posted.").defaultValue(3000).min(200).sliderMax(50000).build());
    private final Setting<Integer> dungeonRadius = sgRun.add(new IntSetting.Builder()
        .name("dungeon-radius").description("You must be within this many blocks of the run's spawner to stash.").defaultValue(10).min(4).sliderMax(24).build());

    /** NONE -> FLYING (nether, Baritone) -> ARRIVED (build a portal) -> OVERWORLD (fly, find a spawner) -> DESIGNATED (stash allowed here) */
    private enum RunStage { NONE, FLYING, ARRIVED, OVERWORLD, DESIGNATED, RETURNING }
    private static QuillHider INSTANCE;
    /** OFF = draw the world; NOTHING = only whitelisted blocks; DUNGEON = the run's dungeon box; BUBBLE = 7x7x7 around the hider */
    private enum Xray { OFF, NOTHING, DUNGEON, BUBBLE }
    private static volatile Xray xray = Xray.OFF;
    private static volatile BlockPos xrayCenter, bubbleCenter;
    private static final int XRAY_XZ = 6, XRAY_DOWN = 2, XRAY_UP = 5, BUBBLE = 3;

    /** Called for every block the chunk builder meshes. True = leave it out. Beds, portals, obsidian and torches always draw. */
    public static boolean hideBlock(BlockPos pos, BlockState state) {
        Xray m = xray;
        if (m == Xray.OFF) return false;
        if (state != null) {
            if (state.getBlock() instanceof BedBlock || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.CRYING_OBSIDIAN)
                || state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH) || state.isOf(Blocks.SOUL_TORCH) || state.isOf(Blocks.SOUL_WALL_TORCH)) return false;
        }
        if (m == Xray.NOTHING) return true;
        if (m == Xray.BUBBLE) {
            BlockPos b = bubbleCenter;
            return b == null || Math.abs(pos.getX() - b.getX()) > BUBBLE || Math.abs(pos.getY() - b.getY()) > BUBBLE || Math.abs(pos.getZ() - b.getZ()) > BUBBLE;
        }
        BlockPos c = xrayCenter;
        if (c == null) return true;
        return Math.abs(pos.getX() - c.getX()) > XRAY_XZ || Math.abs(pos.getZ() - c.getZ()) > XRAY_XZ || pos.getY() < c.getY() - XRAY_DOWN || pos.getY() > c.getY() + XRAY_UP;
    }

    private void setXray(Xray m, BlockPos center) {
        boolean changed = m != xray || (center == null ? xrayCenter != null : !center.equals(xrayCenter));
        xray = m; xrayCenter = center;
        if (changed && mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    /** The bubble follows the hider: re-mesh the sections around the old and new spot when they move a block. */
    private void bubbleTick() {
        if (xray != Xray.BUBBLE || mc.player == null) return;
        BlockPos p = mc.player.getBlockPos();
        if (p.equals(bubbleCenter)) return;
        BlockPos old = bubbleCenter;
        bubbleCenter = p;
        int r = BUBBLE + 1;
        mc.worldRenderer.scheduleBlockRenders(p.getX() - r, p.getY() - r, p.getZ() - r, p.getX() + r, p.getY() + r, p.getZ() + r);
        if (old != null) mc.worldRenderer.scheduleBlockRenders(old.getX() - r, old.getY() - r, old.getZ() - r, old.getX() + r, old.getY() + r, old.getZ() + r);
    }

    // ---------- every block broken during a run gets a ghost outline until the same block is back (memory only, never written)
    private final LinkedHashMap<BlockPos, BlockState> broken = new LinkedHashMap<>();
    private final LinkedHashMap<BlockPos, BlockState> aimed = new LinkedHashMap<>();   // what the crosshair rested on lately: creative breaks a block before it says so

    private void aimTick() {
        if (run == RunStage.NONE || mc.world == null) return;
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockState st = mc.world.getBlockState(hit.getBlockPos());
            if (!st.isAir()) { aimed.put(hit.getBlockPos().toImmutable(), st); while (aimed.size() > 64) aimed.remove(aimed.keySet().iterator().next()); }
            // aiming at a face whose placement spot is a ghost: hold the block that used to be there, if you have it
            BlockPos place = hit.getBlockPos().offset(hit.getSide());
            BlockState want = broken.get(place);
            if (want != null && mc.world.getBlockState(place).isAir()) {
                net.minecraft.item.Item item = want.getBlock().asItem();
                for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(item)) { if (mc.player.getInventory().selectedSlot != i) mc.player.getInventory().selectedSlot = i; break; }
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (run == RunStage.RETURNING) { audit("logged out before leaving the chest - hash posts on next resync"); }
        else if (run != RunStage.NONE) audit("logged out during a run (" + run.name().toLowerCase() + ") - run void");
        run = RunStage.NONE; spawner = null; skipUntil = 0; takeoff = Takeoff.NONE;
        broken.clear(); aimed.clear();
        xray = Xray.OFF; xrayCenter = null; bubbleCenter = null;
        savedView = -1;                                                              // options were restored by the game closing the world; don't double-restore
        if (hudSuppressed) { Hud.get().active = hudWasActive; hudSuppressed = false; }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (run == RunStage.NONE || mc.world == null) return;
        if (event.packet instanceof PlayerActionC2SPacket p && p.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
            BlockPos pos = p.getPos().toImmutable();
            if (broken.containsKey(pos)) return;
            BlockState st = mc.world.getBlockState(pos);
            if (st.isAir()) st = aimed.get(pos);                                   // creative already removed it - use what we saw there
            if (st != null && !st.isAir()) { broken.put(pos, st); feed("ghost kept (" + broken.size() + ")"); }
        }
    }

    private int missingBlocks() {
        int n = 0;
        for (Map.Entry<BlockPos, BlockState> e : broken.entrySet()) if (!mc.world.getBlockState(e.getKey()).isOf(e.getValue().getBlock())) n++;
        return n;
    }

    private static final SettingColor DUNGEON_COLOR = new SettingColor(230, 200, 90, 230), GHOST_COLOR = new SettingColor(90, 200, 255, 200);

    private static final SettingColor BLACK = new SettingColor(0, 0, 0, 255);

    private boolean covered() { return blind.get() && run == RunStage.FLYING && mc.player != null && mc.player.isGliding(); }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || run == RunStage.NONE) return;
        if (covered()) {                                                              // the world pass ends here; chat, hotbar and labels are HUD and land on top
            Vec3d c = mc.gameRenderer.getCamera().getPos();
            event.renderer.box(c.x - 2, c.y - 2, c.z - 2, c.x + 2, c.y + 2, c.z + 2, BLACK, BLACK, ShapeMode.Sides, 0);
            return;
        }
        if (run == RunStage.DESIGNATED && spawner != null && mc.world.getRegistryKey() == World.OVERWORLD) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, spawner.getX() + 0.5, spawner.getY() + 0.5, spawner.getZ() + 0.5, DUNGEON_COLOR);
            event.renderer.box(spawner.getX() - 3, spawner.getY() - 1, spawner.getZ() - 3, spawner.getX() + 4, spawner.getY() + 4, spawner.getZ() + 4, DUNGEON_COLOR, DUNGEON_COLOR, ShapeMode.Lines, 0);
        }
        for (Map.Entry<BlockPos, BlockState> e : broken.entrySet()) {
            BlockPos b = e.getKey();
            if (mc.world.getBlockState(b).isOf(e.getValue().getBlock())) continue;                      // put back
            event.renderer.box(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1, GHOST_COLOR, GHOST_COLOR, ShapeMode.Lines, 0);
        }
    }

    private double stashX, stashZ;                                    // where the book went - lives here until you are far away, then gone
    private int pendingRound, pendingNum; private String pendingHashForPost; private long pendingTs; private boolean pendingBlind; private String pendingServer;
    private String pendingLoc, pendingLocForPost;   // the chest position, AES-256-GCM under a key only the code can derive - never the plain coordinates
    /** Where https://quillcoin.gg (the default site-url) actually posts: the site itself is static. */
    private static final String API_BASE = "https://ovjeipprgkeygnlkraiu.supabase.co/functions/v1";
    private boolean revealed;

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
    private long tick, stageSince;
    private int pendingSlot = -1, pendingNumber;
    private String pendingHash, pendingTitle;
    private boolean warnedMaps, hudWasActive = true, hudSuppressed, elytraWasActive;
    private int savedView = -1;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // Crockford base32: no I, L, O, U

    public QuillHider() {
        super(QuillHiderAddon.CATEGORY, "quill-hider", "Signs a coin book with a random code, hashes it, stashes it in the open chest, and never shows you the code.");
        INSTANCE = this;
    }

    @Override
    public void onDeactivate() {
        if (hudSuppressed) { Hud.get().active = hudWasActive; hudSuppressed = false; }
        if (takeoff != Takeoff.NONE) { mc.options.jumpKey.setPressed(false); takeoff = Takeoff.NONE; }
        setXray(Xray.OFF, null);
        broken.clear(); aimed.clear();
        if (run == RunStage.RETURNING) audit("module switched off before leaving the chest - hash posts on next resync");
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
        if (mc.player == null) return;
        if (event.key == GLFW.GLFW_KEY_F3 && run != RunStage.NONE) { event.cancel(); return; }   // F3 is dead during a run, no flicker
        if (event.action != KeyAction.Press) return;
        if (runKey.get().matches(true, event.key, event.modifiers)) { event.cancel(); startOrResumeRun(); return; }
        if (devSkipKey.get().matches(true, event.key, event.modifiers)) { event.cancel(); devSkip(); return; }
        if (previewKey.get().matches(true, event.key, event.modifiers)) { event.cancel(); previewBook(); return; }
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
        String title = "R" + effectiveRound() + " Coin " + number;
        pendingHash = sha256(code);
        List<String> pages = bookPages(code, title, effectiveRound(), number, pendingHash);
        pendingLoc = sealLocation(code);                              // where we stand, readable by nobody until this code is redeemed
        code = null;                                                  // the code's whole life: made, written, hashed, sealed, gone
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
        if (blind.get() && mc.getDebugHud().shouldShowDebugHud()) mc.getDebugHud().toggleDebugHud();
        if (run != RunStage.NONE && !blind.get()) { audit("blind flight turned off during a run - run void"); run = RunStage.NONE; spawner = null; warn("Blind flight was turned off - the run is void."); }
        // Meteor's HUD draws after everything else and any element (Position, Waypoints...) can be added in two clicks:
        // while a run is live or the screen is covered, the HUD is simply off. It comes back when the run ends.
        if (run != RunStage.FLYING && savedAutoJump != null) restoreAutoJump();
        boolean onGroundPhase = run == RunStage.OVERWORLD || run == RunStage.DESIGNATED || run == RunStage.RETURNING;
        if (onGroundPhase && savedView == -1) shrinkView();
        else if (!onGroundPhase && savedView != -1) restoreView();
        boolean wantHudOff = run != RunStage.NONE;
        Hud hud = Hud.get();
        if (wantHudOff && !hudSuppressed) { hudWasActive = hud.active; hud.active = false; hudSuppressed = true; }
        else if (!wantHudOff && hudSuppressed) { hud.active = hudWasActive; hudSuppressed = false; }
        else if (wantHudOff && hud.active) hud.active = false;                       // someone toggled it back on mid-run
        if (feedbackRestoreTick != 0 && tick >= feedbackRestoreTick) { feedbackRestoreTick = 0; mc.getNetworkHandler().sendChatCommand("gamerule sendCommandFeedback true"); }
        runTick();
        bubbleTick();
        aimTick();

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
    private void onRenderBlockEntity(RenderBlockEntityEvent event) {
        if (xray != Xray.OFF && hideBlock(event.blockEntity.getPos(), null)) event.cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST - 1000)
    private void onTooltipData(TooltipDataEvent event) {
        if (isCoinBook(event.itemStack)) event.tooltipData = null;        // Meteor's Better Tooltips previews book pages here
    }

    @EventHandler
    private void onTooltip(ItemStackTooltipEvent event) {
        if (mc.isInSingleplayer()) return;                                            // singleplayer books are test or sample books: readable, so they can be designed
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
        if (!(event.screen instanceof BookScreen) || mc.isInSingleplayer()) return;
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
        boolean covered = covered();                                               // the black itself is drawn in the world pass (onRender3D)
        event.drawContext.getMatrices().push();
        event.drawContext.getMatrices().scale(sx, sy, 1f);
        if (covered) {
            int rockets = 0;
            for (int i = 0; i < 36; i++) { ItemStack s = mc.player.getInventory().getStack(i); if (s.isOf(Items.FIREWORK_ROCKET)) rockets += s.getCount(); }
            int cx = sw / 2, cy = sh / 2;
            String big = run == RunStage.FLYING ? "FLYING" : run == RunStage.RETURNING ? "STASHED" : run == RunStage.DESIGNATED ? "TO THE DUNGEON" : "LOOKING";
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, big, cx, cy - 10, 0xFFFFFFFF);
            String sub;
            if (run == RunStage.FLYING) {
                double left = Math.hypot(mc.player.getX() - targetNX, mc.player.getZ() - targetNZ);
                double spd = Math.hypot(mc.player.getVelocity().x, mc.player.getVelocity().z) * 20;
                sub = spd > 5 ? "about " + Math.max(1, (int) Math.ceil(left / spd / 60)) + " min" : "…";   // minutes only: no metres, no speed, no direction
            } else if (run == RunStage.RETURNING) sub = "pearl or tp away - the hash posts when you're gone";
            else if (run == RunStage.DESIGNATED) sub = arrow();
            else sub = "baritone is searching for a dungeon";
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, sub, cx, cy + 4, 0xFFFFFFFF);
            event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "rockets " + rockets + "   hp " + (int) mc.player.getHealth(), cx, cy + 16, 0xFFAAAAAA);
            if (run == RunStage.FLYING) event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, ",stop voids the run and gives you your eyes back", cx, cy + 28, 0xFF666666);


        } else {
            int miss = missingBlocks();
            String fix = miss > 0 ? "   |   " + miss + " block" + (miss > 1 ? "s" : "") + " to put back" : "";
            String top = run == RunStage.DESIGNATED ? arrow() + fix
                : run == RunStage.FLYING && !mc.player.isGliding() ? "blind run: taking off"
                : run == RunStage.ARRIVED ? "blind run: build a portal here and go through"
                : run == RunStage.OVERWORLD ? "blind run: walk until a dungeon appears"
                : run == RunStage.RETURNING ? "stashed - put your blocks back, then pearl or tp away; the hash posts when you're " + returnDistance.get() + " blocks from here" + fix : "";
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
            info("A run is live. Land somewhere that isn't the point (,stop) to void it, or let it finish.");
            return;
        }
        if (run != RunStage.NONE) { say("A run is already in progress (%s).", run.name().toLowerCase()); return; }
        // uniform over the ring between min and max overworld radius, then scaled to the nether
        double a = RNG.nextDouble() * Math.PI * 2;
        boolean test = testRadius.get() > 0;
        if (test) {
            double r = testRadius.get() * (0.8 + RNG.nextDouble() * 0.4);
            targetNX = (int) Math.round(mc.player.getX() + r * Math.cos(a));
            targetNZ = (int) Math.round(mc.player.getZ() + r * Math.sin(a));
        } else {
            double lo = minRadius.get(), hi = Math.max(minRadius.get() + 1000, maxRadius.get());
            double r = Math.sqrt(lo * lo + RNG.nextDouble() * (hi * hi - lo * lo));
            targetNX = (int) Math.round(r * Math.cos(a) / 8.0);
            targetNZ = (int) Math.round(r * Math.sin(a) / 8.0);
        }
        spawner = null;
        elytraWasActive = false;
        takeoff = Takeoff.NONE;
        takeoffTries = 0;
        run = RunStage.FLYING;
        audit(test ? "run started (TEST: point near the player)" : "run started");
        try {
            if (savedAutoJump == null) savedAutoJump = BaritoneAPI.getSettings().elytraAutoJump.value;
            BaritoneAPI.getSettings().elytraAutoJump.value = false;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(new GoalXZ(targetNX, targetNZ));   // goal only; pathTo once airborne
        } catch (Throwable t) { fail("Couldn't hand the goal to Baritone: %s", t.getMessage()); run = RunStage.NONE; return; }
        info("Blind run started - Baritone has a point you will never be shown. Take off; the screen goes dark while you glide.");
        feed("blind run started");
    }

    private Boolean savedAutoJump;

    private void fly() {
        try {
            if (savedAutoJump == null) savedAutoJump = BaritoneAPI.getSettings().elytraAutoJump.value;
            BaritoneAPI.getSettings().elytraAutoJump.value = false;                // we do the takeoff (Flight+ style); Baritone steers once airborne
            GoalXZ g = new GoalXZ(targetNX, targetNZ);
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(g);
            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(g);
        } catch (Throwable t) { fail("Couldn't hand the goal to Baritone: %s", t.getMessage()); run = RunStage.NONE; }
    }

    // ------------------------------------------------------------------ takeoff, Flight+ style: nose up, one tap to hop,
    // a fresh press on the way down to deploy, a rocket once gliding, then the goal is handed to Baritone in the air

    private enum Takeoff { NONE, JUMPING, BOOST, DONE }
    private Takeoff takeoff = Takeoff.NONE;
    private long takeoffSince, boostTick;
    private int takeoffTries;
    private boolean jumpTapped, wasAirborne, boostFired, secondRocket;

    private void takeoffTick() {
        if (takeoff == Takeoff.NONE) {
            if (++takeoffTries > 4) { run = RunStage.NONE; audit("takeoff failed four times - run void"); warn("Couldn't take off - the run is void. Elytra on, rockets in the hotbar, some room around you."); return; }
            takeoff = Takeoff.JUMPING; takeoffSince = tick; jumpTapped = wasAirborne = boostFired = secondRocket = false;
            feed("taking off (" + takeoffTries + ")");
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { slot = i; break; }
        if (slot == -1 || !mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            if (tick % 100 == 0) warn("Need an elytra on and rockets in the hotbar.");
            return;
        }
        if (mc.player.getInventory().selectedSlot != slot) mc.player.getInventory().selectedSlot = slot;
        mc.player.setPitch(-30f);
        switch (takeoff) {
            case JUMPING -> {
                if (tick - takeoffSince > 200) { mc.options.jumpKey.setPressed(false); takeoff = Takeoff.NONE; return; }   // try again
                if (mc.player.isGliding()) { mc.options.jumpKey.setPressed(false); takeoff = Takeoff.BOOST; boostFired = false; secondRocket = false; audit("takeoff: gliding"); return; }
                if (mc.player.getAbilities().flying) {                                   // creative hover: drop out of it, then it's an ordinary fall
                    mc.player.getAbilities().flying = false;
                    mc.player.sendAbilitiesUpdate();
                    mc.options.jumpKey.setPressed(false);
                    wasAirborne = true;
                    return;
                }
                if (mc.player.isOnGround()) {
                    if (wasAirborne) { jumpTapped = false; wasAirborne = false; }
                    if (!jumpTapped) { mc.options.jumpKey.setPressed(true); jumpTapped = true; audit("takeoff: hop"); }
                    else mc.options.jumpKey.setPressed(false);
                } else {
                    wasAirborne = true;
                    mc.options.jumpKey.setPressed(false);                                 // never hold jump in the air (creative: that's "ascend")
                    if (mc.player.getVelocity().y < 0 && tick % 2 == 0)                   // falling: ask the server to deploy, the way a jump press would
                        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
            case BOOST -> {
                if (!mc.player.isGliding()) { if (mc.player.isOnGround()) takeoff = Takeoff.NONE; return; }
                if (!boostFired) { mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); mc.player.swingHand(Hand.MAIN_HAND); boostFired = true; boostTick = tick; audit("takeoff: rocket"); return; }
                if (!secondRocket && tick - boostTick == 8 && mc.player.getVelocity().horizontalLength() < 0.8) {
                    mc.player.setPitch(-25f); mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); mc.player.swingHand(Hand.MAIN_HAND); secondRocket = true; return;
                }
                if (tick - boostTick > 12 && !mc.player.isOnGround() && mc.player.getVelocity().horizontalLength() > 0.5) { takeoff = Takeoff.DONE; boostTick = tick; fly(); audit("takeoff: airborne, goal handed to baritone"); }
            }
            case DONE -> {
                if (mc.player.isOnGround()) { takeoff = Takeoff.NONE; return; }                       // glide died before Baritone took it: go around
                if (tick - boostTick > 40 && (tick - boostTick) % 40 == 0) fly();                     // Baritone hasn't engaged yet: hand it the goal again
            }
            default -> {}
        }
    }

    private long feedbackRestoreTick, skipUntil;

    /** Test worlds only: jump to the point without the flight. Feedback is muted so the tp message can't print the coordinates. */
    private void devSkip() {
        if (run != RunStage.FLYING) { fail("Dev skip only works during the flight leg of a run."); return; }
        if (!mc.isInSingleplayer()) { fail("Dev skip only works in a singleplayer world."); return; }
        mc.options.jumpKey.setPressed(false);
        takeoff = Takeoff.NONE;
        try { BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything(); } catch (Throwable ignored) { }
        elytraWasActive = false;                                                 // the flight is over by teleport, not by stopping
        skipUntil = 1;
        mc.getNetworkHandler().sendChatCommand("gamerule sendCommandFeedback false");
        mc.getNetworkHandler().sendChatCommand("tp @s " + targetNX + " ~ " + targetNZ);
        feedbackRestoreTick = tick + 40;
        audit("DEV SKIP used - teleported to the point (test run)");
        feed("dev skip");
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
                setXray(Xray.BUBBLE, null);                                        // on the ground you see a 7x7x7 bubble; in the air the cover is black anyway
                double d = Math.hypot(mc.player.getX() - targetNX, mc.player.getZ() - targetNZ);
                boolean active = elytraActive();
                if (d <= arriveRadius.get() && !mc.player.isGliding()) {
                    skipUntil = 0;
                    run = RunStage.ARRIVED;
                    elytraWasActive = false;
                    audit("arrived at the point");
                    say("Arrived. Make a portal here and go through it - the run continues in the overworld.");
                } else if (skipUntil != 0) {
                    return;                                                          // dev skip: wait for the teleport and the touchdown, nothing else
                } else if (elytraWasActive && !active) {
                    // ,stop, an emergency landing, out of rockets: the flight ended somewhere that isn't the point - void now, see now, land yourself
                    run = RunStage.NONE;
                    elytraWasActive = false;
                    audit("flight ended before the point - run void");
                    warn("The flight ended before the point - the run is void. Press %s for a new one.", runKey.get());
                } else if (!elytraWasActive && !(active && mc.player.isGliding())) {
                    takeoffTick();                                                       // we launch; Baritone steers once it's airborne
                }
                if (active && mc.player.isGliding()) { elytraWasActive = true; if (takeoff != Takeoff.NONE) { mc.options.jumpKey.setPressed(false); takeoff = Takeoff.NONE; } }
            }
            case ARRIVED -> setXray(Xray.BUBBLE, null);
            case OVERWORLD -> {
                if (dim != World.OVERWORLD) { setXray(Xray.BUBBLE, null); return; }
                if (Math.hypot(mc.player.getX() - targetNX * 8.0, mc.player.getZ() - targetNZ * 8.0) > exitTolerance.get() + 2000) {
                    run = RunStage.NONE; setXray(Xray.OFF, null);
                    audit("far from the point in the overworld (respawned elsewhere?) - run void");
                    warn("You're far from the run's point - the run is void.");
                    return;
                }
                setXray(Xray.NOTHING, null);                                       // nothing but sky, fluids and mobs until a dungeon is in range
                if (tick - lastScan < 20) return;
                lastScan = tick;
                BlockPos found = nearestDungeonSpawner();
                if (found != null) {
                    spawner = found;
                    revealed = false;
                    run = RunStage.DESIGNATED;
                    setXray(Xray.DUNGEON, spawner);
                    audit("dungeon designated");
                    say("Dungeon found - it's the only thing drawn. Walk there, dig down, put your blocks back, stash.");
                } else if (tick % 200 == 0) feed("no dungeon in range - keep walking");
            }
            case DESIGNATED -> {
                if (dim != World.OVERWORLD || spawner == null) { setXray(Xray.BUBBLE, null); return; }
                if (Math.hypot(mc.player.getX() - targetNX * 8.0, mc.player.getZ() - targetNZ * 8.0) > exitTolerance.get() + 2000) {
                    run = RunStage.NONE; spawner = null; setXray(Xray.OFF, null);
                    audit("far from the point in the overworld (respawned elsewhere?) - run void");
                    warn("You're far from the run's point - the run is void.");
                    return;
                }
                setXray(Xray.DUNGEON, spawner);
                boolean inside = mc.player.getBlockPos().isWithinDistance(spawner, dungeonRadius.get());
                if (inside && !revealed) { revealed = true; audit("in the dungeon"); say("You're in the dungeon. Open the chest and press %s.", stashKey.get()); }
            }
            case RETURNING -> {
                setXray(dim == World.OVERWORLD ? Xray.NOTHING : Xray.BUBBLE, null); // the world stays hidden until you're gone
                double d = Math.hypot(mc.player.getX() - stashX, mc.player.getZ() - stashZ);
                if (d > returnDistance.get()) finishReturn("far from the chest");
            }
            default -> { if (xray != Xray.OFF) setXray(Xray.OFF, null); }
        }
    }

    private void onDimensionChange(RegistryKey<World> from, RegistryKey<World> to) {
        if (run == RunStage.RETURNING) { finishReturn("dimension change"); return; }
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

    /** The run's last leg: dark and locked until you are away from the chest. Only then does the hash reach the site. */
    private void finishReturn(String how) {
        audit("away from the stash (" + how + ") - posting the hash");
        run = RunStage.NONE;
        setXray(Xray.OFF, null);
        broken.clear();
        spawner = null;
        stashX = stashZ = 0;
        final int r = pendingRound, n = pendingNum; final String hsh = pendingHashForPost; final long ts = pendingTs; final boolean bl = pendingBlind; final String srv = pendingServer; final String loc = pendingLocForPost;
        pendingHashForPost = null;
        say("Away from the chest. Posting the hash now.");
        if (hsh != null) new Thread(() -> { if (post(r, n, hsh, ts, bl, srv, loc)) { markSynced(hsh); say("Hash posted - the coin is live on the site."); } else say("Site unreachable - the hash is saved locally and will be posted next time the module turns on."); }, "quillcoin-post").start();
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
        if (mc.world.getRegistryKey() != World.OVERWORLD) return "run paused - go back through the portal";   // never measure across dimensions
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

    /** The whole book comes from meteor-client/quillcoin-book.txt: pages split by a line "---", placeholders {code} {title} {round} {number}
     *  {hash8} {date} {year} {quote} {art}. {quote} = a random line of quillcoin-quotes.txt, {art} = a random page of quillcoin-art.txt.
     *  A template without {code} gets the code as page 1. No template at all = code page + a how-to-redeem page. */
    private List<String> bookPages(String code, String title, int round, int number, String hash) {
        List<String> pages = new ArrayList<>();
        try {
            File tpl = new File(MeteorClient.FOLDER, "quillcoin-book.txt");
            if (tpl.exists()) {
                String quote = randomEntry(new File(MeteorClient.FOLDER, "quillcoin-quotes.txt"), "\n");
                String art = randomEntry(new File(MeteorClient.FOLDER, "quillcoin-art.txt"), "(?m)^---\\s*$");
                java.time.LocalDate today = java.time.LocalDate.now();
                String date = today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH));
                boolean hasCode = false;
                for (String page : Files.readString(tpl.toPath(), StandardCharsets.UTF_8).split("(?m)^---\\s*$")) {
                    if (page.contains("{code}")) hasCode = true;
                    String t = page.replace("{code}", code).replace("{round}", String.valueOf(round)).replace("{number}", String.valueOf(number)).replace("{title}", title)
                        .replace("{hash8}", hash.substring(0, 8)).replace("{date}", date).replace("{year}", String.valueOf(today.getYear()))
                        .replace("{quote}", quote).replace("{art}", art).strip();
                    if (!t.isEmpty() && pages.size() < 100) pages.add(t);
                }
                if (!hasCode) pages.add(0, code);
                if (!pages.isEmpty()) return pages;
            }
        } catch (Exception e) { warn("Couldn't read the book template: %s", e.getMessage()); }
        pages.clear();
        pages.add(code);
        pages.add("This book is one QuillCoin.\n\nRedeem the code on the first page at\nquillcoin.gg\n\nFirst redemption wins.");
        return pages;
    }

    /** A random non-empty, non-# entry of a text file split by the given regex; "" if the file is missing. */
    private String randomEntry(File f, String splitRegex) {
        try {
            if (!f.exists()) return "";
            List<String> entries = new ArrayList<>();
            for (String e : Files.readString(f.toPath(), StandardCharsets.UTF_8).split(splitRegex)) {
                StringBuilder keep = new StringBuilder();
                for (String line : e.split("\n")) if (!line.startsWith("#")) keep.append(line).append('\n');
                String t = keep.toString().strip();
                if (!t.isEmpty()) entries.add(t);
            }
            return entries.isEmpty() ? "" : entries.get(RNG.nextInt(entries.size()));
        } catch (Exception e) { return ""; }
    }

    /** Design helper: signs a sample book with a fake code. Singleplayer only, records nothing, posts nothing. */
    private void previewBook() {
        if (mc.player == null || mc.world == null) return;
        if (!mc.isInSingleplayer()) { fail("The sample book only works in a singleplayer world."); return; }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(Items.WRITABLE_BOOK)) { slot = i; break; }
        if (slot == -1) { fail("Put a book-and-quill in your hotbar first."); return; }
        String fake = "QLL-DEMO0-DEMO0-DEMO0-DEMO0";
        mc.getNetworkHandler().sendPacket(new BookUpdateC2SPacket(slot, bookPages(fake, "R0 Coin 0", 0, 0, sha256(fake)), Optional.of("R0 Coin 0")));
        say("Sample book signed in slot %d. Open it. Edit meteor-client/quillcoin-book.txt, quillcoin-quotes.txt or quillcoin-art.txt, then press the key again.", slot + 1);
    }

    /** {"x","y","z"} of the player (at the chest), AES-256-GCM with key = SHA-256("quillcoin-loc:" + code), iv||ciphertext||tag, base64. The site stores it blind. */
    private String sealLocation(String code) {
        try {
            BlockPos p = mc.player.getBlockPos();
            byte[] key = MessageDigest.getInstance("SHA-256").digest(("quillcoin-loc:" + code).getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[12]; RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(("{\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ() + "}").getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { return ""; }
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
                if (p.length >= 2 && Integer.parseInt(p[0].trim()) == effectiveRound()) used.add(Integer.parseInt(p[1].trim()));
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

    /** round,number,hash,unix-seconds,synced,blind,server,sealed-position - and nothing else, ever. */
    private void record() {
        long now = System.currentTimeMillis() / 1000;
        boolean blindRun = run == RunStage.DESIGNATED && spawner != null;
        audit("stashed " + pendingTitle + (blindRun ? " at the end of a blind run" : " WITHOUT a blind run"));
        String line = effectiveRound() + "," + pendingNumber + "," + pendingHash + "," + now + ",0," + (blindRun ? 1 : 0) + "," + serverName() + "," + (pendingLoc == null ? "" : pendingLoc);
        stashX = mc.player.getX(); stashZ = mc.player.getZ();
        spawner = null;
        revealed = false;
        run = RunStage.RETURNING;                                                 // dark and locked until you're gone
        try {
            Files.writeString(hidesFile().toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { fail("Couldn't write quillcoin-hides.txt: %s", e.getMessage()); }
        say("%s hidden%s. Now pearl or tp away - the screen stays dark and the hash posts once you're %d blocks from here.", pendingTitle, blindRun ? " at the end of a blind run" : "", returnDistance.get());
        pendingRound = effectiveRound(); pendingNum = pendingNumber; pendingHashForPost = pendingHash; pendingTs = now; pendingBlind = blindRun; pendingServer = serverName(); pendingLocForPost = pendingLoc; pendingLoc = null;
        pendingHash = null;
    }

    /** Singleplayer hides are always round 0 (the test round), whatever the setting says: only a 2b2t hide can carry a real round number. */
    private int effectiveRound() { return mc.isInSingleplayer() ? 0 : round.get(); }

    private String serverName() {
        if (mc.isInSingleplayer()) return "singleplayer";
        ServerInfo si = mc.getCurrentServerEntry();
        return si == null || si.address == null ? "unknown" : si.address.trim().toLowerCase();
    }

    /** The api-key setting, or meteor-client/quillcoin-key.txt when the setting is empty. */
    private String hiderKey() {
        String k = apiKey.get().trim();
        if (!k.isEmpty()) return k;
        try {
            File f = new File(MeteorClient.FOLDER, "quillcoin-key.txt");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private boolean post(int r, int n, String hash, long ts, boolean blindRun, String server, String loc) {
        String url = siteUrl.get().trim(), key = hiderKey();
        if (url.isEmpty() || key.isEmpty()) return false;
        if (url.replaceAll("/+$", "").endsWith("quillcoin.gg")) url = API_BASE;
        try {
            String body = String.format("{\"round\":%d,\"number\":%d,\"hash\":\"%s\",\"ts\":%d,\"blind\":%s,\"server\":\"%s\"%s}", r, n, hash, ts, blindRun, server.replaceAll("[^a-z0-9.:-]", ""),
                loc == null || loc.isEmpty() ? "" : ",\"loc\":\"" + loc.replaceAll("[^A-Za-z0-9+/=]", "") + "\"");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/api/hide"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
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
            if (!hidesFile().exists() || hiderKey().isEmpty()) return;
            int sent = 0;
            for (String line : Files.readAllLines(hidesFile().toPath())) {
                String[] p = line.split(",");
                if (p.length < 5 || !p[4].trim().equals("0")) continue;
                boolean bl = p.length > 5 && p[5].trim().equals("1");
                String srv = p.length > 6 && !p[6].trim().isEmpty() ? p[6].trim() : serverName();
                String loc = p.length > 7 ? p[7].trim() : "";
                if (post(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), p[2].trim(), Long.parseLong(p[3].trim()), bl, srv, loc)) { markSynced(p[2].trim()); sent++; }
            }
            if (sent > 0) say("Posted %d hash%s that were waiting.", sent, sent == 1 ? "" : "es");
        } catch (Exception ignored) { }
    }
}
