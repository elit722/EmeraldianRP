package fr.watherp;

import dev.doctor4t.wathe.index.WatheItems;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Enregistrement de l'objet « papiers d'identité » et logique serveur (consulter, remplir, montrer). */
public final class IdentityItems {
    private IdentityItems() {}

    public static Item PAPER;
    private static final Map<UUID, Long> lastShow = new HashMap<>();
    /** Distance maximale pour montrer ses papiers à quelqu'un (blocs). */
    private static final double SHOW_RANGE = 6.0;

    public static void register() {
        PAPER = Registry.register(Registries.ITEM, Identifier.of(WatheRP.MOD_ID, "identity_paper"),
            new IdentityPaperItem(new Item.Settings().maxCount(1)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(PAPER));
        try {
            // aussi dans l'onglet « Équipement » de Wathe (où se trouvent les objets de jeu)
            ItemGroupEvents.modifyEntriesEvent(WatheItems.EQUIPMENT_GROUP).register(e -> e.add(PAPER));
        } catch (Throwable t) {
            WatheRP.LOGGER.warn("Papiers d'identité : onglet Wathe introuvable ({})", t.toString());
        }
    }

    public static ItemStack newPaper() {
        return new ItemStack(PAPER);
    }

    /** Vrai si le joueur a déjà des papiers d'identité dans son inventaire (ou en main). */
    private static boolean hasPaper(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).getItem() instanceof IdentityPaperItem) return true;
        }
        return false;
    }

    /** Donne des papiers vierges à un joueur (s'il n'en a pas déjà). @return false s'il en avait déjà ou si l'inventaire est plein. */
    public static boolean give(ServerPlayerEntity p, boolean force) {
        if (PAPER == null) return false;
        if (!force && hasPaper(p)) return false;
        return p.getInventory().insertStack(newPaper());
    }

    /** Début de partie : chaque participant reçoit des papiers vierges (option giveIdentityPaper). */
    public static void giveToParticipants(MinecraftServer server) {
        if (!RpConfig.INSTANCE.giveIdentityPaper) return;
        for (ServerPlayerEntity p : RpGame.participants(server)) {
            try {
                give(p, false);
            } catch (Throwable t) {
                WatheRP.LOGGER.warn("Papiers d'identité : impossible d'en donner à {} ({})", p.getGameProfile().getName(), t.toString());
            }
        }
    }

    // ---------------------------------------------------------------- consulter / remplir

    /** Papiers d'identité tenus en main (principale puis secondaire), sinon null. */
    private static ItemStack heldPaper(ServerPlayerEntity p) {
        ItemStack main = p.getStackInHand(Hand.MAIN_HAND);
        if (main.getItem() instanceof IdentityPaperItem) return main;
        ItemStack off = p.getStackInHand(Hand.OFF_HAND);
        if (off.getItem() instanceof IdentityPaperItem) return off;
        return null;
    }

    /** Nom RP actuel du titulaire : c'est le seul nom qui puisse figurer sur des papiers (pas de faux papiers). */
    private static String ownerName(Identity id) {
        String n = RpGame.currentOrPrevious(id.owner());
        return n == null || n.isBlank() ? id.name() : n;
    }

    /** Clic droit dans le vide : ouvre la carte (modifiable si vierge ou si le joueur en est le titulaire). */
    public static void open(ServerPlayerEntity p, ItemStack stack) {
        Identity id = Identity.read(stack);
        boolean editable = id.isBlank() || id.owner().equals(p.getUuid());
        if (!id.isBlank()) id = id.withName(ownerName(id));
        ServerPlayNetworking.send(p, new Net.OpenIdentity(editable, "", id));
    }

    /** Le joueur enregistre ses papiers (écran de modification). Le serveur revérifie tout. */
    public static void save(ServerPlayerEntity p, Net.SaveIdentity in) {
        ItemStack stack = heldPaper(p);
        if (stack == null) {
            p.sendMessage(Msg.err("Tenez vos papiers d'identité en main pour les modifier."), true);
            return;
        }
        Identity cur = Identity.read(stack);
        if (!cur.isBlank() && !cur.owner().equals(p.getUuid())) {
            p.sendMessage(Msg.err("Ces papiers ne sont pas les vôtres."), true);
            return;
        }
        // le nom est imposé : c'est votre nom RP (le champ « nom » envoyé par le client est ignoré)
        String rp = RpGame.currentOrPrevious(p.getUuid());
        if (rp == null || rp.isBlank()) {
            p.sendMessage(Msg.err("Définissez d'abord votre nom RP."), true);
            return;
        }
        Identity n = Identity.of(p.getUuid(), p.getGameProfile().getName(), rp, in.age(), in.profession(),
            in.provenance(), in.destination(), in.mark());
        n.writeTo(stack);
        p.sendMessage(Msg.ok("Papiers d'identité enregistrés au nom de « " + n.name() + " »."), true);
    }

    // ---------------------------------------------------------------- montrer

    private static boolean cooldown(ServerPlayerEntity p) {
        long now = System.currentTimeMillis();
        Long last = lastShow.get(p.getUuid());
        if (last != null && now - last < 1000) return true;
        lastShow.put(p.getUuid(), now);
        return false;
    }

    /** Montre la fiche à {@code to}. Si ce sont les papiers de celui qui les montre, {@code to} apprend son nom RP (lui ne connaît pas {@code to} pour autant). */
    private static void deliver(ServerPlayerEntity from, ServerPlayerEntity to, Identity id) {
        MinecraftServer server = from.getServer();
        String shownName = ownerName(id);
        boolean own = id.owner().equals(from.getUuid());
        boolean learned = own && server != null && RpGame.learnFromPapers(server, from, to);
        RpGame.Known k = RpGame.knowledgeOf(to.getUuid()).get(from.getUuid());
        ServerPlayNetworking.send(to, new Net.OpenIdentity(false, k == null ? "" : k.name(), id.withName(shownName)));
        IntroLog.papers(from.getUuid(), to.getUuid(), id.owner(), shownName, learned);
    }

    private static Identity filled(ServerPlayerEntity p, ItemStack stack) {
        Identity id = Identity.read(stack);
        if (id.isBlank() || id.name().isEmpty()) {
            p.sendMessage(Msg.err("Vos papiers sont vierges : faites un clic droit dans le vide pour les remplir."), true);
            return null;
        }
        return id;
    }

    /** Clic droit sur un joueur : lui montrer ses papiers. */
    public static void showTo(ServerPlayerEntity from, ServerPlayerEntity to, ItemStack stack) {
        if (to == from || to.isSpectator()) return;
        Identity id = filled(from, stack);
        if (id == null) return;
        if (from.getWorld() != to.getWorld() || from.squaredDistanceTo(to) > SHOW_RANGE * SHOW_RANGE) {
            from.sendMessage(Msg.err("Cette personne est trop loin."), true);
            return;
        }
        if (cooldown(from)) return;
        deliver(from, to, id);
        RpGame.Known k = RpGame.knowledgeOf(from.getUuid()).get(to.getUuid());
        from.sendMessage(Msg.info("Vous montrez vos papiers à " + (k == null ? "cette personne" : k.name()) + "."), true);
    }

    /** Bouton « Montrer aux joueurs proches » de l'écran des papiers. */
    public static void showAll(ServerPlayerEntity from) {
        MinecraftServer server = from.getServer();
        ItemStack stack = heldPaper(from);
        if (server == null) return;
        if (stack == null) {
            from.sendMessage(Msg.err("Tenez vos papiers d'identité en main."), true);
            return;
        }
        Identity id = filled(from, stack);
        if (id == null || cooldown(from)) return;
        double r = RpConfig.INSTANCE.introAllRadius;
        int n = 0;
        for (ServerPlayerEntity to : server.getPlayerManager().getPlayerList()) {
            if (to == from || to.isSpectator() || to.isCreative() || to.getWorld() != from.getWorld()) continue;
            if (from.squaredDistanceTo(to) > r * r) continue;
            deliver(from, to, id);
            n++;
        }
        from.sendMessage(n == 0 ? Msg.err("Personne à moins de " + (int) r + " blocs.")
            : Msg.info("Vous montrez vos papiers à " + n + " personne" + (n > 1 ? "s" : "") + "."), true);
    }
}
