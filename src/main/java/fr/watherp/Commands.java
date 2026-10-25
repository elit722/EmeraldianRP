package fr.watherp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.watherp.net.Net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class Commands {
    private Commands() {}

    /** Suggestions : pseudos des joueurs en ligne. */
    private static final SuggestionProvider<ServerCommandSource> PLAYERS = (c, b) -> {
        // pseudos en ligne + derniers pseudos connus (joueurs hors ligne : « fuyards »)
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(java.util.Arrays.asList(c.getSource().getServer().getPlayerManager().getPlayerNames()));
        names.addAll(RpData.INSTANCE.usernames.values());
        return CommandSource.suggestMatching(names, b);
    };

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm");

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // ------------------------------------------------------------ /aide <type> [message]
        d.register(literal("aide")
            // sans argument : ouvre le menu d'aide (type, message, anonymisation, chat privé)
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                ServerPlayNetworking.send(p, new Net.OpenHelp(new java.util.ArrayList<>(RpConfig.INSTANCE.helpTypes)));
                return 1;
            })
            .then(argument("type", StringArgumentType.word())
                .suggests((c, b) -> CommandSource.suggestMatching(RpConfig.INSTANCE.helpTypes, b))
                .executes(ctx -> help(ctx, ""))
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> help(ctx, StringArgumentType.getString(ctx, "message"))))));

        // ------------------------------------------------------------ /r ...  (staff)
        d.register(literal("r")
            .requires(Staff::canUseR)
            .then(literal("rankup").requires(s -> Perms.has(s, Perms.HELPER_MANAGE))
                .then(literal("helper").then(argument("player", EntityArgumentType.player())
                    .executes(ctx -> rank(ctx, true)))))
            .then(literal("derank").requires(s -> Perms.has(s, Perms.HELPER_MANAGE))
                .then(literal("helper").then(argument("player", EntityArgumentType.player())
                    .executes(ctx -> rank(ctx, false)))))
            .then(literal("innactive").requires(Staff::isStaff).executes(Commands::setInactive))
            .then(literal("inactive").requires(Staff::isStaff).executes(Commands::setInactive))
            .then(literal("active").requires(Staff::isStaff).executes(Commands::setActive))
            // forcechange / reset : fonctionnent aussi sur un joueur HORS LIGNE (pseudo ou nom RP)
            .then(literal("forcechange").requires(s -> Perms.has(s, Perms.NAME_FORCE))
                .then(literal("name").then(argument("player", StringArgumentType.string()).suggests(PLAYERS)
                    .then(argument("name", StringArgumentType.greedyString()).executes(Commands::forceChange)))))
            .then(literal("reset").requires(s -> Perms.has(s, Perms.NAME_FORCE))
                .then(literal("name").then(argument("player", StringArgumentType.string()).suggests(PLAYERS)
                    .executes(Commands::resetName))))
            .then(literal("papiers").requires(Staff::isStaff)
                .then(argument("player", EntityArgumentType.player()).executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    boolean ok = IdentityItems.give(target, true);
                    ctx.getSource().sendFeedback(() -> ok ? Msg.ok("Papiers d'identité vierges donnés à " + target.getGameProfile().getName() + ".")
                        : Msg.err("Inventaire plein."), false);
                    return ok ? 1 : 0;
                })))
            .then(literal("voirpseudo").requires(s -> Perms.has(s, Perms.PSEUDO_SEE)).executes(Commands::toggleSeePseudo))
            .then(literal("warn").requires(s -> Perms.has(s, Perms.WARN))
                .then(argument("player", StringArgumentType.string()).suggests(PLAYERS)
                    .then(argument("reason", StringArgumentType.greedyString()).executes(ctx -> addRecord(ctx, StaffRecord.WARN, "reason")))))
            .then(literal("note").requires(s -> Perms.has(s, Perms.WARN))
                .then(argument("player", StringArgumentType.string()).suggests(PLAYERS)
                    .then(argument("text", StringArgumentType.greedyString()).executes(ctx -> addRecord(ctx, StaffRecord.NOTE, "text")))))
            .then(literal("records").requires(s -> Perms.has(s, Perms.WARN))
                .then(argument("player", StringArgumentType.string()).suggests(PLAYERS).executes(Commands::listRecords)))
            .then(literal("record").requires(s -> Perms.has(s, Perms.RECORD_DELETE))
                .then(literal("delete").then(argument("id", IntegerArgumentType.integer(1)).executes(Commands::deleteRecord))))
            .then(literal("names").requires(s -> Perms.has(s, Perms.NAMES_READ))
                .then(literal("player").then(argument("player", StringArgumentType.string()).suggests(PLAYERS).executes(Commands::namesOfPlayer)))
                .then(literal("rp").then(argument("name", StringArgumentType.greedyString()).executes(Commands::namesSearch))))
            .then(literal("webhook").requires(s -> Perms.has(s, Perms.WEBHOOK))
                .then(literal("set").then(argument("url", StringArgumentType.greedyString()).executes(Commands::webhookSet)))
                .then(literal("clear").executes(Commands::webhookClear))
                .then(literal("status").executes(Commands::webhookStatus))
                .then(literal("test").executes(Commands::webhookTest)))
            .then(literal("worker").requires(s -> Perms.has(s, Perms.WORKER))
                .then(literal("url").then(argument("url", StringArgumentType.greedyString()).executes(Commands::workerUrl)))
                .then(literal("slug").then(argument("slug", StringArgumentType.word()).executes(Commands::workerSlug)))
                .then(literal("key").then(argument("key", StringArgumentType.word()).executes(Commands::workerKey)))
                .then(literal("clear").executes(Commands::workerClear))
                .then(literal("status").executes(Commands::workerStatus)))
            .then(literal("ticket").requires(Staff::isStaff)
                .then(argument("action", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestMatching(java.util.List.of("claim", "close", "reopen", "tp", "chat", "deanon", "delete"), b))
                    .then(argument("id", IntegerArgumentType.integer(1)).executes(Commands::ticket)))));

        // ------------------------------------------------------------ /relink : connexion au Worker ReLink
        d.register(literal("relink")
            .requires(s -> Perms.has(s, Perms.WORKER))
            .then(literal("connect")
                .then(argument("code", StringArgumentType.word())
                    .executes(ctx -> relinkConnect(ctx, null))
                    .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> relinkConnect(ctx, StringArgumentType.getString(ctx, "url"))))))
            .then(literal("status").executes(Commands::relinkStatus))
            .then(literal("reload").executes(ctx -> relinkReload(ctx, null))
                .then(argument("player", StringArgumentType.string()).suggests(PLAYERS)
                    .executes(ctx -> relinkReload(ctx, StringArgumentType.getString(ctx, "player")))))
            .then(literal("disconnect").executes(Commands::relinkDisconnect)));

        // ------------------------------------------------------------ commandes joueur internes
        d.register(literal("rpintro")
            .then(literal("accept").then(argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                RpGame.reply(p, IntegerArgumentType.getInteger(ctx, "id"), false, "");
                return 1;
            })))
            .then(literal("alias").then(argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                RpGame.alias(p, IntegerArgumentType.getInteger(ctx, "id"));
                return 1;
            }))));

        // débrief de la dernière partie (écran), disponible à la fin de la partie
        d.register(literal("rpdebrief").executes(ctx -> {
            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
            if (!Debriefs.sendLast(p)) ctx.getSource().sendFeedback(() -> Msg.err("Aucun débrief disponible : il est établi à la fin de la partie."), false);
            return 1;
        }));

        // rouvre le menu de nom RP / affiche le nom actuel
        d.register(literal("rpnom").executes(ctx -> {
            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
            if (RpGame.hasConfirmed(p.getUuid())) {
                ctx.getSource().sendFeedback(() -> Msg.info("Votre nom RP : " + RpGame.confirmedName(p.getUuid())), false);
            } else {
                RpGame.promptName(p, "");
            }
            return 1;
        }));
    }

    private static int help(CommandContext<ServerCommandSource> ctx, String message) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String type = StringArgumentType.getString(ctx, "type").toLowerCase();
        if (!RpConfig.INSTANCE.helpTypes.contains(type)) {
            ctx.getSource().sendFeedback(() -> Msg.err("Type inconnu. Choix : " + String.join(", ", RpConfig.INSTANCE.helpTypes)), false);
            return 0;
        }
        String err = Tickets.create(p, type, message);
        if (err != null) {
            ctx.getSource().sendFeedback(() -> Msg.err(err), false);
            return 0;
        }
        return 1;
    }

    private static int rank(CommandContext<ServerCommandSource> ctx, boolean up) throws CommandSyntaxException {
        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
        String id = t.getUuidAsString();
        boolean changed = up ? RpData.INSTANCE.helpers.add(id) : RpData.INSTANCE.helpers.remove(id);
        RpData.save();
        String name = t.getGameProfile().getName();
        if (up) {
            ctx.getSource().sendFeedback(() -> Msg.ok(name + (changed ? " est maintenant helper." : " était déjà helper.")), true);
            if (changed) t.sendMessage(Msg.ok("Vous êtes maintenant helper : alertes d'aide et panneau d'assistance activés."), false);
        } else {
            ctx.getSource().sendFeedback(() -> Msg.ok(name + (changed ? " n'est plus helper." : " n'était pas helper.")), true);
            if (changed) t.sendMessage(Msg.info("Votre rang helper a été retiré."), false);
        }
        RpGame.syncKnowledge(t); // /r voirpseudo ne reste actif que pour le staff
        Staff.syncCaps(t);       // boutons / touches du menu helper
        Chats.syncTo(t);         // chat helper
        // met à jour l'arbre de commandes (/r) côté client
        if (ctx.getSource().getServer() != null) ctx.getSource().getServer().getPlayerManager().sendCommandTree(t);
        if (changed) Discord.log(up ? "Rang helper donné" : "Rang helper retiré", name + " " + (up ? "est maintenant helper" : "n'est plus helper") + " (par " + ctx.getSource().getName() + ").", up ? 0x2ECC71 : 0x95A5A6);
        return 1;
    }

    private static int setInactive(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean added = RpData.INSTANCE.inactive.add(p.getUuidAsString());
        if (added) RpData.save();
        ctx.getSource().sendFeedback(() -> added
            ? Msg.info("Vous êtes INACTIF : plus de notifications de demandes d'aide. Faites /r active pour les réactiver.")
            : Msg.info("Vous êtes déjà inactif. Faites /r active pour recevoir à nouveau les demandes d'aide."), false);
        return 1;
    }

    /** /r active : se remettre disponible (reçoit à nouveau les alertes d'aide). */
    private static int setActive(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean removed = RpData.INSTANCE.inactive.remove(p.getUuidAsString());
        if (removed) RpData.save();
        ctx.getSource().sendFeedback(() -> removed
            ? Msg.ok("Vous êtes ACTIF : vous recevez à nouveau les demandes d'aide.")
            : Msg.info("Vous étiez déjà actif."), false);
        return 1;
    }

    private static int forceChange(CommandContext<ServerCommandSource> ctx) {
        Target t = targetOrFail(ctx);
        if (t == null) return 0;
        String name = RpGame.clean(StringArgumentType.getString(ctx, "name"));
        var server = ctx.getSource().getServer();
        String err = RpGame.validate(server, t.uuid(), name, true);
        if (err != null) {
            ctx.getSource().sendFeedback(() -> Msg.err(err), false);
            return 0;
        }
        RpGame.setName(server, t.uuid(), name, "staff", ctx.getSource().getName());
        Discord.log("Nom RP imposé", t.name() + " → **" + name + "** (par " + ctx.getSource().getName() + ").", 0x3498DB);
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(t.uuid());
        if (online != null) online.sendMessage(Msg.info("Le staff a changé votre nom RP en : " + name), false);
        boolean off = online == null;
        ctx.getSource().sendFeedback(() -> Msg.ok("Nom RP de " + t.name() + " → " + name
            + (off ? " (hors ligne : appliqué dès sa reconnexion)" : "")), true);
        return 1;
    }

    private static int resetName(CommandContext<ServerCommandSource> ctx) {
        Target t = targetOrFail(ctx);
        if (t == null) return 0;
        var server = ctx.getSource().getServer();
        RpGame.resetName(server, t.uuid());
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(t.uuid());
        if (online != null) online.sendMessage(Msg.info("Le staff vous demande de redéfinir votre nom RP."), false);
        boolean off = online == null;
        ctx.getSource().sendFeedback(() -> Msg.ok(off
            ? "Nom RP de " + t.name() + " réinitialisé (hors ligne : le menu s'ouvrira à sa reconnexion)."
            : "Menu de nom RP rouvert pour " + t.name() + "."), true);
        return 1;
    }

    private static int ticket(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String err = Tickets.act(p, IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "action"));
        if (err != null) {
            ctx.getSource().sendFeedback(() -> Msg.err(err), false);
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Msg.ok("OK."), false);
        return 1;
    }

    // ================================================================ /r voirpseudo

    private static int toggleSeePseudo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String id = p.getUuidAsString();
        boolean on = !RpData.INSTANCE.seePseudo.contains(id);
        if (on) RpData.INSTANCE.seePseudo.add(id);
        else RpData.INSTANCE.seePseudo.remove(id);
        RpData.save();
        RpGame.syncKnowledge(p);
        ctx.getSource().sendFeedback(() -> on
            ? Msg.ok("Vrais pseudos VISIBLES en partie. Refaites /r voirpseudo pour les masquer.")
            : Msg.info("Vrais pseudos masqués : vous voyez comme un joueur (rien tant qu'on ne s'est pas présenté)."), false);
        return 1;
    }

    // ================================================================ cibles (en ligne ou hors ligne)

    private record Target(UUID uuid, String name) {}

    /**
     * Cherche les joueurs correspondant à {@code token} : pseudo exact en ligne, nom RP / pseudonyme d'un joueur en ligne,
     * puis joueurs HORS LIGNE (dernier pseudo connu, puis nom RP). Plusieurs résultats = ambigu.
     */
    private static List<Target> targets(CommandContext<ServerCommandSource> ctx, String token) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(token);
        if (online == null) online = RpGame.resolve(server, ctx.getSource().getPlayer(), true, token);
        if (online != null) return List.of(new Target(online.getUuid(), online.getGameProfile().getName()));
        // hors ligne : pseudo Minecraft
        for (Map.Entry<String, String> e : RpData.INSTANCE.usernames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(token)) {
                try {
                    return List.of(new Target(UUID.fromString(e.getKey()), e.getValue()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // hors ligne : nom RP (c'est le seul nom que le staff voit en partie quand un joueur a fui)
        List<Target> out = new java.util.ArrayList<>();
        for (UUID id : RpGame.offlineByRpName(token)) {
            out.add(new Target(id, RpData.INSTANCE.usernames.getOrDefault(id.toString(), id.toString())));
        }
        return out;
    }

    private static Target targetOrFail(CommandContext<ServerCommandSource> ctx) {
        List<Target> found = targets(ctx, StringArgumentType.getString(ctx, "player"));
        if (found.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Msg.err("Joueur introuvable (jamais connecté depuis l'installation du mod ?)."), false);
            return null;
        }
        if (found.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Target t : found) sb.append(sb.length() == 0 ? "" : ", ").append(t.name());
            ctx.getSource().sendFeedback(() -> Msg.err("Plusieurs joueurs correspondent (" + sb + ") : utilisez le pseudo Minecraft exact."), false);
            return null;
        }
        return found.get(0);
    }

    // ================================================================ warn / note

    private static int addRecord(CommandContext<ServerCommandSource> ctx, String kind, String textArg) {
        Target t = targetOrFail(ctx);
        if (t == null) return 0;
        String text = RpGame.clean(StringArgumentType.getString(ctx, textArg));
        if (text.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Msg.err("Le texte ne peut pas être vide."), false);
            return 0;
        }
        StaffRecord r = Records.add(kind, t.uuid().toString(), t.name(), ctx.getSource().getName(), text);
        boolean warn = StaffRecord.WARN.equals(kind);
        if (warn) Discord.log("Avertissement #" + r.id, t.name() + " : " + r.text + " (par " + ctx.getSource().getName() + ")", 0xE74C3C);
        int n = Records.count(r.targetUuid, kind);
        ctx.getSource().sendFeedback(() -> Msg.ok((warn ? "Avertissement" : "Note") + " #" + r.id + " enregistré(e) pour "
            + t.name() + " (" + n + (warn ? " avertissement(s)" : " note(s)") + " au total). Visible dans le panneau d'assistance."), true);

        if (warn) {
            ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(t.uuid());
            if (target != null) {
                Records.deliverWarn(target, r);
            } else {
                // joueur hors ligne (fuyard) : l'avertissement lui sera affiché à sa prochaine connexion
                r.pending = true;
                RpData.save();
                ctx.getSource().sendFeedback(() -> Msg.info(t.name() + " est hors ligne : il verra l'avertissement à sa prochaine connexion."), false);
            }
        }
        return 1;
    }

    private static int listRecords(CommandContext<ServerCommandSource> ctx) {
        Target t = targetOrFail(ctx);
        if (t == null) return 0;
        List<StaffRecord> list = Records.forPlayer(t.uuid().toString());
        if (list.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Msg.info("Aucun avertissement ni note pour " + t.name() + "."), false);
            return 1;
        }
        ctx.getSource().sendFeedback(() -> Msg.info(t.name() + " : " + Records.count(t.uuid().toString(), StaffRecord.WARN)
            + " avertissement(s), " + Records.count(t.uuid().toString(), StaffRecord.NOTE) + " note(s)."), false);
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            StaffRecord r = list.get(i);
            boolean warn = StaffRecord.WARN.equals(r.kind);
            ctx.getSource().sendFeedback(() -> Text.literal("#" + r.id + " ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(warn ? "[AVERT] " : "[NOTE] ").formatted(warn ? Formatting.RED : Formatting.AQUA))
                .append(Text.literal(DATE.format(new Date(r.createdMs)) + " · " + r.author + " : ").formatted(Formatting.GRAY))
                .append(Text.literal(r.text).formatted(Formatting.WHITE)), false);
        }
        if (list.size() > 15) ctx.getSource().sendFeedback(() -> Msg.info("… et " + (list.size() - 15) + " autre(s) dans le panneau d'assistance."), false);
        return 1;
    }

    private static int deleteRecord(CommandContext<ServerCommandSource> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        boolean ok = Records.delete(id);
        ctx.getSource().sendFeedback(() -> ok ? Msg.ok("Dossier #" + id + " supprimé.") : Msg.err("Dossier #" + id + " introuvable."), ok);
        return ok ? 1 : 0;
    }

    // ================================================================ historique des noms RP (Worker)

    private static String shortDate(String iso) {
        return iso == null ? "?" : iso.replace('T', ' ').replaceAll("(\\.\\d+)?Z?$", "").replaceAll(":\\d\\d$", "");
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static void showHistory(ServerCommandSource src, String title, RpHistory.Result r) {
        if (!r.ok()) {
            src.sendFeedback(() -> Msg.err("Historique indisponible : " + (r.error() == null ? "réponse invalide" : r.error())), false);
            return;
        }
        JsonArray arr = r.json().has("entries") ? r.json().getAsJsonArray("entries") : new JsonArray();
        if (arr.isEmpty()) {
            src.sendFeedback(() -> Msg.info("Aucun résultat pour " + title + "."), false);
            return;
        }
        src.sendFeedback(() -> Msg.info("Historique des noms RP — " + title + " (" + arr.size() + ") :"), false);
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            boolean alias = "alias".equals(str(o, "kind"));
            String who = str(o, "mc_username");
            String line = shortDate(str(o, "created_at")) + " · " + who + " → " + str(o, "rp_name")
                + (alias ? " (pseudonyme présenté à " + str(o, "shown_to_username") + ")" : "")
                + (!alias && !str(o, "previous_rp_name").isEmpty() ? " (avant : " + str(o, "previous_rp_name") + ")" : "")
                + (!alias && "staff".equals(str(o, "source")) ? " [staff : " + str(o, "set_by") + "]" : "")
                + (!alias && "kept".equals(str(o, "source")) ? " [conservé]" : "");
            src.sendFeedback(() -> Text.literal(line).formatted(alias ? Formatting.AQUA : Formatting.WHITE), false);
        }
    }

    private static int namesOfPlayer(CommandContext<ServerCommandSource> ctx) {
        Target t = targetOrFail(ctx);
        if (t == null) return 0;
        ServerCommandSource src = ctx.getSource();
        RpHistory.fetch("uuid=" + t.uuid() + "&limit=15", r -> showHistory(src, t.name(), r));
        return 1;
    }

    private static int namesSearch(CommandContext<ServerCommandSource> ctx) {
        String name = RpGame.clean(StringArgumentType.getString(ctx, "name"));
        ServerCommandSource src = ctx.getSource();
        RpHistory.fetch("q=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&limit=15", r -> showHistory(src, "« " + name + " »", r));
        return 1;
    }

    // ================================================================ /r webhook (OP)

    private static int webhookSet(CommandContext<ServerCommandSource> ctx) {
        String url = StringArgumentType.getString(ctx, "url").trim();
        if (!RpConfig.isDiscordWebhook(url)) {
            ctx.getSource().sendFeedback(() -> Msg.err("URL refusée : il faut une URL de webhook Discord (https://discord.com/api/webhooks/…)."), false);
            return 0;
        }
        RpConfig.INSTANCE.webhookUrl = url;
        RpConfig.save();
        // jamais d'écho de l'URL complète (le token donne le droit d'écrire dans le salon)
        ctx.getSource().sendFeedback(() -> Msg.ok("Webhook enregistré : " + RpConfig.maskWebhook(url) + " — testez avec /r webhook test."), true);
        return 1;
    }

    private static int webhookClear(CommandContext<ServerCommandSource> ctx) {
        RpConfig.INSTANCE.webhookUrl = "";
        RpConfig.save();
        ctx.getSource().sendFeedback(() -> Msg.info("Webhook supprimé : les tickets ne sont plus envoyés sur Discord."), true);
        return 1;
    }

    private static int webhookStatus(CommandContext<ServerCommandSource> ctx) {
        String u = RpConfig.INSTANCE.webhookUrl;
        ctx.getSource().sendFeedback(() -> Msg.info("Webhook Discord : " + RpConfig.maskWebhook(u)
            + (Discord.enabled() ? " (actif)" : " (désactivé)")), false);
        return 1;
    }

    private static int webhookTest(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        Discord.test(src.getServer(), msg -> src.sendFeedback(() -> Msg.info("Webhook : " + msg), false));
        return 1;
    }

    // ================================================================ /r worker (OP)

    private static int workerUrl(CommandContext<ServerCommandSource> ctx) {
        String url = StringArgumentType.getString(ctx, "url").trim();
        if (!RpConfig.isHttpsUrl(url)) {
            ctx.getSource().sendFeedback(() -> Msg.err("URL invalide : https://… requis (ex. https://relink.xxx.workers.dev)."), false);
            return 0;
        }
        RpConfig.INSTANCE.workerUrl = url.replaceAll("/+$", "");
        RpConfig.save();
        ctx.getSource().sendFeedback(() -> Msg.ok("URL du Worker : " + RpConfig.INSTANCE.workerUrl), true);
        return 1;
    }

    private static int workerSlug(CommandContext<ServerCommandSource> ctx) {
        String slug = StringArgumentType.getString(ctx, "slug").trim().toLowerCase(java.util.Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            ctx.getSource().sendFeedback(() -> Msg.err("Slug invalide (minuscules, chiffres, _ et -)."), false);
            return 0;
        }
        RpConfig.INSTANCE.serverSlug = slug;
        RpConfig.save();
        ctx.getSource().sendFeedback(() -> Msg.ok("Slug du serveur : " + slug), true);
        Relink.register(true, true);
        Relink.refreshAllOnline(true);
        return 1;
    }

    private static int workerKey(CommandContext<ServerCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key").trim();
        RpConfig.INSTANCE.workerApiKey = key;
        RpConfig.save();
        ctx.getSource().sendFeedback(() -> Msg.ok("Clé de service enregistrée : " + RpConfig.maskKey(key)), true);
        Relink.retry();
        RpHistory.flush();
        return 1;
    }

    private static int workerClear(CommandContext<ServerCommandSource> ctx) {
        RpConfig.INSTANCE.workerUrl = "";
        Relink.disconnect();
        ctx.getSource().sendFeedback(() -> Msg.info("Worker déconnecté : historique des noms RP et permissions du panel désactivés."), true);
        return 1;
    }

    private static int workerStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        RpConfig c = RpConfig.INSTANCE;
        src.sendFeedback(() -> Msg.info("Worker : " + (c.workerUrl.isBlank() ? "(URL non configurée)" : c.workerUrl)
            + " · clé " + RpConfig.maskKey(c.workerApiKey) + " · " + RpHistory.pending() + " événement(s) en attente d'envoi."), false);
        if (RpHistory.enabled()) {
            // test de lecture : vérifie à la fois l'URL, la clé et le scope rp.read
            RpHistory.fetch("limit=1", r -> src.sendFeedback(() -> r.ok()
                ? Msg.ok("Worker joignable, clé acceptée (lecture).")
                : Msg.err("Worker : " + (r.error() == null ? "réponse invalide" : r.error())
                    + (r.status() == 401 ? " — clé refusée ou scope rp.read manquant." : "")), false));
        }
        return 1;
    }

    // ================================================================ /relink (appairage, état, rechargement)

    private static int relinkConnect(CommandContext<ServerCommandSource> ctx, String url) {
        ServerCommandSource src = ctx.getSource();
        String code = StringArgumentType.getString(ctx, "code");
        src.sendFeedback(() -> Msg.info("Connexion à ReLink…"), false);
        Relink.pair(code, url, msg -> src.sendFeedback(() -> msg.startsWith("✅") ? Msg.ok(msg) : Msg.err(msg), true));
        return 1;
    }

    private static int relinkStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        RpConfig c = RpConfig.INSTANCE;
        src.sendFeedback(() -> Msg.info("ReLink : " + (Relink.configured() ? "serveur « " + c.serverSlug + " » · " + c.workerUrl : "non connecté (/relink connect <code>)")
            + " · clé " + RpConfig.maskKey(c.workerApiKey)), false);
        if (Relink.authFailed()) src.sendFeedback(() -> Msg.err("Clé refusée par le Worker : demandez un nouveau code dans le panel puis /relink connect <code>."), false);
        else if (!Relink.lastError.isEmpty()) src.sendFeedback(() -> Msg.err("Dernier problème : " + Relink.lastError), false);
        else if (Relink.lastOkMs > 0) src.sendFeedback(() -> Msg.ok("Dernier échange réussi il y a " + (System.currentTimeMillis() - Relink.lastOkMs) / 1000 + " s."), false);
        src.sendFeedback(() -> Msg.info(RpHistory.pending() + " événement(s) d'historique des noms RP en attente d'envoi."), false);
        ServerPlayerEntity p = src.getPlayer();
        if (p != null) {
            Relink.PlayerPerms pp = Relink.of(p.getUuid());
            src.sendFeedback(() -> Msg.info("Vos rôles : " + (pp == null ? "(pas encore reçus)" : pp.roles())
                + " · permissions : " + (pp == null ? "(aucune)" : pp.perms())
                + (Relink.hasAdminRole(p.getUuid()) ? " · ADMIN (toutes les permissions)" : "")), false);
        }
        return 1;
    }

    private static int relinkReload(CommandContext<ServerCommandSource> ctx, String player) {
        ServerCommandSource src = ctx.getSource();
        if (!Relink.configured()) {
            src.sendFeedback(() -> Msg.err("Serveur non connecté : /relink connect <code>."), false);
            return 0;
        }
        Relink.retry();
        if (player == null) {
            Relink.refreshAllOnline(true);
            src.sendFeedback(() -> Msg.ok("Permissions rechargées pour tous les joueurs connectés."), false);
            return 1;
        }
        List<Target> found = targets(ctx, player);
        if (found.size() != 1) {
            src.sendFeedback(() -> Msg.err("Joueur introuvable ou ambigu."), false);
            return 0;
        }
        Relink.refresh(found.get(0).uuid(), true);
        src.sendFeedback(() -> Msg.ok("Permissions de " + found.get(0).name() + " rechargées."), false);
        return 1;
    }

    private static int relinkDisconnect(CommandContext<ServerCommandSource> ctx) {
        Relink.disconnect();
        ctx.getSource().sendFeedback(() -> Msg.info("Serveur déconnecté de ReLink (clé effacée)."), true);
        return 1;
    }
}
