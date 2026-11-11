// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.filter.Inspection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.GsonHelper;
import java.io.IOException;
import java.util.*;

/** Five three-dimensional optical rays. The sweep responds to an actual inspection, without collision or damage. */
final class FilterRenderer {
    static final class State {
        boolean root,active,scanning;
        float rotation,scanY;
        int core,glow;
    }
    private record Optics(List<Float> heights,float left,float right,float z){}
    private Optics optics;
    private Optics optics(){
        if(optics!=null)return optics;
        try(var reader=Minecraft.getInstance().getResourceManager().getResource(UmbralContent.id("geometry/filter_optics.json")).orElseThrow().openAsReader()){
            var data=GsonHelper.parse(reader);if(GsonHelper.getAsInt(data,"schema")!=1)throw new IllegalArgumentException("Unsupported FILTRO optics");
            var heights=new ArrayList<Float>();for(var value:data.getAsJsonArray("heights")){float y=value.getAsFloat();if(!Float.isFinite(y)||y<2||y>39||heights.size()>=16)throw new IllegalArgumentException("Invalid FILTRO ray height");heights.add(y);}
            float left=GsonHelper.getAsFloat(data,"left"),right=GsonHelper.getAsFloat(data,"right"),z=GsonHelper.getAsFloat(data,"z");
            if(heights.isEmpty()||!Float.isFinite(left)||!Float.isFinite(right)||!Float.isFinite(z)||left<14||right>34||left>=right||z<4||z>12)throw new IllegalArgumentException("Invalid FILTRO optics");
            return optics=new Optics(List.copyOf(heights),left,right,z);
        }catch(IOException e){throw new IllegalStateException("Cannot load FILTRO optics",e);}
    }
    void extract(DeviceBlockEntity d,State s,float partial){
        s.root=FilterBlock.base(d.getBlockState());s.active=false;s.scanning=false;
        if(!s.root||d.getLevel()==null)return;
        s.rotation=180-d.getBlockState().getValue(DeviceBlock.FACING).toYRot();
        s.active=d.filter().armed()&&d.getBlockState().getValue(DeviceBlock.ONLINE)&&d.filter().complete();
        float elapsed=(d.getLevel().getGameTime()-d.filter().flashAt())+partial;
        s.scanning=elapsed>=0&&elapsed<32;s.scanY=4+Math.clamp(elapsed/32,0,1)*33;
        boolean alert=s.scanning&&(d.filter().flash()==Inspection.Outcome.MATCH||d.filter().flash()==Inspection.Outcome.TOO_COMPLEX);
        s.core=alert?0xFFF5A090:s.scanning?0xFFE2FFC7:0xE0C0FFF1;
        s.glow=alert?0x48C84B3D:s.scanning?0x408ECF75:0x3055C7BC;
    }
    void submit(State s,PoseStack poses,SubmitNodeCollector collector){
        if(!s.root||!s.active)return;var o=optics();
        poses.pushPose();poses.translate(.5,0,.5);poses.rotateDegrees(Axis.YP,s.rotation);poses.translate(-.5,0,-.5);
        collector.submitCustomGeometry(poses,RenderTypes.entityTranslucentEmissive(UmbralContent.id("textures/block/filter_beam.png")),(pose,out)->{
            for(float y:o.heights){
                ray(pose,out,o,y,.22f,s.glow);ray(pose,out,o,y,.055f,s.core);
                for(float x:new float[]{o.left,o.right})box(pose,out,x-.10f,y-.16f,o.z-.16f,x+.10f,y+.16f,o.z+.16f,s.core);
            }
            if(s.scanning){ray(pose,out,o,s.scanY,.45f,s.glow);ray(pose,out,o,s.scanY,.10f,s.core);}
        });poses.popPose();
    }
    private static void ray(PoseStack.Pose p,VertexConsumer out,Optics o,float y,float radius,int color){box(p,out,o.left,y-radius,o.z-radius,o.right,y+radius,o.z+radius,color);}
    private static void box(PoseStack.Pose p,VertexConsumer out,float x,float y,float z,float X,float Y,float Z,int color){
        face(p,out,x,y,z,x,Y,z,X,Y,z,X,y,z,0,0,-1,color);
        face(p,out,X,y,Z,X,Y,Z,x,Y,Z,x,y,Z,0,0,1,color);
        face(p,out,x,Y,z,x,Y,Z,X,Y,Z,X,Y,z,0,1,0,color);
        face(p,out,x,y,Z,x,y,z,X,y,z,X,y,Z,0,-1,0,color);
        face(p,out,x,y,Z,x,Y,Z,x,Y,z,x,y,z,-1,0,0,color);
        face(p,out,X,y,z,X,Y,z,X,Y,Z,X,y,Z,1,0,0,color);
    }
    private static void face(PoseStack.Pose p,VertexConsumer out,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,int nx,int ny,int nz,int color){
        vertex(p,out,ax,ay,az,0,1,nx,ny,nz,color);vertex(p,out,bx,by,bz,0,0,nx,ny,nz,color);vertex(p,out,cx,cy,cz,1,0,nx,ny,nz,color);vertex(p,out,dx,dy,dz,1,1,nx,ny,nz,color);
    }
    private static void vertex(PoseStack.Pose p,VertexConsumer out,float x,float y,float z,float u,float v,int nx,int ny,int nz,int color){out.addVertex(p,x/16,y/16,z/16).setColor(color).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(p,nx,ny,nz);}
}
