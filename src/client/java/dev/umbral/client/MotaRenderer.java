// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.logistics.MotaEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;

/** Source mesh shared with the held item; only fans and a genuinely carried package move. */
public final class MotaRenderer extends EntityRenderer<MotaEntity,MotaRenderer.State> {
    public static final class State extends EntityRenderState {
        final ItemStackRenderState body=new ItemStackRenderState(),fanLeft=new ItemStackRenderState(),fanRight=new ItemStackRenderState(),parcel=new ItemStackRenderState();
        float yaw,spin;boolean carrying;
    }
    private final ItemModelResolver models;
    private ItemStack body,left,right,parcel;
    public MotaRenderer(EntityRendererProvider.Context context){super(context);models=context.getItemModelResolver();shadowRadius=.6f;}
    private static ItemStack piece(String name){var stack=new ItemStack(UmbralContent.MOTA);stack.set(DataComponents.ITEM_MODEL,UmbralContent.id(name));return stack;}
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(MotaEntity entity,State s,float delta){
        super.extractRenderState(entity,s,delta);
        if(body==null){body=piece("mota_body");left=piece("mota_fan_left");right=piece("mota_fan_right");parcel=piece("mota_parcel");}
        s.yaw=180-entity.getYRot();s.spin=(entity.tickCount+delta)*(entity.flying()?65:12);s.carrying=entity.carrying();
        models.updateForTopItem(s.body,body,ItemDisplayContext.NONE,entity.level(),null,0);
        models.updateForTopItem(s.fanLeft,left,ItemDisplayContext.NONE,entity.level(),null,0);
        models.updateForTopItem(s.fanRight,right,ItemDisplayContext.NONE,entity.level(),null,0);
        models.updateForTopItem(s.parcel,parcel,ItemDisplayContext.NONE,entity.level(),null,0);
    }
    @Override public void submit(State s,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){
        poses.pushPose();poses.rotateDegrees(Axis.YP,s.yaw);poses.translate(0,.5,0);s.body.submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);
        if(s.carrying)s.parcel.submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);
        for(int side:new int[]{-1,1}){poses.pushPose();double pivot=(side<0?-6:6)/16d;poses.translate(pivot,0,.3/16);poses.rotateDegrees(Axis.YP,s.spin*side);poses.translate(-pivot,0,-.3/16);(side<0?s.fanLeft:s.fanRight).submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);poses.popPose();}
        poses.popPose();super.submit(s,poses,collector,camera);
    }
}
