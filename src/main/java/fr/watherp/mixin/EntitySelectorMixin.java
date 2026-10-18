package fr.watherp.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.watherp.RpGame;
import fr.watherp.Staff;
import net.minecraft.command.EntitySelector;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Les sélecteurs de joueur par nom (/tp Jean, /msg Jean...) acceptent aussi les noms RP et pseudonymes RP
 * (espaces remplaçables par « _ », accents et casse ignorés).
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @WrapOperation(
        method = {"getPlayers", "getPlayer"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/PlayerManager;getPlayer(Ljava/lang/String;)Lnet/minecraft/server/network/ServerPlayerEntity;"))
    private ServerPlayerEntity watherp$resolveRpName(PlayerManager manager, String name,
                                                    Operation<ServerPlayerEntity> original, ServerCommandSource source) {
        ServerPlayerEntity real = original.call(manager, name);
        if (real != null) return real;
        try {
            return RpGame.resolve(source.getServer(), source.getPlayer(), Staff.isStaff(source), name);
        } catch (Throwable t) {
            return null;
        }
    }
}
