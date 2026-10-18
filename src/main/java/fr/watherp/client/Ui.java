package fr.watherp.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Briques graphiques reprenant le style des menus Wathe (admin / cosmétiques). */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xF00C0C0E;
    public static final int BORDER = 0xFF2A2A30;
    public static final int ACCENT = 0xFFC5AE8B;
    public static final int BTN = 0xFF202026;
    public static final int BTN_HOVER = 0xFF3A3A44;
    public static final int TEXT = 0xFFE8E8E8;
    public static final int MUTED = 0xFF9A9AA2;
    public static final int RED = 0xFFE05555;
    public static final int GREEN = 0xFF5FBF6A;
    public static final int YELLOW = 0xFFE6C34A;
    public static final int AQUA = 0xFF5BC8D8;

    public static int alpha(int color, float a) {
        int base = (color >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(base * a)));
        return (na << 24) | (color & 0xFFFFFF);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void corners(DrawContext c, int x, int y, int w, int h, int len, int color) {
        c.fill(x, y, x + len, y + 1, color);
        c.fill(x, y, x + 1, y + len, color);
        c.fill(x + w - len, y, x + w, y + 1, color);
        c.fill(x + w - 1, y, x + w, y + len, color);
        c.fill(x, y + h - 1, x + len, y + h, color);
        c.fill(x, y + h - len, x + 1, y + h, color);
        c.fill(x + w - len, y + h - 1, x + w, y + h, color);
        c.fill(x + w - 1, y + h - len, x + w, y + h, color);
    }

    /** Fond de panneau + titre agrandi. */
    public static void panel(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String title, float anim) {
        c.fill(x, y, x + w, y + h, alpha(BG, anim));
        c.fillGradient(x, y, x + w, y + 30, alpha(0x38C5AE8B, anim), 0x00000000);
        c.drawBorder(x, y, w, h, alpha(BORDER, anim));
        corners(c, x, y, w, h, 10, alpha(ACCENT, anim));
        c.getMatrices().push();
        c.getMatrices().translate(x + 14, y + 10, 0);
        c.getMatrices().scale(1.6f, 1.6f, 1f);
        c.drawText(tr, Text.literal(title), 0, 0, alpha(ACCENT, anim), false);
        c.getMatrices().pop();
    }

    public static void button(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String label,
                              int mx, int my, boolean enabled, int accent) {
        boolean hover = enabled && inside(mx, my, x, y, w, h);
        c.fill(x, y, x + w, y + h, hover ? BTN_HOVER : BTN);
        c.drawBorder(x, y, w, h, hover ? accent : (enabled ? BORDER : 0xFF18181C));
        int col = enabled ? (hover ? accent : TEXT) : 0xFF5A5A62;
        c.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, col, false);
    }
}
