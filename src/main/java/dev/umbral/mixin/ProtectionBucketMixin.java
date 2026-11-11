// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Deny before the pickup transfers a fluid item, including waterlogged blocks. */
@Mixin(BucketItem.class)
public abstract class ProtectionBucketMixin {
    @WrapOperation(method="use",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack umbral$pickup(BucketPickup block,LivingEntity actor,LevelAccessor level,BlockPos pos,BlockState state,Operation<ItemStack> original){
        if(actor instanceof Player p&&!ProtectionPolicy.player(p,pos,ProtectionData.BUILD,"place"))return ItemStack.EMPTY;
        return original.call(block,actor,level,pos,state);
    }
    @Inject(method="emptyContents",at=@At("HEAD"),cancellable=true)
    private void umbral$empty(LivingEntity actor,Level level,BlockPos pos,BlockHitResult hit,CallbackInfoReturnable<Boolean> ci){
        if(actor instanceof Player p&&!ProtectionPolicy.player(p,pos,ProtectionData.BUILD,"place"))ci.setReturnValue(false);
    }
}
