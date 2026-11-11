// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.ProtectionInventories;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class ProtectionHopperMixin {
    @Inject(method="canTakeItemFromContainer",at=@At("HEAD"),cancellable=true)
    private static void umbral$take(Container a,Container b,ItemStack stack,int slot,Direction direction,CallbackInfoReturnable<Boolean> ci){if(!ProtectionInventories.transfer(a,b))ci.setReturnValue(false);}
    @Inject(method="tryMoveInItem",at=@At("HEAD"),cancellable=true)
    private static void umbral$insert(Container from,Container to,ItemStack stack,int slot,Direction direction,CallbackInfoReturnable<ItemStack> ci){if(!ProtectionInventories.transfer(from,to))ci.setReturnValue(stack);}
    @Inject(method="addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z",at=@At("HEAD"),cancellable=true)
    private static void umbral$pickup(Container to,ItemEntity entity,CallbackInfoReturnable<Boolean> ci){if(!ProtectionInventories.pickup(to,entity))ci.setReturnValue(false);}
}
