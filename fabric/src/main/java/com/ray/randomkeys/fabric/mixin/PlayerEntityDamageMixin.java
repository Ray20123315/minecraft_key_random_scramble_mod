package com.ray.randomkeys.fabric.mixin;

import com.ray.randomkeys.fabric.RandomKeysFabric;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDamageMixin {
    @Unique private float randomKeysSurvival$healthBeforeDamage;
    @Unique private boolean randomKeysSurvival$trackDamage;

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void randomKeysSurvival$captureHealthBefore(DamageSource source, float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity player) {
            randomKeysSurvival$trackDamage = true;
            randomKeysSurvival$healthBeforeDamage = player.getHealth();
        } else {
            randomKeysSurvival$trackDamage = false;
        }
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void randomKeysSurvival$mutateAfterRealHealthLoss(DamageSource source, float amount, CallbackInfo ci) {
        if (!randomKeysSurvival$trackDamage || !((Object) this instanceof ServerPlayerEntity player)) return;
        float before = randomKeysSurvival$healthBeforeDamage;
        randomKeysSurvival$trackDamage = false;
        if (player.getHealth() < before) RandomKeysFabric.onActualHealthDamage(player);
    }
}
