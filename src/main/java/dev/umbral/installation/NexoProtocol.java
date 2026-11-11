// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import java.util.*;

/** Local rule engine and command bus. Snapshots are facts, never promises of a completed action. */
public final class NexoProtocol {
    public enum Operation { QUERY, ARM, DISARM, RANGE, LOAD, UNLOAD, REPLENISH, CANCEL, RECOVER, CONFIGURE_FILTER, CONFIGURE_AIRLOCK, FENCE_CONTROL, RENAME, CYCLE_AMMO }
    public enum Reading { ACTIVE, OFFLINE, UNLOADED, REPLACED, DISARMED, ARMED, LOW_AMMO, EMPTY, ALARM, MONITORING, PAUSED, NO_UNIT, IDLE, COLLECTING, DELIVERING, RETURNING, BLOCKED, STOCK_MISSING, CHECKPOINT, INSPECTION_ALERT, INCOMPLETE }
    public record Row(BlockPos position,UUID identity,DeviceKind kind,String label,Reading reading,int amount,int capacity,int range,boolean armed,String detail,int refill,String source,String transport,String connection,String preference){
        public Row(BlockPos position,UUID identity,DeviceKind kind,String label,Reading reading,int amount,int capacity,int range,boolean armed,String detail,int refill,String source,String transport){this(position,identity,kind,label,reading,amount,capacity,range,armed,detail,refill,source,transport,"", "automatic");}
    }
    public record Open(BlockPos position) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(UmbralContent.id("nexo_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=new StreamCodec<>(){public Open decode(RegistryFriendlyByteBuf b){return new Open(b.readBlockPos());}public void encode(RegistryFriendlyByteBuf b,Open p){b.writeBlockPos(p.position);}};
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(BlockPos root,UUID identity,NetworkLink link,long nonce,Operation operation,BlockPos target,UUID targetIdentity,String text) implements CustomPacketPayload {
        public Request(BlockPos root,UUID identity,NetworkLink link,long nonce,Operation operation,BlockPos target,UUID targetIdentity){this(root,identity,link,nonce,operation,target,targetIdentity,"");}
        public Request {text=text==null?"":text;}
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("nexo_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID(),readLink(b),b.readLong(),b.readEnum(Operation.class),b.readBlockPos(),b.readUUID(),b.readUtf(48));}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeBlockPos(p.root);b.writeUUID(p.identity);writeLink(b,p.link);b.writeLong(p.nonce);b.writeEnum(p.operation);b.writeBlockPos(p.target);b.writeUUID(p.targetIdentity);b.writeUtf(p.text,48);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Event(String label,String code,int amount,long tick,String subject,String item){public Event(String label,String code,int amount,long tick){this(label,code,amount,tick,"","");}}
    public record Snapshot(BlockPos root,UUID identity,NetworkLink link,long nonce,boolean open,boolean valid,String result,List<Row> rows,long observedAt,List<Event> events) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("nexo_snapshot"));
        public Snapshot{rows=List.copyOf(rows);events=List.copyOf(events);}
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>(){
            public Snapshot decode(RegistryFriendlyByteBuf b){
                var root=b.readBlockPos();var id=b.readUUID();var link=readLink(b);long nonce=b.readLong();boolean open=b.readBoolean(),valid=b.readBoolean();String result=b.readUtf(32);
                int count=b.readVarInt();if(count<0||count>128)throw new IllegalArgumentException("Invalid installation size");var rows=new ArrayList<Row>();
                for(int n=0;n<count;n++){
                    var pos=b.readBlockPos();var identity=b.readUUID();var kind=b.readEnum(DeviceKind.class);String label=b.readUtf(48);var reading=b.readEnum(Reading.class);
                    int amount=b.readVarInt(),capacity=b.readVarInt(),range=b.readVarInt();if(amount<0||amount>4096||capacity<0||capacity>4096||range<0||range>16)throw new IllegalArgumentException("Invalid telemetry");
                    boolean armed=b.readBoolean();String detail=b.readUtf(32);int refill=b.readVarInt();if(refill<0||refill>32)throw new IllegalArgumentException("Invalid refill");
                    rows.add(new Row(pos,identity,kind,label,reading,amount,capacity,range,armed,detail,refill,b.readUtf(48),b.readUtf(32),b.readUtf(24),b.readUtf(16)));
                }long now=b.readLong();int eventCount=b.readVarInt();if(eventCount<0||eventCount>16)throw new IllegalArgumentException("Invalid journal size");var events=new ArrayList<Event>();
                for(int n=0;n<eventCount;n++){String label=b.readUtf(48),code=b.readUtf(32);int amount=b.readVarInt();if(amount<0||amount>1000000)throw new IllegalArgumentException("Invalid journal amount");events.add(new Event(label,code,amount,b.readLong(),b.readUtf(32),b.readUtf(128)));}
                return new Snapshot(root,id,link,nonce,open,valid,result,rows,now,events);
            }
            public void encode(RegistryFriendlyByteBuf b,Snapshot p){
                b.writeBlockPos(p.root);b.writeUUID(p.identity);writeLink(b,p.link);b.writeLong(p.nonce);b.writeBoolean(p.open);b.writeBoolean(p.valid);b.writeUtf(p.result,32);b.writeVarInt(p.rows.size());
                for(var r:p.rows){b.writeBlockPos(r.position);b.writeUUID(r.identity);b.writeEnum(r.kind);b.writeUtf(r.label,48);b.writeEnum(r.reading);b.writeVarInt(r.amount);b.writeVarInt(r.capacity);b.writeVarInt(r.range);b.writeBoolean(r.armed);b.writeUtf(r.detail,32);b.writeVarInt(r.refill);b.writeUtf(r.source,48);b.writeUtf(r.transport,32);b.writeUtf(r.connection,24);b.writeUtf(r.preference,16);}
                b.writeLong(p.observedAt);b.writeVarInt(p.events.size());for(var e:p.events){b.writeUtf(e.label,48);b.writeUtf(e.code,32);b.writeVarInt(e.amount);b.writeLong(e.tick);b.writeUtf(e.subject,32);b.writeUtf(e.item,128);}
            }
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    private static final MotaService.Plan NO_REFILL=new MotaService.Plan(0,"","ready");
    private static NetworkLink readLink(RegistryFriendlyByteBuf b){return b.readBoolean()?NetworkLink.STREAM_CODEC.decode(b):null;}
    private static void writeLink(RegistryFriendlyByteBuf b,NetworkLink link){b.writeBoolean(link!=null);if(link!=null)NetworkLink.STREAM_CODEC.encode(b,link);}
    public static boolean manages(ServerPlayer p,DeviceBlockEntity root){return p.isAlive()&&!p.isSpectator()&&p.level()==root.getLevel()&&root.ownedBy(p.getUUID())&&root.getBlockPos().distToCenterSqr(p.position())<=64;}
    public static void open(ServerPlayer p,DeviceBlockEntity root){if(manages(p,root))ServerPlayNetworking.send(p,snapshot(p,root,0,true,"ready"));}
    private static Row row(ServerLevel level,InstallationDirectory.Entry e){
        if(!level.isLoaded(e.position()))return new Row(e.position(),e.identity(),e.kind(),e.label(),Reading.UNLOADED,0,0,0,false,"idle",0,"","offline");
        var d=InstallationDirectory.loaded(level,e);if(d==null)return new Row(e.position(),e.identity(),e.kind(),e.label(),Reading.REPLACED,0,0,0,false,"idle",0,"","offline");
        var connection=NetworkService.inspect(level,d);boolean online=connection.online();Reading reading=online?Reading.ACTIVE:Reading.OFFLINE;int amount=0,capacity=0,range=0;boolean armed=false;String detail="idle";
        if(d.kind().turret()){
            var t=d.turret();amount=t.count();capacity=64;range=t.range();armed=t.armed();
            if(online)reading=amount==0?Reading.EMPTY:amount<=8?Reading.LOW_AMMO:armed?Reading.ARMED:Reading.DISARMED;
        }else if(d.kind()==DeviceKind.SENSOR||d.kind()==DeviceKind.PULSE_MINE){
            var s=d.safety();armed=s.armed();range=s.range();amount=s.count();capacity=d.kind()==DeviceKind.PULSE_MINE?16:0;
            if(online)reading=s.alarm()?Reading.ALARM:!armed?Reading.DISARMED:d.kind()==DeviceKind.PULSE_MINE&&amount==0?Reading.EMPTY:Reading.MONITORING;
        }else if(d.kind()==DeviceKind.FILTER){
            var s=d.filter();amount=s.rules().size();capacity=16;armed=s.armed();detail=s.mode().id();
            if(online)reading=!dev.umbral.block.FilterBlock.complete(d)?Reading.INCOMPLETE:!armed?Reading.DISARMED:s.alarm(level.getGameTime())?Reading.INSPECTION_ALERT:Reading.CHECKPOINT;
        }else if(d.kind()==DeviceKind.FENCE){
            var s=d.fence();amount=s.segments;capacity=128;range=s.redstone()?1:0;armed=s.armed();detail=s.target().id();
            if(online)reading=d.getBlockState().getValue(ElectricFenceBlock.ENERGIZED)?Reading.ARMED:armed?Reading.PAUSED:Reading.DISARMED;
        }else if(d.kind()==DeviceKind.DOCK){
            var actor=MotaService.actor(level,d);detail=d.dock().result();amount=d.dock().delivered();capacity=32;
            if(online){
                if(actor==null)reading=MotaService.active(level,d)?Reading.PAUSED:Reading.NO_UNIT;
                else{reading=switch(actor.condition()){case "collecting"->Reading.COLLECTING;case "delivering"->Reading.DELIVERING;case "returning"->Reading.RETURNING;case "paused"->Reading.PAUSED;case "blocked"->Reading.BLOCKED;default->Reading.IDLE;};if(actor.busy())detail=actor.condition();}
                if(actor!=null&&!actor.busy()&&MotaService.stock(level,d,d.dock().stock())==null)reading=Reading.STOCK_MISSING;
            }
        }
        if(d.kind()==DeviceKind.FILTER){var latest=d.filter().latest();return new Row(e.position(),e.identity(),e.kind(),e.label(),reading,amount,capacity,0,armed,detail,0,latest==null?"":latest.name(),latest==null?"idle":latest.outcome().name().toLowerCase(java.util.Locale.ROOT),connection.status().name().toLowerCase(Locale.ROOT),"automatic");}
        var plan=d.kind().turret()?MotaService.plan(level,d,connection):NO_REFILL;
        return new Row(e.position(),e.identity(),e.kind(),e.label(),reading,amount,capacity,range,armed,detail,plan.count(),plan.stock(),plan.state(),connection.status().name().toLowerCase(Locale.ROOT),d.kind()==DeviceKind.CENTELLA?d.turret().preferenceId():"automatic");
    }
    public static Snapshot snapshot(ServerPlayer p,DeviceBlockEntity root,long nonce,boolean open,String result){
        var level=(ServerLevel)p.level();var directory=InstallationDirectory.get(level);directory.remember(root);boolean connected=root.link()!=null&&NetworkService.inspect(level,root).online();
        List<Row> rows;
        if(root.link()==null)rows=List.of(row(level,new InstallationDirectory.Entry(root.getBlockPos(),root.identity(),root.owner(),new NetworkLink(level.dimension().identifier(),root.getBlockPos(),root.identity()),root.kind(),root.label())));
        else {
            rows=directory.entries(p.getUUID(),root.link()).stream().map(e->connected||e.position().equals(root.getBlockPos())?row(level,e):new Row(e.position(),e.identity(),e.kind(),e.label(),Reading.OFFLINE,0,0,0,false,"idle",0,"","offline")).toList();
        }
        if(root.kind()==DeviceKind.FENCE){var head=dev.umbral.fence.FenceService.controller(root);if(head!=null)rows=List.of(row(level,new InstallationDirectory.Entry(head.getBlockPos(),head.identity(),head.owner(),head.link(),head.kind(),head.label())));}
        // A local unpaired device still has controls and truthful offline status.
        if(root.link()==null&&root.kind()!=DeviceKind.FENCE)rows=List.of(new Row(root.getBlockPos(),root.identity(),root.kind(),root.label(),Reading.OFFLINE,root.kind().turret()?root.turret().count():root.safety().count(),root.kind().turret()?64:root.kind()==DeviceKind.PULSE_MINE?16:0,root.kind()==DeviceKind.SENSOR?root.safety().range():0,false,"idle",0,"","offline","unbound",root.kind()==DeviceKind.CENTELLA?root.turret().preferenceId():"automatic"));
        var events=connected?NexoJournal.get(level).events(p.getUUID(),root.link()).stream().map(e->new Event(e.label(),e.code(),e.amount(),e.tick(),e.subject(),e.item())).toList():List.<Event>of();
        return new Snapshot(root.getBlockPos(),root.identity(),root.link(),nonce,open,true,result,rows,level.getGameTime(),events);
    }
    private static Snapshot denied(Request r,String result){return new Snapshot(r.root,r.identity,r.link,r.nonce,false,false,result,List.of(),0,List.of());}
    public static Snapshot handle(ServerPlayer p,Request r){
        var level=(ServerLevel)p.level();
        if(!level.isLoaded(r.root)||!(level.getBlockEntity(r.root) instanceof DeviceBlockEntity root)||!root.identity().equals(r.identity)||!Objects.equals(root.link(),r.link)||!manages(p,root))return denied(r,"invalid");
        if(r.operation==Operation.QUERY)return snapshot(p,root,r.nonce,false,"ready");
        if(!level.isLoaded(r.target)||!(level.getBlockEntity(r.target) instanceof DeviceBlockEntity target)||!target.identity().equals(r.targetIdentity)||!target.ownedBy(p.getUUID())||!Objects.equals(target.link(),root.link()))return snapshot(p,root,r.nonce,false,"invalid");
        boolean local=root==target;
        if(!local&&!dev.umbral.fence.FenceService.same(root,target)&&root.kind()!=DeviceKind.NEXO&&root.kind()!=DeviceKind.TERMINAL&&root.kind()!=DeviceKind.CORE)return snapshot(p,root,r.nonce,false,"invalid");
        NetworkService.invalidate(level,root.link());MotaService.invalidate(level);
        if(!local&&(!NetworkService.inspect(level,root).online()||!NetworkService.inspect(level,target).online()))return snapshot(p,root,r.nonce,false,"offline");
        String result="invalid";long now=level.getGameTime();
        switch(r.operation){
            case RENAME -> {result=target.rename(p.getUUID(),r.text)?"renamed":"bad_name";}
            case CYCLE_AMMO -> {if(target.kind()==DeviceKind.CENTELLA){target.turret().cyclePreference(now);result="ammo_preference";}}
            case ARM -> {
                if(!NetworkService.inspect(level,target).online())result="offline";
                else if(target.kind().turret()){if(target.turret().count()==0)result="no_stock";else{target.turret().setArmed(true,now);result="armed";}}
                else if(target.kind()==DeviceKind.FENCE)result=dev.umbral.fence.FenceService.arm(target,true);
                else if(target.kind()==DeviceKind.FILTER)result=dev.umbral.filter.FilterService.arm(target,true);
                else if(target.kind()==DeviceKind.SENSOR||target.kind()==DeviceKind.PULSE_MINE){if(target.kind()==DeviceKind.PULSE_MINE&&target.safety().count()==0)result="no_stock";else{target.safety().arm(true);result="armed";}}
            }
            case DISARM -> {if(target.kind()==DeviceKind.FENCE)result=dev.umbral.fence.FenceService.arm(target,false);else if(target.kind()==DeviceKind.FILTER)result=dev.umbral.filter.FilterService.arm(target,false);else if(target.kind().turret()){target.turret().setArmed(false,now);target.turret().stop(now);result="disarmed";}else if(target.kind()==DeviceKind.SENSOR||target.kind()==DeviceKind.PULSE_MINE){target.safety().arm(false);result="disarmed";}}
            case RANGE -> {if(target.kind()==DeviceKind.FENCE){target.fence().cycle();result="fence_mode";}else if(target.kind().turret()){target.turret().cycleRange(now);result="range";}else if(target.kind()==DeviceKind.SENSOR){target.safety().cycleRange();result="range";}}
            case LOAD -> {
                if(local&&target.kind()==DeviceKind.PULSE_MINE){int total=0;for(int n=0;n<p.getInventory().getContainerSize();n++)total+=target.safety().load(p.getInventory().getItem(n));p.getInventory().setChanged();p.containerMenu.broadcastChanges();result=total>0?"loaded":"no_stock";}
            }
            case UNLOAD -> {if(local&&target.kind()==DeviceKind.PULSE_MINE){InventoryTransfer.give(p,target.safety().unload());result="unloaded";}}
            case REPLENISH -> {if(target.kind().turret())result=MotaService.replenish(level,target);}
            case CANCEL -> {if(target.kind()==DeviceKind.DOCK){var actor=MotaService.actor(level,target);if(actor!=null&&actor.busy()){actor.cancel();result="returning";}else if(actor==null&&MotaRegistry.get(level).cancel(target.identity(),true))result="cancel_pending";}}
            case RECOVER -> {if(local&&target.kind()==DeviceKind.DOCK)result=MotaService.recover(p,target)?"recovered":"busy";}
            case FENCE_CONTROL -> {if(target.kind()==DeviceKind.FENCE){target.fence().redstone(!target.fence().redstone());result="fence_control";}}
            case CONFIGURE_AIRLOCK -> {if(dev.umbral.filter.FilterService.scanner(target)){dev.umbral.airlock.AirlockProtocol.open(p,root,target);result="ready";}}
            case CONFIGURE_FILTER -> {if(dev.umbral.filter.FilterService.scanner(target)){dev.umbral.filter.FilterProtocol.open(p,root,target);result="ready";}}
            default -> {}
        }
        target.setChanged();target.syncTurret();MotaService.syncMetal(level,target);MotaService.invalidate(level);return snapshot(p,root,r.nonce,false,result);
    }
    private static boolean limit(ServerPlayer p,boolean query){var map=query?QUERIES:ACTIONS;long now=p.level().getServer().overworld().getGameTime();var last=map.get(p.getUUID());if(last!=null&&now>=last&&now-last<(query?10:5))return false;map.put(p.getUUID(),now);return true;}
    public static void initialize(){
        PayloadTypeRegistry.serverboundPlay().register(Open.TYPE,Open.CODEC);PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Open.TYPE,(r,c)->{var p=c.player();if(!limit(p,true)||!p.level().isLoaded(r.position))return;if(p.level().getBlockEntity(r.position) instanceof DeviceBlockEntity d)open(p,d);});
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{if(limit(c.player(),r.operation==Operation.QUERY))ServerPlayNetworking.send(c.player(),handle(c.player(),r));else if(r.operation!=Operation.QUERY)ServerPlayNetworking.send(c.player(),denied(r,"busy"));});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{QUERIES.remove(handler.getPlayer().getUUID());ACTIONS.remove(handler.getPlayer().getUUID());});
    }
    public static void clear(){QUERIES.clear();ACTIONS.clear();MotaService.clearAdvisoryPlans();}
    private NexoProtocol(){}
}
