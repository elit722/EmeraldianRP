package fr.watherp.client;

import fr.watherp.net.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Débrief de fin de partie : les vrais noms, les pseudonymes utilisés, qui a menti à qui et qui s'est révélé.
 * Trois onglets (identités / mensonges / révélations) ; les données viennent du serveur (identiques pour tous les joueurs).
 */
public class DebriefScreen extends Screen {
    private static final int W = 560, H = 340, ROW_H = 26;
    private static final int TAB_PLAYERS = 0, TAB_LIES = 1, TAB_REVEALS = 2;

    private int tab = TAB_PLAYERS;
    private int scroll = 0;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0, listX, listY, listW, listH;

    public DebriefScreen() {
        super(Text.literal("Débrief"));
    }

    private Net.Debrief data() {
        return ClientRp.debrief;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        listX = x0 + 14;
        listY = y0 + 84;
        listW = W - 28;
        listH = H - 84 - 44;
        buildButtons();
    }

    private void buildButtons() {
        btns.clear();
        Net.Debrief d = data();
        int tw = (W - 28 - 8) / 3;
        String[] labels = {
            "Identités" + (d == null ? "" : " (" + d.players().size() + ")"),
            "Mensonges" + (d == null ? "" : " (" + d.lies().size() + ")"),
            "Révélations" + (d == null ? "" : " (" + d.reveals().size() + ")")
        };
        int[] accents = {Ui.ACCENT, Ui.RED, Ui.GREEN};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            Btn b = new Btn(x0 + 14 + i * (tw + 4), y0 + 58, tw, 20, labels[i], tab == i ? accents[i] : Ui.MUTED, () -> {
                tab = t;
                scroll = 0;
                buildButtons();
            });
            btns.add(b);
        }
        btns.add(new Btn(x0 + 14, y0 + H - 32, 140, 20, "Carnet de contacts", Ui.AQUA, () -> {
            if (client != null) WatheRPClient.openContacts(client);
        }));
        btns.add(new Btn(x0 + W - 14 - 140, y0 + H - 32, 140, 20, "Fermer", Ui.ACCENT, this::close));
    }

    private int count() {
        Net.Debrief d = data();
        if (d == null) return 0;
        return switch (tab) {
            case TAB_PLAYERS -> d.players().size();
            case TAB_LIES -> d.lies().size();
            default -> d.reveals().size();
        };
    }

    private int rows() {
        return listH / ROW_H;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, count() - rows()), scroll));
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "DÉBRIEF DE LA PARTIE", anim());
    }

    private static String ref(Net.DbRef r) {
        return r.rp() + " (" + r.mc() + ")";
    }

    private static String t(int min) {
        return "t+" + min + " min";
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        Net.Debrief d = data();
        if (d == null) {
            c.drawText(tr, "Aucun débrief disponible.", x0 + 14, y0 + 40, Ui.MUTED, false);
            for (Btn b : btns) Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
            return;
        }
        int real = 0, alias = 0;
        for (Net.DbPlayer p : d.players()) {
            real += p.realIntros();
            alias += p.aliasIntros();
        }
        c.drawText(tr, d.minutes() + " min · " + d.players().size() + " joueurs · " + (real + alias) + " présentations ("
            + alias + " sous pseudonyme) · " + d.reveals().size() + " révélations", x0 + 14, y0 + 40, Ui.MUTED, false);

        clampScroll();
        c.fill(listX, listY, listX + listW, listY + listH, 0x60000000);
        c.drawBorder(listX, listY, listW, listH, Ui.BORDER);
        if (count() == 0) {
            String empty = switch (tab) {
                case TAB_PLAYERS -> "Aucun joueur identifié.";
                case TAB_LIES -> "Personne n'a utilisé de pseudonyme : aucun mensonge.";
                default -> "Personne ne s'est révélé.";
            };
            c.drawText(tr, empty, listX + 8, listY + 8, Ui.MUTED, false);
        }
        c.enableScissor(listX, listY, listX + listW, listY + listH);
        int textW = listW - 16;
        for (int i = 0; i < rows() + 1 && scroll + i < count(); i++) {
            int idx = scroll + i;
            int ry = listY + i * ROW_H;
            String l1, l2;
            int bar, c1 = Ui.TEXT;
            switch (tab) {
                case TAB_PLAYERS -> {
                    Net.DbPlayer p = d.players().get(idx);
                    l1 = ref(p.p());
                    l2 = p.realIntros() + " vrai(s) nom(s) donné(s) · " + p.aliasIntros() + " pseudonyme(s) · " + p.reveals()
                        + " révélation(s) · connaît " + p.contacts() + " personne(s)";
                    bar = Ui.ACCENT;
                }
                case TAB_LIES -> {
                    Net.DbLie l = d.lies().get(idx);
                    l1 = ref(l.liar()) + "  →  " + ref(l.victim()) + "  :  « " + l.claimed() + " »";
                    l2 = t(l.atMin()) + " · " + (l.revealedAtMin() >= 0 ? "révélé à " + t(l.revealedAtMin()) : "jamais révélé")
                        + (l.note().isEmpty() ? "" : " · " + l.note());
                    bar = l.revealedAtMin() >= 0 ? Ui.YELLOW : Ui.RED;
                }
                default -> {
                    Net.DbReveal r = d.reveals().get(idx);
                    l1 = ref(r.who()) + "  →  " + ref(r.to());
                    l2 = "connu sous « " + r.alias() + " » · vrai nom révélé à " + t(r.atMin());
                    bar = Ui.GREEN;
                }
            }
            if (Ui.inside(mx, my, listX, ry, listW, ROW_H) && my < listY + listH) c.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, Ui.BTN_HOVER);
            c.fill(listX + 1, ry, listX + 3, ry + ROW_H, bar);
            c.drawText(tr, tr.trimToWidth(l1, textW), listX + 8, ry + 3, c1, false);
            c.drawText(tr, tr.trimToWidth(l2, textW), listX + 8, ry + 14, Ui.MUTED, false);
        }
        c.disableScissor();
        for (Btn b : btns) Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
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
