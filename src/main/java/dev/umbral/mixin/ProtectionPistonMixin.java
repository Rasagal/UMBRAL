// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class ProtectionPistonMixin {
    @Inject(method="triggerEvent",at=@At("HEAD"),cancellable=true)
    private void umbral$head(net.minecraft.world.level.block.state.BlockState state,Level level,BlockPos pos,int event,int data,CallbackInfoReturnable<Boolean> ci){if(!ProtectionPolicy.automation(level,pos,pos.relative(state.getValue(PistonBaseBlock.FACING)),ProtectionData.PISTONS,"piston"))ci.setReturnValue(false);}

    @Inject(method="moveBlocks",at=@At("HEAD"),cancellable=true)
    private void umbral$move(Level level,BlockPos pos,Direction facing,boolean extending,CallbackInfoReturnable<Boolean> ci){
        if(level.isClientSide())return;
        if(!ProtectionPolicy.automation(level,pos,pos.relative(facing),ProtectionData.PISTONS,"piston")){ci.setReturnValue(false);return;}
        var resolver=new PistonStructureResolver(level,pos,facing,extending);if(!resolver.resolve())return;var direction=extending?facing:facing.getOpposite();
        for(var block:resolver.getToPush())if(!ProtectionPolicy.automation(level,pos,block,ProtectionData.PISTONS,"piston")||!ProtectionPolicy.automation(level,block,block.relative(direction),ProtectionData.PISTONS,"piston")){ci.setReturnValue(false);return;}
        for(var block:resolver.getToDestroy())if(!ProtectionPolicy.automation(level,pos,block,ProtectionData.PISTONS,"piston")){ci.setReturnValue(false);return;}
    }
}
