package fr.watherp;

import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Notion de staff.
 *  - « Helper » (menu d'assistance, tickets, chat intégré) : rang helper local (/r rankup helper), OU permission rp.helper
 *    accordée dans le panel ReLink, OU rôle Admin du panel.
 *  - Les commandes du mod (/r warn, /r forcechange…) demandent chacune leur permission : voir {@link Perms}.
 */
public final class Staff {
    private Staff() {}

    /** Ancien nom : administrateur du mod (rôle Admin du panel ; OP niveau 2 tant que le serveur n'est pas relié au Worker). */
    public static boolean isOp(ServerPlayerEntity p) {
        return Perms.isAdmin(p);
    }

    /** Rang helper local donné par /r rankup helper. */
    public static boolean isHelper(ServerPlayerEntity p) {
        return RpData.INSTANCE.helpers.contains(p.getUuidAsString());
    }

    public static boolean isStaff(ServerPlayerEntity p) {
        return isHelper(p) || Perms.has(p, Perms.HELPER);
    }

    public static boolean isStaff(ServerCommandSource s) {
        ServerPlayerEntity p = s.getPlayer();
        if (p == null) return s.hasPermissionLevel(2); // console
        return isStaff(p);
    }

    /** Peut voir la commande /r dans l'arbre de commandes (helper, ou au moins une permission du mod). */
    public static boolean canUseR(ServerCommandSource s) {
        return isStaff(s) || Perms.hasAny(s);
    }

    public static boolean isInactive(ServerPlayerEntity p) {
        return RpData.INSTANCE.inactive.contains(p.getUuidAsString());
    }

    /** Staff ayant activé /r voirpseudo : voit les vrais pseudos Minecraft en partie. */
    public static boolean isSeeingPseudo(ServerPlayerEntity p) {
        return RpData.INSTANCE.seePseudo.contains(p.getUuidAsString()) && Perms.has(p, Perms.PSEUDO_SEE);
    }

    /** Dit au client s'il a accès aux outils helper (boutons du menu, touches). Le serveur revérifie toujours chaque action. */
    public static void syncCaps(ServerPlayerEntity p) {
        ServerPlayNetworking.send(p, new Net.Caps(isStaff(p)));
    }
}
