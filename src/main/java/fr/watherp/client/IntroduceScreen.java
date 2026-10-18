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

/** Menu ouvert par Maj + clic gauche sur un joueur. */
public class IntroduceScreen extends Screen {
    private static final int W = 340, H = 170;

    private final UUID target;
    private final List<Btn> btns = new ArrayList<>();
    private long openTime;
    private int x0, y0;

    public IntroduceScreen(UUID target) {
        super(Text.literal("Se présenter"));
        this.target = target;
    }

    @Override
    protected void init() {
        openTime = System.currentTimeMillis();
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        btns.clear();
        boolean hasName = ClientRp.confirmed;
        btns.add(new Btn(x0 + 20, y0 + 66, W - 40, 24, "Se présenter", Ui.GREEN, () -> {
            ClientPlayNetworking.send(new Net.Introduce(target, false, ""));
            close();
        }).enabled(hasName));
        btns.add(new Btn(x0 + 20, y0 + 98, W - 40, 24, "Se présenter sous pseudonyme", Ui.AQUA, () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.setScreen(new AliasScreen("Sous quel pseudonyme voulez-vous vous présenter ?", alias ->
                ClientPlayNetworking.send(new Net.Introduce(target, true, alias))));
        }));
        btns.add(new Btn(x0 + 20, y0 + H - 34, W - 40, 20, "Annuler", Ui.RED, this::close));
    }

    private float anim() {
        return Math.min(1f, (System.currentTimeMillis() - openTime) / 300f);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.alpha(0xB0050505, anim()));
        Ui.panel(c, textRenderer, x0, y0, W, H, "SE PRÉSENTER", anim());
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        c.drawText(textRenderer, "La personne recevra une proposition limitée dans le temps.", x0 + 20, y0 + 46, Ui.MUTED, false);
        if (!ClientRp.confirmed) c.drawText(textRenderer, "Définissez d'abord votre nom RP (/rpnom).", x0 + 20, y0 + 128, Ui.RED, false);
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
    public boolean shouldPause() {
        return false;
    }
}
