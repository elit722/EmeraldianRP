package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Menu « Aide » (/aide, touche Aide, bouton du menu pause) : type de demande, message, anonymisation du ticket
 * (recommandée pour les questions sur le rôle) et demande de chat privé.
 */
public class HelpScreen extends Screen {
    private static final int W = 380, H = 270;
    /** Types pour lesquels l'anonymat est activé d'office (le joueur peut le désactiver). */
    private static final Set<String> ROLE_TYPES = Set.of("role", "rôle", "question");

    private final List<String> types;
    private String type;
    private boolean anonymous = false, anonTouched = false, wantChat = false;
    private TextFieldWidget message;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;

    public HelpScreen(List<String> types) {
        super(Text.literal("Aide"));
        this.types = types.isEmpty() ? List.of("autre") : types;
        this.type = this.types.get(0);
        applyRoleDefault();
    }

    private void applyRoleDefault() {
        if (!anonTouched) anonymous = ROLE_TYPES.contains(type.toLowerCase());
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        String keep = message == null ? "" : message.getText();
        message = new TextFieldWidget(textRenderer, x0 + 20, y0 + 112, W - 40, 20, Text.literal("Message"));
        message.setMaxLength(300);
        message.setPlaceholder(Text.literal("Décris ton problème ou ta question…"));
        message.setText(keep);
        addDrawableChild(message);
        setInitialFocus(message);
        rebuild();
    }

    private void rebuild() {
        btns.clear();
        // types sur une ou deux lignes
        int x = x0 + 20, y = y0 + 58;
        for (String t : types) {
            int w = textRenderer.getWidth(t) + 16;
            if (x + w > x0 + W - 20) {
                x = x0 + 20;
                y += 22;
            }
            final String tt = t;
            Btn b = new Btn(x, y, w, 18, t, tt.equals(type) ? Ui.GREEN : Ui.ACCENT, () -> {
                type = tt;
                applyRoleDefault();
                rebuild();
            });
            btns.add(b);
            x += w + 4;
        }
        btns.add(new Btn(x0 + 20, y0 + 142, W - 40, 20, (anonymous ? "[X] " : "[  ] ") + "Anonymiser le ticket (recommandé pour les questions de rôle)",
            anonymous ? Ui.GREEN : Ui.ACCENT, () -> {
                anonymous = !anonymous;
                anonTouched = true;
                rebuild();
            }));
        btns.add(new Btn(x0 + 20, y0 + 182, W - 40, 20, (wantChat ? "[X] " : "[  ] ") + "Demander l'ouverture d'un chat privé",
            wantChat ? Ui.GREEN : Ui.ACCENT, () -> {
                wantChat = !wantChat;
                rebuild();
            }));
        btns.add(new Btn(x0 + 20, y0 + H - 34, 160, 22, "Annuler", Ui.RED, this::close));
        btns.add(new Btn(x0 + W - 180, y0 + H - 34, 160, 22, "Envoyer", Ui.GREEN, this::submit));
    }

    private void submit() {
        ClientPlayNetworking.send(new Net.HelpSubmit(type, message.getText().trim(), anonymous, wantChat));
        close();
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "AIDE", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        c.drawText(tr, "Type de demande", x0 + 20, y0 + 46, Ui.MUTED, false);
        c.drawText(tr, "Message", x0 + 20, y0 + 100, Ui.MUTED, false);
        c.drawText(tr, tr.trimToWidth(anonymous
            ? "Les helpers ne verront ni ton pseudo, ni ton nom RP, ni ta position."
            : "Les helpers verront ton pseudo, ton nom RP et ta position.", W - 40), x0 + 20, y0 + 166, anonymous ? Ui.GREEN : Ui.MUTED, false);
        c.drawText(tr, tr.trimToWidth(wantChat
            ? "Un helper pourra t'ouvrir un chat : une popup t'indiquera la touche."
            : "Sans chat privé, la réponse viendra dans le jeu ou par un helper.", W - 40), x0 + 20, y0 + 206, wantChat ? Ui.GREEN : Ui.MUTED, false);
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
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
