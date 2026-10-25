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

/** Enregistrement de l'objet « billet de train » (id technique : identity_paper) et logique serveur (consulter, remplir, montrer, correction par un admin). */
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
            WatheRP.LOGGER.warn("Billet de train : onglet Wathe introuvable ({})", t.toString());
        }
    }

    public static ItemStack newPaper() {
        return new ItemStack(PAPER);
    }

    /** Vrai si le joueur a déjà un billet de train dans son inventaire (ou en main). */
    private static boolean hasPaper(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).getItem() instanceof IdentityPaperItem) return true;
        }
        return false;
    }

    /** Donne un billet vierge à un joueur (s'il n'en a pas déjà). @return false s'il en avait déjà ou si l'inventaire est plein. */
    public static boolean give(ServerPlayerEntity p, boolean force) {
        if (PAPER == null) return false;
        if (!force && hasPaper(p)) return false;
        return p.getInventory().insertStack(newPaper());
    }

    /** Début de partie : chaque participant reçoit un billet vierge (option giveIdentityPaper). */
    public static void giveToParticipants(MinecraftServer server) {
        if (!RpConfig.INSTANCE.giveIdentityPaper) return;
        for (ServerPlayerEntity p : RpGame.participants(server)) {
            try {
                give(p, false);
            } catch (Throwable t) {
                WatheRP.LOGGER.warn("Billet de train : impossible d'en donner à {} ({})", p.getGameProfile().getName(), t.toString());
            }
        }
    }

    // ---------------------------------------------------------------- consulter / remplir

    /** Billet de train tenu en main (principale puis secondaire), sinon null. */
    private static ItemStack heldPaper(ServerPlayerEntity p) {
        ItemStack main = p.getStackInHand(Hand.MAIN_HAND);
        if (main.getItem() instanceof IdentityPaperItem) return main;
        ItemStack off = p.getStackInHand(Hand.OFF_HAND);
        if (off.getItem() instanceof IdentityPaperItem) return off;
        return null;
    }

    /** Nom RP actuel du titulaire : c'est le seul nom qui puisse figurer sur un billet (pas de faux billets). */
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
            p.sendMessage(Msg.err("Tenez votre billet de train en main pour le modifier."), true);
            return;
        }
        Identity cur = Identity.read(stack);
        if (!cur.isBlank() && !cur.owner().equals(p.getUuid())) {
            p.sendMessage(Msg.err("Ce billet n'est pas le vôtre."), true);
            return;
        }
        // le nom est imposé : c'est votre nom RP (le champ « nom » envoyé par le client est ignoré)
        String rp = RpGame.currentOrPrevious(p.getUuid());
        if (rp == null || rp.isBlank()) {
            p.sendMessage(Msg.err("Définissez d'abord votre nom RP."), true);
            return;
        }
        // provenance et destination ne se saisissent pas : celles du billet (valeurs imposées ou exception décidée par un admin) sont conservées
        Identity n = Identity.of(p.getUuid(), p.getGameProfile().getName(), rp, in.age(), in.profession(),
            cur.provenance(), cur.destination(), in.mark());
        n.writeTo(stack);
        p.sendMessage(Msg.ok("Billet de train enregistré au nom de « " + n.name() + " »."), true);
    }

    // ---------------------------------------------------------------- correction par un admin

    /** Champs qu'un admin peut corriger avec /r billet modifier (le nom RP, lui, se change avec /r forcechange). */
    public static final java.util.List<String> FIELDS = java.util.List.of("age", "profession", "signe", "provenance", "destination");

    /**
     * Change une info des billets de {@code target} (ceux dont il est le titulaire ; à défaut, un billet vierge de son inventaire,
     * qui lui est alors attribué). {@code value} vide = remise à zéro (pour provenance / destination : retour à la valeur imposée).
     * @return le nombre de billets modifiés (0 : aucun billet dans son inventaire)
     */
    public static int adminSet(ServerPlayerEntity target, String field, String value) {
        java.util.List<ItemStack> mine = new java.util.ArrayList<>(), blanks = new java.util.ArrayList<>();
        for (int i = 0; i < target.getInventory().size(); i++) {
            ItemStack st = target.getInventory().getStack(i);
            if (!(st.getItem() instanceof IdentityPaperItem)) continue;
            Identity id = Identity.read(st);
            if (id.isBlank()) blanks.add(st);
            else if (id.owner().equals(target.getUuid())) mine.add(st);
        }
        if (mine.isEmpty() && !blanks.isEmpty()) {
            String rp = RpGame.currentOrPrevious(target.getUuid());
            Identity claimed = Identity.read(blanks.get(0)).withOwner(target.getUuid(), target.getGameProfile().getName(), rp == null ? "" : rp);
            claimed.writeTo(blanks.get(0));
            mine.add(blanks.get(0));
        }
        for (ItemStack st : mine) Identity.read(st).withField(field, value).writeTo(st);
        return mine.size();
    }

    /** Provenance / destination imposées à TOUS les billets (config). Un billet avec une exception propre n'est pas touché. */
    public static void setFixed(boolean from, String value) {
        String v = Identity.clip(value, from ? Identity.FROM_MAX : Identity.DEST_MAX);
        if (from) RpConfig.INSTANCE.ticketFrom = v;
        else RpConfig.INSTANCE.ticketDest = v;
        RpConfig.save();
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
            p.sendMessage(Msg.err("Votre billet est vierge : faites un clic droit dans le vide pour le remplir."), true);
            return null;
        }
        return id;
    }

    /** Clic droit sur un joueur : lui montrer son billet. */
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
        from.sendMessage(Msg.info("Vous montrez votre billet à " + (k == null ? "cette personne" : k.name()) + "."), true);
    }

    /** Bouton « Montrer aux joueurs proches » de l'écran du billet. */
    public static void showAll(ServerPlayerEntity from) {
        MinecraftServer server = from.getServer();
        ItemStack stack = heldPaper(from);
        if (server == null) return;
        if (stack == null) {
            from.sendMessage(Msg.err("Tenez votre billet de train en main."), true);
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
            : Msg.info("Vous montrez votre billet à " + n + " personne" + (n > 1 ? "s" : "") + "."), true);
    }
}
