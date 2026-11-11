// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Prevent the player's eye-height interpolation from moving the fixed lens through a ceiling. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("eyeHeight") void umbral$setEyeHeight(float value);
    @Accessor("eyeHeightOld") void umbral$setOldEyeHeight(float value);
}
