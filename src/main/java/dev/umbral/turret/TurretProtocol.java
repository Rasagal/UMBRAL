// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.network.NetworkLink;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class TurretProtocol {
    public enum Operation { QUERY, LOAD, UNLOAD, ARM, DISARM, RANGE, PREFERENCE }
    public enum Result { READY, LOADED, UNLOADED, ARMED, DISARMED, RANGE, NO_ARROWS, FULL, INVALID, OFFLINE, BUSY, PREFERENCE;
        public String key(){return "turret.umbral.result."+name().toLowerCase(Locale.ROOT);}
    }
    public record Request(BlockPos position,UUID identity,NetworkLink link,long nonce,Operation operation) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("turret_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID(),readLink(b),b.readLong(),b.readEnum(Operation.class));}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeBlockPos(p.position);b.writeUUID(p.identity);writeLink(b,p.link);b.writeLong(p.nonce);b.writeEnum(p.operation);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(BlockPos position,UUID identity,NetworkLink link,long nonce,boolean open,boolean valid,String label,
                           TurretService.Status status,boolean armed,int ammunition,int range,boolean metal,Metal material,TurretState.Preference preference,String target,boolean canLoad,Result result) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("turret_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b) {
                BlockPos pos=b.readBlockPos();UUID id=b.readUUID();NetworkLink link=readLink(b);long nonce=b.readLong();
                boolean open=b.readBoolean(),valid=b.readBoolean();String label=b.readUtf(48);var status=b.readEnum(TurretService.Status.class);
                boolean armed=b.readBoolean();int ammo=b.readVarInt(),range=b.readVarInt();
                if(ammo<0||ammo>64||(range!=8&&range!=12&&range!=16))throw new IllegalArgumentException("Invalid turret snapshot");
                return new Snapshot(pos,id,link,nonce,open,valid,label,status,armed,ammo,range,b.readBoolean(),b.readEnum(Metal.class),b.readEnum(TurretState.Preference.class),b.readUtf(96),b.readBoolean(),b.readEnum(Result.class));
            }
            public void encode(RegistryFriendlyByteBuf b,Snapshot p) {
                b.writeBlockPos(p.position);b.writeUUID(p.identity);writeLink(b,p.link);b.writeLong(p.nonce);b.writeBoolean(p.open);b.writeBoolean(p.valid);
                b.writeUtf(p.label,48);b.writeEnum(p.status);b.writeBoolean(p.armed);b.writeVarInt(p.ammunition);b.writeVarInt(p.range);b.writeBoolean(p.metal);b.writeEnum(p.material);
                b.writeEnum(p.preference);
                b.writeUtf(p.target,96);b.writeBoolean(p.canLoad);b.writeEnum(p.result);
            }
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    private static NetworkLink readLink(RegistryFriendlyByteBuf b){return b.readBoolean()?NetworkLink.STREAM_CODEC.decode(b):null;}
    private static void writeLink(RegistryFriendlyByteBuf b,NetworkLink link){b.writeBoolean(link!=null);if(link!=null)NetworkLink.STREAM_CODEC.encode(b,link);}
    public static boolean manages(ServerPlayer p,DeviceBlockEntity turret) {
        return turret.kind().turret()&&p.isAlive()&&!p.isSpectator()&&p.level()==turret.getLevel()
                &&turret.ownedBy(p.getUUID())&&turret.getBlockPos().distToCenterSqr(p.position())<=64;
    }
    private static boolean canLoad(ServerPlayer p,TurretState s) {
        if(s.count()>=64)return false;var held=s.ammunition();
        for(int n=0;n<p.getInventory().getContainerSize();n++){
            var stack=p.getInventory().getItem(n);
            if(s.accepts(stack)&&s.count()<Math.min(64,stack.getMaxStackSize())&&(held.isEmpty()||net.minecraft.world.item.ItemStack.isSameItemSameComponents(held,stack)))return true;
        }
        return false;
    }
    public static Snapshot snapshot(ServerPlayer p,DeviceBlockEntity turret,long nonce,boolean open,Result result) {
        var s=turret.turret();return new Snapshot(turret.getBlockPos(),turret.identity(),turret.link(),nonce,open,true,turret.label(),
                TurretService.status((ServerLevel)p.level(),turret),s.armed(),s.count(),s.range(),s.metal(),s.material(),s.preference(),TurretService.targetName(turret),canLoad(p,s),result);
    }
    public static void open(ServerPlayer p,DeviceBlockEntity turret){if(manages(p,turret))ServerPlayNetworking.send(p,snapshot(p,turret,0,true,Result.READY));}
    public static Snapshot handle(ServerPlayer p,Request request) {
        if(!p.level().isLoaded(request.position)||!(p.level().getBlockEntity(request.position) instanceof DeviceBlockEntity turret)
                ||!turret.identity().equals(request.identity)||!Objects.equals(turret.link(),request.link)||!manages(p,turret))return denied(request,Result.INVALID);
        var s=turret.turret();long now=p.level().getGameTime();Result result=Result.READY;
        switch(request.operation) {
            case QUERY -> { }
            case DISARM -> {s.setArmed(false,now);s.stop(now);result=Result.DISARMED;}
            case ARM -> {
                NetworkService.invalidate(p.level());
                if(!NetworkService.inspect((ServerLevel)p.level(),turret).online())result=Result.OFFLINE;
                else if(s.count()==0)result=Result.NO_ARROWS;
                else {s.setArmed(true,now);result=Result.ARMED;}
            }
            case RANGE -> {s.cycleRange(now);result=Result.RANGE;}
            case PREFERENCE -> {if(!s.metal())result=Result.INVALID;else{s.cyclePreference(now);result=Result.PREFERENCE;}}
            case LOAD -> {
                int count=s.load(p.getMainHandItem(),now);
                for(int n=0;n<p.getInventory().getContainerSize();n++)count+=s.load(p.getInventory().getItem(n),now);
                p.getInventory().setChanged();p.containerMenu.broadcastChanges();
                result=count>0?Result.LOADED:s.count()==64?Result.FULL:Result.NO_ARROWS;
            }
            case UNLOAD -> {
                var stack=s.unload(now);if(!stack.isEmpty()){
                    // Vanilla Inventory.add discards overflow in creative. Merge and place explicitly.
                    for(int n=0;n<36&&!stack.isEmpty();n++) {
                        var existing=p.getInventory().getItem(n);
                        if(!existing.isEmpty()&&net.minecraft.world.item.ItemStack.isSameItemSameComponents(existing,stack)) {
                            int moved=Math.min(stack.getCount(),existing.getMaxStackSize()-existing.getCount());
                            if(moved>0){existing.grow(moved);stack.shrink(moved);}
                        }
                    }
                    if(!stack.isEmpty()){
                        int free=p.getInventory().getFreeSlot();
                        if(free>=0)p.getInventory().setItem(free,stack);else p.drop(stack,false,net.minecraft.util.Prediction.SERVER_ONLY);
                    }
                    p.getInventory().setChanged();p.containerMenu.broadcastChanges();
                }
                result=Result.UNLOADED;
            }
        }
        if(turret.getBlockState().getBlock() instanceof dev.umbral.block.CentellaBlock)
            p.level().setBlock(turret.getBlockPos(),turret.getBlockState().setValue(dev.umbral.block.CentellaBlock.METAL,s.material()),2);
        turret.setChanged();turret.syncTurret();return snapshot(p,turret,request.nonce,false,result);
    }
    private static Snapshot denied(Request r,Result result){return new Snapshot(r.position,r.identity,r.link,r.nonce,false,false,"",TurretService.Status.DISARMED,false,0,12,false,Metal.EMPTY,TurretState.Preference.AUTOMATIC,"",false,result);}
    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{
            var p=c.player();long now=((ServerLevel)p.level()).getServer().overworld().getGameTime();var limiter=r.operation==Operation.QUERY?QUERIES:ACTIONS;
            Long last=limiter.get(p.getUUID());if(last!=null&&now>=last&&now-last<(r.operation==Operation.QUERY?10:5)){
                if(r.operation!=Operation.QUERY)ServerPlayNetworking.send(p,denied(r,Result.BUSY));return;
            }
            limiter.put(p.getUUID(),now);ServerPlayNetworking.send(p,handle(p,r));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{QUERIES.remove(handler.getPlayer().getUUID());ACTIONS.remove(handler.getPlayer().getUUID());});
    }
    public static void clear(){QUERIES.clear();ACTIONS.clear();}
    private TurretProtocol(){ }
}
