package fr.watherp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Journal staff Discord des présentations et des révélations de nom (« A a révélé son nom à B »).
 * Aide à arbitrer les litiges de rôle : on voit qui a dit quoi à qui, avec le pseudo Minecraft ET le vrai nom RP.
 *
 * Les lignes sont regroupées en un seul embed toutes les {@code introLogFlushSeconds} secondes (une présentation de
 * groupe dans un wagon produit plusieurs lignes d'un coup : un message par ligne dépasserait la limite d'envoi de Discord).
 * Utilise le webhook du journal staff déjà configuré (logWebhookUrl, sinon webhookUrl).
 */
public final class IntroLog {
    private IntroLog() {}

    private static final int COLOR = 0x5865F2;
    private static final int MAX_LINES = 300;
    private static final List<String> lines = new ArrayList<>();
    private static int waitTicks = 0;

    public static boolean enabled() {
        return RpConfig.INSTANCE.logIntroductions && Discord.journalUrl() != null;
    }

    /** « **pseudo** (*nom RP*) » : le pseudo Minecraft et le vrai nom RP du joueur, mis en forme pour Discord. */
    public static String who(UUID id) {
        String mc = RpData.INSTANCE.usernames.get(id.toString());
        String rp = RpGame.currentOrPrevious(id);
        String a = "**" + Discord.esc(mc == null || mc.isBlank() ? id.toString().substring(0, 8) : mc) + "**";
        return rp == null || rp.isBlank() ? a : a + " (*" + Discord.esc(rp) + "*)";
    }

    /** Ajoute une ligne au journal (envoyée au prochain regroupement). */
    public static void add(String line) {
        if (!enabled()) return;
        String stamp = "<t:" + Instant.now().getEpochSecond() + ":T> ";
        synchronized (lines) {
            if (lines.isEmpty()) waitTicks = 0;
            lines.add(stamp + line);
            while (lines.size() > MAX_LINES) lines.remove(0);
        }
    }

    /** À appeler à chaque tick serveur. */
    public static void tick() {
        synchronized (lines) {
            if (lines.isEmpty()) return;
            if (++waitTicks < RpConfig.INSTANCE.introLogFlushSeconds * 20) return;
        }
        flush();
    }

    /** Envoie immédiatement ce qui est en attente (fin de partie, arrêt du serveur). */
    public static void flush() {
        List<String> out;
        synchronized (lines) {
            if (lines.isEmpty()) return;
            out = new ArrayList<>(lines);
            lines.clear();
            waitTicks = 0;
        }
        String url = Discord.journalUrl();
        if (url == null || !RpConfig.INSTANCE.logIntroductions) return;
        Discord.postLines(url, RpConfig.INSTANCE.serverName + " · Journal staff", "🤝 Présentations & révélations", out, COLOR, 6);
    }

    // ---------------------------------------------------------------- lignes types

    /** {@code actor} se présente à {@code other}. */
    public static void intro(UUID actor, UUID other, String shown, boolean alias, String real) {
        String s = Discord.esc(shown);
        if (alias) {
            add("🎭 " + who(actor) + " s'est présenté à " + who(other) + " sous le **pseudonyme** « " + s + " »"
                + (real == null || real.isBlank() ? "" : " (vrai nom : " + Discord.esc(real) + ")") + ".");
        } else {
            add("🤝 " + who(actor) + " s'est présenté à " + who(other) + " sous son **vrai nom** (« " + s + " »).");
        }
    }

    /** {@code actor} révèle son vrai nom à {@code other}, qui le connaissait sous un pseudonyme. */
    public static void reveal(UUID actor, UUID other, String aliasKnown, String real) {
        add("🔓 " + who(actor) + " a **révélé son vrai nom** (« " + Discord.esc(real) + " ») à " + who(other)
            + ", qui le connaissait sous « " + Discord.esc(aliasKnown) + " ».");
    }

    /**
     * {@code actor} montre des papiers d'identité à {@code other}. Le nom des papiers est toujours le nom RP de leur titulaire
     * ({@code owner}) ; {@code learned} : {@code other} vient d'apprendre ce nom (papiers du porteur, le titulaire ne le connaît pas pour autant).
     */
    public static void papers(UUID actor, UUID other, UUID owner, String nameOnPaper, boolean learned) {
        boolean own = owner.equals(actor);
        add("🪪 " + who(actor) + " a montré " + (own ? "ses" : "les") + " **papiers d'identité** " + (own ? "" : "de " + who(owner) + " ")
            + "à " + who(other) + " : « " + Discord.esc(nameOnPaper) + " »"
            + (own ? (learned ? " — " + who(other) + " connaît maintenant son vrai nom" : " — déjà connu")
                : " ⚠️ *(papiers d'un autre joueur : rien n'est appris)*") + ".");
    }
}
