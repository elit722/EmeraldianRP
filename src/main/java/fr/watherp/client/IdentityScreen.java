package fr.watherp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.watherp.Identity;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Papiers d'identité : une fiche d'identité sur papier, avec la « photo » (le skin du titulaire) à gauche et les
 * informations à droite. Le titulaire peut remplir / modifier les champs ; les autres joueurs voient la fiche en lecture seule.
 */
public class IdentityScreen extends Screen {
    private static final int W = 400, H = 262;

    // palette « papier »
    private static final int FRAME = 0xFF1F2F28, PAPER = 0xFFE6DCC3, PAPER_EDGE = 0xFF8C7E5E;
    private static final int INK = 0xFF2B2418, LABEL = 0xFF6E5F40, RED = 0xFFA82A2A, DOT = 0xFF7E7258;

    private static final String[] LABELS = {"Nom", "Âge", "Profession", "Provenance", "Destination", "Signe particulier"};
    private static final int[] MAXLEN = {Identity.NAME_MAX, Identity.AGE_MAX, Identity.JOB_MAX, Identity.FROM_MAX, Identity.DEST_MAX, Identity.MARK_MAX};

    private final Identity data;
    private final boolean canEdit;
    private final String shownBy;
    private boolean editing;
    private final String[] vals = new String[6];
    private final TextFieldWidget[] fields = new TextFieldWidget[6];
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0, rowsY, colX, valX, valW;

    public IdentityScreen(boolean editable, String shownBy, Identity data) {
        super(Text.literal("Papiers d'identité"));
        this.data = data;
        this.canEdit = editable;
        this.shownBy = shownBy == null ? "" : shownBy;
        // un papier vierge s'ouvre directement en modification, avec le nom RP du joueur en suggestion
        this.editing = editable && data.isBlank();
        vals[0] = data.isBlank() ? ClientRp.own : data.name();
        vals[1] = data.age();
        vals[2] = data.profession();
        vals[3] = data.provenance();
        vals[4] = data.destination();
        vals[5] = data.mark();
    }

    public boolean isEditing() {
        return editing;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        colX = x0 + 112;
        valX = colX + 100;
        valW = x0 + W - 18 - valX;
        rowsY = y0 + 84;
        for (int i = 0; i < 6; i++) fields[i] = null;
        if (editing) {
            for (int i = 0; i < 6; i++) {
                final int idx = i;
                TextFieldWidget f = new TextFieldWidget(textRenderer, valX, rowY(i) - 1, valW, 12, Text.literal(LABELS[i]));
                f.setDrawsBackground(false);
                f.setEditableColor(INK);
                f.setMaxLength(MAXLEN[i]);
                f.setText(vals[i]);
                f.setChangedListener(t -> vals[idx] = t);
                if (i == 0) f.setPlaceholder(Text.literal("Nom"));
                fields[i] = f;
                addDrawableChild(f);
            }
            setInitialFocus(fields[0]);
        }
        buildButtons();
    }

    private int rowY(int i) {
        return rowsY + i * 20;
    }

    private void buildButtons() {
        btns.clear();
        int by = y0 + H - 36, bh = 20;
        if (editing) {
            int bw = (W - 36 - 8) / 2;
            btns.add(new Btn(x0 + 18, by, bw, bh, "Enregistrer", Ui.GREEN, this::save));
            btns.add(new Btn(x0 + 18 + bw + 8, by, bw, bh, data.isBlank() ? "Annuler" : "Annuler les modifications", Ui.RED, () -> {
                if (data.isBlank()) close();
                else {
                    editing = false;
                    for (int i = 0; i < 6; i++) vals[i] = original(i);
                    clearAndInit();
                }
            }));
        } else if (canEdit) {
            int bw = (W - 36 - 16) / 3;
            btns.add(new Btn(x0 + 18, by, bw, bh, "Modifier", Ui.ACCENT, () -> {
                editing = true;
                clearAndInit();
            }));
            btns.add(new Btn(x0 + 18 + bw + 8, by, bw, bh, "Montrer aux proches", Ui.AQUA, () -> {
                ClientPlayNetworking.send(new Net.ShowIdentity());
                close();
            }));
            btns.add(new Btn(x0 + 18 + 2 * (bw + 8), by, bw, bh, "Fermer", Ui.RED, this::close));
        } else {
            btns.add(new Btn(x0 + 18, by, W - 36, bh, "Fermer", Ui.RED, this::close));
        }
    }

