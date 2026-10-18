package fr.watherp.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Saisie d'un pseudonyme RP (présentation sous pseudonyme). */
public class AliasScreen extends Screen {
    private static final int W = 340, H = 170;

    private final String subtitle;
    private final Consumer<String> onSubmit;
    private Runnable onCancel;
    private boolean submitted;
    private TextFieldWidget field;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;

    public AliasScreen(String subtitle, Consumer<String> onSubmit) {
        super(Text.literal("Pseudonyme RP"));
        this.subtitle = subtitle;
        this.onSubmit = onSubmit;
    }

    /** Appelé quand l'écran se ferme sans validation (annuler, Échap). */
    public AliasScreen onCancel(Runnable r) {
        this.onCancel = r;
        return this;
    }

    @Override
    public void removed() {
        if (!submitted && onCancel != null) onCancel.run();
        super.removed();
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        field = new TextFieldWidget(textRenderer, x0 + 20, y0 + 78, W - 40, 20, Text.literal("Pseudonyme"));
        field.setMaxLength(32);
        field.setPlaceholder(Text.literal("Pseudonyme RP"));
        addDrawableChild(field);
        setInitialFocus(field);
        btns.clear();
        btns.add(new Btn(x0 + 20, y0 + H - 38, 140, 22, "Annuler", Ui.RED, this::close));
        btns.add(new Btn(x0 + W - 160, y0 + H - 38, 140, 22, "Valider", Ui.GREEN, this::submit));
    }

    private void submit() {
        String t = field.getText().trim();
        if (t.isEmpty()) return;
        submitted = true;
        close();
        onSubmit.accept(t);
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "PSEUDONYME RP", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        c.drawText(textRenderer, subtitle, x0 + 20, y0 + 46, Ui.TEXT, false);
        c.drawText(textRenderer, "Cette personne vous verra sous ce nom à la place de votre nom RP.", x0 + 20, y0 + 60, Ui.MUTED, false);
        for (Btn b : btns) Ui.button(c, textRenderer, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : btns) {
                if (Ui.inside(mx, my, b.x, b.y, b.w, b.h)) {
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
