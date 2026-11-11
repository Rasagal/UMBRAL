// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import dev.umbral.UmbralContent;
import dev.umbral.access.DoorTarget;
import dev.umbral.block.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import dev.umbral.turret.TurretState;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

public final class MotaService {
    public static boolean container(BlockEntity be){return be instanceof ChestBlockEntity||be instanceof BarrelBlockEntity;}
    public static DoorTarget seal(ServerPlayer player,BlockPos position,NetworkLink link){
        var level=(ServerLevel)player.level();
        if(link==null||position.distToCenterSqr(player.position())>64||!level.isLoaded(position)||!level.isLoaded(link.core())||!link.dimension().equals(level.dimension().identifier())
                ||!(level.getBlockEntity(link.core()) instanceof DeviceBlockEntity core)||!core.ownedBy(player.getUUID())||!link.equals(core.link()))return null;
        var be=level.getBlockEntity(position);if(!container(be)||!dev.umbral.protection.ProtectionPolicy.permits(level,position,player.getUUID(),dev.umbral.protection.ProtectionData.INVENTORY))return null;
        var old=be.components().get(UmbralContent.STOCK_SEAL);if(old!=null&&!old.owner().equals(player.getUUID()))return null;
        var seal=old!=null&&old.link().equals(link)?old:new StockSeal(UUID.randomUUID(),player.getUUID(),link);
        be.setComponents(DataComponentMap.builder().addAll(be.components()).set(UmbralContent.STOCK_SEAL,seal).build());be.setChanged();invalidate(level);
        return new DoorTarget(level.dimension().identifier(),position,seal.identity());
    }
    public static boolean bindStock(ServerPlayer player,DeviceBlockEntity dock,DoorTarget target){
        if(dock.kind()!=DeviceKind.DOCK||!dock.ownedBy(player.getUUID())||dock.getBlockPos().distToCenterSqr(player.position())>64||dock.link()==null||target==null)return false;
        var actor=actor((ServerLevel)player.level(),dock);if(actor!=null&&actor.busy()||active((ServerLevel)player.level(),dock)&&actor==null)return false;
        if(stock((ServerLevel)player.level(),dock,target)==null||dock.getBlockPos().distSqr(target.position())>24*24)return false;
        dock.dock().stock(target);dock.dock().result("idle",0);dock.setChanged();invalidate((ServerLevel)player.level());return true;
    }
    public static Container stock(ServerLevel level,DeviceBlockEntity dock,DoorTarget reference){
        if(reference==null||dock.link()==null||!reference.dimension().equals(level.dimension().identifier())||!level.isLoaded(reference.position())||dock.getBlockPos().distSqr(reference.position())>24*24)return null;
        var be=level.getBlockEntity(reference.position());if(!container(be)||!dev.umbral.protection.ProtectionPolicy.permits(level,reference.position(),dock.owner(),dev.umbral.protection.ProtectionData.INVENTORY))return null;var seal=be.components().get(UmbralContent.STOCK_SEAL);
        return seal!=null&&seal.identity().equals(reference.identity())&&seal.owner().equals(dock.owner())&&seal.link().equals(dock.link())?(Container)be:null;
    }
    public record Plan(int count,String stock,String state){}
    private static final Plan OFFLINE=new Plan(0,"","offline"),MISMATCH=new Plan(0,"","ammo_mismatch"),FULL=new Plan(0,"","full");
    private record Count(boolean metal,TurretState.Preference preference,ItemStack held,ItemStack pattern,int amount){
        boolean matches(TurretState turret,ItemStack item){return metal==turret.metal()&&preference==turret.preference()&&(held.isEmpty()?item.isEmpty():!item.isEmpty()&&ItemStack.isSameItemSameComponents(held,item));}
    }
    private static final class StockView {
        final Container source;
        final List<Count> counts=new ArrayList<>();
        StockView(Container source){this.source=source;}
        Count available(TurretState turret){
            var held=turret.ammunition();for(var count:counts)if(count.matches(turret,held))return count;
            var pattern=held;int total=0;
            for(int n=0;n<source.getContainerSize()&&total<32;n++){
                var stack=source.getItem(n);if(!turret.acceptsResupply(stack)||!pattern.isEmpty()&&!ItemStack.isSameItemSameComponents(pattern,stack))continue;
                if(pattern.isEmpty())pattern=stack.copyWithCount(1);total=Math.min(32,total+stack.getCount());
            }
            var count=new Count(turret.metal(),turret.preference(),held,pattern,total);counts.add(count);return count;
        }
    }
    private record Station(String failure,StockView stock,String label){}
    private record TargetPlan(DeviceBlockEntity target,long revision,Plan plan){}
    private static final class PlanningContext {
        final long revision;
        final List<Station> stations=new ArrayList<>();
        final Map<UUID,TargetPlan> targets=new HashMap<>();
        PlanningContext(ServerLevel level,UUID owner,NetworkLink link,long revision){
            this.revision=revision;var stocks=new IdentityHashMap<Container,StockView>();
            for(var entry:InstallationDirectory.get(level).entries(owner,link,DeviceKind.DOCK)){
                var dock=InstallationDirectory.loaded(level,entry);if(dock==null){stations.add(new Station("paused",null,""));continue;}
                var drone=actor(level,dock);if(drone==null){if(active(level,dock))stations.add(new Station("paused",null,""));continue;}
                if(drone.busy()){stations.add(new Station("busy",null,""));continue;}
                if(!NetworkService.inspect(level,dock).online()){stations.add(new Station("offline",null,""));continue;}
                var source=stock(level,dock,dock.dock().stock());if(source==null){stations.add(new Station("stock_missing",null,""));continue;}
                String label=source instanceof BaseContainerBlockEntity be?be.getName().getString():"";label=label.substring(0,Math.min(label.length(),24))+" · "+NetworkService.coordinates(dock.dock().stock().position());
                stations.add(new Station(null,stocks.computeIfAbsent(source,StockView::new),label.substring(0,Math.min(label.length(),48))));
            }
        }
        Plan plan(DeviceBlockEntity target){
            var turret=target.turret();long revision=turret.motion().revision();var cached=targets.get(target.identity());if(cached!=null&&cached.target==target&&cached.revision==revision)return cached.plan;
            String failure="no_unit";Plan result=null;
            for(var station:stations){
                if(station.failure!=null){failure=station.failure;continue;}
                var count=station.stock.available(turret);int total=Math.min(count.amount,Math.min(64-turret.count(),count.pattern.isEmpty()?0:count.pattern.getMaxStackSize()-turret.count()));
                if(total<=0){failure="no_stock";continue;}result=new Plan(total,station.label,"ready");break;
            }
            if(result==null)result=new Plan(0,"",failure);targets.put(target.identity(),new TargetPlan(target,revision,result));return result;
        }
    }
    private static final class AdvisoryTick {
        final long tick;
        final Map<NetworkLink,Map<UUID,PlanningContext>> networks=new HashMap<>();
        AdvisoryTick(long tick){this.tick=tick;}
    }
    private static final Map<ServerLevel,AdvisoryTick> ADVISORY=new WeakHashMap<>();
    /** UI advice is shared only within one tick. Commands and physical transfers never trust it. */
    public static Plan plan(ServerLevel level,DeviceBlockEntity target){
        return plan(level,target,NetworkService.inspect(level,target));
    }
    /** A snapshot can reuse the connection reading it just obtained for this same device. */
    public static Plan plan(ServerLevel level,DeviceBlockEntity target,NetworkService.Inspection connection){
        if(target.link()==null||!connection.online())return OFFLINE;
        if(target.turret().resupplyMismatch())return MISMATCH;if(target.turret().count()>=64)return FULL;
        long now=level.getGameTime();var tick=ADVISORY.get(level);if(tick==null||tick.tick!=now){tick=new AdvisoryTick(now);ADVISORY.put(level,tick);}
        var owners=tick.networks.computeIfAbsent(target.link(),k->new HashMap<>());var context=owners.get(target.owner());long revision=InstallationDirectory.get(level).revision(target.owner(),target.link());
        if(context==null||context.revision!=revision){context=new PlanningContext(level,target.owner(),target.link(),revision);owners.put(target.owner(),context);}
        return context.plan(target);
    }
    public static void invalidate(ServerLevel level){ADVISORY.remove(level);}
    public static void clearAdvisoryPlans(){ADVISORY.clear();}
    public static ItemStack withdraw(Container source,TurretState target,int limit){
        int remaining=Math.min(Math.min(limit,32),64-target.count());if(remaining<=0)return ItemStack.EMPTY;
        ItemStack cargo=ItemStack.EMPTY;var existing=target.ammunition();
        for(int n=0;n<source.getContainerSize()&&remaining>0;n++){
            var at=source.getItem(n);if(!target.acceptsResupply(at)||!existing.isEmpty()&&!ItemStack.isSameItemSameComponents(existing,at)||!cargo.isEmpty()&&!ItemStack.isSameItemSameComponents(cargo,at))continue;
            int count=Math.min(remaining,Math.min(at.getCount(),at.getMaxStackSize()-cargo.getCount()));if(count<=0)continue;
            var removed=source.removeItem(n,count);if(removed.isEmpty())continue;
            if(cargo.isEmpty())cargo=removed;else cargo.grow(removed.getCount());remaining-=removed.getCount();
        }if(!cargo.isEmpty()){source.setChanged();clearAdvisoryPlans();}return cargo;
    }
    public static boolean active(ServerLevel level,DeviceBlockEntity dock){var u=MotaRegistry.get(level).unit(dock.identity());return u!=null&&u.active();}
    public static MotaEntity actor(ServerLevel level,DeviceBlockEntity dock){var u=MotaRegistry.get(level).unit(dock.identity());return u!=null&&u.active()&&level.getEntity(u.actor()) instanceof MotaEntity e&&!e.isRemoved()?e:null;}
    public static boolean install(ServerPlayer player,DeviceBlockEntity dock,ItemStack stack){
        var level=(ServerLevel)player.level();if(!stack.is(UmbralContent.MOTA)||dock.kind()!=DeviceKind.DOCK||!dock.ownedBy(player.getUUID())||dock.link()==null||dock.getBlockPos().distToCenterSqr(player.position())>64||!player.isAlive()||player.isSpectator()||!NetworkService.inspect(level,dock).online()||active(level,dock))return false;
        if(!MotaRoute.clear(level,MotaRoute.approach(dock.getBlockPos())))return false;
        var drone=new MotaEntity(UmbralContent.MOTA_ENTITY,level);drone.initialize(dock);
        var unit=new MotaRegistry.Unit(dock.identity(),dock.getBlockPos(),dock.owner(),dock.link(),drone.getUUID(),true,false);
        if(!MotaRegistry.get(level).install(unit))return false;
        if(!level.addFreshEntity(drone)){MotaRegistry.get(level).forget(dock.identity(),drone.getUUID());return false;}
        stack.shrink(1);dock.dock().result("idle",0);dock.setChanged();invalidate(level);return true;
    }
    public static String order(ServerLevel level,DeviceBlockEntity dock,DeviceBlockEntity target){
        invalidate(level);
        if(dock.getLevel()!=level||target.getLevel()!=level||!level.isLoaded(dock.getBlockPos())||!level.isLoaded(target.getBlockPos())||level.getBlockEntity(dock.getBlockPos())!=dock||level.getBlockEntity(target.getBlockPos())!=target)return "offline";
        var drone=actor(level,dock);if(drone==null)return active(level,dock)?"paused":"no_unit";
        if(drone.busy())return "busy";
        if(!target.kind().turret()||!Objects.equals(target.link(),dock.link())||!Objects.equals(target.owner(),dock.owner())||!NetworkService.inspectFresh(level,dock).online()||!NetworkService.inspect(level,target).online())return "offline";
        if(target.turret().count()>=64)return "full";
        if(target.turret().resupplyMismatch())return "ammo_mismatch";
        var source=stock(level,dock,dock.dock().stock());if(source==null)return "stock_missing";
        boolean any=false;var held=target.turret().ammunition();for(int n=0;n<source.getContainerSize();n++){var at=source.getItem(n);if(target.turret().acceptsResupply(at)&&(held.isEmpty()||ItemStack.isSameItemSameComponents(held,at))){any=true;break;}}
        if(!any)return "no_stock";
        var pickup=MotaRoute.approach(dock.dock().stock().position());var deposit=MotaRoute.approach(target.getBlockPos());
        var pickupRoute=MotaRoute.find(level,drone.position(),pickup);if(pickupRoute.isEmpty())return "blocked";
        var deliveryRoute=MotaRoute.find(level,pickup,deposit);if(deliveryRoute.isEmpty())return "blocked";
        var homeRoute=MotaRoute.find(level,deposit,MotaRoute.approach(dock.getBlockPos()));if(homeRoute.isEmpty())return "blocked";
        if(!drone.assign(target,dock.dock().stock(),pickupRoute,deliveryRoute,homeRoute))return "busy";dock.dock().result("queued",0);dock.setChanged();invalidate(level);return "queued";
    }
    public static String replenish(ServerLevel level,DeviceBlockEntity target){
        if(target.link()==null)return "offline";
        // One outstanding delivery per target, even with multiple docks.
        var stations=InstallationDirectory.get(level).entries(target.owner(),target.link(),DeviceKind.DOCK).stream().map(e->InstallationDirectory.loaded(level,e)).filter(Objects::nonNull).toList();
        for(var dock:stations){var actor=actor(level,dock);if(actor!=null&&actor.busy()&&actor.target()!=null&&actor.target().identity().equals(target.identity()))return "busy";if(active(level,dock)&&actor==null)return "paused";}
        if(target.turret().resupplyMismatch())return "ammo_mismatch";
        String failure="no_unit";
        for(var dock:stations){var result=order(level,dock,target);if(result.equals("queued"))return result;failure=result;}return failure;
    }
    public static boolean recover(ServerPlayer player,DeviceBlockEntity dock){
        var level=(ServerLevel)player.level();var drone=actor(level,dock);
        if(drone==null||drone.busy()||!drone.cargo().isEmpty()||drone.position().distanceToSqr(MotaRoute.approach(dock.getBlockPos()))>1)return false;
        MotaRegistry.get(level).forget(dock.identity(),drone.getUUID());drone.discard();InventoryTransfer.give(player,new ItemStack(UmbralContent.MOTA));dock.setChanged();invalidate(level);return true;
    }
    public static void removed(ServerLevel level,DeviceBlockEntity dock){
        invalidate(level);
        var registry=MotaRegistry.get(level);var u=registry.unit(dock.identity());if(u==null)return;
        if(registry.deactivate(dock.identity(),u.actor()))
            net.minecraft.world.Containers.dropItemStack(level,dock.getBlockPos().getX(),dock.getBlockPos().getY(),dock.getBlockPos().getZ(),new ItemStack(UmbralContent.MOTA));
        if(level.getEntity(u.actor()) instanceof MotaEntity drone)drone.discard();
        // An absent installation is also an orphan marker. Unloaded actors drop only cargo on return.
        // Retaining tombstones for destroyed stations would eventually exhaust the global unit limit.
        registry.forget(dock.identity(),u.actor());
    }
    public static void syncMetal(ServerLevel level,DeviceBlockEntity target){if(target.kind()==DeviceKind.CENTELLA)level.setBlock(target.getBlockPos(),target.getBlockState().setValue(CentellaBlock.METAL,target.turret().material()),2);}
    private MotaService(){}
}
