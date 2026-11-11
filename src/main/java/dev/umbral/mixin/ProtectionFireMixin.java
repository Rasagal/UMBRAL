// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class ProtectionFireMixin {
    @Inject(method="checkBurnOut",at=@At("HEAD"),cancellable=true)
    private void umbral$burn(Level level,BlockPos pos,int chance,RandomSource random,int age,CallbackInfo ci){if(ProtectionPolicy.environmental(level,pos,ProtectionData.FIRE,"fire"))ci.cancel();}
}
