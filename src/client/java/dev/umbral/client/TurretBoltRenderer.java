// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.turret.TurretBolt;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.resources.Identifier;

public final class TurretBoltRenderer extends ArrowRenderer<TurretBolt,ArrowRenderState> {
    public TurretBoltRenderer(EntityRendererProvider.Context context){super(context);}
    @Override public ArrowRenderState createRenderState(){return new ArrowRenderState();}
    @Override protected Identifier getTextureLocation(ArrowRenderState state){return Identifier.withDefaultNamespace("textures/entity/projectiles/arrow.png");}
}
