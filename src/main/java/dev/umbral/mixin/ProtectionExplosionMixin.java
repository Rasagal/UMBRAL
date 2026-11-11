// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(ServerExplosion.class)
public abstract class ProtectionExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="calculateExplodedPositions",at=@At("RETURN"),cancellable=true)
    private void umbral$blocks(CallbackInfoReturnable<List<BlockPos>> ci){var out=new ArrayList<>(ci.getReturnValue());out.removeIf(p->ProtectionPolicy.environmental(level,p,ProtectionData.EXPLOSIONS,"explosion"));ci.setReturnValue(out);}
}
