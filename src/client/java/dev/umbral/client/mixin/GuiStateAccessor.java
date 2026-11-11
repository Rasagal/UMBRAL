// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiGraphicsExtractor.class)
public interface GuiStateAccessor {
    @Accessor("guiRenderState") GuiRenderState umbral$guiState();
}
