// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;

import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.network.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import java.util.*;

/** Server-side checkpoint policy. Opening still needs a valid bearer pass; inspection grants no pass. */
public final class FilterService {
    public enum Gate { ALLOW,REQUIRED,BLOCKED,UNAVAILABLE }
    public static final int APPROVAL_TICKS=200;
    public static boolean scanner(DeviceBlockEntity d){return d.kind()==DeviceKind.FILTER&&FilterBlock.base(d.getBlockState());}
    public static DeviceBlockEntity door(ServerLevel level,DeviceBlockEntity scanner){
        var ref=scanner.filter().door();if(ref==null||!ref.dimension().equals(level.dimension().identifier())||!level.isLoaded(ref.position()))return null;
        if(!(level.getBlockEntity(ref.position()) instanceof DeviceBlockEntity d)||d.kind()!=DeviceKind.DOOR||!d.identity().equals(ref.identity())||!Objects.equals(d.owner(),scanner.owner())||!Objects.equals(d.link(),scanner.link()))return null;return d;
    }
    public static String link(ServerPlayer p,DeviceBlockEntity scanner,DoorTarget ref){
        if(scanner.airlock().enabled())return "airlock_active";
        if(!scanner(scanner)||!scanner.ownedBy(p.getUUID())||scanner.link()==null||!ref.dimension().equals(p.level().dimension().identifier())||!p.level().isLoaded(ref.position()))return "door_invalid";
        if(scanner.filter().armed()&&scanner.filter().mode()==FilterState.Mode.ACCESS)return "disarm_first";
        if(!(p.level().getBlockEntity(ref.position()) instanceof DeviceBlockEntity target)||target.kind()!=DeviceKind.DOOR||!target.identity().equals(ref.identity())||!target.ownedBy(p.getUUID())||!Objects.equals(target.link(),scanner.link()))return "door_invalid";
        var facing=scanner.getBlockState().getValue(DeviceBlock.FACING);var center=scanner.getBlockPos().relative(facing.getClockWise());var delta=target.getBlockPos().subtract(center);
        int behind=-delta.getX()*facing.getStepX()-delta.getZ()*facing.getStepZ();int sideways=delta.getX()*facing.getClockWise().getStepX()+delta.getZ()*facing.getClockWise().getStepZ();
        if(delta.getY()!=0||sideways!=0||behind<1||behind>4)return "door_position";
        if(target.getBlockState().getValue(DeviceBlock.FACING)!=facing)return "door_facing";
        if(target.filterGuard()!=null&&!target.filterGuard().equals(scanner.asDoorTarget()))return "door_busy";
        scanner.filter().door(ref);sync(scanner);return "door_linked";
    }
    private static String release(DeviceBlockEntity scanner){
        var ref=scanner.filter().door();if(ref==null)return "ready";var level=(ServerLevel)scanner.getLevel();
        if(!level.isLoaded(ref.position()))return "door_unavailable";
        if(level.getBlockEntity(ref.position()) instanceof DeviceBlockEntity d&&d.identity().equals(ref.identity())&&scanner.asDoorTarget().equals(d.filterGuard()))d.filterGuard(null);
        return "ready";
    }
    public static String arm(DeviceBlockEntity scanner,boolean armed){
        if(!scanner(scanner)||!(scanner.getLevel() instanceof ServerLevel level))return "invalid";var s=scanner.filter();
        if(!armed&&scanner.airlock().enabled())return "airlock_active";
        if(armed){
            if(!FilterBlock.complete(scanner))return "incomplete";if(!NetworkService.inspect(level,scanner).online())return "offline";if(s.rules().isEmpty())return "no_rules";
            if(s.mode()==FilterState.Mode.ACCESS){var door=door(level,scanner);if(door==null)return "door_invalid";if(door.filterGuard()!=null&&!door.filterGuard().equals(scanner.asDoorTarget()))return "door_busy";door.filterGuard(scanner.asDoorTarget());}
        }else{String result=release(scanner);if(!result.equals("ready"))return result;}
        s.armed(armed);s.alarmUntil=0;sync(scanner);return armed?"armed":"disarmed";
    }
    public static String mode(DeviceBlockEntity scanner,FilterState.Mode mode){
        if(!scanner(scanner)||!(scanner.getLevel() instanceof ServerLevel level))return "invalid";var s=scanner.filter();
        if(mode==s.mode())return "ready";
        if(scanner.airlock().enabled()&&mode!=FilterState.Mode.ACCESS)return "airlock_active";
        if(mode==FilterState.Mode.ACCESS){var target=door(level,scanner);if(target==null)return "door_invalid";if(target.filterGuard()!=null&&!target.filterGuard().equals(scanner.asDoorTarget()))return "door_busy";if(s.armed())target.filterGuard(scanner.asDoorTarget());}
        else if(s.mode()==FilterState.Mode.ACCESS){String result=release(scanner);if(!result.equals("ready"))return result;}
        s.mode(mode);s.alarmUntil=0;sync(scanner);return "mode_saved";
    }
    public static String unlink(DeviceBlockEntity scanner){if(scanner.airlock().enabled())return "airlock_active";String result=release(scanner);if(!result.equals("ready"))return result;scanner.filter().door(null);if(scanner.filter().mode()==FilterState.Mode.ACCESS)scanner.filter().armed(false);sync(scanner);return "door_unlinked";}
    /** Owner recovery from the reader also works when the scanner is broken or unloaded. */
    public static boolean clearGuard(ServerPlayer p,DeviceBlockEntity door){
        if(!door.ownedBy(p.getUUID())||door.kind()!=DeviceKind.DOOR||door.airlockLink()!=null)return false;var ref=door.filterGuard();if(ref==null)return false;
        if(p.level().isLoaded(ref.position())&&p.level().getBlockEntity(ref.position()) instanceof DeviceBlockEntity scanner&&scanner.identity().equals(ref.identity())&&scanner(scanner)&&Objects.equals(scanner.filter().door(),door.asDoorTarget())){scanner.filter().door(null);if(scanner.filter().mode()==FilterState.Mode.ACCESS)scanner.filter().armed(false);sync(scanner);}
        door.filterGuard(null);return true;
    }
    public static Inspection.Result inspect(DeviceBlockEntity scanner,Player p,boolean test){
        if(!test&&scanner.ownedBy(p.getUUID()))return Inspection.EXEMPT;
        return FilterInventory.inspect(p,scanner.filter().rules());
    }
    public static boolean inPassage(DeviceBlockEntity scanner,Player p){return FilterBlock.passage(scanner).intersects(p.getBoundingBox());}
    public static void tick(ServerLevel level,DeviceBlockEntity scanner){
        if(!scanner(scanner))return;var s=scanner.filter();long now=level.getGameTime();
        int phase=scanner.getBlockPos().hashCode();
        // Keep the same maximum latency while distributing neighboring scanners across ticks.
        if(Math.floorMod(now,20)==Math.floorMod(phase,20))FilterBlock.refreshFrame(level,scanner.getBlockPos(),scanner.getBlockState().getValue(DeviceBlock.FACING));
        if(Math.floorMod(now,5)==Math.floorMod(phase,5)){
            boolean complete=FilterBlock.complete(scanner);if(s.complete!=complete){s.complete=complete;sync(scanner);}
            boolean online=NetworkService.inspect(level,scanner).online();
            for(var part:FilterBlock.Part.values())if(part!=FilterBlock.Part.BASE){var at=FilterBlock.partPos(scanner.getBlockPos(),scanner.getBlockState().getValue(DeviceBlock.FACING),part);if(level.isLoaded(at)){var state=level.getBlockState(at);if(state.getBlock() instanceof FilterBlock&&state.getValue(DeviceBlock.ONLINE)!=online)level.setBlock(at,state.setValue(DeviceBlock.ONLINE,online),Block.UPDATE_CLIENTS);}}
            boolean active=s.armed()&&complete&&online;
            if(!active){s.approvals.clear();s.visits.clear();s.alarmUntil=0;}
            else{
                for(var p:level.getEntitiesOfClass(ServerPlayer.class,FilterBlock.passage(scanner),p->p.isAlive()&&!p.isSpectator())){
                    var result=inspect(scanner,p,false);var previous=s.visits.get(p.getUUID());
                    if(previous==null||!previous.result().equals(result)){report(scanner,p,result,false);if(!result.accepted()&&s.mode()!=FilterState.Mode.INFORM)s.alarmUntil=now+40;}
                    s.visits.put(p.getUUID(),new FilterState.Visit(now,result));if(result.accepted())s.approvals.put(p.getUUID(),now+APPROVAL_TICKS);else s.approvals.remove(p.getUUID());
                }
                s.visits.entrySet().removeIf(e->now-e.getValue().seen()>20);s.approvals.entrySet().removeIf(e->e.getValue()<now);if(s.visits.size()>64)s.visits.clear();if(s.approvals.size()>128)s.approvals.clear();
            }
        }
        boolean signal=s.armed()&&s.mode()!=FilterState.Mode.INFORM&&s.alarmUntil>now;
        if(signal!=s.signal){s.signal=signal;level.updateNeighborsAt(scanner.getBlockPos(),scanner.getBlockState().getBlock());}
    }
    public static void report(DeviceBlockEntity scanner,ServerPlayer p,Inspection.Result result,boolean test){
        var s=scanner.filter();long now=p.level().getGameTime();s.record(new FilterState.Record(p.getUUID(),p.getGameProfile().name(),result.outcome(),result.matches(),now,test));s.flashAt=now;s.flash=result.outcome();
        if(!test&&!result.accepted())dev.umbral.installation.NexoJournal.get((ServerLevel)p.level()).inspection((ServerLevel)p.level(),scanner,p,result);
        if(!test&&p.connection!=null)p.sendOverlayMessage(explanation(scanner,result));sync(scanner);
    }
    public static Component explanation(DeviceBlockEntity scanner,Inspection.Result result){
        if(result.outcome()==Inspection.Outcome.MATCH){var match=result.matches().getFirst();var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(match.item())).getDefaultInstance().getHoverName();return Component.translatable("filter.umbral.detected",item,match.count());}
        return Component.translatable("filter.umbral.scan."+result.outcome().name().toLowerCase(Locale.ROOT));
    }
    public static Gate checkDoor(ServerPlayer p,DeviceBlockEntity door){
        var ref=door.filterGuard();if(ref==null||door.ownedBy(p.getUUID()))return Gate.ALLOW;
        var level=(ServerLevel)p.level();if(!ref.dimension().equals(level.dimension().identifier())||!level.isLoaded(ref.position())||!(level.getBlockEntity(ref.position()) instanceof DeviceBlockEntity scanner)||!scanner(scanner)||!scanner.identity().equals(ref.identity())||!Objects.equals(scanner.owner(),door.owner())||!Objects.equals(scanner.link(),door.link())||!Objects.equals(scanner.filter().door(),door.asDoorTarget()))return deny(p,Gate.UNAVAILABLE);
        var s=scanner.filter();if(!s.armed()||s.mode()!=FilterState.Mode.ACCESS)return deny(p,Gate.UNAVAILABLE);
        if(!FilterBlock.complete(scanner)||!NetworkService.inspect(level,scanner).online())return deny(p,Gate.UNAVAILABLE);
        long now=level.getGameTime();boolean nearby=inPassage(scanner,p);
        if(!nearby&&s.approvals.getOrDefault(p.getUUID(),-1L)<now)return deny(p,Gate.REQUIRED);
        // Reinspect at the exact opening request: a previous clean result cannot conceal an item swap.
        var current=inspect(scanner,p,false);if(!current.accepted()){var previous=s.visits.get(p.getUUID());if(previous==null||!previous.result().equals(current))report(scanner,p,current,false);else if(p.connection!=null)p.sendOverlayMessage(explanation(scanner,current));s.visits.put(p.getUUID(),new FilterState.Visit(now,current));s.approvals.remove(p.getUUID());s.alarmUntil=now+40;return Gate.BLOCKED;}
        if(nearby){s.approvals.put(p.getUUID(),now+APPROVAL_TICKS);var previous=s.visits.get(p.getUUID());if(previous==null||!previous.result().equals(current))report(scanner,p,current,false);s.visits.put(p.getUUID(),new FilterState.Visit(now,current));}
        return Gate.ALLOW;
    }
    private static Gate deny(ServerPlayer p,Gate result){if(p.connection!=null)p.sendOverlayMessage(Component.translatable("filter.umbral.gate."+result.name().toLowerCase(Locale.ROOT)));return result;}
    public static void sync(DeviceBlockEntity scanner){scanner.setChanged();if(scanner.getLevel() instanceof ServerLevel level)level.sendBlockUpdated(scanner.getBlockPos(),scanner.getBlockState(),scanner.getBlockState(),Block.UPDATE_CLIENTS);}
    private FilterService(){}
}
