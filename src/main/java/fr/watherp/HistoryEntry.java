package fr.watherp;

/**
 * Un événement d'historique des noms RP, en attente d'envoi au Worker.
 * kind = "name" (nom RP de la partie) | "alias" (pseudonyme sous lequel on s'est présenté à quelqu'un).
 * source = "player" (saisi) | "kept" (ancien nom conservé) | "staff" (/r forcechange) | "alias".
 */
public class HistoryEntry {
    public String eventId = "";
    public String mcUuid = "";
    public String mcUsername = "";
    public String kind = "name";
    public String rpName = "";
    public String previousRpName = "";
    public String source = "player";
    public String setBy = "";
    public String shownToUuid = "";
    public String shownToUsername = "";
    public String createdAt = "";
}
