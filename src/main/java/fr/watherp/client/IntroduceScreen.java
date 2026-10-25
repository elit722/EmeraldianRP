package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menu de présentation.
 *  - ouvert par un clic droit sur un joueur : se présenter à CETTE personne (une seule présentation par personne),
 *    ou, en bas, à tous les joueurs proches d'un coup ;
 *  - ouvert par la touche « Se présenter à tous les proches » ({@code target == null}) : uniquement la présentation de groupe
 *    (même nom RP ou même pseudonyme pour tous : pratique dans un wagon).
 */
public class IntroduceScreen extends Screen {
    private static final int W = 360;

    /** Personne visée ; null = présentation de groupe uniquement. */
    private final UUID target;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0, h;

    public IntroduceScreen(UUID target) {
        super(Text.literal(target == null ? "Se présenter à tous" : "Se présenter"));
        this.target = target;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        h = target == null ? 178 : 258;
        x0 = (width - W) / 2;
        y0 = (height - h) / 2;
        btns.clear();
        boolean hasName = ClientRp.confirmed;
        int y = y0 + 66;
        if (target != null) {
            btns.add(new Btn(x0 + 20, y, W - 40, 24, "Se présenter", Ui.GREEN, () -> {
                ClientPlayNetworking.send(new Net.Introduce(target, false, ""));
                close();
            }).enabled(hasName));
            y += 32;
            btns.add(new Btn(x0 + 20, y, W - 40, 24, "Se présenter sous pseudonyme", Ui.AQUA, () -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.setScreen(new AliasScreen("Sous quel pseudonyme voulez-vous vous présenter ?", alias ->
                    ClientPlayNetworking.send(new Net.Introduce(target, true, alias))));
            }));
            y += 50;
        }
        btns.add(new Btn(x0 + 20, y, W - 40, 24, "À tous les proches : sous mon vrai nom", Ui.GREEN, () -> {
            ClientPlayNetworking.send(new Net.IntroduceAll(false, ""));
            close();
        }).enabled(hasName));
        y += 32;
        btns.add(new Btn(x0 + 20, y, W - 40, 24, "À tous les proches : sous un pseudonyme", Ui.AQUA, () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.setScreen(new AliasScreen("Sous quel pseudonyme voulez-vous vous présenter à tous les proches ?", alias ->
                ClientPlayNetworking.send(new Net.IntroduceAll(true, alias))));
        }));
        btns.add(new Btn(x0 + 20, y0 + h - 34, W - 40, 20, "Annuler", Ui.RED, this::close));
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xB0050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, h, target == null ? "SE PRÉSENTER À TOUS" : "SE PRÉSENTER", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        var tr = textRenderer;
        c.drawText(tr, "Chaque personne recevra une proposition limitée dans le temps.", x0 + 20, y0 + 40, Ui.MUTED, false);
        c.drawText(tr, target == null ? "Seuls les joueurs proches que vous ne connaissez pas encore sont concernés."
            : "Une seule présentation par personne : choisissez bien.", x0 + 20, y0 + 52, Ui.YELLOW, false);
        if (target != null) {
            int sepY = y0 + 130;
            c.fill(x0 + 20, sepY, x0 + W - 20, sepY + 1, Ui.BORDER);
            c.drawText(tr, "Ou à tous les joueurs proches d'un coup (même nom pour tous) :", x0 + 20, sepY + 6, Ui.MUTED, false);
        }
        if (!ClientRp.confirmed) c.drawText(tr, "Définissez d'abord votre nom RP (/rpnom).", x0 + 20, y0 + h - 48, Ui.RED, false);
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

    @Override
    public boolean shouldPause() {
        return false;
    }
}
