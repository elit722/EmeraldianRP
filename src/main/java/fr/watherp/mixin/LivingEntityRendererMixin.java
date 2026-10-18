package fr.watherp.mixin;

import fr.watherp.client.ClientRp;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Un joueur « inconnu » n'a pas de nom affiché : on supprime aussi son étiquette au-dessus de la tête,
 * sinon Minecraft dessinerait un petit cadre vide à la place du nom.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void watherp$hideUnknownLabel(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity p && ClientRp.isHidden(p)) cir.setReturnValue(false);
    }
}
