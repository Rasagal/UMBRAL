// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class ProtectionFluidMixin {
    @Inject(method="canPassThrough",at=@At("HEAD"),cancellable=true)
    private void umbral$flow(BlockGetter getter,Fluid fluid,BlockPos from,BlockState fromState,Direction direction,BlockPos to,BlockState toState,FluidState toFluid,CallbackInfoReturnable<Boolean> ci){if(getter instanceof Level level&&!ProtectionPolicy.automation(level,from,to,ProtectionData.FLUIDS,"fluid"))ci.setReturnValue(false);}
}
