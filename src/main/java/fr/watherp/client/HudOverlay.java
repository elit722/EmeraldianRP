package fr.watherp.client;

import fr.watherp.net.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.Iterator;

/** Bandeaux à l'écran : proposition de présentation (joueur) et alertes d'aide (staff). */
public final class HudOverlay {
    private HudOverlay() {}

    /** HUD : la proposition de présentation s'affiche toujours (même HUD masqué avec F3+H/F1) ; le reste respecte le HUD masqué. */
    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        TextRenderer tr = mc.textRenderer;
        int sw = c.getScaledWindowWidth();
        long now = System.currentTimeMillis();
        // sous un écran ouvert, c'est le hook ScreenEvents.afterRender qui la dessine (par-dessus l'écran)
        if (mc.currentScreen == null) renderProposal(c, tr, sw, now);
        if (mc.options.hudHidden) return;

        renderToasts(c, tr, now);

        // ---- alertes d'aide (staff)
        Iterator<ClientRp.AlertCard> it = ClientRp.alerts.iterator();
        int y = 10;
        while (it.hasNext()) {
            ClientRp.AlertCard a = it.next();
            if (now > a.endMs()) {
                it.remove();
                continue;
            }
            float fade = Math.min(1f, (a.endMs() - now) / 600f);
            Net.Alert al = a.alert();
            int w = 230, h = 40, x = sw - w - 10;
            c.fill(x, y, x + w, y + h, Ui.alpha(Ui.BG, fade));
            c.drawBorder(x, y, w, h, Ui.alpha(Ui.BORDER, fade));
            c.fill(x, y, x + 3, y + h, Ui.alpha(Ui.RED, fade));
            c.drawText(tr, "AIDE #" + al.id() + " · " + al.type().toUpperCase() + (al.wantChat() ? " · chat demandé" : ""), x + 8, y + 5, Ui.alpha(Ui.RED, fade), false);
            String who = al.anonymous() ? "Anonyme" : al.player() + (al.rp().isEmpty() ? "" : " (" + al.rp() + ")");
            c.drawText(tr, tr.trimToWidth(who, w - 16), x + 8, y + 16, Ui.alpha(Ui.TEXT, fade), false);
            c.drawText(tr, tr.trimToWidth(al.message().isEmpty() ? "(pas de message)" : al.message(), w - 16), x + 8, y + 27, Ui.alpha(Ui.MUTED, fade), false);
            y += h + 4;
        }
    }

    /** Popups de chat (à gauche) : invitation / ajout avec la touche pour ouvrir, ou nouveau message. */
    /** Bandeau « X souhaite se présenter à vous » : dessiné par le HUD ET par-dessus tout écran ouvert (menus, inventaire…). */
    public static void renderProposal(DrawContext c, TextRenderer tr, int sw, long now) {
        Net.Proposal p = ClientRp.activeProposal();
        if (p != null) {
            // Les touches sont lues à chaque image depuis les réglages Minecraft : si le joueur les a changées
            // (Options > Contrôles > Wathe RP), le bandeau affiche la nouvelle touche immédiatement.
            boolean u1 = WatheRPClient.acceptKey == null || WatheRPClient.acceptKey.isUnbound();
            boolean u2 = WatheRPClient.aliasKey == null || WatheRPClient.aliasKey.isUnbound();
            String hint;
            if (u1 && u2) hint = "Aucune touche assignée : Options > Contrôles > Wathe RP";
            else if (u1) hint = "[" + keyName(WatheRPClient.aliasKey) + "] Sous pseudonyme  ·  assignez l'autre touche";
            else if (u2) hint = "[" + keyName(WatheRPClient.acceptKey) + "] Se présenter  ·  assignez l'autre touche";
            else hint = "[" + keyName(WatheRPClient.acceptKey) + "] Se présenter     [" + keyName(WatheRPClient.aliasKey) + "] Sous pseudonyme";
            int w = Math.max(270, Math.max(tr.getWidth(hint), tr.getWidth(p.fromName())) + 20), h = 56, x = (sw - w) / 2, y = 22;
            ClientRp.Prop pp = ClientRp.prop(p.id());
            long left = pp == null ? 0 : Math.max(0, pp.popupEndMs - now);
            c.fill(x, y, x + w, y + h, Ui.BG);
            c.drawBorder(x, y, w, h, Ui.BORDER);
            Ui.corners(c, x, y, w, h, 6, Ui.ACCENT);
            c.drawText(tr, tr.trimToWidth(p.fromName(), w - 20), x + 10, y + 8, Ui.ACCENT, false);
            c.drawText(tr, "souhaite se présenter à vous", x + 10, y + 19, Ui.TEXT, false);
            c.drawText(tr, hint, x + 10, y + 33, (u1 && u2) ? Ui.YELLOW : Ui.GREEN, false);
            int bw = (int) ((w - 2) * (left / (float) Math.max(1L, pp == null ? 1L : pp.popupTotalMs)));
            c.fill(x + 1, y + h - 4, x + 1 + bw, y + h - 2, Ui.ACCENT);
        }

    }

    /** Appelé après le rendu de n'importe quel écran. */
    public static void renderProposalOverScreen(DrawContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        renderProposal(c, mc.textRenderer, c.getScaledWindowWidth(), System.currentTimeMillis());
    }

    private static void renderToasts(DrawContext c, TextRenderer tr, long now) {
        java.util.Iterator<ClientRp.Toast> it = ClientRp.toasts.iterator();
        int y = 10;
        String key = keyName(WatheRPClient.chatsKey);
        boolean unbound = WatheRPClient.chatsKey == null || WatheRPClient.chatsKey.isUnbound();
        while (it.hasNext()) {
            ClientRp.Toast t = it.next();
            if (now > t.endMs) {
                it.remove();
                continue;
            }
            float fade = Math.min(1f, (t.endMs - now) / 600f);
            int w = 250, h = t.invite ? 52 : 40, x = 10;
            c.fill(x, y, x + w, y + h, Ui.alpha(Ui.BG, fade));
            c.drawBorder(x, y, w, h, Ui.alpha(Ui.BORDER, fade));
            c.fill(x, y, x + 3, y + h, Ui.alpha(t.invite ? Ui.GREEN : Ui.AQUA, fade));
            c.drawText(tr, tr.trimToWidth(t.title, w - 16), x + 8, y + 5, Ui.alpha(Ui.ACCENT, fade), false);
            c.drawText(tr, tr.trimToWidth(t.line1, w - 16), x + 8, y + 16, Ui.alpha(Ui.TEXT, fade), false);
            if (t.invite) {
                String hint = unbound ? "Menu Options > Contrôles > Wathe RP : assignez Messagerie"
                    : "[" + key + "] Ouvrir le chat" + (t.line2.isEmpty() ? "" : "  ·  " + t.line2);
                c.drawText(tr, tr.trimToWidth(hint, w - 16), x + 8, y + 31, Ui.alpha(unbound ? Ui.YELLOW : Ui.GREEN, fade), false);
            } else {
                c.drawText(tr, unbound ? "Assignez la touche Messagerie (Contrôles)" : "[" + key + "] Ouvrir le chat", x + 8, y + 27, Ui.alpha(unbound ? Ui.YELLOW : Ui.GREEN, fade), false);
            }
            y += h + 4;
        }
    }

    private static String keyName(net.minecraft.client.option.KeyBinding kb) {
        if (kb == null) return "?";
        return kb.getBoundKeyLocalizedText().getString();
    }
}
