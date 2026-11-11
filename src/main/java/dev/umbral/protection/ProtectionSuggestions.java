// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import java.util.*;

/** Bounded factual assistance. Both construction hints and room detection remain unapplied drafts. */
public final class ProtectionSuggestions {
    private record Placement(net.minecraft.resources.Identifier dimension,UUID area,BlockPos pos,long tick){}
    public record Hint(UUID area,String label,int blocks){}
    private static final Map<UUID,List<Placement>> RECENT=new HashMap<>();
    private static final Map<UUID,ProtectionNotice> NOTICES=new HashMap<>();
    public static void placed(ServerPlayer p,BlockPos pos){
        var level=(ServerLevel)p.level();var data=ProtectionData.get(level);if(data.at(level.dimension().identifier(),pos)!=null)return;
        var root=data.owned(p.getUUID()).stream().filter(r->r.parent().isEmpty()&&r.dimension().equals(level.dimension().identifier())&&near(r.shape().bounds(),pos)).min(Comparator.comparingLong(r->distance(r.shape().bounds(),pos))).orElse(null);if(root==null)return;
        if(!RECENT.containsKey(p.getUUID())&&RECENT.size()>=1024)return;
        long now=level.getServer().overworld().getGameTime();var list=RECENT.computeIfAbsent(p.getUUID(),k->new ArrayList<>());list.removeIf(v->now-v.tick>6000||v.dimension.equals(level.dimension().identifier())&&v.pos.equals(pos));list.add(new Placement(level.dimension().identifier(),root.id(),pos.immutable(),now));while(list.size()>64)list.removeFirst();
    }
    private static long distance(ProtectionShape.Box b,BlockPos p){long x=Math.max(0,Math.max(b.x0()-p.getX(),p.getX()-b.x1()+1)),y=Math.max(0,Math.max(b.y0()-p.getY(),p.getY()-b.y1()+1)),z=Math.max(0,Math.max(b.z0()-p.getZ(),p.getZ()-b.z1()+1));return x*x+y*y+z*z;}
    private static boolean near(ProtectionShape.Box b,BlockPos p){return p.getX()>=b.x0()-12&&p.getX()<b.x1()+12&&p.getZ()>=b.z0()-12&&p.getZ()<b.z1()+12&&p.getY()>=b.y0()-12&&p.getY()<b.y1()+12;}
    private static List<BlockPos> positions(ServerPlayer p,ProtectionData.Region r){
        var level=(ServerLevel)p.level();var data=ProtectionData.get(level);long now=level.getServer().overworld().getGameTime();
        var list=RECENT.get(p.getUUID());if(list==null)return List.of();
        list.removeIf(v->now-v.tick>6000||data.region(v.area)==null||v.dimension.equals(level.dimension().identifier())&&(data.at(v.dimension,v.pos)!=null||level.isLoaded(v.pos)&&level.getBlockState(v.pos).isAir()));
        return list.stream().filter(v->v.area.equals(r.id())&&v.dimension.equals(level.dimension().identifier())&&near(r.shape().bounds(),v.pos)&&level.isLoaded(v.pos)).map(Placement::pos).distinct().toList();
    }
    public static List<Hint> hints(ServerPlayer p){
        var data=ProtectionData.get((ServerLevel)p.level());var result=new ArrayList<Hint>();
        for(var r:data.owned(p.getUUID()))if(r.parent().isEmpty()&&r.dimension().equals(p.level().dimension().identifier())){int count=positions(p,r).size();if(count>0)result.add(new Hint(r.id(),r.label(),count));}
        result.sort(Comparator.comparing(Hint::label));return result.stream().limit(8).toList();
    }
    public static String ignore(ServerPlayer p,UUID id){
        var r=ProtectionData.get((ServerLevel)p.level()).region(id);if(r==null||!r.owner().equals(p.getUUID()))return "changed";UUID root=r.parent().orElse(r.id());var list=RECENT.get(p.getUUID());if(list!=null)list.removeIf(v->v.area.equals(root));return "suggestion_ignored";
    }
    public static void tick(net.minecraft.server.MinecraftServer server){
        long now=server.overworld().getGameTime();if(now%20!=0)return;
        for(var entry:RECENT.entrySet()){
            var p=server.getPlayerList().getPlayer(entry.getKey());if(p==null||p.connection==null||!p.isAlive()||entry.getValue().isEmpty())continue;
            long last=entry.getValue().getLast().tick;var notice=NOTICES.computeIfAbsent(p.getUUID(),k->new ProtectionNotice());if(!notice.ready(now,last))continue;
            var hints=hints(p);if(hints.isEmpty())continue;
            p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("protection.umbral.nexo_notice",hints.getFirst().label()));notice.sent(now,last);
        }
    }
    public static String suggest(ServerPlayer p,UUID id){
        var level=(ServerLevel)p.level();var r=ProtectionData.get(level).region(id);if(r==null||!r.owner().equals(p.getUUID())||!r.dimension().equals(level.dimension().identifier()))return "changed";
        if(r.parent().isPresent())r=ProtectionData.get(level).region(r.parent().get());if(r==null)return "changed";
        var bounds=r.shape().bounds();var points=positions(p,r);if(points.isEmpty())return "no_suggestion";
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;for(var pos:points){x0=Math.min(x0,pos.getX());y0=Math.min(y0,pos.getY());z0=Math.min(z0,pos.getZ());x1=Math.max(x1,pos.getX());y1=Math.max(y1,pos.getY());z1=Math.max(z1,pos.getZ());}
        try{var shape=r.shape().add(ProtectionShape.rectangle(x0-1,Math.max(level.getMinY(),Math.min(bounds.y0(),y0-1)),z0-1,x1+1,Math.min(level.getMaxY(),Math.max(bounds.y1()-1,y1+1)),z1+1));ProtectionSelections.begin(p,new ProtectionSelections.Draft(r.id(),r.revision(),ProtectionData.NONE,r.label(),shape,false,ProtectionSelections.Mode.NONE,List.of(),shape.bounds().y0(),shape.bounds().y1()));return "proposal";}catch(IllegalArgumentException e){return "complex";}
    }
    public static String room(ServerPlayer p,UUID id,BlockPos seed){
        var level=(ServerLevel)p.level();var r=ProtectionData.get(level).region(id);if(r==null||!r.owner().equals(p.getUUID())||!r.dimension().equals(level.dimension().identifier()))return "changed";
        if(r.parent().isPresent())r=ProtectionData.get(level).region(r.parent().get());if(r==null)return "changed";
        if(!r.shape().contains(seed.getX(),seed.getY(),seed.getZ())||!level.isLoaded(seed))return "room_seed";
        int y=seed.getY();if(!open(level,seed))return "room_seed";
        int floor=y,roof=y+1;for(int n=0;n<16&&open(level,seed.atY(floor-1));n++)floor--;for(int n=0;n<16&&open(level,seed.atY(roof));n++)roof++;
        if(open(level,seed.atY(floor-1))||open(level,seed.atY(roof)))return "open_room";
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();queue.add(seed.atY(y));seen.add(seed.atY(y));var boxes=new ArrayList<ProtectionShape.Box>();
        while(!queue.isEmpty()){
            var pos=queue.remove();if(seen.size()>1024||Math.abs(pos.getX()-seed.getX())>24||Math.abs(pos.getZ()-seed.getZ())>24)return "open_room";
            if(!r.shape().contains(pos.getX(),y,pos.getZ()))return "open_room";
            boxes.add(new ProtectionShape.Box(pos.getX(),floor,pos.getZ(),pos.getX()+1,roof,pos.getZ()+1));
            for(var next:List.of(pos.north(),pos.south(),pos.west(),pos.east())){if(!level.isLoaded(next))return "unloaded";if(!seen.contains(next)&&open(level,next)){seen.add(next);queue.add(next);}}
        }
        try{
            // Merge scan-line runs before entering the bounded shape representation.
            var rows=new TreeMap<Integer,List<Integer>>();for(var b:boxes)rows.computeIfAbsent(b.z0(),k->new ArrayList<>()).add(b.x0());var runs=new ArrayList<ProtectionShape.Box>();
            for(var row:rows.entrySet()){row.getValue().sort(Integer::compare);int a=row.getValue().getFirst(),last=a;for(int n=1;n<=row.getValue().size();n++){if(n<row.getValue().size()&&row.getValue().get(n)==last+1){last++;continue;}runs.add(new ProtectionShape.Box(a,floor,row.getKey(),last+1,roof,row.getKey()+1));if(n<row.getValue().size())a=last=row.getValue().get(n);}}
            var shape=new ProtectionShape(runs);if(!r.shape().contains(shape))return "room_outside";
            ProtectionSelections.begin(p,new ProtectionSelections.Draft(ProtectionData.NONE,0,r.id(),"",shape,false,ProtectionSelections.Mode.NONE,List.of(),floor,roof));return "detected";
        }catch(IllegalArgumentException e){return "complex";}
    }
    private static boolean open(ServerLevel level,BlockPos p){return p.getY()>=level.getMinY()&&p.getY()<=level.getMaxY()&&level.isLoaded(p)&&level.getBlockState(p).getCollisionShape(level,p).isEmpty();}
    public static void clear(UUID player){RECENT.remove(player);NOTICES.remove(player);}
    public static void reset(){RECENT.clear();NOTICES.clear();}
    private ProtectionSuggestions(){}
}
