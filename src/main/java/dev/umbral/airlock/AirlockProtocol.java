// SPDX-License-Identifier: MPL-2.0

package dev.umbral.airlock;
import dev.umbral.UmbralContent;
import dev.umbral.access.DoorTarget;
import dev.umbral.block.*;
import dev.umbral.filter.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import java.util.*;

/** Bounded owner console, authenticated against physical origin and scanner identities. */
public final class AirlockProtocol {
    public enum Operation { QUERY,SAVE,ENABLE,DISABLE,RETURN }
    public record Door(DoorTarget ref,String name){}
    public record Request(BlockPos root,UUID rootId,NetworkLink link,BlockPos scanner,UUID scannerId,long nonce,long revision,Operation operation,DoorTarget outer,DoorTarget inner,int width) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("airlock_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID(),readLink(b),b.readBlockPos(),b.readUUID(),b.readLong(),b.readLong(),b.readEnum(Operation.class),readDoor(b),readDoor(b),b.readVarInt());}
            public void encode(RegistryFriendlyByteBuf b,Request r){b.writeBlockPos(r.root);b.writeUUID(r.rootId);writeLink(b,r.link);b.writeBlockPos(r.scanner);b.writeUUID(r.scannerId);b.writeLong(r.nonce);b.writeLong(r.revision);b.writeEnum(r.operation);writeDoor(b,r.outer);writeDoor(b,r.inner);b.writeVarInt(r.width);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(BlockPos root,UUID rootId,NetworkLink link,BlockPos scanner,UUID scannerId,long nonce,long revision,boolean open,boolean valid,String result,boolean enabled,DoorTarget outer,DoorTarget inner,int width,String phase,int occupants,String subject,float outerProgress,float innerProgress,List<Door> doors) implements CustomPacketPayload {
        public Snapshot{doors=List.copyOf(doors);}
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("airlock_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>(){
            public Snapshot decode(RegistryFriendlyByteBuf b){var root=b.readBlockPos();var rootId=b.readUUID();var link=readLink(b);var scanner=b.readBlockPos();var id=b.readUUID();long nonce=b.readLong(),revision=b.readLong();boolean open=b.readBoolean(),valid=b.readBoolean();String result=b.readUtf(32);boolean enabled=b.readBoolean();var outer=readDoor(b);var inner=readDoor(b);int width=b.readVarInt();String phase=b.readUtf(32);int occupants=b.readVarInt();String subject=b.readUtf(32);float outerProgress=b.readFloat(),innerProgress=b.readFloat();int count=b.readVarInt();if(!Float.isFinite(outerProgress)||!Float.isFinite(innerProgress)||outerProgress<0||outerProgress>1||innerProgress<0||innerProgress>1||width!=1&&width!=3&&width!=5||occupants<0||occupants>4096||count<0||count>16)throw new IllegalArgumentException("Invalid airlock snapshot");var doors=new ArrayList<Door>();for(int n=0;n<count;n++)doors.add(new Door(DoorTarget.STREAM_CODEC.decode(b),b.readUtf(48)));return new Snapshot(root,rootId,link,scanner,id,nonce,revision,open,valid,result,enabled,outer,inner,width,phase,occupants,subject,outerProgress,innerProgress,doors);}
            public void encode(RegistryFriendlyByteBuf b,Snapshot s){b.writeBlockPos(s.root);b.writeUUID(s.rootId);writeLink(b,s.link);b.writeBlockPos(s.scanner);b.writeUUID(s.scannerId);b.writeLong(s.nonce);b.writeLong(s.revision);b.writeBoolean(s.open);b.writeBoolean(s.valid);b.writeUtf(s.result,32);b.writeBoolean(s.enabled);writeDoor(b,s.outer);writeDoor(b,s.inner);b.writeVarInt(s.width);b.writeUtf(s.phase,32);b.writeVarInt(s.occupants);b.writeUtf(s.subject,32);b.writeFloat(s.outerProgress);b.writeFloat(s.innerProgress);b.writeVarInt(s.doors.size());for(var d:s.doors){DoorTarget.STREAM_CODEC.encode(b,d.ref);b.writeUtf(d.name,48);}}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    private static DoorTarget readDoor(RegistryFriendlyByteBuf b){return b.readBoolean()?DoorTarget.STREAM_CODEC.decode(b):null;}
    private static void writeDoor(RegistryFriendlyByteBuf b,DoorTarget ref){b.writeBoolean(ref!=null);if(ref!=null)DoorTarget.STREAM_CODEC.encode(b,ref);}
    private static NetworkLink readLink(RegistryFriendlyByteBuf b){return b.readBoolean()?NetworkLink.STREAM_CODEC.decode(b):null;}
    private static void writeLink(RegistryFriendlyByteBuf b,NetworkLink link){b.writeBoolean(link!=null);if(link!=null)NetworkLink.STREAM_CODEC.encode(b,link);}
    public static Snapshot snapshot(ServerPlayer p,DeviceBlockEntity root,DeviceBlockEntity scanner,long nonce,boolean open,String result){
        var level=(ServerLevel)p.level();var s=scanner.airlock();var actor=s.subject==null?null:level.getServer().getPlayerList().getPlayer(s.subject);var choices=new LinkedHashMap<DoorTarget,Door>();
        for(var ref:Arrays.asList(s.outer(),scanner.filter().door())){var d=AirlockService.resolve(level,ref);if(d!=null)choices.put(ref,new Door(ref,d.label()));}
        if(scanner.link()!=null)for(var entry:InstallationDirectory.get(level).entries(p.getUUID(),scanner.link()))if(entry.kind()==DeviceKind.DOOR&&level.isLoaded(entry.position())){var d=InstallationDirectory.loaded(level,entry);if(d!=null)choices.putIfAbsent(d.asDoorTarget(),new Door(d.asDoorTarget(),d.label()));}
        return new Snapshot(root.getBlockPos(),root.identity(),root.link(),scanner.getBlockPos(),scanner.identity(),nonce,scanner.filter().revision(),open,true,result,s.enabled(),s.outer(),scanner.filter().door(),s.width(),s.phase,Math.min(4096,s.occupants),actor==null?"":actor.getGameProfile().name(),progress(level,AirlockService.outer(scanner)),progress(level,AirlockService.inner(scanner)),choices.values().stream().limit(16).toList());
    }
    private static float progress(ServerLevel l,DeviceBlockEntity d){return d==null?0:d.doorMotion().state().position(l.getGameTime());}
    public static void open(ServerPlayer p,DeviceBlockEntity root,DeviceBlockEntity scanner){if(FilterProtocol.authority(p,root,scanner))ServerPlayNetworking.send(p,snapshot(p,root,scanner,0,true,"ready"));}
    private static Snapshot denied(Request r,String result){return new Snapshot(r.root,r.rootId,r.link,r.scanner,r.scannerId,r.nonce,r.revision,false,false,result,false,null,null,3,"offline",0,"",0,0,List.of());}
    public static Snapshot handle(ServerPlayer p,Request r){
        var l=(ServerLevel)p.level();if(!l.isLoaded(r.root)||!l.isLoaded(r.scanner)||!(l.getBlockEntity(r.root) instanceof DeviceBlockEntity root)||!(l.getBlockEntity(r.scanner) instanceof DeviceBlockEntity scanner)||!root.identity().equals(r.rootId)||!scanner.identity().equals(r.scannerId)||!Objects.equals(root.link(),r.link)||!FilterProtocol.authority(p,root,scanner))return denied(r,"invalid");
        if(r.operation==Operation.QUERY)return snapshot(p,root,scanner,r.nonce,false,"ready");if(r.revision!=scanner.filter().revision())return snapshot(p,root,scanner,r.nonce,false,"stale");
        String result=switch(r.operation){case SAVE->AirlockService.configure(p,scanner,r.outer,r.inner,r.width);case ENABLE->AirlockService.enable(scanner,true);case DISABLE->AirlockService.enable(scanner,false);case RETURN->{AirlockService.returnOutside(scanner);yield "airlock_returning";}default->"invalid";};return snapshot(p,root,scanner,r.nonce,false,result);
    }
    public static void initialize(){PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{var p=c.player();boolean query=r.operation==Operation.QUERY;var map=query?QUERIES:ACTIONS;long now=p.level().getServer().overworld().getGameTime();var last=map.get(p.getUUID());if(last!=null&&now>=last&&now-last<(query?10:5)){if(!query)ServerPlayNetworking.send(p,denied(r,"busy"));return;}map.put(p.getUUID(),now);ServerPlayNetworking.send(p,handle(p,r));});ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{QUERIES.remove(h.getPlayer().getUUID());ACTIONS.remove(h.getPlayer().getUUID());});}
    public static void clear(){QUERIES.clear();ACTIONS.clear();}
    private AirlockProtocol(){}
}
