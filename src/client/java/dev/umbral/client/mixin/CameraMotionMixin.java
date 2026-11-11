// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client.mixin;

import dev.umbral.client.CameraViewScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sample the same frame time used by block entities before vanilla positions the live lens. */
@Mixin(Camera.class)
public abstract class CameraMotionMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void umbral$servoFrame(DeltaTracker tracker, CallbackInfo ci) {
        CameraViewScreen.beforeCameraFrame(tracker.getGameTimeDeltaPartialTick(true));
    }
}
