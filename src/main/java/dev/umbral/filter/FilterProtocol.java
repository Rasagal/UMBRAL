// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import dev.umbral.UmbralContent;
import dev.umbral.access.DoorTarget;
import dev.umbral.block.*;
import dev.umbral.network.*;
import dev.umbral.installation.NexoProtocol;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import java.util.*;

/** Owner-only bounded editing channel. Root identity, network, scanner identity and revision are authoritative. */
public final class FilterProtocol {
    public enum Operation { QUERY,RULE,REMOVE,EXEMPT,MODE,ARM,DISARM,TEST,PRESET,UNLINK }
    public record Profile(UUID id,String name){}
    public record Request(BlockPos root,UUID identity,NetworkLink link,BlockPos target,UUID targetIdentity,long nonce,long revision,Operation operation,String selector,int value,UUID player) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("filter_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readBlockPos(),b.readUUID(),readLink(b),b.readBlockPos(),b.readUUID(),b.readLong(),b.readLong(),b.readEnum(Operation.class),b.readUtf(128),b.readVarInt(),b.readBoolean()?b.readUUID():null);}
            public void encode(RegistryFriendlyByteBuf b,Request r){b.writeBlockPos(r.root);b.writeUUID(r.identity);writeLink(b,r.link);b.writeBlockPos(r.target);b.writeUUID(r.targetIdentity);b.writeLong(r.nonce);b.writeLong(r.revision);b.writeEnum(r.operation);b.writeUtf(r.selector,128);b.writeVarInt(r.value);b.writeBoolean(r.player!=null);if(r.player!=null)b.writeUUID(r.player);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(BlockPos root,UUID identity,NetworkLink link,BlockPos target,UUID targetIdentity,long nonce,long revision,boolean open,boolean valid,String label,String result,FilterState.Mode mode,boolean armed,boolean online,boolean complete,DoorTarget door,String doorName,List<FilterRule> rules,List<Profile> profiles,List<FilterState.Record> history) implements CustomPacketPayload {
        public Snapshot{rules=List.copyOf(rules);profiles=List.copyOf(profiles);history=List.copyOf(history);}
        public static final Type<Snapshot> TYPE=new Type<>(UmbralContent.id("filter_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>(){
            public Snapshot decode(RegistryFriendlyByteBuf b){
                var root=b.readBlockPos();var id=b.readUUID();var link=readLink(b);var target=b.readBlockPos();var targetId=b.readUUID();long nonce=b.readLong(),revision=b.readLong();boolean open=b.readBoolean(),valid=b.readBoolean();String label=b.readUtf(48),result=b.readUtf(32);var mode=b.readEnum(FilterState.Mode.class);boolean armed=b.readBoolean(),online=b.readBoolean(),complete=b.readBoolean();var door=b.readBoolean()?DoorTarget.STREAM_CODEC.decode(b):null;String doorName=b.readUtf(48);
                var rules=new ArrayList<FilterRule>();int n=bounded(b,16);for(int j=0;j<n;j++){String selector=b.readUtf(128);int min=b.readVarInt();int size=bounded(b,16);var exceptions=new ArrayList<FilterRule.Exemption>();for(int k=0;k<size;k++)exceptions.add(new FilterRule.Exemption(b.readUUID(),b.readUtf(32)));rules.add(new FilterRule(selector,min,exceptions));}
                var profiles=new ArrayList<Profile>();n=bounded(b,64);for(int j=0;j<n;j++)profiles.add(new Profile(b.readUUID(),b.readUtf(32)));
                var history=new ArrayList<FilterState.Record>();n=bounded(b,16);for(int j=0;j<n;j++){var player=b.readUUID();String name=b.readUtf(32);var outcome=b.readEnum(Inspection.Outcome.class);int size=bounded(b,16);var matches=new ArrayList<Inspection.Match>();for(int k=0;k<size;k++){String item=b.readUtf(128);int count=b.readVarInt();if(!FilterRule.validSelector(item)||count<1||count>Inspection.MAX_COUNT)throw new IllegalArgumentException("Invalid inspection match");matches.add(new Inspection.Match(item,count));}history.add(new FilterState.Record(player,name,outcome,matches,b.readLong(),b.readBoolean()));}
                return new Snapshot(root,id,link,target,targetId,nonce,revision,open,valid,label,result,mode,armed,online,complete,door,doorName,rules,profiles,history);
            }
            public void encode(RegistryFriendlyByteBuf b,Snapshot s){
                b.writeBlockPos(s.root);b.writeUUID(s.identity);writeLink(b,s.link);b.writeBlockPos(s.target);b.writeUUID(s.targetIdentity);b.writeLong(s.nonce);b.writeLong(s.revision);b.writeBoolean(s.open);b.writeBoolean(s.valid);b.writeUtf(s.label,48);b.writeUtf(s.result,32);b.writeEnum(s.mode);b.writeBoolean(s.armed);b.writeBoolean(s.online);b.writeBoolean(s.complete);b.writeBoolean(s.door!=null);if(s.door!=null)DoorTarget.STREAM_CODEC.encode(b,s.door);b.writeUtf(s.doorName,48);
                b.writeVarInt(s.rules.size());for(var r:s.rules){b.writeUtf(r.selector(),128);b.writeVarInt(r.minimum());b.writeVarInt(r.exemptions().size());for(var e:r.exemptions()){b.writeUUID(e.id());b.writeUtf(e.name(),32);}}
                b.writeVarInt(s.profiles.size());for(var p:s.profiles){b.writeUUID(p.id);b.writeUtf(p.name,32);}
                b.writeVarInt(s.history.size());for(var r:s.history){b.writeUUID(r.player());b.writeUtf(r.name(),32);b.writeEnum(r.outcome());b.writeVarInt(r.matches().size());for(var m:r.matches()){b.writeUtf(m.item(),128);b.writeVarInt((int)m.count());}b.writeLong(r.tick());b.writeBoolean(r.test());}
            }
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final Map<UUID,Long> QUERIES=new HashMap<>(),ACTIONS=new HashMap<>();
    private static int bounded(RegistryFriendlyByteBuf b,int max){int n=b.readVarInt();if(n<0||n>max)throw new IllegalArgumentException("Invalid inspection list");return n;}
    private static NetworkLink readLink(RegistryFriendlyByteBuf b){return b.readBoolean()?NetworkLink.STREAM_CODEC.decode(b):null;}
    private static void writeLink(RegistryFriendlyByteBuf b,NetworkLink link){b.writeBoolean(link!=null);if(link!=null)NetworkLink.STREAM_CODEC.encode(b,link);}
    public static boolean authority(ServerPlayer p,DeviceBlockEntity root,DeviceBlockEntity target){
        if(!NexoProtocol.manages(p,root)||!FilterService.scanner(target)||!target.ownedBy(p.getUUID())||!Objects.equals(root.link(),target.link()))return false;
        return root==target||(Set.of(DeviceKind.CORE,DeviceKind.TERMINAL,DeviceKind.NEXO).contains(root.kind())&&NetworkService.inspect((ServerLevel)p.level(),root).online()&&NetworkService.inspect((ServerLevel)p.level(),target).online());
    }
    public static List<Profile> profiles(ServerPlayer p,DeviceBlockEntity target){
        var map=new LinkedHashMap<UUID,String>();map.put(p.getUUID(),p.getGameProfile().name());p.level().getServer().getPlayerList().getPlayers().forEach(v->map.put(v.getUUID(),v.getGameProfile().name()));target.filter().history().reversed().forEach(r->map.putIfAbsent(r.player(),r.name()));target.filter().rules().forEach(r->r.exemptions().forEach(e->map.putIfAbsent(e.id(),e.name())));return map.entrySet().stream().limit(64).map(e->new Profile(e.getKey(),e.getValue())).toList();
    }
    public static Snapshot snapshot(ServerPlayer p,DeviceBlockEntity root,DeviceBlockEntity target,long nonce,boolean open,String result){var s=target.filter();var door=FilterService.door((ServerLevel)p.level(),target);return new Snapshot(root.getBlockPos(),root.identity(),root.link(),target.getBlockPos(),target.identity(),nonce,s.revision(),open,true,target.label(),result,s.mode(),s.armed(),NetworkService.inspect((ServerLevel)p.level(),target).online(),FilterBlock.complete(target),s.door(),door==null?"":door.label(),s.rules(),profiles(p,target),s.history().reversed());}
    public static void open(ServerPlayer p,DeviceBlockEntity root,DeviceBlockEntity target){if(authority(p,root,target))ServerPlayNetworking.send(p,snapshot(p,root,target,0,true,"ready"));}
    private static Snapshot denied(Request r,String result){return new Snapshot(r.root,r.identity,r.link,r.target,r.targetIdentity,r.nonce,r.revision,false,false,"",result,FilterState.Mode.INFORM,false,false,false,null,"",List.of(),List.of(),List.of());}
    public static Snapshot handle(ServerPlayer p,Request r){
        var level=(ServerLevel)p.level();if(!level.isLoaded(r.root)||!level.isLoaded(r.target)||!(level.getBlockEntity(r.root) instanceof DeviceBlockEntity root)||!(level.getBlockEntity(r.target) instanceof DeviceBlockEntity target)||!root.identity().equals(r.identity)||!target.identity().equals(r.targetIdentity)||!Objects.equals(root.link(),r.link)||!authority(p,root,target))return denied(r,"invalid");
        var s=target.filter();if(r.operation==Operation.QUERY)return snapshot(p,root,target,r.nonce,false,"ready");if(r.revision!=s.revision())return snapshot(p,root,target,r.nonce,false,"stale");
        String result=switch(r.operation){
            case RULE -> {String selector=r.selector;if(r.value<0){int slot=-r.value-1;if(slot<0||slot>=36||p.getInventory().getItem(slot).isEmpty())yield "bad_rule";selector=BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(slot).getItem()).toString();yield s.rule(selector,1);}yield s.rule(selector,r.value);}
            case REMOVE -> s.remove(r.selector);
            case EXEMPT -> {var existing=s.rules().stream().filter(v->v.selector().equals(r.selector)).flatMap(v->v.exemptions().stream()).filter(v->v.id().equals(r.player)).findFirst();if(existing.isPresent())yield s.exempt(r.selector,existing.get().id(),existing.get().name());var profile=profiles(p,target).stream().filter(v->v.id.equals(r.player)).findFirst();yield profile.isPresent()?s.exempt(r.selector,profile.get().id,profile.get().name):"unknown_player";}
            case MODE -> r.value>=0&&r.value<FilterState.Mode.values().length?FilterService.mode(target,FilterState.Mode.values()[r.value]):"invalid";
            case ARM -> FilterService.arm(target,true);case DISARM -> FilterService.arm(target,false);
            case TEST -> {FilterService.report(target,p,FilterService.inspect(target,p,true),true);yield "tested";}
            case PRESET -> {var preset=List.of("minecraft:tnt","minecraft:tnt_minecart","minecraft:lava_bucket","minecraft:fire_charge");long missing=preset.stream().filter(v->s.rules().stream().noneMatch(a->a.selector().equals(v))).count();if(s.rules().size()+missing>16)yield "full";preset.forEach(v->s.rule(v,1));yield "preset_saved";}
            case UNLINK -> FilterService.unlink(target);default -> "invalid";
        };FilterService.sync(target);return snapshot(p,root,target,r.nonce,false,result);
    }
    public static void initialize(){PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{var p=c.player();boolean query=r.operation==Operation.QUERY;var map=query?QUERIES:ACTIONS;long now=p.level().getServer().overworld().getGameTime();var last=map.get(p.getUUID());if(last!=null&&now>=last&&now-last<(query?10:5)){if(!query)ServerPlayNetworking.send(p,denied(r,"busy"));return;}map.put(p.getUUID(),now);ServerPlayNetworking.send(p,handle(p,r));});ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{QUERIES.remove(h.getPlayer().getUUID());ACTIONS.remove(h.getPlayer().getUUID());});}
    public static void clear(){QUERIES.clear();ACTIONS.clear();}
    private FilterProtocol(){}
}
