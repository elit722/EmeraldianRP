package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Messagerie : liste des chats à gauche, conversation à droite. Tout le monde peut lire / écrire dans ses chats
 * (tant que le staff ne les a pas fermés) ; les helpers ont en plus : nouveau chat avec un joueur, nouveau groupe,
 * ajout de membres, fermeture, et le chat helper.
 */
public class ChatsScreen extends Screen {
    private static final int PANEL_W = 480, PANEL_H = 338, ROW_H = 24, LIST_H = 206;
    private static int lastSelected = -1;

    private int selected;
    private int listScroll = 0;
    private int msgScroll = 0; // lignes remontées depuis le bas
    private TextFieldWidget input;
    private String inputText = "";
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;
    private long closeArmedUntil = 0;
    private long deleteArmedUntil = 0;

    // cache de mise en page des messages
    private List<Line> laidOut = new ArrayList<>();
    private int laidKey = Integer.MIN_VALUE;

    private record Line(OrderedText text) {}

    public ChatsScreen(int focus) {
        super(Text.literal("Messagerie"));
        this.selected = focus > 0 ? focus : lastSelected;
    }

    // ---------------------------------------------------------------- état

    private Net.ChatSummary current() {
        for (Net.ChatSummary c : ClientRp.chats) if (c.id() == selected) return c;
        return null;
    }

    private void select(int id) {
        selected = id;
        lastSelected = id;
        ClientRp.viewingChat = id;
        ClientRp.unread.remove(id);
        msgScroll = 0;
        laidKey = Integer.MIN_VALUE;
        closeArmedUntil = 0;
        deleteArmedUntil = 0;
        ClientPlayNetworking.send(new Net.ChatQuery(id));
        rebuild();
    }

