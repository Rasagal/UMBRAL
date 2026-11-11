// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import java.util.*;

/** Bounded owner-only editor protocol. Geometry is a proposal until the server commits it. */
public final class ProtectionProtocol {
    public enum Action{QUERY,INSTALL,BEGIN_BOX,BEGIN_CONTOUR,CANCEL,APPLY,GRANT,REVOKE,FLAGS,DELETE,SUGGEST,DETECT_ROOM,BEGIN_AREA,APPLY_WITH_MATRICES,IGNORE_SUGGESTION}
    public record Access(boolean tool,Identifier dimension,BlockPos position,UUID identity){
        public static Access tool(ServerPlayer p){return new Access(true,p.level().dimension().identifier(),BlockPos.ZERO,ProtectionData.NONE);}
    }
    public record Profile(UUID id,String name){}
    public record Open(boolean tool,BlockPos position) implements CustomPacketPayload{
        public static final Type<Open> TYPE=new Type<>(UmbralContent.id("protection_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=new StreamCodec<>(){public Open decode(RegistryFriendlyByteBuf b){return new Open(b.readBoolean(),b.readBlockPos());}public void encode(RegistryFriendlyByteBuf b,Open p){b.writeBoolean(p.tool);b.writeBlockPos(p.position);}};
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(Access access,long nonce,Action action,UUID region,long revision,UUID parent,ProtectionShape shape,String label,UUID member,int value,int floor,int ceiling) implements CustomPacketPayload{
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("protection_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){
            public Request decode(RegistryFriendlyByteBuf b){return new Request(readAccess(b),b.readLong(),b.readEnum(Action.class),b.readUUID(),b.readLong(),b.readUUID(),readShape(b),b.readUtf(48),b.readUUID(),b.readVarInt(),b.readInt(),b.readInt());}
            public void encode(RegistryFriendlyByteBuf b,Request p){writeAccess(b,p.access);b.writeLong(p.nonce);b.writeEnum(p.action);b.writeUUID(p.region);b.writeLong(p.revision);b.writeUUID(p.parent);writeShape(b,p.shape);b.writeUtf(p.label,48);b.writeUUID(p.member);b.writeVarInt(p.value);b.writeInt(p.floor);b.writeInt(p.ceiling);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(Access access,long nonce,boolean open,boolean valid,String result,int matrices,long capacity,long used,List<ProtectionData.Region> regions,List<Profile> players,ProtectionSelections.Draft selection,List<ProtectionData.Incident> incidents,List<ProtectionSuggestions.Hint> hints) implements CustomPacketPayload{
        public Snapshot{regions=List.copyOf(regions);players=List.copyOf(players);incidents=List.copyOf(incidents);hints=List.copyOf(hints);}
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("protection_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>(){
            public Snapshot decode(RegistryFriendlyByteBuf b){
                var access=readAccess(b);long nonce=b.readLong();boolean open=b.readBoolean(),valid=b.readBoolean();String result=b.readUtf(32);int matrices=bounded(b.readVarInt(),0,4096);long cap=b.readLong(),used=b.readLong();if(cap<0||used<0)throw new IllegalArgumentException("Capacity");
                var regions=new ArrayList<ProtectionData.Region>();int count=bounded(b.readVarInt(),0,64);for(int n=0;n<count;n++){
                    var id=b.readUUID();var owner=b.readUUID();var dim=Identifier.parse(b.readUtf(128));String label=b.readUtf(48);var parent=readOptional(b);var shape=readShape(b);var members=new ArrayList<ProtectionData.Member>();int m=bounded(b.readVarInt(),0,32);
                    for(int j=0;j<m;j++)members.add(new ProtectionData.Member(b.readUUID(),b.readUtf(32),bounded(b.readVarInt(),0,7)));regions.add(new ProtectionData.Region(id,owner,dim,label,parent,shape,members,bounded(b.readVarInt(),0,63),b.readLong()));
                }
                var players=new ArrayList<Profile>();int profiles=bounded(b.readVarInt(),0,128);for(int n=0;n<profiles;n++)players.add(new Profile(b.readUUID(),b.readUtf(32)));
                var region=b.readUUID();long revision=b.readLong();var parent=b.readUUID();String label=b.readUtf(48);var shape=readShape(b);boolean cut=b.readBoolean();var mode=b.readEnum(ProtectionSelections.Mode.class);int points=bounded(b.readVarInt(),0,32);var list=new ArrayList<BlockPos>();for(int n=0;n<points;n++)list.add(b.readBlockPos());var draft=new ProtectionSelections.Draft(region,revision,parent,label,shape,cut,mode,list,b.readInt(),b.readInt());
                var incidents=new ArrayList<ProtectionData.Incident>();int size=bounded(b.readVarInt(),0,16);for(int n=0;n<size;n++)incidents.add(new ProtectionData.Incident(b.readUUID(),b.readUtf(48),b.readUtf(24),b.readUtf(32),b.readBlockPos(),b.readLong()));
                var hints=new ArrayList<ProtectionSuggestions.Hint>();int hintCount=bounded(b.readVarInt(),0,8);for(int n=0;n<hintCount;n++)hints.add(new ProtectionSuggestions.Hint(b.readUUID(),b.readUtf(48),bounded(b.readVarInt(),1,64)));
                return new Snapshot(access,nonce,open,valid,result,matrices,cap,used,regions,players,draft,incidents,hints);
            }
            public void encode(RegistryFriendlyByteBuf b,Snapshot p){
                writeAccess(b,p.access);b.writeLong(p.nonce);b.writeBoolean(p.open);b.writeBoolean(p.valid);b.writeUtf(p.result,32);b.writeVarInt(p.matrices);b.writeLong(p.capacity);b.writeLong(p.used);b.writeVarInt(p.regions.size());
                for(var r:p.regions){b.writeUUID(r.id());b.writeUUID(r.owner());b.writeUtf(r.dimension().toString(),128);b.writeUtf(r.label(),48);writeOptional(b,r.parent());writeShape(b,r.shape());b.writeVarInt(r.members().size());for(var m:r.members()){b.writeUUID(m.player());b.writeUtf(m.name(),32);b.writeVarInt(m.permissions());}b.writeVarInt(r.flags());b.writeLong(r.revision());}
                b.writeVarInt(p.players.size());for(var u:p.players){b.writeUUID(u.id);b.writeUtf(u.name,32);}var d=p.selection;
                b.writeUUID(d.region());b.writeLong(d.revision());b.writeUUID(d.parent());b.writeUtf(d.label(),48);writeShape(b,d.base());b.writeBoolean(d.cut());b.writeEnum(d.mode());b.writeVarInt(d.points().size());for(var point:d.points())b.writeBlockPos(point);b.writeInt(d.floor());b.writeInt(d.ceiling());
                b.writeVarInt(p.incidents.size());for(var i:p.incidents){b.writeUUID(i.owner());b.writeUtf(i.area(),48);b.writeUtf(i.action(),24);b.writeUtf(i.actor(),32);b.writeBlockPos(i.pos());b.writeLong(i.tick());}
                b.writeVarInt(p.hints.size());for(var hint:p.hints){b.writeUUID(hint.area());b.writeUtf(hint.label(),48);b.writeVarInt(hint.blocks());}
            }
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static int bounded(int value,int low,int high){if(value<low||value>high)throw new IllegalArgumentException("Protection packet limit");return value;}
    private static Access readAccess(RegistryFriendlyByteBuf b){return new Access(b.readBoolean(),Identifier.parse(b.readUtf(128)),b.readBlockPos(),b.readUUID());}
    private static void writeAccess(RegistryFriendlyByteBuf b,Access a){b.writeBoolean(a.tool);b.writeUtf(a.dimension.toString(),128);b.writeBlockPos(a.position);b.writeUUID(a.identity);}
    private static Optional<UUID> readOptional(RegistryFriendlyByteBuf b){return b.readBoolean()?Optional.of(b.readUUID()):Optional.empty();}
    private static void writeOptional(RegistryFriendlyByteBuf b,Optional<UUID> value){b.writeBoolean(value.isPresent());value.ifPresent(b::writeUUID);}
    public static ProtectionShape readShape(RegistryFriendlyByteBuf b){int count=bounded(b.readVarInt(),0,ProtectionShape.MAX_PARTS);var out=new ArrayList<ProtectionShape.Box>();for(int n=0;n<count;n++){int x0=bounded(b.readInt(),-30000000,30000000),y0=bounded(b.readInt(),-2048,2048),z0=bounded(b.readInt(),-30000000,30000000),x1=bounded(b.readInt(),-30000000,30000000),y1=bounded(b.readInt(),-2048,2048),z1=bounded(b.readInt(),-30000000,30000000);out.add(new ProtectionShape.Box(x0,y0,z0,x1,y1,z1));}return new ProtectionShape(out);}
    public static void writeShape(RegistryFriendlyByteBuf b,ProtectionShape shape){b.writeVarInt(shape.boxes().size());for(var v:shape.boxes()){b.writeInt(v.x0());b.writeInt(v.y0());b.writeInt(v.z0());b.writeInt(v.x1());b.writeInt(v.y1());b.writeInt(v.z1());}}
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    public static boolean authorized(ServerPlayer p,Access a){
        if(!p.isAlive()||p.isSpectator()||!a.dimension.equals(p.level().dimension().identifier()))return false;
        if(a.tool)return p.getMainHandItem().is(UmbralContent.CONFIGURATOR)||p.getOffhandItem().is(UmbralContent.CONFIGURATOR);
        return a.position.distToCenterSqr(p.position())<=64&&p.level().isLoaded(a.position)&&p.level().getBlockEntity(a.position) instanceof DeviceBlockEntity d&&d.identity().equals(a.identity)&&d.ownedBy(p.getUUID())&&(d.kind()==DeviceKind.CORE||d.kind()==DeviceKind.NEXO||d.kind()==DeviceKind.TERMINAL);
    }
    public static Snapshot snapshot(ServerPlayer p,Access a,long nonce,boolean open,String result){var data=ProtectionData.get((ServerLevel)p.level());return new Snapshot(a,nonce,open,true,result,data.matrices(p.getUUID()),data.capacity(p.getUUID()),data.used(p.getUUID()),data.owned(p.getUUID()).stream().filter(r->r.dimension().equals(a.dimension)).toList(),p.level().getServer().getPlayerList().getPlayers().stream().filter(v->!v.getUUID().equals(p.getUUID())).limit(128).map(v->new Profile(v.getUUID(),v.getGameProfile().name())).toList(),ProtectionSelections.draft(p),data.incidents(p.getUUID()),ProtectionSuggestions.hints(p));}
    public static void sendTool(ServerPlayer p,boolean open,String result){ServerPlayNetworking.send(p,snapshot(p,Access.tool(p),0,open,result));}
    public static void openTool(ServerPlayer p){if(authorized(p,Access.tool(p)))sendTool(p,true,"ready");}
    public static Snapshot handle(ServerPlayer p,Request r){
        if(!authorized(p,r.access))return new Snapshot(r.access,r.nonce,false,false,"invalid",0,0,0,List.of(),List.of(),ProtectionSelections.Draft.empty(),List.of(),List.of());
        String result="ready";
        switch(r.action){
            case QUERY -> {}
            case INSTALL -> result=ProtectionService.install(p);
            case APPLY,APPLY_WITH_MATRICES -> {try{result=ProtectionService.apply(p,r.region,r.revision,r.parent,r.shape,r.label,r.action==Action.APPLY_WITH_MATRICES,Math.clamp(r.value,0,ProtectionEconomy.MAX_MATRICES));if(result.equals("applied"))ProtectionSelections.clear(p.getUUID());}catch(IllegalArgumentException e){result="complex";}}
            case IGNORE_SUGGESTION -> result=ProtectionSuggestions.ignore(p,r.region);
            case SUGGEST -> result=ProtectionSuggestions.suggest(p,r.region);
            case DETECT_ROOM -> {var box=r.shape.bounds();result=box==null?"room_seed":ProtectionSuggestions.room(p,r.region,new BlockPos(box.x0(),box.y0(),box.z0()));}
            case DELETE -> result=ProtectionService.delete(p,r.region,r.revision);
            case FLAGS -> result=ProtectionService.flags(p,r.region,r.revision,r.value);
            case GRANT,REVOKE -> result=ProtectionService.member(p,r.region,r.revision,r.member,"",r.value,r.action==Action.REVOKE);
            case CANCEL -> {ProtectionSelections.clear(p.getUUID());result="cancelled";}
            case BEGIN_BOX,BEGIN_CONTOUR,BEGIN_AREA -> {
                boolean tool=false;for(int n=0;n<p.getInventory().getContainerSize();n++)if(p.getInventory().getItem(n).is(UmbralContent.CONFIGURATOR)){tool=true;break;}
                var old=r.region.equals(ProtectionData.NONE)?null:ProtectionData.get((ServerLevel)p.level()).region(r.region);
                if(!tool)result="need_tool";else if(!r.region.equals(ProtectionData.NONE)&&(old==null||!old.owner().equals(p.getUUID())||old.revision()!=r.revision))result="changed";
                else if(r.floor>=r.ceiling||r.floor<p.level().getMinY()||r.ceiling>p.level().getMaxY()+1)result="bounds";
                else{ProtectionSelections.begin(p,new ProtectionSelections.Draft(r.region,r.revision,r.parent,ProtectionData.clean(r.label,48),r.action==Action.BEGIN_AREA?(old==null?new ProtectionShape():old.shape()):r.shape,r.action!=Action.BEGIN_AREA&&(r.value&1)!=0,r.action==Action.BEGIN_AREA?ProtectionSelections.Mode.FOOTPRINT:r.action==Action.BEGIN_BOX?ProtectionSelections.Mode.BOX:ProtectionSelections.Mode.CONTOUR,List.of(),r.floor,r.ceiling));result=r.action==Action.BEGIN_AREA?"marking_area":"marking";if(p.connection!=null)p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("protection.umbral.result."+result));}
            }
        }
        return snapshot(p,r.access,r.nonce,false,result);
    }
    private static boolean limit(ServerPlayer p,boolean query){var map=query?QUERIES:ACTIONS;long now=p.level().getServer().overworld().getGameTime();var last=map.get(p.getUUID());if(last!=null&&now>=last&&now-last<(query?10:5))return false;map.put(p.getUUID(),now);return true;}
    public static void initialize(){
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ProtectionSuggestions::tick);
        PayloadTypeRegistry.serverboundPlay().register(Open.TYPE,Open.CODEC);PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE,(r,c)->{
            var p=c.player();if(!limit(p,true))return;if(r.tool){openTool(p);return;}if(!p.level().isLoaded(r.position)||!(p.level().getBlockEntity(r.position) instanceof DeviceBlockEntity d))return;
            var a=new Access(false,p.level().dimension().identifier(),r.position,d.identity());if(authorized(p,a))ServerPlayNetworking.send(p,snapshot(p,a,0,true,"ready"));
        });
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{if(limit(c.player(),r.action==Action.QUERY))ServerPlayNetworking.send(c.player(),handle(c.player(),r));});
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{var id=h.getPlayer().getUUID();QUERIES.remove(id);ACTIONS.remove(id);ProtectionSelections.clear(id);ProtectionSuggestions.clear(id);});
    }
    public static void clear(){QUERIES.clear();ACTIONS.clear();ProtectionSelections.reset();ProtectionSuggestions.reset();}
    private ProtectionProtocol(){}
}
