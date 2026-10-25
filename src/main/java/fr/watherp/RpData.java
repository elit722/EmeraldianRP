package fr.watherp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Données persistantes (tous mondes) : rangs helper, staff inactifs, derniers noms RP, tickets. */
public final class RpData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static RpData INSTANCE = new RpData();

    public Set<String> helpers = new LinkedHashSet<>();
    public Set<String> inactive = new LinkedHashSet<>();
    public Map<String, String> lastNames = new LinkedHashMap<>();
    public List<Ticket> tickets = new ArrayList<>();
    public int nextTicket = 1;

    /** Noms RP confirmés de la partie EN COURS (uuid -> nom). Persisté pour survivre à un redémarrage du serveur :
     *  sans ça, un restart en pleine partie débloquait le changement de nom. Vidé à chaque début/fin de partie. */
    public Map<String, String> gameNames = new LinkedHashMap<>();
    /** Dernier pseudo Minecraft connu par UUID (pour cibler un joueur hors ligne avec /r warn, /r note...). */
    public Map<String, String> usernames = new LinkedHashMap<>();
    /** Staff ayant activé /r voirpseudo. */
    public Set<String> seePseudo = new LinkedHashSet<>();
    /** Avertissements et notes du staff. */
    public List<StaffRecord> records = new ArrayList<>();
    public int nextRecord = 1;
    /** File d'attente des événements à envoyer au Worker (renvoyée tant que le Worker n'a pas répondu 2xx). */
    public List<HistoryEntry> pendingHistory = new ArrayList<>();

    /** Un évènement de présentation de la partie en cours (sert au débrief et aux carnets de contacts). */
    public static final class GameEvent {
        public long ms;
        /** intro : {@code actor} s'est présenté à {@code other} ; reveal : {@code actor} a révélé son vrai nom à {@code other}. */
        public String kind = "intro";
        public String actor = "", other = "";
        /** intro : nom montré ; reveal : le pseudonyme que {@code other} connaissait. */
        public String shown = "";
        /** intro : le nom montré est un pseudonyme. */
        public boolean alias;
        /** Vrai nom RP de {@code actor} à ce moment-là. */
        public String real = "";
    }

    /** Présentations et révélations de la partie EN COURS (vidé au début de la partie suivante : le débrief est construit à la fin). */
    public List<GameEvent> gameEvents = new ArrayList<>();
    /** Début de la partie en cours (ms), pour les horodatages « t+X min » du débrief. */
    public long gameStartMs = 0;
    /** Notes privées du carnet de contacts : observateur (uuid) -> (contact (uuid) -> note). Effacées à la fin de la partie. */
    public Map<String, Map<String, String>> contactNotes = new LinkedHashMap<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("watherp-data.json");
    }

    public static void load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    RpData d = GSON.fromJson(r, RpData.class);
                    if (d != null) INSTANCE = d;
                }
            }
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible de lire {}", p, e);
        }
        if (INSTANCE.helpers == null) INSTANCE.helpers = new LinkedHashSet<>();
        if (INSTANCE.inactive == null) INSTANCE.inactive = new LinkedHashSet<>();
        if (INSTANCE.lastNames == null) INSTANCE.lastNames = new LinkedHashMap<>();
        if (INSTANCE.tickets == null) INSTANCE.tickets = new ArrayList<>();
        if (INSTANCE.gameNames == null) INSTANCE.gameNames = new LinkedHashMap<>();
        if (INSTANCE.usernames == null) INSTANCE.usernames = new LinkedHashMap<>();
        if (INSTANCE.seePseudo == null) INSTANCE.seePseudo = new LinkedHashSet<>();
        if (INSTANCE.records == null) INSTANCE.records = new ArrayList<>();
        if (INSTANCE.pendingHistory == null) INSTANCE.pendingHistory = new ArrayList<>();
        if (INSTANCE.gameEvents == null) INSTANCE.gameEvents = new ArrayList<>();
        if (INSTANCE.contactNotes == null) INSTANCE.contactNotes = new LinkedHashMap<>();
    }

    public static synchronized void save() {
        Path p = path();
        try {
            // on garde les 300 derniers tickets
            while (INSTANCE.tickets.size() > 300) INSTANCE.tickets.remove(0);
            while (INSTANCE.records.size() > 2000) INSTANCE.records.remove(0);
            while (INSTANCE.pendingHistory.size() > 500) INSTANCE.pendingHistory.remove(0);
            while (INSTANCE.gameEvents.size() > 3000) INSTANCE.gameEvents.remove(0);
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (Exception e) {
            WatheRP.LOGGER.error("Impossible d'écrire {}", p, e);
        }
    }

    public Ticket ticket(int id) {
        for (Ticket t : tickets) if (t.id == id) return t;
        return null;
    }
}