    private String original(int i) {
        return switch (i) {
            case 0 -> data.name();
            case 1 -> data.age();
            case 2 -> data.profession();
            case 3 -> data.provenance();
            case 4 -> data.destination();
            default -> data.mark();
        };
    }

    private void save() {
        if (vals[0] == null || vals[0].trim().isEmpty()) return;
        ClientPlayNetworking.send(new Net.SaveIdentity(vals[0], vals[1], vals[2], vals[3], vals[4], vals[5]));
        close();
    }

    // ---------------------------------------------------------------- rendu

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 250f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xB0050505, anim()));
        c.fill(x0, y0, x0 + W, y0 + H, Ui.alpha(FRAME, anim()));
        c.fill(x0 + 6, y0 + 6, x0 + W - 6, y0 + H - 6, Ui.alpha(PAPER, anim()));
        c.drawBorder(x0 + 6, y0 + 6, W - 12, H - 12, Ui.alpha(PAPER_EDGE, anim()));
        // léger vieillissement du papier
        c.fillGradient(x0 + 7, y0 + 7, x0 + W - 7, y0 + 40, Ui.alpha(0x22FFFFFF, anim()), 0x00000000);
        c.fillGradient(x0 + 7, y0 + H - 50, x0 + W - 7, y0 + H - 7, 0x00000000, Ui.alpha(0x2A8C7E5E, anim()));
    }

    /** Petit texte en capitales (échelle réduite), comme les libellés d'une fiche administrative. */
    private void small(DrawContext c, String text, int x, int y, int color, float scale) {
        c.getMatrices().push();
        c.getMatrices().translate(x, y, 0);
        c.getMatrices().scale(scale, scale, 1f);
        c.drawText(textRenderer, text, 0, 0, color, false);
        c.getMatrices().pop();
    }

    private SkinTextures skin() {
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID id = data.isBlank() ? (mc.player == null ? data.owner() : mc.player.getUuid()) : data.owner();
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(id);
            if (e != null) return e.getSkinTextures();
        }
        return DefaultSkinHelper.getSkinTextures(id);
    }

    /** Une couche de la peau (région u,v de taille w×h) dessinée à (x,y) en pixels de peau × s. */
    private static void part(DrawContext c, Identifier tex, int x, int y, int s, int px, int py, int w, int h, int u, int v) {
        c.drawTexture(tex, x + px * s, y + py * s, w * s, h * s, u, v, w, h, 64, 64);
    }

    /** Dessine le skin de face (tête, corps, bras, jambes + couches extérieures) : la « photo » de la fiche. */
    private void drawFigure(DrawContext c, int x, int y, int s) {
        SkinTextures st = skin();
        Identifier tex = st.texture();
        boolean slim = st.model() == SkinTextures.Model.SLIM;
        int aw = slim ? 3 : 4;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        c.setShaderColor(0.88f, 0.82f, 0.70f, 1f); // teinte sépia : photo d'identité vieillie
        // couches de base
        part(c, tex, x, y, s, 4, 0, 8, 8, 8, 8);                  // tête
        part(c, tex, x, y, s, 4, 8, 8, 12, 20, 20);               // corps
        part(c, tex, x, y, s, slim ? 1 : 0, 8, aw, 12, 44, 20);   // bras droit (à gauche de l'image)
        part(c, tex, x, y, s, 12, 8, aw, 12, 36, 52);             // bras gauche
        part(c, tex, x, y, s, 4, 20, 4, 12, 4, 20);               // jambe droite
        part(c, tex, x, y, s, 8, 20, 4, 12, 20, 52);              // jambe gauche
        // couches extérieures (chapeau, veste, manches, pantalon)
        part(c, tex, x, y, s, 4, 0, 8, 8, 40, 8);
        part(c, tex, x, y, s, 4, 8, 8, 12, 20, 36);
        part(c, tex, x, y, s, slim ? 1 : 0, 8, aw, 12, 44, 36);
        part(c, tex, x, y, s, 12, 8, aw, 12, 52, 52);
        part(c, tex, x, y, s, 4, 20, 4, 12, 4, 36);
        part(c, tex, x, y, s, 8, 20, 4, 12, 4, 52);
        c.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;

        // en-tête
        String head = "PIÈCE D'IDENTITÉ  ·  SERVICE DES PASSAGERS";
        small(c, head, x0 + (W - (int) (tr.getWidth(head) * 0.8f)) / 2, y0 + 14, LABEL, 0.8f);
        c.fill(x0 + 18, y0 + 28, x0 + W - 18, y0 + 29, INK);
        c.fill(x0 + 18, y0 + 31, x0 + W - 18, y0 + 32, DOT);

        // photo (skin à gauche)
        int s = 4, fw = 16 * s, fh = 32 * s;
        int px = x0 + 20, py = y0 + 42, pw = fw + 16, ph = fh + 16;
        c.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, INK);
        c.fill(px, py, px + pw, py + ph, 0xFFC9C0AC);
        c.fillGradient(px, py, px + pw, py + ph, 0x00000000, 0x40000000);
        drawFigure(c, px + 8, py + 8, s);
        c.drawBorder(px, py, pw, ph, 0xFF4A4232);
        String who = data.isBlank() ? "PHOTO" : "N° " + data.number();
        small(c, who, px + (pw - (int) (tr.getWidth(who) * 0.75f)) / 2, py + ph + 6, LABEL, 0.75f);

        // numéro de document
        small(c, "TITULAIRE N°", colX, y0 + 40, LABEL, 0.75f);
        String num = data.isBlank() ? "----" : data.number();
        c.getMatrices().push();
        c.getMatrices().translate(colX, y0 + 50, 0);
        c.getMatrices().scale(2f, 2f, 1f);
        c.drawText(tr, num, 0, 0, RED, false);
        c.getMatrices().pop();
        if (!shownBy.isEmpty()) small(c, "Papiers montrés par " + shownBy, colX, y0 + 72, LABEL, 0.75f);
        else if (editing) small(c, data.isBlank() ? "À remplir : le skin affiché est le vôtre" : "Modification de vos papiers", colX, y0 + 72, LABEL, 0.75f);

        // champs
        for (int i = 0; i < 6; i++) {
            int ry = rowY(i);
            small(c, LABELS[i].toUpperCase(), colX, ry + 2, LABEL, 0.8f);
            for (int dx = 0; dx < valW; dx += 3) c.fill(valX + dx, ry + 11, valX + dx + 1, ry + 12, DOT);
            if (!editing) {
                String v = vals[i] == null ? "" : vals[i];
                if (i == 5) {
                    // le signe particulier peut tenir sur deux lignes
                    List<net.minecraft.text.OrderedText> lines = tr.wrapLines(Text.literal(v.isEmpty() ? "—" : v), valW);
                    int ly = ry + 1;
                    for (int k = 0; k < Math.min(2, lines.size()); k++) {
                        c.drawText(tr, lines.get(k), valX, ly + k * 10, INK, false);
                    }
                } else {
                    c.drawText(tr, tr.trimToWidth(v.isEmpty() ? "—" : v, valW), valX, ry + 1, INK, false);
                }
            }
        }
        if (editing && (vals[0] == null || vals[0].trim().isEmpty())) {
            small(c, "Le nom est obligatoire.", colX, rowY(5) + 26, RED, 0.75f);
        }
        if (!editing && !canEdit && !data.isBlank()) {
            small(c, "Document présenté — lecture seule", colX, rowY(5) + 26, LABEL, 0.75f);
        }
        for (Btn b : btns) {
            boolean en = b.enabled && !(editing && b.label.equals("Enregistrer") && (vals[0] == null || vals[0].trim().isEmpty()));
            Ui.button(c, tr, b.x, b.y, b.w, b.h, b.label, mx, my, en, b.accent);
        }
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
        if (editing && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            save();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
