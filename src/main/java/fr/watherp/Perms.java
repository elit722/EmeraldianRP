package fr.watherp;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * Permissions du mod, gérées depuis l'Admin Panel ReLink (le mod ne fait que les appliquer).
 *
 * Règles :
 *  - la console et les blocs de commande passent toujours ;
 *  - un joueur avec un rôle « admin » (cf. config adminRoles) a TOUTES les permissions automatiquement ;
 *  - sinon il lui faut la permission précise accordée dans le panel (liste ci-dessous, déclarée au Worker) ;
 *  - tant que le serveur n'est PAS relié au Worker (jamais appairé), les OP niveau 2 passent : sans ça, personne
 *    ne pourrait taper « /relink connect » la toute première fois. Une fois relié, les OP n'ont plus de passe-droit.
 */
public final class Perms {
    private Perms() {}

    /** Menu helper : tickets, alertes d'aide, chat intégré (en plus du rang helper donné avec /r rankup helper). */
    public static final String HELPER = "rp.helper";
    public static final String HELPER_MANAGE = "rp.helper.manage";
    public static final String NAME_FORCE = "rp.name.force";
    public static final String WARN = "rp.warn";
    public static final String RECORD_DELETE = "rp.record.delete";
    public static final String NAMES_READ = "rp.names.read";
    public static final String PSEUDO_SEE = "rp.pseudo.see";
    public static final String WEBHOOK = "rp.webhook";
    public static final String WORKER = "rp.worker";
    public static final String BILLET = "rp.billet";

    /** Permissions déclarées au Worker (nom court + description affichée dans le panel). */
    public static final List<String[]> DECLARED = List.of(
        new String[]{HELPER, "Menu helper : tickets d'aide, alertes, chat intégré (équivaut au rang helper)"},
        new String[]{HELPER_MANAGE, "Donner / retirer le rang helper (/r rankup|derank helper)"},
        new String[]{NAME_FORCE, "Forcer ou réinitialiser le nom RP d'un joueur (/r forcechange, /r reset)"},
        new String[]{WARN, "Avertissements, notes et dossiers (/r warn, note, records)"},
        new String[]{RECORD_DELETE, "Supprimer un dossier d'avertissement ou de note"},
        new String[]{NAMES_READ, "Consulter l'historique des noms RP (/r names)"},
        new String[]{PSEUDO_SEE, "Voir les vrais pseudos en partie (/r voirpseudo)"},
        new String[]{WEBHOOK, "Configurer le webhook Discord des tickets (/r webhook)"},
        new String[]{WORKER, "Gérer la connexion au Worker ReLink (/relink, /r worker)"},
        new String[]{BILLET, "Corriger le billet de train d'un joueur ou changer la provenance / destination de tous les billets (/r billet)"}
    );

    /** Le serveur est-il relié au Worker (url + clé + slug) ? Sert à désactiver le passe-droit OP. */
    public static boolean linked() {
        return Relink.configured();
    }

    public static boolean isAdmin(ServerPlayerEntity p) {
        if (Relink.hasAdminRole(p.getUuid())) return true;
        return !linked() && p.hasPermissionLevel(2);
    }

    public static boolean has(ServerPlayerEntity p, String perm) {
        if (isAdmin(p)) return true;
        return Relink.hasPermission(p.getUuid(), perm);
    }

    public static boolean has(ServerCommandSource s, String perm) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return s.hasPermissionLevel(2); // console / bloc de commande
        return has(p, perm);
    }

    /** A au moins une permission du mod (sert à afficher la commande /r dans l'arbre de commandes). */
    public static boolean hasAny(ServerCommandSource s) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return s.hasPermissionLevel(2);
        if (isAdmin(p)) return true;
        for (String[] d : DECLARED) if (Relink.hasPermission(p.getUuid(), d[0])) return true;
        return false;
    }
}
