package fr.watherp.mixin;

import fr.watherp.client.ClientRp;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Côté client uniquement : en partie, le nom affiché d'un joueur (HUD de Wathe, etc.) est son nom RP
 * s'il s'est présenté à nous, rien sinon (sauf staff avec /r voirpseudo).
 */
@Mixin(Entity.class)
public abstract class EntityDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void watherp$rpName(CallbackInfoReturnable<Text> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PlayerEntity p && self.getWorld() != null && self.getWorld().isClient) {
            Text t = ClientRp.displayName(p);
            if (t != null) cir.setReturnValue(t);
        }
    }
}
