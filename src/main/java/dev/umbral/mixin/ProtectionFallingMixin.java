// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A rejected landing follows vanilla's item-drop branch; the block is never duplicated. */
@Mixin(FallingBlockEntity.class)
public abstract class ProtectionFallingMixin {
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean umbral$landing(Level level,BlockPos pos,BlockState state,Operation<Boolean> original){
        var entity=(FallingBlockEntity)(Object)this;
        return ProtectionPolicy.automation(level,entity.getStartPos(),pos,ProtectionData.PISTONS,"piston")&&original.call(level,pos,state);
    }
}
