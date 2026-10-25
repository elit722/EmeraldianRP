package fr.watherp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Roue de sélection : s'ouvre tant que la touche (ou le duo de touches) est maintenue. La souris part du centre (rien n'est
 * sélectionné) ; en la déplaçant on met une tranche en évidence ; relâcher la touche ou cliquer valide la tranche
 * en évidence. La tranche du haut est « Annuler ». Relâcher sans avoir rien survolé ne fait rien.
 */
public class RadialMenuScreen extends Screen {
    /**
     * Une entrée de la roue. {@code special} : la tranche prend sa propre couleur (comme « Annuler » est rouge) ;
     * sinon {@code color} ne sert qu'aux accents.
     */
    public record Entry(String label, String hint, int color, IntSupplier badge, Consumer<MinecraftClient> action, boolean special) {
        public Entry(String label, String hint, int color, IntSupplier badge, Consumer<MinecraftClient> action) {
            this(label, hint, color, badge, action, false);
        }
    }

    /** Couleurs des tranches spéciales (menus de Wathe). */
    private static final int WATHE_CONFIG_COLOR = 0xFFB57BEE, WATHE_SKINS_COLOR = 0xFFF0903C;
    private static final String WATHE_ADMIN_SCREEN = "dev.doctor4t.wathe.client.gui.screen.ingame.AdminMenuScreen";
    private static final String WATHE_SKINS_SCREEN = "dev.doctor4t.wathe.client.gui.screen.ingame.SkinSelectionScreen";

