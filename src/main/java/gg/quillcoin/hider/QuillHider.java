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
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
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
        .name("round").description("Round number written into the book title (R1 Coin 37).").defaultValue(1).min(1).sliderMax(50).build());
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
    private final Setting<Integer> damageReveal = sgBlind.add(new IntSetting.Builder()
        .name("damage-reveal").description("Ticks the screen stays visible after you take damage, so you can deal with it.").defaultValue(200).min(0).sliderMax(1200).build());

    private enum Stage { IDLE, SIGNING, STASHING, RETRY }
    private Stage stage = Stage.IDLE;
    private long tick, stageSince, lastDamageTick = -100000;
    private float lastHealth;
    private int pendingSlot = -1, pendingNumber;
    private String pendingHash, pendingTitle;
    private boolean warnedMaps;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // Crockford base32: no I, L, O, U

    public QuillHider() {
        super(QuillHiderAddon.CATEGORY, "quill-hider", "Signs a coin book with a random code, hashes it, stashes it in the open chest, and never shows you the code.");
    }

    @Override
    public void onActivate() {
        stage = Stage.IDLE;
        warnedMaps = false;
        lastHealth = mc.player == null ? 20 : mc.player.getHealth();
        new Thread(this::resync, "quillcoin-resync").start();
    }

    private static File hidesFile() { return new File(MeteorClient.FOLDER, "quillcoin-hides.txt"); }

    // ------------------------------------------------------------------ the key

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press || mc.player == null) return;
        if (!stashKey.get().matches(true, event.key, event.modifiers)) return;
        event.cancel();
        if (stage == Stage.RETRY) { retryStash(); return; }
        if (stage != Stage.IDLE) { info("Busy (%s).", stage.name().toLowerCase()); return; }
        stash();
    }

    private void stash() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !(hs.getScreenHandler() instanceof GenericContainerScreenHandler handler)) {
            error("Open the dungeon chest first, then press the key."); return;
        }
        String me = mc.getSession() == null ? "" : mc.getSession().getUsername();
        if (!me.equals(author.get())) { error("You are %s, not %s - not hiding.", me, author.get()); return; }
        for (String logger : new String[]{"chest-dump", "stash-audit", "net-worth", "layer-kit"}) {
            Module m = Modules.get().get(logger);
            if (m != null && m.isActive()) { error("%s is on - it logs chest contents. Turn it off first.", m.title); return; }
        }
        if (!warnedMaps && (FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaeroworldmap"))) {
            warnedMaps = true;
            warning("This instance has Xaero maps installed. Blind flight can't cover them - the hider instance should have no map mods.");
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isOf(Items.WRITABLE_BOOK)) { slot = i; break; }
        if (slot == -1) { error("No book-and-quill in your hotbar."); return; }
        Inventory chest = handler.getInventory();
        boolean room = false;
        for (int i = 0; i < chest.size(); i++) if (chest.getStack(i).isEmpty()) { room = true; break; }
        if (!room) { error("That chest is full."); return; }

        int number = drawNumber();
        if (number == -1) { error("Every number in this round is used - raise numbers-per-round."); return; }
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
        stage = Stage.SIGNING;
        stageSince = tick;
    }

    private void retryStash() {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !(hs.getScreenHandler() instanceof GenericContainerScreenHandler handler)) {
            error("Open a chest, then press the key to stash the signed book."); return;
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) if (isCoinBook(mc.player.getInventory().getStack(i))) { slot = i; break; }
        if (slot == -1) { stage = Stage.IDLE; error("No signed coin book in the hotbar any more."); return; }
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

        switch (stage) {
            case SIGNING -> {
                ItemStack st = mc.player.getInventory().getStack(pendingSlot);
                if (isCoinBook(st)) {
                    if (mc.currentScreen instanceof HandledScreen<?> hs && hs.getScreenHandler() instanceof GenericContainerScreenHandler handler) {
                        quickMove(handler, pendingSlot);
                        stage = Stage.STASHING;
                        stageSince = tick;
                    } else {
                        stage = Stage.RETRY;
                        warning("Book signed but the chest closed. Open a chest and press the key - and do not open the book.");
                    }
                } else if (tick - stageSince > 60) {
                    stage = Stage.IDLE;
                    error("The server didn't sign the book. Nothing was hidden; the number is free again.");
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
                    warning("The signed book is still in your hotbar (chest full?). Open a chest with room and press the key. Do not open the book.");
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
        if (!(event.screen instanceof BookScreen) || mc.player == null) return;
        if (isCoinBook(mc.player.getMainHandStack()) || isCoinBook(mc.player.getOffHandStack())) {
            event.cancel();
            warning("That's a coin book. Not showing it.");
        }
    }

    // ------------------------------------------------------------------ blind flight

    @EventHandler(priority = EventPriority.LOWEST)
    private void onRender2D(Render2DEvent event) {
        if (!blind.get() || mc.player == null || !mc.player.isGliding()) return;
        if (tick - lastDamageTick < damageReveal.get() || peekKey.get().isPressed()) return;
        event.drawContext.fill(0, 0, event.screenWidth, event.screenHeight, 0xFF000000);
        int rockets = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = mc.player.getInventory().getStack(i); if (s.isOf(Items.FIREWORK_ROCKET)) rockets += s.getCount(); }
        int cx = event.screenWidth / 2, cy = event.screenHeight / 2;
        event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "FLYING", cx, cy - 10, 0xFFFFFFFF);
        event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "rockets " + rockets + "   hp " + (int) mc.player.getHealth(), cx, cy + 4, 0xFFAAAAAA);
        event.drawContext.drawCenteredTextWithShadow(mc.textRenderer, "hold " + peekKey.get() + " to peek", cx, cy + 16, 0xFF666666);
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
        String line = round.get() + "," + pendingNumber + "," + pendingHash + "," + now + ",0";
        try {
            Files.writeString(hidesFile().toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { error("Couldn't write quillcoin-hides.txt: %s", e.getMessage()); }
        info("%s hidden. Hash %s… recorded.", pendingTitle, pendingHash.substring(0, 10));
        final int r = round.get(), n = pendingNumber; final String hsh = pendingHash;
        pendingHash = null;
        new Thread(() -> { if (post(r, n, hsh, now)) { markSynced(hsh); info("Hash posted to the site."); } else info("Site unreachable - the hash is saved locally and will be posted next time the module turns on."); }, "quillcoin-post").start();
    }

    private boolean post(int r, int n, String hash, long ts) {
        String url = siteUrl.get().trim();
        if (url.isEmpty() || apiKey.get().isEmpty()) return false;
        try {
            String body = String.format("{\"round\":%d,\"number\":%d,\"hash\":\"%s\",\"ts\":%d}", r, n, hash, ts);
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
            if (sent > 0) info("Posted %d hash%s that were waiting.", sent, sent == 1 ? "" : "es");
        } catch (Exception ignored) { }
    }
}
