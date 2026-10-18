package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Centre des notifications : historique des popups (chats, invitations, alertes staff) avec renvoi vers les chats. */
public class NotifCenterScreen extends Screen {
    private static final int W = 420, H = 300, ROW_H = 26, LIST_H = 208;

    private int scroll = 0;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;

    public NotifCenterScreen() {
        super(Text.literal("Notifications"));
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        ClientRp.unreadNotifs = 0;
        btns.clear();
        int by = y0 + H - 32, bw = (W - 28 - 8) / 3;
        btns.add(new Btn(x0 + 14, by, bw, 20, "Messagerie", Ui.AQUA, () -> {
            if (client != null) client.setScreen(new ChatsScreen(-1));
        }));
        btns.add(new Btn(x0 + 14 + bw + 4, by, bw, 20, "Tout effacer", Ui.RED, () -> {
            ClientRp.notifs.clear();
            ClientRp.unread.clear();
            scroll = 0;
        }));
        btns.add(new Btn(x0 + 14 + 2 * (bw + 4), by, bw, 20, "Retour", Ui.ACCENT, () -> {
            if (client != null) client.setScreen(new CommandScreen());
        }));
    }

    private int rows() {
        return LIST_H / ROW_H;
    }

    private static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min";
        return (s / 3600) + " h";
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "NOTIFICATIONS", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        int lx = x0 + 14, ly = y0 + 44, lw = W - 28;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        if (ClientRp.notifs.isEmpty()) c.drawText(tr, "Aucune notification.", lx + 8, ly + 8, Ui.MUTED, false);
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        for (int i = 0; i < rows() + 1 && scroll + i < ClientRp.notifs.size(); i++) {
            ClientRp.Notif n = ClientRp.notifs.get(scroll + i);
            int ry = ly + i * ROW_H;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, Ui.BTN_HOVER);
            int col = "staff".equals(n.kind()) ? Ui.RED : ("invite".equals(n.kind()) || "proposal".equals(n.kind()) ? Ui.GREEN : Ui.AQUA);
            c.fill(lx + 1, ry, lx + 3, ry + ROW_H, col);
            String when = ago(n.ms());
            c.drawText(tr, tr.trimToWidth(n.title(), lw - 24 - tr.getWidth(when)), lx + 8, ry + 3, hover ? Ui.ACCENT : Ui.TEXT, false);
            c.drawText(tr, when, lx + lw - 6 - tr.getWidth(when), ry + 3, Ui.MUTED, false);
            ClientRp.Prop pr = "proposal".equals(n.kind()) ? ClientRp.prop(n.chat()) : null;
            if (pr != null) {
                int bw = 70, bx = lx + lw - 3 * (bw + 3) - 3, bh = 12, byy = ry + 12;
                long left = pr.paused() ? -1 : Math.max(0, (pr.endMs - System.currentTimeMillis()) / 1000);
                c.drawText(tr, pr.paused() ? "saisie en cours…" : "expire dans " + left + " s", lx + 8, ry + 14, Ui.YELLOW, false);
                Ui.button(c, tr, bx, byy, bw, bh, "Accepter", mx, my, true, Ui.GREEN);
                Ui.button(c, tr, bx + bw + 3, byy, bw, bh, "Pseudonyme", mx, my, true, Ui.ACCENT);
                Ui.button(c, tr, bx + 2 * (bw + 3), byy, bw, bh, "Refuser", mx, my, true, Ui.RED);
            } else if ("proposal".equals(n.kind())) {
                c.drawText(tr, "Demande expirée ou traitée", lx + 8, ry + 14, Ui.MUTED, false);
            } else {
                c.drawText(tr, tr.trimToWidth(n.text(), lw - 16), lx + 8, ry + 14, Ui.MUTED, false);
            }
        }
        c.disableScissor();
        c.drawText(tr, "Clic : ouvrir le chat (ou le panneau pour une alerte d'aide)", x0 + 14, y0 + H - 46, Ui.MUTED, false);
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
            int lx = x0 + 14, ly = y0 + 44, lw = W - 28;
            if (Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                int idx = scroll + (int) ((my - ly) / ROW_H);
                if (idx >= 0 && idx < ClientRp.notifs.size()) {
                    ClientRp.Notif n = ClientRp.notifs.get(idx);
                    if ("proposal".equals(n.kind())) {
                        ClientRp.Prop pr = ClientRp.prop(n.chat());
                        if (pr == null) return true;
                        int bw = 70, bx = lx + lw - 3 * (bw + 3) - 3, ry = ly + (idx - scroll) * ROW_H;
                        if (Ui.inside(mx, my, bx, ry + 12, bw, 12)) {
                            ClientPlayNetworking.send(new Net.Reply(pr.p.id(), false, ""));
                            ClientRp.removeProposal(pr.p.id());
                        } else if (Ui.inside(mx, my, bx + bw + 3, ry + 12, bw, 12) && client != null) {
                            WatheRPClient.openAlias(client, pr.p.id(), pr.p.fromName());
                        } else if (Ui.inside(mx, my, bx + 2 * (bw + 3), ry + 12, bw, 12)) {
                            ClientPlayNetworking.send(new Net.ProposalAction(pr.p.id(), "decline"));
                            ClientRp.removeProposal(pr.p.id());
                        }
                        return true;
                    }
                    if ("staff".equals(n.kind())) {
                        ClientRp.wantPanel = true;
                        ClientPlayNetworking.send(new Net.RequestTickets());
                    } else if (client != null) {
                        client.setScreen(new ChatsScreen(n.chat()));
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(Math.max(0, ClientRp.notifs.size() - rows()), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
