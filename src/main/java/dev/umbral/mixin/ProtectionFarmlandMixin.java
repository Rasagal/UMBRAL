// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.ProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class ProtectionFarmlandMixin {
    @Inject(method="turnToBaseBlock",at=@At("HEAD"),cancellable=true)
    private void umbral$trample(Entity entity,BlockState state,Level level,BlockPos pos,CallbackInfo ci){if(!ProtectionPolicy.entityChange(level,pos,entity))ci.cancel();}
}
