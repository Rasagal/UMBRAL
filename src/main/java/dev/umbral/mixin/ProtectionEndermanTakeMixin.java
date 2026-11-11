// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class ProtectionEndermanTakeMixin {
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"),cancellable=true)
    private void umbral$take(CallbackInfo ci,@Local(ordinal=0) Level level,@Local(ordinal=0) BlockPos pos){if(ProtectionPolicy.environmental(level,pos,ProtectionData.MOBS,"mob"))ci.cancel();}
}
