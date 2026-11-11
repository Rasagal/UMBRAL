// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.ProtectionPolicy;
import dev.umbral.protection.ProtectionData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TntBlock.class)
public abstract class ProtectionTntMixin {
    @Inject(method="prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",at=@At("HEAD"),cancellable=true)
    private static void umbral$prime(Level level,BlockPos pos,LivingEntity source,ItemStack weapon,CallbackInfoReturnable<Boolean> ci){
        var actor=source==null?ProtectionPolicy.actor():source;
        boolean allowed=actor==null?!ProtectionPolicy.environmental(level,pos,ProtectionData.EXPLOSIONS,"explosion"):ProtectionPolicy.entityChange(level,pos,actor);
        if(!allowed)ci.setReturnValue(false);
    }
    @Inject(method="onProjectileHit",at=@At("HEAD"),cancellable=true)
    private void umbral$ignite(Level level,BlockState state,BlockHitResult hit,Projectile projectile,CallbackInfo ci){if(!ProtectionPolicy.entityChange(level,hit.getBlockPos(),projectile))ci.cancel();}
}
