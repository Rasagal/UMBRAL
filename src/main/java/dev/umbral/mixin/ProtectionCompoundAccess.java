// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import net.minecraft.world.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface ProtectionCompoundAccess {
    @Accessor("container1") Container umbral$first();
    @Accessor("container2") Container umbral$second();
}
