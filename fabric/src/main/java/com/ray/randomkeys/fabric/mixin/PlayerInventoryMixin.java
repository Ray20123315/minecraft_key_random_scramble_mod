package com.ray.randomkeys.fabric.mixin;

import com.ray.randomkeys.fabric.RandomKeysFabricClient;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Inject(method = "scrollInHotbar", at = @At("HEAD"), cancellable = true)
    private void randomKeysSurvival$blockHotbarScroll(double scrollAmount, CallbackInfo ci) {
        if (RandomKeysFabricClient.shouldBlockHotbarScroll()) ci.cancel();
    }
}
