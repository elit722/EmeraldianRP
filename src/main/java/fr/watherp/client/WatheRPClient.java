package fr.watherp.client;

import fr.watherp.net.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

public class WatheRPClient implements ClientModInitializer {
    public static KeyBinding panelKey, acceptKey, aliasKey, helpKey, commandsKey, chatsKey;

    /**
     * Objets Wathe avec lesquels un clic droit sur un joueur NE déclenche PAS la présentation : tous les autres objets Wathe
     * (couteau, armes, grenade, cocktails, sac mortuaire, crochet…) ont une action au clic droit qu'il ne faut pas voler.
     * Main vide, clé, note ou objet d'un autre mod : le clic droit ouvre la présentation.
     */
    private static final Set<String> RIGHT_CLICK_OK = Set.of("key", "note");

    private boolean prevUse = false;

    @Override
    public void onInitializeClient() {
        ClientRp.loadPrefs();
        panelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.panel", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.watherp"));
        acceptKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.accept", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.watherp"));
        aliasKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.alias", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.watherp"));

        helpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.help", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.watherp"));
        commandsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.commands", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.watherp"));
        chatsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.watherp.chats", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, "category.watherp"));

        // ---------------- réception des paquets serveur -> client
        ClientPlayNetworking.registerGlobalReceiver(Net.OpenName.ID, (payload, ctx) -> {
            // rappel périodique : ne pas écraser un menu déjà ouvert (le joueur est peut-être en train de taper)
            if (ctx.client().currentScreen instanceof NameEntryScreen && payload.error().isEmpty()) return;
            ctx.client().setScreen(new NameEntryScreen(payload.previous(), payload.error(), payload.mandatory()));
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.CloseName.ID, (payload, ctx) -> {
            if (ctx.client().currentScreen instanceof NameEntryScreen) ctx.client().setScreen(null);
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.Caps.ID, (payload, ctx) -> ClientRp.helper = payload.helper());

        ClientPlayNetworking.registerGlobalReceiver(Net.OpenHelp.ID, (payload, ctx) -> {
            if (ctx.client().currentScreen instanceof NameEntryScreen) return; // ne pas masquer le menu de nom RP obligatoire
            ctx.client().setScreen(new HelpScreen(payload.types()));
        });

        // ---- chat intégré
        ClientPlayNetworking.registerGlobalReceiver(Net.ChatSync.ID, (payload, ctx) -> {
            ClientRp.chats = new java.util.ArrayList<>(payload.chats());
            if (ctx.client().currentScreen instanceof ChatsScreen s) s.onUpdate();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.ChatHistory.ID, (payload, ctx) -> {
            ClientRp.history.put(payload.chat(), new java.util.ArrayList<>(payload.lines()));
            if (ctx.client().currentScreen instanceof ChatsScreen s) s.onUpdate();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.ChatPush.ID, (payload, ctx) -> {
            ClientRp.onChatPush(payload);
            if (ctx.client().currentScreen instanceof ChatsScreen s) s.onUpdate();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.ChatInvite.ID, (payload, ctx) -> ClientRp.onChatInvite(payload));
        ClientPlayNetworking.registerGlobalReceiver(Net.ChatOpen.ID, (payload, ctx) -> {
            if (ctx.client().currentScreen instanceof NameEntryScreen) return;
            ctx.client().setScreen(new ChatsScreen(payload.chat()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.PlayerList.ID, (payload, ctx) -> {
            var cb = ClientRp.pendingPlayers;
            ClientRp.pendingPlayers = null;
            if (cb != null) cb.accept(payload.players());
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.Sync.ID, (payload, ctx) -> {
            ClientRp.known = new java.util.HashMap<>(payload.known());
            ClientRp.own = payload.own();
            ClientRp.confirmed = payload.confirmed();
            ClientRp.seePseudo = payload.seePseudo();
            ClientRp.onAliasList(payload.aliased());
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.Revealed.ID, (payload, ctx) -> ClientRp.onRevealed(payload));

        ClientPlayNetworking.registerGlobalReceiver(Net.Proposal.ID, (payload, ctx) -> ClientRp.onProposal(payload));
        ClientPlayNetworking.registerGlobalReceiver(Net.ProposalGone.ID, (payload, ctx) -> ClientRp.onProposalGone(payload.id()));

        ClientPlayNetworking.registerGlobalReceiver(Net.OpenAlias.ID, (payload, ctx) -> {
            int id = payload.proposalId();
            ClientRp.pauseProp(ClientRp.prop(id), false); // le serveur a déjà suspendu l'expiration
            ctx.client().setScreen(new AliasScreen("Sous quel pseudonyme voulez-vous vous présenter ?", alias -> {
                ClientPlayNetworking.send(new Net.Reply(id, true, alias));
                ClientRp.removeProposal(id);
            }).onCancel(() -> ClientRp.resumeProp(id)));
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.Alert.ID, (payload, ctx) -> ClientRp.onAlert(payload));

        ClientPlayNetworking.registerGlobalReceiver(Net.Tickets.ID, (payload, ctx) -> {
            ClientRp.tickets = payload.list();
            MinecraftClient mc = ctx.client();
            if (mc.currentScreen instanceof AssistPanelScreen s) {
                s.update(payload.list());
            } else if (ClientRp.wantPanel) {
                ClientRp.wantPanel = false;
                mc.setScreen(new AssistPanelScreen(payload.list()));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.Records.ID, (payload, ctx) -> {
            ClientRp.records = payload.list();
            if (ctx.client().currentScreen instanceof AssistPanelScreen s) s.onRecordsUpdated();
        });

        // retour des commandes tapées dans le panneau : on garde les messages système reçus tant qu'il est ouvert
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            var cur = MinecraftClient.getInstance().currentScreen;
            if (!overlay && (cur instanceof AssistPanelScreen || cur instanceof CommandScreen)) {
                ClientRp.pushFeedback(message.getString());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientRp.reset());

        // ---------------- HUD
        HudRenderCallback.EVENT.register(HudOverlay::render);

        // ---------------- bouton « Aide » dans le menu pause
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // la proposition de présentation reste visible et utilisable par-dessus n'importe quel écran ouvert
            ScreenEvents.afterRender(screen).register((sc, ctx, mx, my, delta) -> HudOverlay.renderProposalOverScreen(ctx));
            ScreenKeyboardEvents.allowKeyPress(screen).register((sc, key, scancode, modifiers) -> !answerProposalFromScreen(sc, key, scancode));
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aide"), b -> requestHelp())
                    .dimensions(scaledWidth - 90, 10, 80, 20).build());
            }
        });

        // ---------------- clic droit sur un joueur (main principale seulement : l'évènement est aussi émis pour la main secondaire)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            // CONSUME (et non FAIL) : le clic est « accepté », donc l'objet tenu n'est pas utilisé en plus
            if (world.isClient && hand == Hand.MAIN_HAND && tryIntroduce(entity)) return ActionResult.CONSUME;
            return ActionResult.PASS;
        });

        // ---------------- touches + repli pour le clic droit
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            while (panelKey.wasPressed()) {
                ClientRp.wantPanel = true;
                ClientPlayNetworking.send(new Net.RequestTickets());
            }
            while (acceptKey.wasPressed()) {
                Net.Proposal p = ClientRp.activeProposal();
                if (p != null) {
                    ClientPlayNetworking.send(new Net.Reply(p.id(), false, ""));
                    ClientRp.removeProposal(p.id());
                }
            }
            while (aliasKey.wasPressed()) {
                Net.Proposal p = ClientRp.activeProposal();
                if (p != null) openAlias(mc, p.id(), p.fromName());
            }
            while (helpKey.wasPressed()) {
                if (mc.currentScreen == null) requestHelp();
            }
            while (commandsKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new CommandScreen());
            }
            while (chatsKey.wasPressed()) {
                if (mc.currentScreen != null) continue;
                // une popup (invitation ou message) active : la touche ouvre directement ce chat
                ClientRp.Toast inv = ClientRp.latestToast();
                int focus = inv == null ? -1 : inv.chat;
                if (inv != null) ClientRp.toasts.remove(inv);
                mc.setScreen(new ChatsScreen(focus));
            }
            boolean use = mc.options.useKey.isPressed();
            if (use && !prevUse && mc.currentScreen == null && mc.crosshairTarget != null
                && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
                tryIntroduce(((EntityHitResult) mc.crosshairTarget).getEntity());
            }
            prevUse = use;
        });
    }

    /**
     * Touches de présentation pressées alors qu'un écran est ouvert (les touches normales n'y fonctionnent pas).
     * Ignorées quand un champ de texte a le focus (on est en train d'écrire).
     * @return true si la touche a été consommée
     */
    private static boolean answerProposalFromScreen(net.minecraft.client.gui.screen.Screen sc, int key, int scancode) {
        Net.Proposal p = ClientRp.activeProposal();
        if (p == null || sc.getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (acceptKey != null && !acceptKey.isUnbound() && acceptKey.matchesKey(key, scancode)) {
            ClientPlayNetworking.send(new Net.Reply(p.id(), false, ""));
            ClientRp.removeProposal(p.id());
            return true;
        }
        if (aliasKey != null && !aliasKey.isUnbound() && aliasKey.matchesKey(key, scancode)) {
            openAlias(mc, p.id(), p.fromName());
            return true;
        }
        return false;
    }

    /** Ouvre la saisie du pseudonyme : l'expiration de la demande est suspendue tant que l'écran est ouvert. */
    public static void openAlias(MinecraftClient mc, int id, String fromName) {
        ClientRp.pauseProp(ClientRp.prop(id), true);
        mc.setScreen(new AliasScreen("Sous quel pseudonyme voulez-vous vous présenter à " + fromName + " ?", alias -> {
            ClientPlayNetworking.send(new Net.Reply(id, true, alias));
            ClientRp.removeProposal(id);
        }).onCancel(() -> ClientRp.resumeProp(id)));
    }

    /** Demande au serveur d'ouvrir le menu « Aide » (il renvoie les types de demande acceptés). */
    public static void requestHelp() {
        ClientPlayNetworking.send(new Net.RequestHelp());
    }

    /** Ouvre le menu de présentation (clic droit sur un joueur) si les conditions sont réunies. @return true si le clic est consommé. */
    private static boolean tryIntroduce(Entity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity me = mc.player;
        if (me == null || mc.currentScreen != null) return false;
        if (!(entity instanceof PlayerEntity target) || target == me) return false;
        if (me.isSpectator() || me.isCreative() || target.isSpectator()) return false;
        if (!ClientRp.gameRunning()) return false;
        if (me.squaredDistanceTo(target) > 6 * 6) return false;
        // une seule présentation par personne : déjà présentés = le clic droit reste un clic droit normal (le serveur revérifie)
        if (ClientRp.known.containsKey(target.getUuid())) return false;
        ItemStack held = me.getMainHandStack();
        if (!held.isEmpty()) {
            Identifier id = Registries.ITEM.getId(held.getItem());
            if (id.getNamespace().equals("wathe") && !RIGHT_CLICK_OK.contains(id.getPath())) return false;
        }
        mc.setScreen(new IntroduceScreen(target.getUuid()));
        return true;
    }
}
