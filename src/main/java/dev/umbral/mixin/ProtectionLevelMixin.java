// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class ProtectionLevelMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void umbral$mutation(BlockPos pos,BlockState state,int flags,int recursion,CallbackInfoReturnable<Boolean> ci){if(state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock&&ProtectionPolicy.environmental((Level)(Object)this,pos,ProtectionData.FIRE,"fire")||!ProtectionPolicy.mutation((Level)(Object)this,pos,state))ci.setReturnValue(false);}
    @Inject(method="destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",at=@At("HEAD"),cancellable=true)
    private void umbral$mob(BlockPos pos,boolean drops,Entity entity,int recursion,CallbackInfoReturnable<Boolean> ci){if(!ProtectionPolicy.mutation((Level)(Object)this,pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())||entity!=null&&!(entity instanceof Player)&&ProtectionPolicy.environmental((Level)(Object)this,pos,ProtectionData.MOBS,"mob"))ci.setReturnValue(false);}
}
