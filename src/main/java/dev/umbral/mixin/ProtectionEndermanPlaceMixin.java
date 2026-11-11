// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.world.entity.monster.Enderman$EndermanLeaveBlockGoal")
public abstract class ProtectionEndermanPlaceMixin {
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),cancellable=true)
    private void umbral$put(CallbackInfo ci,@Local(ordinal=0) Level level,@Local(ordinal=0) BlockPos pos){if(ProtectionPolicy.environmental(level,pos,ProtectionData.MOBS,"mob"))ci.cancel();}
}
