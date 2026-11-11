// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.CentellaBlock;
import dev.umbral.block.DeviceKind;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.turret.TurretState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

public final class TurretRenderer implements BlockEntityRenderer<DeviceBlockEntity,TurretRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        final ItemStackRenderState head=new ItemStackRenderState(),crown=new ItemStackRenderState(),carriage=new ItemStackRenderState();
        float yaw,pitch,recoil,draw; boolean metal;
    }
    private final ItemModelResolver models;
    private ItemStack[] heads;
    private ItemStack[] crowns;
    private ItemStack carriage;
    private ItemStack[] metalHeads,metalCrowns;
    public TurretRenderer(BlockEntityRendererProvider.Context context){models=context.itemModelResolver();}
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(DeviceBlockEntity turret,State s,float partial,Vec3 position,ModelFeatureRenderer.CrumblingOverlay progress){
        BlockEntityRenderer.super.extractRenderState(turret,s,partial,position,progress);
        if(heads==null){
            heads=new ItemStack[4];for(int i=0;i<4;i++){
                heads[i]=new ItemStack(UmbralContent.TURRET);
                heads[i].set(DataComponents.ITEM_MODEL,UmbralContent.id("baluarte_head_"+(i%2==1?"loaded":"empty")+"_"+(i>=2?"on":"off")));
            }
            crowns=new ItemStack[]{piece("baluarte_crown_off"),piece("baluarte_crown_on")};
            carriage=piece("baluarte_carriage");
        }
        s.metal=turret.kind()==DeviceKind.CENTELLA;
        if(s.metal&&metalHeads==null){
            metalHeads=new ItemStack[8];
            for(int n=0;n<8;n++)metalHeads[n]=piece("centella_head_"+dev.umbral.turret.Metal.values()[n%4].getSerializedName()+"_"+(n>=4?"on":"off"));
            metalCrowns=new ItemStack[]{piece("centella_crown_off"),piece("centella_crown_on")};
        }
        var motion=turret.turret().motion();double now=turret.getLevel().getGameTime()+partial,age=now-motion.shotAt();
        s.yaw=180-turret.getBlockState().getValue(DeviceBlock.FACING).toYRot()-motion.yawAt(now);s.pitch=motion.pitchAt(now);
        s.recoil=age>=0&&age<8?(float)Math.sin(age*Math.PI/8)/16:0;
        s.draw=motion.ammunition()==0?0:(float)Math.clamp(age/TurretState.RELOAD_TICKS,0,1);
        int index=(turret.getBlockState().getValue(DeviceBlock.ONLINE)?2:0)+(s.draw>.85?1:0);
        models.updateForTopItem(s.head,s.metal?metalHeads[(index>=2?4:0)+turret.getBlockState().getValue(CentellaBlock.METAL).ordinal()]:heads[index],ItemDisplayContext.NONE,turret.getLevel(),null,0);
        models.updateForTopItem(s.crown,s.metal?metalCrowns[index>=2?1:0]:crowns[index>=2?1:0],ItemDisplayContext.NONE,turret.getLevel(),null,0);
        models.updateForTopItem(s.carriage,carriage,ItemDisplayContext.NONE,turret.getLevel(),null,0);
    }
    private static ItemStack piece(String name){
        var stack=new ItemStack(UmbralContent.TURRET);stack.set(DataComponents.ITEM_MODEL,UmbralContent.id(name));return stack;
    }
    @Override public void submit(State s,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){
        poses.pushPose();poses.translate(.5,.5,.5);poses.rotateDegrees(Axis.YP,s.yaw);
        s.crown.submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);poses.popPose();
        poses.pushPose();poses.translate(.5,.5,.5);poses.rotateDegrees(Axis.YP,s.yaw);poses.rotateDegrees(Axis.XP,-s.pitch);
        poses.translate(0,0,s.recoil);s.head.submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);
        if(s.metal){poses.popPose();return;}
        poses.translate(0,0,(-1.15+9.5*s.draw-8.35)/16);
        s.carriage.submit(poses,collector,s.lightCoords,OverlayTexture.NO_OVERLAY,0);poses.popPose();
        poses.pushPose();poses.translate(.5,.5,.5);poses.rotateDegrees(Axis.YP,s.yaw);poses.rotateDegrees(Axis.XP,-s.pitch);
        poses.translate(-.5,-.5,-.5+s.recoil);
        collector.submitCustomGeometry(poses,RenderTypes.entitySolid(UmbralContent.id("textures/block/metal_gasket.png")),(pose,buffer)->{
            float z=(-1.15f+9.5f*s.draw)/16;
            string(pose,buffer,-1.5f/16,9.5f/16,-1.15f/16,.5f,9.5f/16,z,s.lightCoords);
            string(pose,buffer,17.5f/16,9.5f/16,-1.15f/16,.5f,9.5f/16,z,s.lightCoords);
        });poses.popPose();
    }
    private static void string(PoseStack.Pose pose,VertexConsumer buffer,float ax,float ay,float az,float bx,float by,float bz,int light){
        float dx=bx-ax,dz=bz-az,length=(float)Math.sqrt(dx*dx+dz*dz),px=-dz/length/160,pz=dx/length/160,py=1/160f;
        float[][] v={{ax+px,ay-py,az+pz},{ax+px,ay+py,az+pz},{bx+px,by+py,bz+pz},{bx+px,by-py,bz+pz},
                {ax-px,ay-py,az-pz},{ax-px,ay+py,az-pz},{bx-px,by+py,bz-pz},{bx-px,by-py,bz-pz}};
        int[][] faces={{0,1,2,3},{7,6,5,4},{1,5,6,2},{4,0,3,7}};
        for(int[] face:faces)for(int index:face){var p=v[index];buffer.addVertex(pose,p[0],p[1],p[2]).setColor(-1).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,0,1,0);}
    }
}
