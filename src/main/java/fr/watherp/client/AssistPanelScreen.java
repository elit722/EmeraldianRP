package fr.watherp.client;

import fr.watherp.StaffRecord;
import fr.watherp.Ticket;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Panneau d'assistance staff (même style que le menu admin de Wathe).
 * Deux vues : « Tickets » (demandes d'aide, filtrables et triables par type) et « Dossiers » (avertissements et notes).
 */
public class AssistPanelScreen extends Screen {
    private static final int PANEL_W = 480, PANEL_H = 350, ROW_H = 22, LIST_H = 154;
    private static final int HISTORY_MAX = 30;
    private static final String[] SORTS = {"récent", "ancien", "type"};

    private List<Ticket> all;

    // vue Tickets
    private boolean recordsMode = false;
    private int tab = 0; // 0 ouverts, 1 pris en charge, 2 clos
    private int scroll = 0;
    private int selectedId = -1;
    private String typeFilter = ""; // "" = tous les types
    private int sortMode = 0;       // index dans SORTS

    // vue Dossiers
    private String kindFilter = ""; // "", warn, note
    private int rScroll = 0;
    private int rSelected = -1;
    private String searchText = "";
    private TextFieldWidget search;
    private boolean searchAttached = false;

    // entrée de commande (focalisée dès l'ouverture : on peut écrire tout de suite)
    private static final List<String> HISTORY = ClientRp.cmdHistory;
    /** Suppression en deux clics : id du ticket « armé » et heure limite. */
    private int deleteArmedId = -1;
    private long deleteArmedUntil = 0;
    private TextFieldWidget command;
    private String commandText = "";
    private int histIdx = -1;
    private String histDraft = "";

    private long openTime;
    private int x0, y0, refresh, tabsEnd;
    private final List<Btn> btns = new ArrayList<>();

    public AssistPanelScreen(List<Ticket> tickets) {
        super(Text.literal("Assistance"));
        this.all = tickets;
    }

    public void update(List<Ticket> tickets) {
        this.all = tickets;
        if (selectedId != -1 && find(selectedId) == null) selectedId = -1;
        if (!typeFilter.isEmpty() && !types().contains(typeFilter)) typeFilter = "";
        clampScroll();
        rebuild();
    }

    /** Appelé quand le serveur envoie une nouvelle liste de dossiers (ClientRp.records est déjà à jour). */
    public void onRecordsUpdated() {
        if (rSelected != -1 && findRecord(rSelected) == null) rSelected = -1;
        clampScroll();
        rebuild();
    }

    // ---------------------------------------------------------------- données

    private Ticket find(int id) {
        for (Ticket t : all) if (t.id == id) return t;
        return null;
    }

    private StaffRecord findRecord(int id) {
        for (StaffRecord r : ClientRp.records) if (r.id == id) return r;
        return null;
    }

    /** Types présents dans les tickets reçus (ordre alphabétique). */
    private List<String> types() {
        TreeSet<String> set = new TreeSet<>();
        for (Ticket t : all) if (!t.type.isEmpty()) set.add(t.type);
        return new ArrayList<>(set);
    }

    private boolean matchesType(Ticket t) {
        return typeFilter.isEmpty() || typeFilter.equals(t.type);
    }

    private List<Ticket> shown() {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : all) {
            boolean ok = switch (tab) {
                case 0 -> Ticket.OPEN.equals(t.status);
                case 1 -> Ticket.CLAIMED.equals(t.status);
                default -> Ticket.CLOSED.equals(t.status);
            };
            if (ok && matchesType(t)) out.add(t);
        }
        Comparator<Ticket> byRecent = Comparator.comparingLong((Ticket t) -> t.createdMs).reversed();
        switch (sortMode) {
            case 1 -> out.sort(Comparator.comparingLong((Ticket t) -> t.createdMs));
            case 2 -> out.sort(Comparator.comparing((Ticket t) -> t.type).thenComparing(byRecent));
            default -> out.sort(byRecent);
        }
        return out;
    }

    private int count(String status) {
        int n = 0;
        for (Ticket t : all) if (status.equals(t.status) && matchesType(t)) n++;
        return n;
    }

    private List<StaffRecord> shownRecords() {
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        List<StaffRecord> out = new ArrayList<>();
        for (StaffRecord r : ClientRp.records) {
            if (!kindFilter.isEmpty() && !kindFilter.equals(r.kind)) continue;
            if (!q.isEmpty() && !r.targetName.toLowerCase(Locale.ROOT).contains(q) && !r.targetRp.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(r);
        }
        out.sort(Comparator.comparingInt((StaffRecord r) -> r.id).reversed());
        return out;
    }

    private int rows() {
        return LIST_H / ROW_H;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, shown().size() - rows()), scroll));
        rScroll = Math.max(0, Math.min(Math.max(0, shownRecords().size() - rows()), rScroll));
    }

    private String[] tabLabels() {
        return new String[]{"Ouverts (" + count(Ticket.OPEN) + ")", "Pris en charge (" + count(Ticket.CLAIMED) + ")", "Clos (" + count(Ticket.CLOSED) + ")"};
    }

    // ---------------------------------------------------------------- construction

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - PANEL_W) / 2;
        y0 = (height - PANEL_H) / 2;
        search = new TextFieldWidget(textRenderer, x0 + 14 + 112, y0 + 39, 150, 20, Text.literal("Rechercher"));
        search.setMaxLength(32);
        search.setPlaceholder(Text.literal("Joueur / nom RP"));
        search.setText(searchText);
        search.setChangedListener(s -> {
            searchText = s;
            rScroll = 0;
        });
        searchAttached = false;

        command = new TextFieldWidget(textRenderer, x0 + 14, cmdY(), PANEL_W - 28, 20, Text.literal("Commande"));
        command.setMaxLength(256);
        command.setPlaceholder(Text.literal("Commande (le / est facultatif), ex. r warn Pseudo raison — Entrée pour envoyer"));
        command.setText(commandText);
        command.setChangedListener(t -> commandText = t);
        addDrawableChild(command);
        setInitialFocus(command);
        rebuild();
    }

    private int cmdY() {
        return y0 + 278;
    }

    private void focusCommand() {
        if (command != null) setFocused(command);
    }

    /** Pré-remplit la commande (ex. « /r warn Pseudo ») et donne le focus au champ : il ne reste plus qu'à taper la raison. */
    private void prefill(String text) {
        command.setText(text);
        focusCommand();
    }

    /** Envoie la commande tapée au serveur, comme si elle était écrite dans le chat. */
    private void sendCommand() {
        String raw = command.getText().trim();
        String cmd = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (cmd.isEmpty() || client == null || client.getNetworkHandler() == null) return;
        ClientRp.feedback.clear();
        ClientRp.feedback.add("> /" + cmd);
        client.getNetworkHandler().sendChatCommand(cmd);
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(cmd)) HISTORY.add(cmd);
        while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(0);
        histIdx = -1;
        command.setText("");
        refresh = 85; // rafraîchit tickets / dossiers peu après (un warn doit apparaître dans la liste)
    }

    private void history(int dir) {
        if (HISTORY.isEmpty()) return;
        if (histIdx == -1) {
            if (dir > 0) return;
            histDraft = command.getText();
            histIdx = HISTORY.size() - 1;
        } else {
            histIdx += dir;
            if (histIdx >= HISTORY.size()) {
                histIdx = -1;
                command.setText(histDraft);
                return;
            }
            if (histIdx < 0) histIdx = 0;
        }
        command.setText(HISTORY.get(histIdx));
    }

    private void act(String action) {
        if (selectedId < 0) return;
        ClientPlayNetworking.send(new Net.TicketAction(selectedId, action));
    }

    private void cycleType() {
        List<String> opts = new ArrayList<>();
        opts.add("");
        opts.addAll(types());
        int i = opts.indexOf(typeFilter);
        typeFilter = opts.get((i + 1) % opts.size());
        scroll = 0;
        selectedId = -1;
        rebuild();
    }

    private void cycleSort() {
        sortMode = (sortMode + 1) % SORTS.length;
        scroll = 0;
        rebuild();
    }

    private void cycleKind() {
        kindFilter = switch (kindFilter) {
            case "" -> StaffRecord.WARN;
            case StaffRecord.WARN -> StaffRecord.NOTE;
            default -> "";
        };
        rScroll = 0;
        rSelected = -1;
        rebuild();
    }

    private int[] modeRect(int i) {
        int w = 104;
        return new int[]{x0 + PANEL_W - 14 - 3 * w - 8 + i * (w + 4), y0 + 12, w, 18};
    }

    private void rebuild() {
        btns.clear();
        if (textRenderer == null) return;

        // champ de recherche : seulement dans la vue Dossiers
        if (recordsMode && !searchAttached) {
            addDrawableChild(search);
            searchAttached = true;
        } else if (!recordsMode && searchAttached) {
            remove(search);
            searchAttached = false;
        }

        int tabsW = 0;
        for (String n : tabLabels()) tabsW += textRenderer.getWidth(n) + 16 + 4;
        tabsEnd = x0 + 14 + tabsW;

        int px = x0 + 232, py = y0 + 66 + LIST_H - 40 + 4, pw = PANEL_W - 232 - 16;
        if (!recordsMode) {
            btns.add(new Btn(tabsEnd + 4, y0 + 40, 84, 18, "Type : " + (typeFilter.isEmpty() ? "tous" : typeFilter), Ui.ACCENT, this::cycleType));
            btns.add(new Btn(tabsEnd + 4 + 88, y0 + 40, 84, 18, "Tri : " + SORTS[sortMode], Ui.ACCENT, this::cycleSort));

            Ticket t = find(selectedId);
            boolean has = t != null;
            boolean closed = has && Ticket.CLOSED.equals(t.status);
            boolean anon = has && t.anonymous;
            int hw = pw / 2 - 3, rx = px + pw / 2 + 3, bh = 20, pitch = 22;
            btns.add(new Btn(px, py, hw, bh, "Prendre en charge", Ui.GREEN, () -> act("claim")).enabled(has && !closed));
            btns.add(new Btn(rx, py, hw, bh, "Se téléporter", Ui.AQUA, () -> act("tp")).enabled(has && !anon));
            btns.add(new Btn(px, py + pitch, hw, bh, "Chat privé", Ui.AQUA, () -> act("chat")).enabled(has));
            btns.add(new Btn(rx, py + pitch, hw, bh, "Désanonymiser", Ui.YELLOW, () -> act("deanon")).enabled(anon));
            if (closed) {
                btns.add(new Btn(px, py + 2 * pitch, hw, bh, "Rouvrir", Ui.YELLOW, () -> act("reopen")));
            } else {
                btns.add(new Btn(px, py + 2 * pitch, hw, bh, "Clore le ticket", Ui.RED, () -> act("close")).enabled(has));
            }
            boolean armed = has && deleteArmedId == t.id && System.currentTimeMillis() < deleteArmedUntil;
            btns.add(new Btn(rx, py + 2 * pitch, hw, bh, armed ? "Confirmer ?" : "Supprimer", Ui.RED, () -> {
                if (selectedId < 0) return;
                if (deleteArmedId == selectedId && System.currentTimeMillis() < deleteArmedUntil) {
                    deleteArmedId = -1;
                    act("delete");
                } else {
                    deleteArmedId = selectedId;
                    deleteArmedUntil = System.currentTimeMillis() + 3000;
                    rebuild();
                }
            }).enabled(has));
            // actions sur l'auteur, même s'il a quitté le serveur (le serveur retrouve le joueur par son pseudo)
            String who = has && !anon ? t.playerName : "";
            btns.add(new Btn(px, py + 3 * pitch, hw, bh, "Avertir…", Ui.RED, () -> prefill("/r warn " + who + " ")).enabled(has && !anon));
            btns.add(new Btn(rx, py + 3 * pitch, hw, bh, "Note…", Ui.AQUA, () -> prefill("/r note " + who + " ")).enabled(has && !anon));
        } else {
            String kl = kindFilter.isEmpty() ? "Tous" : (StaffRecord.WARN.equals(kindFilter) ? "Avertissements" : "Notes");
            btns.add(new Btn(x0 + 14, y0 + 39, 108, 20, "Type : " + kl, Ui.ACCENT, this::cycleKind));

            StaffRecord r = findRecord(rSelected);
            boolean has = r != null;
            btns.add(new Btn(px, py, pw / 2 - 3, 22, "Ce joueur", Ui.AQUA, () -> {
                if (r == null) return;
                search.setText(r.targetName);
                rScroll = 0;
            }).enabled(has));
            btns.add(new Btn(px + pw / 2 + 3, py, pw / 2 - 3, 22, "Tout afficher", Ui.YELLOW, () -> {
                search.setText("");
                kindFilter = "";
                rScroll = 0;
                rebuild();
            }));
            String who = has ? r.targetName : "";
            btns.add(new Btn(px, py + 28, pw / 2 - 3, 22, "Avertir…", Ui.RED, () -> prefill("/r warn " + who + " ")).enabled(has));
            btns.add(new Btn(px + pw / 2 + 3, py + 28, pw / 2 - 3, 22, "Note…", Ui.AQUA, () -> prefill("/r note " + who + " ")).enabled(has));
        }
    }

    private void setMode(boolean records) {
        if (recordsMode == records) return;
        recordsMode = records;
        rebuild();
        focusCommand();
    }

    @Override
    public void tick() {
        if (deleteArmedId != -1 && System.currentTimeMillis() > deleteArmedUntil) {
            deleteArmedId = -1;
            rebuild();
        }
        if (++refresh >= 100) {
            refresh = 0;
            ClientPlayNetworking.send(new Net.RequestTickets()); // renvoie aussi les dossiers
        }
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, PANEL_W, PANEL_H, "ASSISTANCE", anim());
    }

    // ---------------------------------------------------------------- rendu

    private static int statusColor(String s) {
        return switch (s) {
            case Ticket.CLAIMED -> Ui.YELLOW;
            case Ticket.CLOSED -> Ui.GREEN;
            default -> Ui.RED;
        };
    }

    private static int kindColor(String kind) {
        return StaffRecord.WARN.equals(kind) ? Ui.RED : Ui.AQUA;
    }

    private static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return "il y a " + s + " s";
        if (s < 3600) return "il y a " + (s / 60) + " min";
        if (s < 86400) return "il y a " + (s / 3600) + " h";
        return "il y a " + (s / 86400) + " j";
    }

    private void drawModeTab(DrawContext c, int i, String label, boolean sel, int mx, int my) {
        int[] r = modeRect(i);
        boolean hover = Ui.inside(mx, my, r[0], r[1], r[2], r[3]);
        c.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], sel ? Ui.BTN_HOVER : Ui.BTN);
        c.drawBorder(r[0], r[1], r[2], r[3], sel ? Ui.ACCENT : (hover ? Ui.MUTED : Ui.BORDER));
        c.drawText(textRenderer, label, r[0] + (r[2] - textRenderer.getWidth(label)) / 2, r[1] + 5, sel ? Ui.ACCENT : Ui.TEXT, false);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;

        drawModeTab(c, 0, "Tickets (" + openCount() + ")", !recordsMode, mx, my);
        drawModeTab(c, 1, "Dossiers (" + ClientRp.records.size() + ")", recordsMode, mx, my);
        drawModeTab(c, 2, "Chat intégré" + (ClientRp.totalUnread() > 0 ? " (" + ClientRp.totalUnread() + ")" : ""), false, mx, my);

        if (recordsMode) renderRecords(c, mx, my);
        else renderTickets(c, mx, my);

        for (Btn b : btns) Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);

        // retour des commandes (derniers messages du serveur reçus pendant que le panneau est ouvert)
        List<String> fb = new ArrayList<>(ClientRp.feedback);
        int from = Math.max(0, fb.size() - 3);
        for (int i = from; i < fb.size(); i++) {
            String line = fb.get(i);
            c.drawText(tr, tr.trimToWidth(line, PANEL_W - 28), x0 + 14, cmdY() + 24 + (i - from) * 10,
                line.startsWith("> ") ? Ui.ACCENT : Ui.TEXT, false);
        }
        String hint = recordsMode
            ? "Hors ligne accepté (pseudo ou nom RP) · \"Nom RP\" entre guillemets · ↑↓ historique"
            : "Entrée : envoyer · ↑↓ historique · /r active · /r innactive · alertes auto";
        c.drawText(tr, tr.trimToWidth(hint, PANEL_W - 28), x0 + 14, y0 + PANEL_H - 14, Ui.MUTED, false);
    }

    private int openCount() {
        int n = 0;
        for (Ticket t : all) if (Ticket.OPEN.equals(t.status)) n++;
        return n;
    }

    private void renderTickets(DrawContext c, int mx, int my) {
        var tr = textRenderer;

        // onglets de statut
        String[] names = tabLabels();
        int tx = x0 + 14;
        for (int i = 0; i < 3; i++) {
            int w = tr.getWidth(names[i]) + 16;
            boolean sel = tab == i;
            boolean hover = Ui.inside(mx, my, tx, y0 + 40, w, 18);
            c.fill(tx, y0 + 40, tx + w, y0 + 58, sel ? Ui.BTN_HOVER : Ui.BTN);
            c.drawBorder(tx, y0 + 40, w, 18, sel ? Ui.ACCENT : (hover ? Ui.MUTED : Ui.BORDER));
            c.drawText(tr, names[i], tx + 8, y0 + 45, sel ? Ui.ACCENT : Ui.TEXT, false);
            tx += w + 4;
        }

        // liste
        int lx = x0 + 14, ly = y0 + 66, lw = 208;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        List<Ticket> list = shown();
        if (list.isEmpty()) c.drawText(tr, "Aucune demande.", lx + 8, ly + 8, Ui.MUTED, false);
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        for (int i = 0; i < rows() + 1 && scroll + i < list.size(); i++) {
            Ticket t = list.get(scroll + i);
            int ry = ly + i * ROW_H;
            boolean sel = t.id == selectedId;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (sel || hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, sel ? Ui.BTN_HOVER : Ui.BTN);
            c.fill(lx + 1, ry, lx + 3, ry + ROW_H, statusColor(t.status));
            String who = t.anonymous ? "Anonyme" : (t.rpName.isEmpty() ? t.playerName : t.rpName);
            c.drawText(tr, tr.trimToWidth("#" + t.id + " · " + t.type + " · " + who + (t.wantChat ? " · chat" : ""), lw - 14), lx + 8, ry + 7, sel ? Ui.ACCENT : Ui.TEXT, false);
        }
        c.disableScissor();
        drawScrollbar(c, lx, ly, lw, list.size(), scroll);

        // détail
        int dx = x0 + 232, dy = y0 + 66, dw = PANEL_W - 232 - 16;
        c.fill(dx, dy, dx + dw, dy + LIST_H - 40, 0x60000000);
        c.drawBorder(dx, dy, dw, LIST_H - 40, Ui.BORDER);
        Ticket t = find(selectedId);
        if (t == null) {
            c.drawText(tr, "Sélectionnez une demande.", dx + 8, dy + 8, Ui.MUTED, false);
            return;
        }
        int yy = dy + 6;
        c.drawText(tr, "#" + t.id + " · " + t.type.toUpperCase(), dx + 8, yy, statusColor(t.status), false);
        yy += 12;
        if (t.anonymous) {
            c.drawText(tr, "Anonyme (identité masquée)", dx + 8, yy, Ui.YELLOW, false);
        } else {
            c.drawText(tr, tr.trimToWidth(t.playerName + (t.rpName.isEmpty() ? "" : "  (" + t.rpName + ")"), dw - 16), dx + 8, yy, Ui.TEXT, false);
        }
        yy += 12;
        c.drawText(tr, ago(t.createdMs) + (t.inGame ? " · en partie" : ""), dx + 8, yy, Ui.MUTED, false);
        yy += 12;
        if (t.anonymous) {
            c.drawText(tr, "Position masquée", dx + 8, yy, Ui.MUTED, false);
        } else {
            c.drawText(tr, tr.trimToWidth(String.format("%s  %.0f %.0f %.0f", t.world, t.x, t.y, t.z), dw - 16), dx + 8, yy, Ui.MUTED, false);
        }
        yy += 12;
        if (t.wantChat) {
            c.drawText(tr, t.chatId > 0 ? "Chat privé ouvert" : "Demande un chat privé", dx + 8, yy, Ui.AQUA, false);
            yy += 12;
        }
        yy += 2;
        if (!t.claimedBy.isEmpty()) {
            c.drawText(tr, "Pris par : " + t.claimedBy, dx + 8, yy, Ui.YELLOW, false);
            yy += 12;
        }
        drawWrapped(c, t.message.isEmpty() ? "(pas de message)" : t.message, dx + 8, yy, dw - 16, dy + LIST_H - 52);
    }

    private void renderRecords(DrawContext c, int mx, int my) {
        var tr = textRenderer;
        List<StaffRecord> list = shownRecords();
        String count = list.size() + " dossier(s)";
        c.drawText(tr, count, x0 + PANEL_W - 14 - tr.getWidth(count), y0 + 45, Ui.MUTED, false);

        int lx = x0 + 14, ly = y0 + 66, lw = 208;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        if (list.isEmpty()) c.drawText(tr, "Aucun dossier.", lx + 8, ly + 8, Ui.MUTED, false);
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        for (int i = 0; i < rows() + 1 && rScroll + i < list.size(); i++) {
            StaffRecord r = list.get(rScroll + i);
            int ry = ly + i * ROW_H;
            boolean sel = r.id == rSelected;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (sel || hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, sel ? Ui.BTN_HOVER : Ui.BTN);
            c.fill(lx + 1, ry, lx + 3, ry + ROW_H, kindColor(r.kind));
            String tag = StaffRecord.WARN.equals(r.kind) ? "avert." : "note";
            c.drawText(tr, tr.trimToWidth("#" + r.id + " · " + tag + " · " + r.targetName, lw - 14), lx + 8, ry + 7, sel ? Ui.ACCENT : Ui.TEXT, false);
        }
        c.disableScissor();
        drawScrollbar(c, lx, ly, lw, list.size(), rScroll);

        int dx = x0 + 232, dy = y0 + 66, dw = PANEL_W - 232 - 16;
        c.fill(dx, dy, dx + dw, dy + LIST_H - 40, 0x60000000);
        c.drawBorder(dx, dy, dw, LIST_H - 40, Ui.BORDER);
        StaffRecord r = findRecord(rSelected);
        if (r == null) {
            c.drawText(tr, "Sélectionnez un dossier.", dx + 8, dy + 8, Ui.MUTED, false);
            return;
        }
        int warns = 0, notes = 0;
        for (StaffRecord o : ClientRp.records) {
            if (!o.targetUuid.equals(r.targetUuid)) continue;
            if (StaffRecord.WARN.equals(o.kind)) warns++;
            else notes++;
        }
        int yy = dy + 6;
        c.drawText(tr, "#" + r.id + " · " + (StaffRecord.WARN.equals(r.kind) ? "AVERTISSEMENT" : "NOTE"), dx + 8, yy, kindColor(r.kind), false);
        yy += 12;
        c.drawText(tr, tr.trimToWidth(r.targetName + (r.targetRp.isEmpty() ? "" : "  (" + r.targetRp + ")"), dw - 16), dx + 8, yy, Ui.TEXT, false);
        yy += 12;
        c.drawText(tr, tr.trimToWidth("Par " + r.author + " · " + ago(r.createdMs), dw - 16), dx + 8, yy, Ui.MUTED, false);
        yy += 12;
        c.drawText(tr, "Ce joueur : " + warns + " avert. · " + notes + " note(s)", dx + 8, yy, Ui.MUTED, false);
        yy += 14;
        drawWrapped(c, r.text, dx + 8, yy, dw - 16, dy + LIST_H - 52);
    }

    private void drawScrollbar(DrawContext c, int lx, int ly, int lw, int size, int sc) {
        if (size <= rows()) return;
        int max = size - rows();
        int bh = Math.max(14, LIST_H * rows() / size);
        int by = ly + (LIST_H - bh) * sc / Math.max(1, max);
        c.fill(lx + lw - 3, by, lx + lw - 1, by + bh, Ui.ACCENT);
    }

    private void drawWrapped(DrawContext c, String text, int x, int y, int w, int maxY) {
        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(text), w);
        for (OrderedText l : lines) {
            if (y > maxY) break;
            c.drawText(textRenderer, l, x, y, Ui.TEXT, false);
            y += 10;
        }
    }

    // ---------------------------------------------------------------- entrées

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < 3; i++) {
                int[] r = modeRect(i);
                if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    if (i == 2) {
                        if (client != null) client.setScreen(new ChatsScreen(-1));
                    } else {
                        setMode(i == 1);
                    }
                    return true;
                }
            }
            // boutons (filtres, actions) : traités avant les listes
            for (Btn b : btns) {
                if (b.enabled && Ui.inside(mx, my, b.x, b.y, b.w, b.h)) {
                    b.action.run();
                    return true;
                }
            }
            int lx = x0 + 14, ly = y0 + 66, lw = 208;
            if (!recordsMode) {
                int tx = x0 + 14;
                String[] names = tabLabels();
                for (int i = 0; i < 3; i++) {
                    int w = textRenderer.getWidth(names[i]) + 16;
                    if (Ui.inside(mx, my, tx, y0 + 40, w, 18)) {
                        tab = i;
                        scroll = 0;
                        selectedId = -1;
                        rebuild();
                        return true;
                    }
                    tx += w + 4;
                }
                if (Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                    int idx = scroll + (int) ((my - ly) / ROW_H);
                    List<Ticket> list = shown();
                    if (idx >= 0 && idx < list.size()) {
                        selectedId = list.get(idx).id;
                        rebuild();
                    }
                    return true;
                }
            } else if (Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                int idx = rScroll + (int) ((my - ly) / ROW_H);
                List<StaffRecord> list = shownRecords();
                if (idx >= 0 && idx < list.size()) {
                    rSelected = list.get(idx).id;
                    rebuild();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** La lettre de la touche qui vient d'ouvrir le panneau (N par défaut) ne doit pas se retrouver dans le champ. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (System.currentTimeMillis() - openTime < 150) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (getFocused() == command) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                sendCommand();
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
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (recordsMode) rScroll -= (int) Math.signum(v);
        else scroll -= (int) Math.signum(v);
        clampScroll();
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