    /** Wathe est présent ET sa version contient cet écran (la classe existe) : sinon la tranche n'apparaît pas. */
    private static boolean watheHas(String className) {
        if (!FabricLoader.getInstance().isModLoaded("wathe")) return false;
        try {
            Class.forName(className, false, RadialMenuScreen.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ouvre un écran de Wathe (constructeur sans argument), par réflexion : aucune dépendance dure à une version précise. */
    private static void openWathe(MinecraftClient mc, String className) {
        try {
            mc.setScreen((Screen) Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            fr.watherp.WatheRP.LOGGER.warn("Roue des menus : impossible d'ouvrir {} ({})", className, t.toString());
        }
    }

    /** Couleur de fond d'une tranche spéciale : sa couleur assombrie (au repos) ou franche (en évidence). */
    private static int tint(int color, boolean hover) {
        if (hover) return 0xE0000000 | (color & 0xFFFFFF);
        int r = (int) (((color >> 16) & 255) * 0.30 + 0x14 * 0.70), g = (int) (((color >> 8) & 255) * 0.30 + 0x14 * 0.70),
            b = (int) ((color & 255) * 0.30 + 0x18 * 0.70);
        return 0xC0000000 | (r << 16) | (g << 8) | b;
    }

    private static final double TAU = Math.PI * 2;

    private final List<Entry> entries;
    private long openTime;
    private float cx, cy, r0, r1;
    private int hovered = -1; // -1 : centre (rien), 0 : annuler, 1.. : entrées

    public RadialMenuScreen() {
        super(Text.literal("Roue des menus"));
        this.entries = buildEntries();
    }

    /** Tous les menus du mod. Les menus staff n'apparaissent que pour ceux qui y ont accès (le serveur revérifie chaque action). */
    private static List<Entry> buildEntries() {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("Messagerie", "Chats privés", Ui.AQUA, ClientRp::totalUnread,
            mc -> mc.setScreen(new ChatsScreen(-1))));
        l.add(new Entry("Notifications", "Centre des notifications", Ui.ACCENT, () -> ClientRp.unreadNotifs,
            mc -> mc.setScreen(new NotifCenterScreen())));
        l.add(new Entry("Carnet", "Carnet de contacts", Ui.GREEN, () -> 0,
            WatheRPClient::openContacts));
        l.add(new Entry("Révéler nom", "Révéler son vrai nom", Ui.GREEN, () -> ClientRp.aliased.size(),
            mc -> mc.setScreen(new RevealScreen())));
        l.add(new Entry("Surnoms", "Donner des surnoms", Ui.AQUA, () -> 0,
            mc -> mc.setScreen(new NicknamesScreen())));
        l.add(new Entry("Mon nom RP", "Afficher / modifier mon nom RP", Ui.ACCENT, () -> 0, mc -> {
            mc.setScreen(null);
            if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand("rpnom");
        }));
        if (WatheRPClient.canIntroduceAll(MinecraftClient.getInstance())) {
            l.add(new Entry("Se présenter", "Se présenter à tous les proches", Ui.GREEN, () -> 0,
                mc -> mc.setScreen(new IntroduceScreen(null))));
        }
        l.add(new Entry("Commandes", "Taper une commande", Ui.MUTED, () -> 0,
            mc -> mc.setScreen(new CommandScreen())));
        l.add(new Entry("Aide", "Demander de l'aide au staff", Ui.YELLOW, () -> 0, mc -> {
            mc.setScreen(null);
            WatheRPClient.requestHelp();
        }));
        l.add(new Entry("Débrief", "Débrief de la dernière partie", Ui.YELLOW, () -> 0, mc -> {
            mc.setScreen(null);
            WatheRPClient.requestDebrief();
        }));
        // menus de Wathe (seulement si la version de Wathe installée les contient)
        if (watheHas(WATHE_SKINS_SCREEN)) {
            l.add(new Entry("Skins d'armes", "Menu des skins d'armes (Wathe)", WATHE_SKINS_COLOR, () -> 0,
                mc -> openWathe(mc, WATHE_SKINS_SCREEN), true));
        }
        var me = MinecraftClient.getInstance().player;
        // même règle que la touche d'admin de Wathe : niveau d'opérateur 2
        if (me != null && me.hasPermissionLevel(2) && watheHas(WATHE_ADMIN_SCREEN)) {
            l.add(new Entry("Config partie", "Game config : menu admin de Wathe", WATHE_CONFIG_COLOR, () -> 0,
                mc -> openWathe(mc, WATHE_ADMIN_SCREEN), true));
        }
        if (ClientRp.helper) {
            l.add(new Entry("Panneau staff", "Tickets, dossiers, chat staff", Ui.RED, () -> 0, mc -> {
                mc.setScreen(null);
                ClientRp.wantPanel = true;
                ClientPlayNetworking.send(new Net.RequestTickets());
            }));
        }
        return l;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        cx = width / 2f;
        cy = height / 2f;
        r1 = Math.max(48f, Math.min(118f, height / 2f - 24f));
        r0 = r1 * 0.42f;
    }

    private int slices() {
        return entries.size() + 1;
    }

    /** Tranche sous le curseur : -1 au centre (zone morte), 0 = Annuler (haut), 1.. = entrées dans le sens horaire. */
    private int sliceAt(double mx, double my) {
        double dx = mx - cx, dy = my - cy;
        if (Math.sqrt(dx * dx + dy * dy) < r0) return -1;
        double a = Math.atan2(dx, -dy); // 0 = haut, sens horaire
        if (a < 0) a += TAU;
        double w = TAU / slices();
        return (int) Math.floor((a + w / 2) / w) % slices();
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 140f);
    }

    // ---------------------------------------------------------------- dessin

    private static void sector(DrawContext c, float cx, float cy, float in, float out, double a0, double a1, int argb) {
        c.draw(); // vide ce qui est déjà en attente pour garder l'ordre des couches
        int al = (argb >>> 24) & 255, r = (argb >>> 16) & 255, g = (argb >>> 8) & 255, b = argb & 255;
        int steps = Math.max(2, (int) Math.ceil((a1 - a0) / (Math.PI / 40)));
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            double a = a0 + (a1 - a0) * i / steps;
            float s = (float) Math.sin(a), co = (float) Math.cos(a);
            buf.vertex(m, cx + out * s, cy - out * co, 0f).color(r, g, b, al);
            buf.vertex(m, cx + in * s, cy - in * co, 0f).color(r, g, b, al);
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.enableCull();
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0x90000000, anim()));
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        float an = anim();
        hovered = sliceAt(mx, my);
        int n = slices();
        double w = TAU / n, gap = Math.toRadians(1.4);

        for (int i = 0; i < n; i++) {
            double mid = i * w;
            boolean hov = i == hovered;
            int base;
            if (i == 0) base = hov ? 0xE0E05555 : 0xC03A1A1C;
            else if (entries.get(i - 1).special()) base = tint(entries.get(i - 1).color(), hov);
            else base = hov ? 0xE0C5AE8B : 0xC0141418;
            float out = hov ? r1 + 5 : r1;
            sector(c, cx, cy, r0, out, mid - w / 2 + gap, mid + w / 2 - gap, Ui.alpha(base, an));
        }
        // disque central
        sector(c, cx, cy, 0, r0 - 5, 0, TAU, Ui.alpha(0xD00C0C0E, an));

        // libellés autour de la roue
        for (int i = 0; i < n; i++) {
            double mid = i * w;
            boolean hov = i == hovered;
            String label = i == 0 ? "Annuler" : entries.get(i - 1).label();
            int color = hov ? (i == 0 ? Ui.RED : Ui.ACCENT) : Ui.TEXT;
            if (i > 0 && entries.get(i - 1).special()) color = entries.get(i - 1).color();
            double s = Math.sin(mid), co = Math.cos(mid);
            float rr = r1 + (hov ? 12 : 8);
            int lx = Math.round(cx + (float) (rr * s)), ly = Math.round(cy - (float) (rr * co));
            int tw = tr.getWidth(label);
            int tx = s > 0.25 ? lx : (s < -0.25 ? lx - tw : lx - tw / 2);
            int ty = Math.round(ly - 4 - (float) (6 * co));
            c.drawText(tr, label, tx, ty, Ui.alpha(color | 0xFF000000, an), hov);
            if (i > 0) {
                int badge = entries.get(i - 1).badge().getAsInt();
                if (badge > 0) {
                    String bs = String.valueOf(badge);
                    int bw = Math.max(11, tr.getWidth(bs) + 5);
                    int bx = Math.round(cx + (float) ((r0 + r1) / 2 * s)) - bw / 2, by = Math.round(cy - (float) ((r0 + r1) / 2 * co)) - 6;
                    c.fill(bx, by, bx + bw, by + 12, Ui.ACCENT);
                    c.drawText(tr, bs, bx + (bw - tr.getWidth(bs)) / 2, by + 2, 0xFF000000, false);
                }
            }
        }

        // centre : ce qui est en évidence
        String title, sub;
        int tcol = Ui.TEXT;
        if (hovered == 0) {
            title = "Annuler";
            sub = "Ne rien ouvrir";
            tcol = Ui.RED;
        } else if (hovered > 0) {
            Entry e = entries.get(hovered - 1);
            title = e.label();
            sub = e.hint();
            tcol = e.special() ? e.color() : Ui.ACCENT;
        } else {
            title = "Menus";
            sub = "Bougez la souris";
        }
        int maxW = Math.round(r0 * 2 - 14);
        c.drawText(tr, tr.trimToWidth(title, maxW), Math.round(cx - Math.min(maxW, tr.getWidth(title)) / 2f), Math.round(cy - 9), tcol, false);
        String s2 = tr.trimToWidth(sub, maxW);
        c.drawText(tr, s2, Math.round(cx - tr.getWidth(s2) / 2f), Math.round(cy + 3), Ui.MUTED, false);
    }

    // ---------------------------------------------------------------- validation

    @Override
    public void tick() {
        super.tick();
        // touche(s) relâchée(s) : on valide la tranche en évidence (rien si le curseur est resté au centre)
        if (!WatheRPClient.wheelHeld()) {
            double mx = client.mouse.getX() * width / (double) client.getWindow().getWidth();
            double my = client.mouse.getY() * height / (double) client.getWindow().getHeight();
            choose(sliceAt(mx, my));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int s = sliceAt(mx, my);
            if (s >= 0) choose(s);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void choose(int slice) {
        MinecraftClient mc = client;
        if (mc == null) return;
        if (slice <= 0) { // centre ou « Annuler » : on referme sans rien ouvrir
            mc.setScreen(null);
            return;
        }
        Entry e = entries.get(slice - 1);
        mc.setScreen(null);
        WatheRPClient.runAfterWheelRelease(() -> e.action().accept(mc));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
