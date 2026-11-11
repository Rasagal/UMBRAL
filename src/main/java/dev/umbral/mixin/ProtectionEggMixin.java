// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.ProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TurtleEggBlock.class)
public abstract class ProtectionEggMixin {
    @Inject(method="destroyEgg",at=@At("HEAD"),cancellable=true)
    private void umbral$step(Level level,BlockState state,BlockPos pos,Entity entity,int chance,CallbackInfo ci){if(!ProtectionPolicy.entityChange(level,pos,entity))ci.cancel();}
}
