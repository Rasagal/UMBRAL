// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.filter.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import java.util.*;

/** Explicit setup of nearby owned endpoints. Discovery, review and saving have separate semantics. */
public final class AccessSetupProtocol {
    public static final int RADIUS=16,MAX_PER_KIND=128,MAX_CHOICES=MAX_PER_KIND*3;
    public enum Operation { QUERY, REVIEW, APPLY, CONFIGURE_FILTER }
    public record Choice(DoorTarget reference,DeviceKind kind,String label,String reason){}
    public record Open(BlockPos position) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(UmbralContent.id("access_setup_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=new StreamCodec<>(){
            public Open decode(RegistryFriendlyByteBuf b){return new Open(b.readBlockPos());}
            public void encode(RegistryFriendlyByteBuf b,Open p){b.writeBlockPos(p.position);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(BlockPos root,UUID identity,NetworkLink link,long nonce,Operation operation,DoorTarget door,DoorTarget reader,DoorTarget filter) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("access_setup_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID(),NetworkLink.STREAM_CODEC.decode(b),b.readLong(),b.readEnum(Operation.class),readRef(b),readRef(b),readRef(b));}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeBlockPos(p.root);b.writeUUID(p.identity);NetworkLink.STREAM_CODEC.encode(b,p.link);b.writeLong(p.nonce);b.writeEnum(p.operation);writeRef(b,p.door);writeRef(b,p.reader);writeRef(b,p.filter);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(BlockPos root,UUID identity,NetworkLink link,long nonce,boolean open,boolean valid,String result,boolean ready,List<Choice> choices) implements CustomPacketPayload {
        public Snapshot{choices=List.copyOf(choices);}
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("access_setup_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>(){
            public Snapshot decode(RegistryFriendlyByteBuf b){var root=b.readBlockPos();var id=b.readUUID();var link=NetworkLink.STREAM_CODEC.decode(b);long nonce=b.readLong();boolean open=b.readBoolean(),valid=b.readBoolean();String result=b.readUtf(32);boolean ready=b.readBoolean();int count=b.readVarInt();if(count<0||count>MAX_CHOICES)throw new IllegalArgumentException("Invalid setup discovery");var choices=new ArrayList<Choice>();for(int n=0;n<count;n++){var ref=DoorTarget.STREAM_CODEC.decode(b);var kind=b.readEnum(DeviceKind.class);if(kind!=DeviceKind.DOOR&&kind!=DeviceKind.READER&&kind!=DeviceKind.FILTER)throw new IllegalArgumentException("Invalid setup device");choices.add(new Choice(ref,kind,b.readUtf(48),b.readUtf(32)));}return new Snapshot(root,id,link,nonce,open,valid,result,ready,choices);}
            public void encode(RegistryFriendlyByteBuf b,Snapshot p){b.writeBlockPos(p.root);b.writeUUID(p.identity);NetworkLink.STREAM_CODEC.encode(b,p.link);b.writeLong(p.nonce);b.writeBoolean(p.open);b.writeBoolean(p.valid);b.writeUtf(p.result,32);b.writeBoolean(p.ready);b.writeVarInt(p.choices.size());for(var c:p.choices){DoorTarget.STREAM_CODEC.encode(b,c.reference);b.writeEnum(c.kind);b.writeUtf(c.label,48);b.writeUtf(c.reason,32);}}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static DoorTarget readRef(RegistryFriendlyByteBuf b){return b.readBoolean()?DoorTarget.STREAM_CODEC.decode(b):null;}
    private static void writeRef(RegistryFriendlyByteBuf b,DoorTarget r){b.writeBoolean(r!=null);if(r!=null)DoorTarget.STREAM_CODEC.encode(b,r);}
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    public static boolean manages(ServerPlayer p,DeviceBlockEntity root){
        return NexoProtocol.manages(p,root)&&(root.kind()==DeviceKind.CORE||root.kind()==DeviceKind.NEXO||root.kind()==DeviceKind.TERMINAL)&&root.link()!=null&&NetworkService.inspect((ServerLevel)p.level(),root).online();
    }
    private static DeviceBlockEntity resolve(ServerPlayer p,DeviceBlockEntity root,DoorTarget ref,DeviceKind kind){
        if(ref==null||!ref.dimension().equals(p.level().dimension().identifier())||!p.level().isLoaded(ref.position())||root.getBlockPos().distSqr(ref.position())>RADIUS*RADIUS)return null;
        if(!(p.level().getBlockEntity(ref.position()) instanceof DeviceBlockEntity d)||d.kind()!=kind||!d.identity().equals(ref.identity())||!d.ownedBy(p.getUUID())||d.link()!=null&&!d.link().equals(root.link()))return null;
        return kind!=DeviceKind.FILTER||FilterBlock.base(d.getBlockState())?d:null;
    }
    private static DeviceBlockEntity core(ServerPlayer p,DeviceBlockEntity root){
        var link=root.link();if(link==null||!link.dimension().equals(p.level().dimension().identifier())||!p.level().isLoaded(link.core()))return null;
        return p.level().getBlockEntity(link.core()) instanceof DeviceBlockEntity c&&c.kind()==DeviceKind.CORE&&c.ownedBy(p.getUUID())&&link.equals(c.link())?c:null;
    }
    private static String filterReason(DeviceBlockEntity filter,DeviceBlockEntity door){
        if(!FilterBlock.complete(filter))return "filter_incomplete";
        if(filter.filter().armed()||filter.airlock().enabled())return "filter_active";
        if(filter.filter().door()!=null&&!filter.filter().door().equals(door.asDoorTarget()))return "filter_busy";
        if(door.filterGuard()!=null&&!door.filterGuard().equals(filter.asDoorTarget()))return "door_busy";
        var facing=filter.getBlockState().getValue(DeviceBlock.FACING);var center=filter.getBlockPos().relative(facing.getClockWise());var delta=door.getBlockPos().subtract(center);
        int behind=-delta.getX()*facing.getStepX()-delta.getZ()*facing.getStepZ();
        int sideways=delta.getX()*facing.getClockWise().getStepX()+delta.getZ()*facing.getClockWise().getStepZ();
        if(delta.getY()!=0||sideways!=0||behind<1||behind>4)return "filter_position";
        if(door.getBlockState().getValue(DeviceBlock.FACING)!=facing)return "filter_facing";
        return "ready";
    }
    public static String validate(ServerPlayer p,DeviceBlockEntity root,Request r){
        if(!manages(p,root)||core(p,root)==null)return "offline";
        var door=resolve(p,root,r.door,DeviceKind.DOOR);var reader=resolve(p,root,r.reader,DeviceKind.READER);
        if(door==null)return r.door==null?"choose_door":"changed";
        if(reader==null)return r.reader==null?"choose_reader":"changed";
        if(door.airlockLink()!=null)return "door_busy";
        if(reader.getBlockPos().distSqr(door.getBlockPos())>16)return "reader_far";
        if(reader.doorTarget()!=null&&!reader.doorTarget().equals(door.asDoorTarget()))return "reader_busy";
        if(r.filter!=null){var filter=resolve(p,root,r.filter,DeviceKind.FILTER);if(filter==null)return "changed";String result=filterReason(filter,door);if(!result.equals("ready"))return result;}
        var positions=new ArrayList<BlockPos>(List.of(door.getBlockPos(),reader.getBlockPos()));if(r.filter!=null)positions.add(r.filter.position());
        if(!InstallationDirectory.get((ServerLevel)p.level()).hasRoomFor(root.link(),positions))return "directory_full";
        return "ready";
    }
    private static List<Choice> discover(ServerPlayer p,DeviceBlockEntity root,Request r){
        var level=(ServerLevel)p.level();var found=new ArrayList<DeviceBlockEntity>();var pos=root.getBlockPos();
        for(int cx=(pos.getX()-RADIUS)>>4;cx<=(pos.getX()+RADIUS)>>4;cx++)for(int cz=(pos.getZ()-RADIUS)>>4;cz<=(pos.getZ()+RADIUS)>>4;cz++){
            var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
            for(var be:chunk.getBlockEntities().values())if(be instanceof DeviceBlockEntity d&&d.ownedBy(p.getUUID())&&pos.distSqr(d.getBlockPos())<=RADIUS*RADIUS&&(d.link()==null||d.link().equals(root.link()))&&(d.kind()==DeviceKind.DOOR||d.kind()==DeviceKind.READER||d.kind()==DeviceKind.FILTER&&FilterBlock.base(d.getBlockState())))found.add(d);
        }
        var door=resolve(p,root,r.door,DeviceKind.DOOR);
        var counts=new EnumMap<DeviceKind,Integer>(DeviceKind.class);
        return found.stream().sorted(Comparator.comparingDouble((DeviceBlockEntity d)->pos.distSqr(d.getBlockPos())).thenComparing(d->d.getBlockPos().asLong())).filter(d->counts.merge(d.kind(),1,Integer::sum)<=MAX_PER_KIND).map(d->{
            String reason="ready";
            if(d.kind()==DeviceKind.DOOR&&d.airlockLink()!=null)reason="door_busy";
            if(door!=null&&d.kind()==DeviceKind.READER){if(d.getBlockPos().distSqr(door.getBlockPos())>16)reason="reader_far";else if(d.doorTarget()!=null&&!d.doorTarget().equals(door.asDoorTarget()))reason="reader_busy";}
            if(door!=null&&d.kind()==DeviceKind.FILTER)reason=filterReason(d,door);
            return new Choice(d.asDoorTarget(),d.kind(),d.label(),reason);
        }).toList();
    }
    private static Snapshot snapshot(ServerPlayer p,DeviceBlockEntity root,Request r,boolean open,String result){
        return new Snapshot(root.getBlockPos(),root.identity(),root.link(),r.nonce,open,true,result,validate(p,root,r).equals("ready"),discover(p,root,r));
    }
    private static void rollback(ServerPlayer p,List<DeviceBlockEntity> bound){for(int n=bound.size()-1;n>=0;n--)bound.get(n).unbind(p.getUUID());}
    public static Snapshot handle(ServerPlayer p,Request r){
        var level=(ServerLevel)p.level();
        if(!level.isLoaded(r.root)||!(level.getBlockEntity(r.root) instanceof DeviceBlockEntity root)||!root.identity().equals(r.identity)||!Objects.equals(root.link(),r.link)||!manages(p,root))return new Snapshot(r.root,r.identity,r.link,r.nonce,false,false,"invalid",false,List.of());
        String result=validate(p,root,r);
        if(r.operation==Operation.QUERY)return snapshot(p,root,r,false,result);
        if(r.operation==Operation.CONFIGURE_FILTER){var scanner=resolve(p,root,r.filter,DeviceKind.FILTER);if(scanner!=null&&root.link().equals(scanner.link())){if(!NetworkService.inspect(level,scanner).online())return snapshot(p,root,r,false,"filter_offline");FilterProtocol.open(p,root,scanner);return snapshot(p,root,r,false,"saved");}return snapshot(p,root,r,false,"changed");}
        if(!result.equals("ready"))return snapshot(p,root,r,false,result);
        if(r.operation==Operation.REVIEW)return snapshot(p,root,r,false,"review");
        var door=resolve(p,root,r.door,DeviceKind.DOOR);var reader=resolve(p,root,r.reader,DeviceKind.READER);var filter=resolve(p,root,r.filter,DeviceKind.FILTER);var core=core(p,root);
        var bound=new ArrayList<DeviceBlockEntity>();
        for(var d:Arrays.asList(door,reader,filter))if(d!=null&&d.link()==null){if(!d.bind(p.getUUID(),core)){rollback(p,bound);return snapshot(p,root,r,false,"directory_full");}bound.add(d);}
        DoorTarget previous=filter==null?null:filter.filter().door();
        if(filter!=null&&!FilterService.link(p,filter,door.asDoorTarget()).equals("door_linked")){rollback(p,bound);return snapshot(p,root,r,false,"changed");}
        if(!reader.targetDoor(p.getUUID(),door)){if(filter!=null){filter.filter().door(previous);filter.setChanged();}rollback(p,bound);return snapshot(p,root,r,false,"changed");}
        NetworkService.invalidate(level);
        boolean online=NetworkService.inspect(level,door).online()&&NetworkService.inspect(level,reader).online()&&(filter==null||NetworkService.inspect(level,filter).online());
        return snapshot(p,root,r,false,online?"saved":"saved_offline");
    }
    public static void open(ServerPlayer p,DeviceBlockEntity root){if(manages(p,root)){var r=new Request(root.getBlockPos(),root.identity(),root.link(),0,Operation.QUERY,null,null,null);ServerPlayNetworking.send(p,snapshot(p,root,r,true,"choose_door"));}}
    private static boolean limit(ServerPlayer p,boolean query){var map=query?QUERIES:ACTIONS;long now=p.level().getServer().overworld().getGameTime();Long last=map.get(p.getUUID());if(last!=null&&now>=last&&now-last<(query?20:5))return false;map.put(p.getUUID(),now);return true;}
    public static void initialize(){
        PayloadTypeRegistry.serverboundPlay().register(Open.TYPE,Open.CODEC);PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE,(r,c)->{var p=c.player();if(limit(p,false)&&p.level().isLoaded(r.position)&&p.level().getBlockEntity(r.position) instanceof DeviceBlockEntity d)open(p,d);});
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{var p=c.player();if(!limit(p,r.operation==Operation.QUERY)){ServerPlayNetworking.send(p,new Snapshot(r.root,r.identity,r.link,r.nonce,false,true,"busy",false,List.of()));return;}ServerPlayNetworking.send(p,handle(p,r));});
    }
    public static void clear(){QUERIES.clear();ACTIONS.clear();}
    private AccessSetupProtocol(){}
}
