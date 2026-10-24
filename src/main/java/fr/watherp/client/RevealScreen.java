package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menu « Révéler mon nom » (centre des notifications) : les personnes à qui on s'est présenté SOUS PSEUDONYME,
 * avec un bouton pour leur révéler son vrai nom RP. La liste vient du serveur (ClientRp.aliased) et se met à jour toute seule.
 */
public class RevealScreen extends Screen {
    private static final int W = 420, H = 300, ROW_H = 26, LIST_H = 180;
    private static final long CONFIRM_MS = 4000L;

    private int scroll = 0;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;
    /** La révélation est irréversible : un premier clic arme le bouton, un second (dans les 4 s) la confirme. */
    private UUID armed = null;
    private long armedUntil = 0;

    public RevealScreen() {
        super(Text.literal("Révéler mon nom"));
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        btns.clear();
        btns.add(new Btn(x0 + 14, y0 + H - 32, W - 28, 20, "Retour", Ui.ACCENT, () -> {
            if (client != null) client.setScreen(new NotifCenterScreen());
        }));
    }

    private int rows() {
        return LIST_H / ROW_H;
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, ClientRp.aliased.size() - rows()), scroll));
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "RÉVÉLER MON NOM", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        long now = System.currentTimeMillis();
        if (armed != null && now > armedUntil) armed = null;
        clampScroll();

        if (ClientRp.confirmed) {
            c.drawText(tr, "Votre vrai nom RP : " + ClientRp.own, x0 + 14, y0 + 32, Ui.TEXT, false);
            c.drawText(tr, "Personnes à qui vous vous êtes présenté sous pseudonyme :", x0 + 14, y0 + 44, Ui.MUTED, false);
        } else {
            c.drawText(tr, "Définissez d'abord votre nom RP (/rpnom).", x0 + 14, y0 + 38, Ui.RED, false);
        }

        int lx = x0 + 14, ly = y0 + 60, lw = W - 28;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        List<Net.AliasEntry> list = ClientRp.aliased;
        if (list.isEmpty()) {
            c.drawText(tr, "Personne pour l'instant.", lx + 8, ly + 8, Ui.MUTED, false);
            c.drawText(tr, "Quand vous vous présentez sous pseudonyme, la personne apparaît ici.", lx + 8, ly + 20, Ui.MUTED, false);
        }
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        for (int i = 0; i < rows() + 1 && scroll + i < list.size(); i++) {
            Net.AliasEntry e = list.get(scroll + i);
            int ry = ly + i * ROW_H;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, Ui.BTN_HOVER);
            c.fill(lx + 1, ry, lx + 3, ry + ROW_H, Ui.ACCENT);
            int textW = lw - 100;
            c.drawText(tr, tr.trimToWidth(e.targetName(), textW), lx + 8, ry + 3, hover ? Ui.ACCENT : Ui.TEXT, false);
            c.drawText(tr, tr.trimToWidth("Vous lui êtes connu comme « " + e.alias() + " »", textW), lx + 8, ry + 14, Ui.MUTED, false);
            boolean isArmed = e.target().equals(armed);
            Ui.button(c, tr, btnX(lx, lw), ry + 5, BTN_W, 16, isArmed ? "Confirmer ?" : "Révéler", mx, my,
                ClientRp.confirmed, isArmed ? Ui.YELLOW : Ui.GREEN);
        }
        c.disableScissor();
        c.drawText(tr, "Irréversible : la personne saura qui vous êtes vraiment.", x0 + 14, y0 + H - 46, Ui.MUTED, false);
        for (Btn b : btns) Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
    }

    private static final int BTN_W = 84;

    private static int btnX(int lx, int lw) {
        return lx + lw - BTN_W - 6;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : btns) {
                if (b.enabled && Ui.inside(mx, my, b.x, b.y, b.w, b.h)) {
                    b.action.run();
                    return true;
                }
            }
            int lx = x0 + 14, ly = y0 + 60, lw = W - 28;
            if (ClientRp.confirmed && Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                int idx = scroll + (int) ((my - ly) / ROW_H);
                List<Net.AliasEntry> list = ClientRp.aliased;
                if (idx >= 0 && idx < list.size()) {
                    int ry = ly + (idx - scroll) * ROW_H;
                    if (Ui.inside(mx, my, btnX(lx, lw), ry + 5, BTN_W, 16)) {
                        UUID target = list.get(idx).target();
                        if (target.equals(armed) && System.currentTimeMillis() <= armedUntil) {
                            ClientPlayNetworking.send(new Net.Reveal(target));
                            ClientRp.aliased.removeIf(a -> a.target().equals(target)); // le serveur renvoie la liste à jour juste après
                            armed = null;
                        } else {
                            armed = target;
                            armedUntil = System.currentTimeMillis() + CONFIRM_MS;
                        }
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v);
        clampScroll();
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
