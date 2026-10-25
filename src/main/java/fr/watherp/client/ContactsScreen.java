package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Carnet de contacts (une touche) : tous ceux que l'on connaît, avec leur nom ou pseudonyme, ce qu'ils savent de nous,
 * et des notes PRIVÉES (stockées côté serveur, visibles de vous seul, effacées à la fin de la partie).
 */
public class ContactsScreen extends Screen {
    private static final int W = 540, H = 330, ROW_H = 22, NOTE_MAX = 500;

    private final List<Btn> btns = new ArrayList<>();
    private TextFieldWidget search;
    private EditBoxWidget noteBox;
    private List<Net.ContactEntry> shown = new ArrayList<>();
    private UUID selected;
    private int scroll = 0;
    private long openTime;
    private int x0, y0, listX, listY, listW, listH, detX, detW, noteY, noteH;
    /** La note affichée a été modifiée et n'est pas encore envoyée au serveur. */
    private boolean noteDirty = false;
    private long noteEditMs = 0;
    /** Évite de compter comme une modification le texte que l'on place soi-même dans la zone de note. */
    private boolean loading = false;

    public ContactsScreen() {
        super(Text.literal("Carnet de contacts"));
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
        noteH = 92;
        noteY = y0 + H - 44 - noteH;

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

        String keepNote = noteBox == null ? null : noteBox.getText();
        noteBox = new EditBoxWidget(textRenderer, detX, noteY, detW, noteH,
            Text.literal("Notes privées (vous seul les voyez)…"), Text.literal("Notes"));
        noteBox.setMaxLength(NOTE_MAX);
        noteBox.setChangeListener(t -> {
            if (loading) return;
            noteDirty = true;
            noteEditMs = System.currentTimeMillis();
        });
        addDrawableChild(noteBox);
        if (keepNote != null) setNoteText(keepNote);

        btns.clear();
        btns.add(new Btn(x0 + 14, y0 + H - 32, 120, 20, "Débrief", Ui.YELLOW, () -> {
            flushNote();
            close();
            WatheRPClient.requestDebrief();
        }));
        btns.add(new Btn(x0 + 14 + 120 + 8, y0 + H - 32, 120, 20, "Surnoms", Ui.AQUA, () -> {
            flushNote();
            if (client != null) client.setScreen(new NicknamesScreen());
        }));
        btns.add(new Btn(x0 + W - 14 - 120, y0 + H - 32, 120, 20, "Fermer", Ui.ACCENT, this::close));

        ClientPlayNetworking.send(new Net.RequestContacts());
        refreshList();
    }

    private void setNoteText(String t) {
        loading = true;
        noteBox.setText(t);
        loading = false;
    }

    /** Reconstruit la liste filtrée à partir de ClientRp.contacts (appelé à la réception d'une mise à jour). */
    public void refreshList() {
        String q = fold(search == null ? "" : search.getText());
        shown = new ArrayList<>();
        for (Net.ContactEntry e : ClientRp.contacts) {
            if (q.isEmpty() || fold(e.name()).contains(q) || fold(e.nick()).contains(q)) shown.add(e);
        }
        if (selected != null && shown.stream().noneMatch(e -> e.id().equals(selected))) selected = null;
        if (selected == null && !shown.isEmpty()) select(shown.get(0).id());
        if (selected == null) {
            noteBox.visible = false;
        } else {
            // une mise à jour du serveur ne doit pas écraser ce que l'on est en train de taper
            if (!noteDirty) {
                Net.ContactEntry cur = current();
                if (cur != null && !cur.note().equals(noteBox.getText())) setNoteText(cur.note());
            }
            noteBox.visible = true;
        }
        clampScroll();
    }

    private Net.ContactEntry current() {
        if (selected == null) return null;
        for (Net.ContactEntry e : ClientRp.contacts) if (e.id().equals(selected)) return e;
        return null;
    }

    private void select(UUID id) {
        if (id.equals(selected)) return;
        flushNote();
        selected = id;
        Net.ContactEntry e = current();
        setNoteText(e == null ? "" : e.note());
        noteDirty = false;
        if (noteBox != null) noteBox.visible = true;
    }

    /** Envoie la note au serveur si elle a changé. */
    private void flushNote() {
        if (!noteDirty || selected == null) return;
        noteDirty = false;
        String text = noteBox.getText();
        ClientPlayNetworking.send(new Net.ContactNote(selected, text));
        // copie locale immédiate (le serveur renverra la liste à jour)
        for (int i = 0; i < ClientRp.contacts.size(); i++) {
            Net.ContactEntry e = ClientRp.contacts.get(i);
            if (e.id().equals(selected)) {
                ClientRp.contacts.set(i, new Net.ContactEntry(e.id(), e.name(), e.alias(), e.mineToThem(), e.mineAlias(), e.aka(), text, e.online(), e.nick()));
                break;
            }
        }
    }

    private int refreshTicks = 0;

    @Override
    public void tick() {
        super.tick();
        if (noteDirty && System.currentTimeMillis() - noteEditMs > 900) flushNote();
        // nouvelles présentations / statuts en ligne : on redemande la liste toutes les 5 s tant que le carnet est ouvert
        if (++refreshTicks >= 100) {
            refreshTicks = 0;
            ClientPlayNetworking.send(new Net.RequestContacts());
        }
    }

