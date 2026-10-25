package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Surnoms (menu des notifications) : on donne un surnom aux personnes qui se sont présentées à nous. Le surnom est celui qui
 * s'affiche en jeu ; le nom sous lequel la personne s'est présentée (vrai nom ou pseudonyme) reste toujours consultable ici.
 */
public class NicknamesScreen extends Screen {
    private static final int W = 520, H = 300, ROW_H = 22, NICK_MAX = 32;

    private final List<Btn> btns = new ArrayList<>();
    private TextFieldWidget search, nickField;
    private List<Net.ContactEntry> shown = new ArrayList<>();
    private UUID selected;
    private int scroll = 0;
    private long openTime;
    private int x0, y0, listX, listY, listW, listH, detX, detW;
    private int refreshTicks = 0;

    public NicknamesScreen() {
        super(Text.literal("Surnoms"));
    }

    private static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        listX = x0 + 14;
        listY = y0 + 62;
        listW = 190;
        listH = H - 62 - 44;
        detX = listX + listW + 14;
        detW = W - 28 - listW - 14;

        String keepSearch = search == null ? "" : search.getText();
        search = new TextFieldWidget(textRenderer, listX, y0 + 38, listW, 18, Text.literal("Rechercher"));
        search.setMaxLength(32);
        search.setPlaceholder(Text.literal("Rechercher…"));
        search.setText(keepSearch);
        search.setChangedListener(t -> {
            scroll = 0;
            refreshList();
        });
        addDrawableChild(search);

        String keepNick = nickField == null ? null : nickField.getText();
        nickField = new TextFieldWidget(textRenderer, detX, y0 + 118, detW, 18, Text.literal("Surnom"));
        nickField.setMaxLength(NICK_MAX);
        nickField.setPlaceholder(Text.literal("Surnom affiché en jeu…"));
        if (keepNick != null) nickField.setText(keepNick);
        addDrawableChild(nickField);

        btns.clear();
        btns.add(new Btn(detX, y0 + 144, (detW - 6) / 2, 20, "Enregistrer", Ui.GREEN, this::saveNick));
        btns.add(new Btn(detX + (detW - 6) / 2 + 6, y0 + 144, (detW - 6) / 2, 20, "Retirer le surnom", Ui.RED, () -> {
            nickField.setText("");
            saveNick();
        }));
        btns.add(new Btn(x0 + 14, y0 + H - 32, 120, 20, "Carnet", Ui.AQUA, () -> {
            if (client != null) client.setScreen(new ContactsScreen());
        }));
        btns.add(new Btn(x0 + W - 14 - 120, y0 + H - 32, 120, 20, "Retour", Ui.ACCENT, () -> {
            if (client != null) client.setScreen(new NotifCenterScreen());
        }));

