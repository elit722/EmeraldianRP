package fr.watherp.client;

import fr.watherp.net.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** Sélecteur de joueur en ligne (nouveau chat, ajout de membre), avec recherche. */
public class PlayerPickerScreen extends Screen {
    private static final int W = 340, H = 260, ROW_H = 20, LIST_H = 160;

    private final Screen parent;
    private final String heading;
    private final List<Net.PlayerEntry> players;
    private final Consumer<UUID> onPick;
    private TextFieldWidget search;
    private int scroll = 0;
    private long openTime;
    private int x0, y0;
    private final List<Btn> btns = new ArrayList<>();

    public PlayerPickerScreen(Screen parent, String heading, List<Net.PlayerEntry> players, Consumer<UUID> onPick) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.players = players;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        search = new TextFieldWidget(textRenderer, x0 + 20, y0 + 44, W - 40, 18, Text.literal("Rechercher"));
        search.setMaxLength(32);
        search.setPlaceholder(Text.literal("Pseudo / nom RP"));
        search.setChangedListener(s -> scroll = 0);
        addDrawableChild(search);
        setInitialFocus(search);
        btns.clear();
        btns.add(new Btn(x0 + 20, y0 + H - 32, W - 40, 20, "Annuler", Ui.RED, this::close));
    }

    private List<Net.PlayerEntry> shown() {
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Net.PlayerEntry> out = new ArrayList<>();
        for (Net.PlayerEntry p : players) {
            if (q.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(q) || p.rp().toLowerCase(Locale.ROOT).contains(q)) out.add(p);
        }
        return out;
    }

    private int rows() {
        return LIST_H / ROW_H;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, heading.toUpperCase(), anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        int lx = x0 + 20, ly = y0 + 68, lw = W - 40;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        List<Net.PlayerEntry> list = shown();
        if (list.isEmpty()) c.drawText(textRenderer, "Aucun joueur.", lx + 8, ly + 8, Ui.MUTED, false);
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        for (int i = 0; i < rows() + 1 && scroll + i < list.size(); i++) {
            Net.PlayerEntry p = list.get(scroll + i);
            int ry = ly + i * ROW_H;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, Ui.BTN_HOVER);
            String label = p.name() + (p.rp().isEmpty() ? "" : "  (" + p.rp() + ")");
            c.drawText(textRenderer, textRenderer.trimToWidth(label, lw - 14), lx + 8, ry + 6, hover ? Ui.ACCENT : Ui.TEXT, false);
        }
        c.disableScissor();
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
            int lx = x0 + 20, ly = y0 + 68, lw = W - 40;
            if (Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                int idx = scroll + (int) ((my - ly) / ROW_H);
                List<Net.PlayerEntry> list = shown();
                if (idx >= 0 && idx < list.size()) {
                    UUID id = list.get(idx).uuid();
                    close();
                    onPick.accept(id);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(Math.max(0, shown().size() - rows()), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