    @Override
    public void removed() {
        flushNote();
        super.removed();
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
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "CARNET DE CONTACTS", anim());
    }

    private void line(DrawContext c, String label, String value, int y, int color) {
        c.drawText(textRenderer, label, detX, y, Ui.MUTED, false);
        int lw = textRenderer.getWidth(label) + 4;
        c.drawText(textRenderer, textRenderer.trimToWidth(value, detW - lw), detX + lw, y, color, false);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;

        // en-tête : qui je suis
        String me = ClientRp.confirmed ? "Vous : " + ClientRp.own : "Vous n'avez pas encore de nom RP pour cette partie";
        c.drawText(tr, tr.trimToWidth(me, W - 28 - 120), x0 + W - 14 - tr.getWidth(tr.trimToWidth(me, W - 28 - 120)), y0 + 14, Ui.MUTED, false);
        c.drawText(tr, ClientRp.contacts.size() + " contact" + (ClientRp.contacts.size() > 1 ? "s" : ""), x0 + W - 14 - 100, y0 + 40, Ui.MUTED, false);

        // liste
        c.fill(listX, listY, listX + listW, listY + listH, 0x60000000);
        c.drawBorder(listX, listY, listW, listH, Ui.BORDER);
        if (shown.isEmpty()) {
            c.drawText(tr, ClientRp.contacts.isEmpty() ? "Personne pour l'instant." : "Aucun résultat.", listX + 8, listY + 8, Ui.MUTED, false);
            if (ClientRp.contacts.isEmpty()) {
                c.drawText(tr, "Présentez-vous (clic droit sur", listX + 8, listY + 22, Ui.MUTED, false);
                c.drawText(tr, "un joueur) pour remplir le carnet.", listX + 8, listY + 32, Ui.MUTED, false);
            }
        }
        c.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < rows() + 1 && scroll + i < shown.size(); i++) {
            Net.ContactEntry e = shown.get(scroll + i);
            int ry = listY + i * ROW_H;
            boolean sel = e.id().equals(selected);
            boolean hover = Ui.inside(mx, my, listX, ry, listW, ROW_H) && my < listY + listH;
            if (sel) c.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0x40C5AE8B);
            else if (hover) c.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, Ui.BTN_HOVER);
            c.fill(listX + 1, ry, listX + 3, ry + ROW_H, e.alias() ? Ui.YELLOW : Ui.GREEN);
            c.fill(listX + listW - 12, ry + 8, listX + listW - 6, ry + 14, e.online() ? Ui.GREEN : 0xFF55555C);
            String label = e.nick().isEmpty() ? e.name() : e.nick();
            c.drawText(tr, tr.trimToWidth(label, listW - 30), listX + 8, ry + 3, sel ? Ui.ACCENT : Ui.TEXT, false);
            c.drawText(tr, tr.trimToWidth(e.nick().isEmpty() ? (e.alias() ? "pseudonyme" : "vrai nom")
                : e.name() + (e.alias() ? " (pseudonyme)" : ""), listW - 30), listX + 8, ry + 13, Ui.MUTED, false);
        }
        c.disableScissor();

        // détails
        Net.ContactEntry e = current();
        if (e == null) {
            c.drawText(tr, "Sélectionnez un contact.", detX, listY + 6, Ui.MUTED, false);
        } else {
            c.getMatrices().push();
            c.getMatrices().translate(detX, listY, 0);
            c.getMatrices().scale(1.5f, 1.5f, 1f);
            c.drawText(tr, tr.trimToWidth(e.nick().isEmpty() ? e.name() : e.nick(), (int) (detW / 1.5f)), 0, 0, Ui.TEXT, false);
            c.getMatrices().pop();
            int y = listY + 20;
            if (!e.nick().isEmpty()) {
                line(c, "Nom connu :", e.name() + (e.alias() ? " (pseudonyme)" : ""), y, Ui.AQUA);
                y += 12;
            }
            line(c, "Vous le connaissez comme :", e.alias() ? "pseudonyme (vrai nom inconnu)" : "sous son vrai nom", y, e.alias() ? Ui.YELLOW : Ui.GREEN);
            y += 12;
            line(c, "Il vous connaît comme :", e.mineToThem().isEmpty() ? "pas encore présenté en retour"
                : "« " + e.mineToThem() + " »" + (e.mineAlias() ? " (votre pseudonyme)" : " (votre vrai nom)"), y, e.mineToThem().isEmpty() ? Ui.MUTED : Ui.TEXT);
            y += 12;
            if (!e.aka().isEmpty()) {
                line(c, "A aussi utilisé :", e.aka(), y, Ui.AQUA);
                y += 12;
            }
            line(c, "Statut :", e.online() ? "en ligne" : "hors ligne", y, e.online() ? Ui.GREEN : Ui.MUTED);
            c.drawText(tr, "Notes privées (" + noteBox.getText().length() + "/" + NOTE_MAX + ") — enregistrées automatiquement", detX, noteY - 11, Ui.MUTED, false);
            // courte indication si le nom est un pseudonyme : la personne peut se révéler plus tard
            if (e.alias()) {
                List<OrderedText> hint = tr.wrapLines(Text.literal("Cette personne s'est présentée sous un pseudonyme : si elle se révèle, son vrai nom remplacera celui-ci."), detW);
                int hy = y + 14;
                for (OrderedText t : hint) {
                    c.drawText(tr, t, detX, hy, Ui.MUTED, false);
                    hy += 10;
                }
            }
        }
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
            if (Ui.inside(mx, my, listX, listY, listW, listH)) {
                int idx = scroll + (int) ((my - listY) / ROW_H);
                if (idx >= 0 && idx < shown.size()) select(shown.get(idx).id());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
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

    /** La lettre de la touche qui vient d'ouvrir le carnet ne doit pas se retrouver dans un champ. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (System.currentTimeMillis() - openTime < 150) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
