package fr.watherp.mixin;

import fr.watherp.client.ClientRp;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Même chose que EntityDisplayNameMixin, au cas où PlayerEntity redéfinit getDisplayName. */
@Mixin(PlayerEntity.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void watherp$rpName(CallbackInfoReturnable<Text> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getWorld() != null && self.getWorld().isClient) {
            Text t = ClientRp.displayName(self);
            if (t != null) cir.setReturnValue(t);
        }
    }
}
