// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import java.util.*;

/** A player's pending selection is separate from live protection; cancellation never removes a claim. */
public final class ProtectionSelections {
    public enum Mode{NONE,BOX,CONTOUR,FOOTPRINT}
    public record Draft(UUID region,long revision,UUID parent,String label,ProtectionShape base,boolean cut,Mode mode,List<BlockPos> points,int floor,int ceiling){
        public Draft{points=List.copyOf(points);}
        public static Draft empty(){return new Draft(ProtectionData.NONE,0,ProtectionData.NONE,"",new ProtectionShape(),false,Mode.NONE,List.of(),0,8);}
    }
    private record Session(Draft draft,net.minecraft.resources.Identifier dimension,long expires){}
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    public static boolean active(UUID player){var s=SESSIONS.get(player);return s!=null&&s.draft.mode!=Mode.NONE;}
    public static Draft draft(ServerPlayer p){var s=SESSIONS.get(p.getUUID());if(s==null)return Draft.empty();if(!s.dimension.equals(p.level().dimension().identifier())||p.level().getServer().overworld().getGameTime()>s.expires||!p.isAlive()){SESSIONS.remove(p.getUUID());return Draft.empty();}return s.draft;}
    public static void begin(ServerPlayer p,Draft draft){SESSIONS.put(p.getUUID(),new Session(draft,p.level().dimension().identifier(),p.level().getServer().overworld().getGameTime()+6000));}
    public static void clear(UUID player){SESSIONS.remove(player);}
    public static void cancel(ServerPlayer p){clear(p.getUUID());if(p.connection!=null)ProtectionProtocol.sendTool(p,true,"cancelled");}
    public static InteractionResult point(ServerPlayer p,BlockPos pos){
        var d=draft(p);if(d.mode==Mode.NONE)return InteractionResult.PASS;
        if(p.isSpectator()||!p.isAlive()||pos.distToCenterSqr(p.position())>64||!p.level().isLoaded(pos))return InteractionResult.FAIL;
        if(d.mode==Mode.CONTOUR&&(p.isShiftKeyDown()||d.points.size()>=3&&pos.getX()==d.points.getFirst().getX()&&pos.getZ()==d.points.getFirst().getZ())){finish(p);return InteractionResult.SUCCESS;}
        var points=new ArrayList<>(d.points);if(points.size()>=32){p.sendOverlayMessage(Component.translatable("protection.umbral.result.points"));return InteractionResult.SUCCESS;}
        points.add(pos.immutable());int floor=d.floor,ceiling=d.ceiling;
        var next=new Draft(d.region,d.revision,d.parent,d.label,d.base,d.cut,d.mode,points,floor,ceiling);begin(p,next);
        if((d.mode==Mode.BOX||d.mode==Mode.FOOTPRINT)&&points.size()==2)finish(p);else{p.sendOverlayMessage(Component.translatable(d.mode==Mode.FOOTPRINT?"protection.umbral.footprint_point":"protection.umbral.point",points.size()));ProtectionProtocol.sendTool(p,false,"point");}
        return InteractionResult.SUCCESS;
    }
    public static void finish(ServerPlayer p){
        var d=draft(p);if(d.mode==Mode.FOOTPRINT&&d.points.size()!=2){if(p.connection!=null)p.sendOverlayMessage(Component.translatable("protection.umbral.footprint_point",d.points.size()));return;}try{
            ProtectionShape selection;
            if((d.mode==Mode.BOX||d.mode==Mode.FOOTPRINT)&&d.points.size()==2){
                var a=d.points.getFirst();var b=d.points.getLast();
                if(d.mode==Mode.FOOTPRINT&&d.region.equals(ProtectionData.NONE)&&d.parent.equals(ProtectionData.NONE))selection=ProtectionEdits.footprint(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),p.level().getMinY(),p.level().getMaxY());
                else if(d.mode==Mode.FOOTPRINT)selection=ProtectionShape.rectangle(a.getX(),d.floor,a.getZ(),b.getX(),d.ceiling-1,b.getZ());
                else selection=ProtectionShape.rectangle(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());
            }
            else if(d.mode==Mode.CONTOUR&&d.points.size()>=3)selection=ProtectionShape.polygon(d.points.stream().map(v->new ProtectionShape.Point(v.getX(),v.getZ())).toList(),d.floor,d.ceiling);
            else{ProtectionProtocol.sendTool(p,true,"points");return;}
            var shape=d.cut?d.base.subtract(selection):d.base.add(selection);
            begin(p,new Draft(d.region,d.revision,d.parent,d.label,shape,d.cut,Mode.NONE,List.of(),selection.bounds().y0(),selection.bounds().y1()));ProtectionProtocol.sendTool(p,true,"preview");
        }catch(IllegalArgumentException e){ProtectionProtocol.sendTool(p,true,"complex");}
    }
    public static void reset(){SESSIONS.clear();}
    private ProtectionSelections(){}
}
