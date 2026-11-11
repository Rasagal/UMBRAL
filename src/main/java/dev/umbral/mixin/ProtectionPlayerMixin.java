// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.umbral.protection.ProtectionPolicy;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;

/** Covers multi-block items and mutations caused by player item interactions, including buckets. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ProtectionPlayerMixin {
    @Shadow protected ServerPlayer player;
    @WrapMethod(method="destroyBlock")
    private boolean umbral$break(net.minecraft.core.BlockPos pos,Operation<Boolean> original){
        var previous=ProtectionPolicy.enter(player);try{return original.call(pos);}finally{ProtectionPolicy.leave(previous);}
    }
    @WrapMethod(method="useItemOn")
    private InteractionResult umbral$useOn(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original){
        var previous=ProtectionPolicy.enter(player);try{return original.call(player,level,stack,hand,hit);}finally{ProtectionPolicy.leave(previous);}
    }
    @WrapMethod(method="useItem")
    private InteractionResult umbral$use(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,Operation<InteractionResult> original){
        var previous=ProtectionPolicy.enter(player);try{return original.call(player,level,stack,hand);}finally{ProtectionPolicy.leave(previous);}
    }
}
