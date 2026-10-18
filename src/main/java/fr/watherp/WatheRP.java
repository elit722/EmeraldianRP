package fr.watherp;

import dev.doctor4t.wathe.api.event.GameEvents;
import fr.watherp.net.Net;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatheRP implements ModInitializer {
    public static final String MOD_ID = "watherp";
    public static final Logger LOGGER = LoggerFactory.getLogger("WatheRP");

    @Override
    public void onInitialize() {
        RpConfig.load();
        RpData.load();
        Chats.load();
        Net.registerTypes();
        ServerNet.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> Commands.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RpGame.tick(server);
            RpHistory.tick();
            Records.tick(server);
            Relink.tick();
            Chats.tick();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RpHistory.setServer(server);
            Relink.setServer(server);
            RpGame.onServerStarted(server);
            RpHistory.flush(); // renvoie ce qui était resté en attente avant l'arrêt
            if (Relink.configured()) Relink.register(true, true); // déclare les permissions + premier heartbeat
            else WatheRP.LOGGER.info("ReLink : non connecté. Tape « /relink connect <code> » (console ou OP) avec le code affiché dans le panel.");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Relink.shutdown(); // online=false (au mieux)
            Chats.saveNow();
            RpHistory.setServer(null);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            // dernier pseudo connu : permet de cibler un joueur HORS LIGNE avec /r warn, /r note...
            String name = p.getGameProfile().getName();
            if (!name.equals(RpData.INSTANCE.usernames.put(p.getUuidAsString(), name))) RpData.save();
            Relink.refresh(p.getUuid(), true); // permissions du panel (asynchrone) ; l'interface est mise à jour à leur arrivée
            Staff.syncCaps(p);
            RpGame.syncKnowledge(p);
            Records.onJoin(p); // avertissements posés pendant son absence
            Chats.onJoin(p);   // liste des chats + popup des chats ouverts pendant son absence
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Relink.forget(p.getUuid());
            Chats.onDisconnect(p);
        });

        // Wathe : début / fin de partie
        GameEvents.ON_FINISH_INITIALIZE.register((world, gameComponent) -> {
            if (world.getServer() != null) RpGame.onGameInit(world.getServer());
        });
        GameEvents.ON_FINISH_FINALIZE.register((world, gameComponent) -> {
            if (world.getServer() != null) RpGame.onGameEnd(world.getServer());
        });

        LOGGER.info("Wathe RP chargé.");
    }

}