    /** Appelé par les récepteurs réseau quand la liste, l'historique ou une ligne a changé. */
    public void onUpdate() {
        if (selected > 0 && current() == null) {
            selected = -1;
            ClientRp.viewingChat = -1;
        }
        if (selected == -1 && !ClientRp.chats.isEmpty()) {
            // premier affichage : on ouvre le plus récent (la liste est déjà triée par le serveur)
            Net.ChatSummary first = ClientRp.chats.get(0);
            for (Net.ChatSummary c : ClientRp.chats) if (!c.closed() && !c.archived()) { first = c; break; }
            select(first.id());
            return;
        }
        if (selected > 0) ClientRp.unread.remove(selected);
        rebuild();
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - PANEL_W) / 2;
        y0 = (height - PANEL_H) / 2;
        input = new TextFieldWidget(textRenderer, x0 + 172, y0 + 256, PANEL_W - 172 - 14 - 64, 20, Text.literal("Message"));
        input.setMaxLength(256);
        input.setPlaceholder(Text.literal("Écrire un message…"));
        input.setText(inputText);
        input.setChangedListener(t -> inputText = t);
        addDrawableChild(input);
        setInitialFocus(input);
        ClientPlayNetworking.send(new Net.ChatQuery(Math.max(0, selected)));
        if (selected > 0) {
            ClientRp.viewingChat = selected;
            ClientRp.unread.remove(selected);
        }
        rebuild();
    }

    private void rebuild() {
        btns.clear();
        if (textRenderer == null) return;
        Net.ChatSummary cur = current();
        boolean open = cur != null && !cur.closed() && !cur.archived();
        btns.add(new Btn(x0 + PANEL_W - 14 - 60, y0 + 256, 60, 20, "Envoyer", Ui.GREEN, this::send).enabled(open));
        // archiver (consultation seule) / supprimer (définitif) SON exemplaire du chat ; ne touche pas aux autres membres
        boolean manageable = cur != null && !"helper".equals(cur.kind());
        boolean arch = cur != null && cur.archived();
        int dy = ClientRp.helper ? y0 + 306 : y0 + 284, dh = ClientRp.helper ? 16 : 20;
        btns.add(new Btn(x0 + 14, dy, 100, dh, arch ? "Désarchiver" : "Archiver", Ui.YELLOW, () -> {
            ClientPlayNetworking.send(new Net.ChatManage(selected, arch ? "unarchive" : "archive"));
        }).enabled(manageable && !cur.closed()));
        btns.add(new Btn(x0 + 14 + 104, dy, 100, dh, System.currentTimeMillis() < deleteArmedUntil ? "Confirmer ?" : "Supprimer", Ui.RED, () -> {
            if (System.currentTimeMillis() < deleteArmedUntil) {
                ClientPlayNetworking.send(new Net.ChatManage(selected, "delete"));
                ClientRp.removeChatLocal(selected);
                deleteArmedUntil = 0;
                onUpdate(); // sélectionne un autre chat tout de suite
                return;
            }
            deleteArmedUntil = System.currentTimeMillis() + 3000;
            rebuild();
        }).enabled(manageable));
        if (ClientRp.helper) {
            int bx = x0 + 14, by = y0 + 284, bw = 108;
            btns.add(new Btn(bx, by, bw, 20, "Nouveau chat", Ui.AQUA, () -> pickPlayer("Nouveau chat",
                id -> ClientPlayNetworking.send(new Net.ChatOpenWith(id)))));
            btns.add(new Btn(bx + bw + 4, by, bw, 20, "Nouveau groupe", Ui.AQUA, () -> {
                if (client != null) client.setScreen(new PromptScreen(this, "Nouveau groupe", "Nom du groupe de chat (2 à 32 caractères)",
                    "Nom du groupe", 32, t -> ClientPlayNetworking.send(new Net.ChatCreate(t))));
            }));
            boolean canManage = open && !"helper".equals(cur.kind());
            btns.add(new Btn(bx + 2 * (bw + 4), by, bw, 20, "Ajouter…", Ui.ACCENT, () -> pickPlayer("Ajouter au chat",
                id -> ClientPlayNetworking.send(new Net.ChatAdd(selected, id)))).enabled(canManage));
            boolean armed = System.currentTimeMillis() < closeArmedUntil;
            btns.add(new Btn(bx + 3 * (bw + 4), by, bw, 20, armed ? "Confirmer ?" : "Fermer le chat", Ui.RED, () -> {
                if (System.currentTimeMillis() < closeArmedUntil) {
                    ClientPlayNetworking.send(new Net.ChatClose(selected));
                    closeArmedUntil = 0;
                } else {
                    closeArmedUntil = System.currentTimeMillis() + 3000;
                }
                rebuild();
            }).enabled(canManage));
            // retour au panneau d'assistance
            btns.add(new Btn(x0 + PANEL_W - 14 - 100, y0 + 12, 100, 18, "Panneau", Ui.ACCENT, () -> {
                ClientRp.wantPanel = true;
                ClientPlayNetworking.send(new Net.RequestTickets());
            }));
        }
    }

    private void pickPlayer(String title, java.util.function.Consumer<java.util.UUID> then) {
        ClientRp.pendingPlayers = list -> {
            if (client != null) client.setScreen(new PlayerPickerScreen(this, title, list, then));
        };
        ClientPlayNetworking.send(new Net.RequestPlayers());
    }

    private void send() {
        String t = input.getText().trim();
        Net.ChatSummary cur = current();
        if (t.isEmpty() || cur == null || cur.closed()) return;
        ClientPlayNetworking.send(new Net.ChatSend(selected, t));
        input.setText("");
        msgScroll = 0;
    }

    @Override
    public void removed() {
        ClientRp.viewingChat = -1;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if ((closeArmedUntil != 0 && now > closeArmedUntil) || (deleteArmedUntil != 0 && now > deleteArmedUntil)) {
            if (now > closeArmedUntil) closeArmedUntil = 0;
            if (now > deleteArmedUntil) deleteArmedUntil = 0;
            rebuild();
        }
    }

    // ---------------------------------------------------------------- rendu

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xE6050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, PANEL_W, PANEL_H, "MESSAGERIE", anim());
    }

    private void layout(int w) {
        List<Net.ChatLineData> hist = ClientRp.history.getOrDefault(selected, List.of());
        int key = selected * 31 + hist.size() * 7 + w;
        if (key == laidKey) return;
        laidKey = key;
        laidOut = new ArrayList<>();
        for (Net.ChatLineData l : hist) {
            Text t;
            if (l.system()) {
                t = Text.literal("— " + l.text()).formatted(Formatting.GRAY, Formatting.ITALIC);
            } else {
                Formatting nameColor = l.mine() ? Formatting.GOLD : (l.staff() ? Formatting.GREEN : Formatting.AQUA);
                t = Text.literal((l.staff() && !l.mine() ? "[Staff] " : "") + l.label()).formatted(nameColor, Formatting.BOLD)
                    .append(Text.literal(" : " + l.text()).formatted(Formatting.WHITE));
            }
            for (OrderedText o : textRenderer.wrapLines(t, w)) laidOut.add(new Line(o));
        }
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        Net.ChatSummary cur = current();

        // ---- liste des chats
        int lx = x0 + 14, ly = y0 + 44, lw = 150;
        c.fill(lx, ly, lx + lw, ly + LIST_H, 0x60000000);
        c.drawBorder(lx, ly, lw, LIST_H, Ui.BORDER);
        if (ClientRp.chats.isEmpty()) c.drawText(tr, "Aucun chat.", lx + 8, ly + 8, Ui.MUTED, false);
        c.enableScissor(lx, ly, lx + lw, ly + LIST_H);
        int rows = LIST_H / ROW_H;
        for (int i = 0; i < rows + 1 && listScroll + i < ClientRp.chats.size(); i++) {
            Net.ChatSummary s = ClientRp.chats.get(listScroll + i);
            int ry = ly + i * ROW_H;
            boolean sel = s.id() == selected;
            boolean hover = Ui.inside(mx, my, lx, ry, lw, ROW_H) && my < ly + LIST_H;
            if (sel || hover) c.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H, sel ? Ui.BTN_HOVER : Ui.BTN);
            int bar = s.closed() || s.archived() ? Ui.MUTED : ("helper".equals(s.kind()) ? Ui.GREEN : ("group".equals(s.kind()) ? Ui.YELLOW : Ui.AQUA));
            c.fill(lx + 1, ry, lx + 3, ry + ROW_H, bar);
            int un = ClientRp.unread.getOrDefault(s.id(), 0);
            String badge = un > 0 ? " (" + un + ")" : "";
            String t = (s.closed() ? "[fermé] " : s.archived() ? "[archivé] " : "") + s.title();
            c.drawText(tr, tr.trimToWidth(t, lw - 14 - tr.getWidth(badge)), lx + 8, ry + 8, sel ? Ui.ACCENT : (s.closed() || s.archived() ? Ui.MUTED : Ui.TEXT), false);
            if (un > 0) c.drawText(tr, badge, lx + lw - 6 - tr.getWidth(badge), ry + 8, Ui.RED, false);
        }
        c.disableScissor();

        // ---- conversation
        int mxl = x0 + 172, mw = PANEL_W - 172 - 14;
        if (cur == null) {
            c.drawText(tr, "Sélectionnez un chat.", mxl + 4, y0 + 48, Ui.MUTED, false);
        } else {
            String members = "helper".equals(cur.kind()) ? "Tous les helpers" : String.join(", ", cur.members());
            c.drawText(tr, tr.trimToWidth(cur.title(), mw), mxl, y0 + 44, Ui.ACCENT, false);
            c.drawText(tr, tr.trimToWidth(members, mw), mxl, y0 + 55, Ui.MUTED, false);
            int my0 = y0 + 68, mh = 182;
            c.fill(mxl, my0, mxl + mw, my0 + mh, 0x60000000);
            c.drawBorder(mxl, my0, mw, mh, Ui.BORDER);
            layout(mw - 12);
            int visible = (mh - 6) / 10;
            int total = laidOut.size();
            msgScroll = Math.max(0, Math.min(Math.max(0, total - visible), msgScroll));
            int end = total - msgScroll;
            int start = Math.max(0, end - visible);
            int yy = my0 + 4 + (visible - (end - start)) * 10;
            for (int i = start; i < end; i++) {
                c.drawText(tr, laidOut.get(i).text(), mxl + 6, yy, Ui.TEXT, false);
                yy += 10;
            }
            if (msgScroll > 0) c.drawText(tr, "↓ " + msgScroll, mxl + mw - 8 - tr.getWidth("↓ " + msgScroll), my0 + mh - 11, Ui.YELLOW, false);
            if (cur.closed()) c.drawText(tr, "Ce chat est fermé (lecture seule).", mxl, y0 + 280 - 2, Ui.YELLOW, false);
            else if (cur.archived()) c.drawText(tr, "Chat archivé (lecture seule) : « Désarchiver » pour écrire.", mxl, y0 + 280 - 2, Ui.YELLOW, false);
        }

        for (Btn b : btns) Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, b.enabled, b.accent);
        c.drawText(tr, "Entrée : envoyer · molette : défiler · Échap : fermer", x0 + 14, y0 + PANEL_H - 12, Ui.MUTED, false);
    }

    // ---------------------------------------------------------------- entrées

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : btns) {
                if (b.enabled && Ui.inside(mx, my, b.x, b.y, b.w, b.h)) {
                    b.action.run();
                    return true;
                }
            }
            int lx = x0 + 14, ly = y0 + 44, lw = 150;
            if (Ui.inside(mx, my, lx, ly, lw, LIST_H)) {
                int idx = listScroll + (int) ((my - ly) / ROW_H);
                if (idx >= 0 && idx < ClientRp.chats.size()) select(ClientRp.chats.get(idx).id());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (mx < x0 + 168) {
            listScroll = Math.max(0, Math.min(Math.max(0, ClientRp.chats.size() - LIST_H / ROW_H), listScroll - (int) Math.signum(v)));
        } else {
            msgScroll += (int) Math.signum(v) * 3;
            if (msgScroll < 0) msgScroll = 0;
        }
        return true;
    }

    /** La lettre de la touche Messagerie qui vient d'ouvrir le menu ne doit pas se retrouver dans le champ. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (System.currentTimeMillis() - openTime < 150) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == input) {
            send();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
