// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({DispenserBlock.class,DropperBlock.class})
public abstract class ProtectionDispenserMixin {
    @Inject(method="dispenseFrom",at=@At("HEAD"),cancellable=true)
    private void umbral$dispense(ServerLevel level,BlockState state,BlockPos pos,CallbackInfo ci){if(!ProtectionPolicy.automation(level,pos,pos.relative(state.getValue(DispenserBlock.FACING)),ProtectionData.CONTAINERS,"automation"))ci.cancel();}
}
