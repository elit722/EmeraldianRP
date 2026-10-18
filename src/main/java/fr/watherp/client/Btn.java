package fr.watherp.client;

/** Bouton simple dessiné à la main (comme dans les menus Wathe). */
public final class Btn {
    public int x, y, w, h;
    public String label;
    public Runnable action;
    public boolean enabled = true;
    public int accent = Ui.ACCENT;

    public Btn(int x, int y, int w, int h, String label, int accent, Runnable action) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = label;
        this.accent = accent;
        this.action = action;
    }

    public Btn enabled(boolean e) {
        this.enabled = e;
        return this;
    }
}
