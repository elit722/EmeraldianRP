package fr.watherp.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Invite de commande pour TOUS les joueurs : le chat de Wathe reste masqué en partie (on ne le force ni à l'activer ni
 * à le désactiver), ce menu permet juste de taper une commande (/aide, /rpnom…) et d'en lire le retour.
 */
public class CommandScreen extends Screen {
    private static final int W = 420, H = 204, HISTORY_MAX = 30;

    private TextFieldWidget command;
    private String text = "";
    private int histIdx = -1;
    private String histDraft = "";
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;

    public CommandScreen() {
        super(Text.literal("Commandes"));
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        command = new TextFieldWidget(textRenderer, x0 + 14, y0 + 50, W - 28, 20, Text.literal("Commande"));
        command.setMaxLength(256);
        command.setPlaceholder(Text.literal("Commande (le / est facultatif), ex. aide — Entrée pour envoyer"));
        command.setText(text);
        command.setChangedListener(t -> text = t);
        addDrawableChild(command);
        setInitialFocus(command);
        buildButtons();
    }

    private void buildButtons() {
        btns.clear();
        int half = (W - 28 - 8) / 2;
        int r1 = y0 + H - 60, r2 = y0 + H - 34;
        btns.add(new Btn(x0 + 14, r1, half, 20, "Notifs chat : " + (ClientRp.notifChat ? "OUI" : "NON"),
            ClientRp.notifChat ? Ui.GREEN : Ui.RED, () -> {
                ClientRp.notifChat = !ClientRp.notifChat;
                if (!ClientRp.notifChat) ClientRp.toasts.clear();
                ClientRp.savePrefs();
                buildButtons();
            }));
        if (ClientRp.helper) {
            btns.add(new Btn(x0 + 14 + half + 8, r1, half, 20, "Notifs staff : " + (ClientRp.notifStaff ? "OUI" : "NON"),
                ClientRp.notifStaff ? Ui.GREEN : Ui.RED, () -> {
                    ClientRp.notifStaff = !ClientRp.notifStaff;
                    if (!ClientRp.notifStaff) ClientRp.alerts.clear();
                    ClientRp.savePrefs();
                    buildButtons();
                }));
        }
        btns.add(new Btn(x0 + 14, r2, 100, 20, "Aide", Ui.GREEN, () -> {
            if (client != null) client.setScreen(null);
            WatheRPClient.requestHelp();
        }));
        String center = "Centre des notifications" + (ClientRp.unreadNotifs > 0 ? " (" + ClientRp.unreadNotifs + ")" : "");
        btns.add(new Btn(x0 + 14 + 108, r2, W - 28 - 108, 20, center, Ui.ACCENT, () -> {
            if (client != null) client.setScreen(new NotifCenterScreen());
        }));
    }

    private void send() {
        String raw = command.getText().trim();
        String cmd = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (cmd.isEmpty() || client == null || client.getNetworkHandler() == null) return;
        ClientRp.feedback.clear();
        ClientRp.feedback.add("> /" + cmd);
        client.getNetworkHandler().sendChatCommand(cmd);
        List<String> h = ClientRp.cmdHistory;
        if (h.isEmpty() || !h.get(h.size() - 1).equals(cmd)) h.add(cmd);
        while (h.size() > HISTORY_MAX) h.remove(0);
        histIdx = -1;
        command.setText("");
    }

    private void history(int dir) {
        List<String> h = ClientRp.cmdHistory;
        if (h.isEmpty()) return;
        if (histIdx == -1) {
            if (dir > 0) return;
            histDraft = command.getText();
            histIdx = h.size() - 1;
        } else {
            histIdx += dir;
            if (histIdx >= h.size()) {
                histIdx = -1;
                command.setText(histDraft);
                return;
            }
            if (histIdx < 0) histIdx = 0;
        }
        command.setText(h.get(histIdx));
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xB0050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "COMMANDES", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        List<String> fb = new ArrayList<>(ClientRp.feedback);
        int from = Math.max(0, fb.size() - 4);
        for (int i = from; i < fb.size(); i++) {
            String line = fb.get(i);
            c.drawText(tr, tr.trimToWidth(line, W - 28), x0 + 14, y0 + 78 + (i - from) * 10, line.startsWith("> ") ? Ui.ACCENT : Ui.TEXT, false);
        }
        c.drawText(tr, "↑↓ historique · Échap pour fermer", x0 + 14, y0 + H - 78, Ui.MUTED, false);
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

    /** La lettre de la touche qui vient d'ouvrir le menu ne doit pas se retrouver dans le champ. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (System.currentTimeMillis() - openTime < 150) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            send();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            history(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            history(1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
