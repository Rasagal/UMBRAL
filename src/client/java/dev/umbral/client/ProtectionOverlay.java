// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import dev.umbral.UmbralContent;
import dev.umbral.protection.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;

/** Local-only selection markers; no extra terrain is loaded to display protection bounds. */
final class ProtectionOverlay {
    static ProtectionProtocol.Snapshot latest;
    private static int ticks;
    static void tick(Minecraft c){
        if(c.level==null||c.player==null){latest=null;return;}
        if(++ticks%10!=0||latest==null||!latest.access().dimension().equals(c.level.dimension().identifier())||!c.player.getMainHandItem().is(UmbralContent.CONFIGURATOR)&&!c.player.getOffhandItem().is(UmbralContent.CONFIGURATOR))return;
        var d=latest.selection();ProtectionShape shape=d.base();
        if((d.mode()==ProtectionSelections.Mode.BOX||d.mode()==ProtectionSelections.Mode.FOOTPRINT)&&d.points().size()==1&&c.hitResult instanceof BlockHitResult hit){var a=d.points().getFirst();var b=hit.getBlockPos();try{var next=d.mode()==ProtectionSelections.Mode.FOOTPRINT?(d.region().equals(ProtectionData.NONE)&&d.parent().equals(ProtectionData.NONE)?ProtectionEdits.footprint(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),c.level.getMinY(),c.level.getMaxY()):ProtectionShape.rectangle(a.getX(),d.floor(),a.getZ(),b.getX(),d.ceiling()-1,b.getZ())):ProtectionShape.rectangle(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());shape=d.cut()?d.base().subtract(next):d.base().add(next);}catch(IllegalArgumentException ignored){}}
        if(shape.empty()&&c.hitResult instanceof BlockHitResult hit)shape=latest.regions().stream().filter(r->r.shape().contains(hit.getBlockPos().getX(),hit.getBlockPos().getY(),hit.getBlockPos().getZ())).map(ProtectionData.Region::shape).findFirst().orElse(shape);
        boolean preview=!d.base().empty()||d.mode()!=ProtectionSelections.Mode.NONE;var particle=new DustParticleOptions(d.cut()?0xC84B3D:preview?0xD69B64:0x739850,1.1f);int budget=240;
        for(var b:shape.boxes()){
            if(budget<=0)break;double[][] p={{b.x0(),b.y0(),b.z0()},{b.x1(),b.y0(),b.z0()},{b.x1(),b.y0(),b.z1()},{b.x0(),b.y0(),b.z1()},{b.x0(),b.y1(),b.z0()},{b.x1(),b.y1(),b.z0()},{b.x1(),b.y1(),b.z1()},{b.x0(),b.y1(),b.z1()}};
            for(int[] e:new int[][]{{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}}){var a=p[e[0]];var z=p[e[1]];int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(z[0]-a[0]),Math.max(Math.abs(z[1]-a[1]),Math.abs(z[2]-a[2])))/2));for(int n=0;n<=steps&&budget>0;n++,budget--){double t=(double)n/steps;emit(c,particle,a[0]+(z[0]-a[0])*t,a[1]+(z[1]-a[1])*t,a[2]+(z[2]-a[2])*t);}}
        }
        for(var point:d.points()){emit(c,particle,point.getX(),point.getY()+.1,point.getZ());emit(c,particle,point.getX(),d.ceiling(),point.getZ());}
    }
    private static void emit(Minecraft c,DustParticleOptions p,double x,double y,double z){if(c.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(x,y,z))<=64*64&&c.level.isLoaded(BlockPos.containing(x,y,z)))c.level.addParticle(p,x,y,z,0,0,0);}
    private ProtectionOverlay(){}
}