        ClientPlayNetworking.send(new Net.RequestContacts());
        refreshList();
    }

    private Net.ContactEntry current() {
        if (selected == null) return null;
        for (Net.ContactEntry e : ClientRp.contacts) if (e.id().equals(selected)) return e;
        return null;
    }

    private void select(UUID id) {
        selected = id;
        Net.ContactEntry e = current();
        nickField.setText(e == null ? "" : e.nick());
    }

    /** Reconstruit la liste filtrée (appelé à chaque mise à jour du carnet). */
    public void refreshList() {
        String q = fold(search == null ? "" : search.getText());
        shown = new ArrayList<>();
        for (Net.ContactEntry e : ClientRp.contacts) {
            if (q.isEmpty() || fold(e.name()).contains(q) || fold(e.nick()).contains(q)) shown.add(e);
        }
        if (selected != null && shown.stream().noneMatch(e -> e.id().equals(selected))) selected = null;
        if (selected == null && !shown.isEmpty() && nickField != null) select(shown.get(0).id());
        clampScroll();
    }

    private void saveNick() {
        if (selected == null) return;
        ClientPlayNetworking.send(new Net.SetNick(selected, nickField.getText()));
    }

    private int rows() {
        return listH / ROW_H;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, shown.size() - rows()), scroll));
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 100) {
            refreshTicks = 0;
            ClientPlayNetworking.send(new Net.RequestContacts());
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "SURNOMS", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;

        c.fill(listX, listY, listX + listW, listY + listH, 0x60000000);
        c.drawBorder(listX, listY, listW, listH, Ui.BORDER);
        if (shown.isEmpty()) {
            c.drawText(tr, ClientRp.contacts.isEmpty() ? "Personne ne s'est encore présenté." : "Aucun résultat.", listX + 8, listY + 8, Ui.MUTED, false);
        }
        c.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < rows() + 1 && scroll + i < shown.size(); i++) {
            Net.ContactEntry e = shown.get(scroll + i);
            int ry = listY + i * ROW_H;
            boolean sel = e.id().equals(selected);
            boolean hover = Ui.inside(mx, my, listX, ry, listW, ROW_H) && my < listY + listH;
            if (sel) c.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0x40C5AE8B);
            else if (hover) c.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, Ui.BTN_HOVER);
            c.fill(listX + 1, ry, listX + 3, ry + ROW_H, e.nick().isEmpty() ? Ui.MUTED : Ui.AQUA);
            c.drawText(tr, tr.trimToWidth(e.nick().isEmpty() ? e.name() : e.nick(), listW - 16), listX + 8, ry + 3, sel ? Ui.ACCENT : Ui.TEXT, false);
            c.drawText(tr, tr.trimToWidth(e.nick().isEmpty() ? "pas de surnom" : e.name() + (e.alias() ? " (pseudonyme)" : ""), listW - 16),
                listX + 8, ry + 13, Ui.MUTED, false);
        }
        c.disableScissor();

        Net.ContactEntry e = current();
        if (e == null) {
            c.drawText(tr, "Sélectionnez une personne.", detX, listY + 6, Ui.MUTED, false);
            nickField.visible = false;
        } else {
            nickField.visible = true;
            c.drawText(tr, "Nom sous lequel elle s'est présentée :", detX, listY, Ui.MUTED, false);
            c.getMatrices().push();
            c.getMatrices().translate(detX, listY + 12, 0);
            c.getMatrices().scale(1.5f, 1.5f, 1f);
            c.drawText(tr, tr.trimToWidth(e.name(), (int) (detW / 1.5f)), 0, 0, Ui.TEXT, false);
            c.getMatrices().pop();
            c.drawText(tr, e.alias() ? "C'est un pseudonyme : son vrai nom ne vous est pas connu." : "C'est son vrai nom.", detX, listY + 34,
                e.alias() ? Ui.YELLOW : Ui.GREEN, false);
            c.drawText(tr, "Surnom (affiché en jeu à la place de ce nom) :", detX, y0 + 106, Ui.MUTED, false);
            c.drawText(tr, "Le nom d'origine reste toujours consultable ici.", detX, y0 + 172, Ui.MUTED, false);
            if (!e.nick().isEmpty()) c.drawText(tr, "Actuellement affiché : " + e.nick(), detX, y0 + 184, Ui.AQUA, false);
        }
        for (Btn b : btns) {
            boolean en = b.enabled && (e != null || !(b.label.equals("Enregistrer") || b.label.startsWith("Retirer")));
            Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, en, b.accent);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : btns) {
                boolean needsSel = b.label.equals("Enregistrer") || b.label.startsWith("Retirer");
                if (b.enabled && (!needsSel || selected != null) && Ui.inside(mx, my, b.x, b.y, b.w, b.h)) {
                    b.action.run();
                    return true;
                }
            }
            if (Ui.inside(mx, my, listX, listY, listW, listH)) {
                int idx = scroll + (int) ((my - listY) / ROW_H);
                if (idx >= 0 && idx < shown.size()) select(shown.get(idx).id());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (getFocused() == nickField && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            saveNick();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (Ui.inside(mx, my, listX, listY, listW, listH)) {
            scroll -= (int) Math.signum(v);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
