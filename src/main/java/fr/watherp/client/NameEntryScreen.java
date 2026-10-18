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

/**
 * Menu de saisie du nom RP, ouvert au début de chaque partie.
 * Deux choix qui s'excluent : entrer un NOUVEAU nom, ou conserver celui de la partie précédente.
 */
public class NameEntryScreen extends Screen {
    private static final int W = 340;

    private final String previous;
    private final String error;
    /** En partie : Échap ne ferme pas le menu (le joueur doit choisir un nom). */
    private final boolean mandatory;
    private TextFieldWidget field;
    private Btn confirmBtn;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0, h;

    public NameEntryScreen(String previous, String error, boolean mandatory) {
        super(Text.literal("Nom RP"));
        this.previous = previous == null ? "" : previous;
        this.error = error == null ? "" : error;
        this.mandatory = mandatory;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !mandatory;
    }

    private boolean hasPrevious() {
        return !previous.isEmpty();
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        h = hasPrevious() ? 222 : 176;
        x0 = (width - W) / 2;
        y0 = (height - h) / 2;

        // le champ reste VIDE : le nom précédent n'est proposé que par le bouton « Conserver », jamais prérempli
        field = new TextFieldWidget(textRenderer, x0 + 20, y0 + 92, W - 40, 20, Text.literal("Nom RP"));
        field.setMaxLength(32);
        // aucun chiffre dans le prénom RP (le serveur revérifie)
        field.setTextPredicate(t -> t.codePoints().noneMatch(Character::isDigit));
        field.setPlaceholder(Text.literal(hasPrevious() ? "Entrez un nouveau nom…" : "Prénom de ton personnage"));
        addDrawableChild(field);
        setInitialFocus(field);

        btns.clear();
        confirmBtn = new Btn(x0 + 20, y0 + 128, W - 40, 22, "Confirmer ce nom", Ui.ACCENT, () -> submit(field.getText()));
        btns.add(confirmBtn);
        if (hasPrevious()) {
            btns.add(new Btn(x0 + 20, y0 + h - 40, W - 40, 22, keepLabel(), Ui.GREEN, () -> submit(previous)));
        }
    }

    /** « Conserver [Nom précédent] comme mon identité » — le nom est raccourci si le bouton est trop étroit. */
    private String keepLabel() {
        int max = W - 40 - 16;
        String name = previous;
        String label = "Conserver « " + name + " » comme mon identité";
        while (textRenderer.getWidth(label) > max && name.length() > 1) {
            name = name.substring(0, name.length() - 1);
            label = "Conserver « " + name + "… » comme mon identité";
        }
        return label;
    }

    private void submit(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return;
        ClientPlayNetworking.send(new Net.SubmitName(n));
        close();
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, h, "TON NOM RP", anim());
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        confirmBtn.enabled = !field.getText().trim().isEmpty(); // « Confirmer » ne sert qu'à valider un nom saisi
        super.render(c, mx, my, delta);
        c.drawText(textRenderer, "Choisis le prénom de ton personnage pour cette partie.", x0 + 20, y0 + 48, Ui.TEXT, false);
        c.drawText(textRenderer, mandatory ? "⚠ Définitif pour cette partie · Échap désactivé." : "⚠ Définitif pour cette partie : seul le staff pourra le modifier.", x0 + 20, y0 + 64, Ui.YELLOW, false);
        c.drawText(textRenderer, "Lettres uniquement (pas de chiffres), espace, ' - .  ·  2 à 32 car.", x0 + 20, y0 + 78, Ui.MUTED, false);
        if (!error.isEmpty()) c.drawText(textRenderer, error, x0 + 20, y0 + 115, Ui.RED, false);
        if (hasPrevious()) {
            // séparateur « ou » entre les deux choix
            int ly = y0 + 166, mid = x0 + W / 2, tw = textRenderer.getWidth("ou");
            c.fill(x0 + 20, ly + 4, mid - tw / 2 - 8, ly + 5, Ui.BORDER);
            c.fill(mid + tw / 2 + 8, ly + 4, x0 + W - 20, ly + 5, Ui.BORDER);
            c.drawText(textRenderer, "ou", mid - tw / 2, ly, Ui.MUTED, false);
        }
        for (Btn b : btns) Ui.button(c, textRenderer, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
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
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // Entrée valide uniquement un nom SAISI : garder l'ancien nom demande un clic explicite
            if (!field.getText().trim().isEmpty()) submit(field.getText());
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
