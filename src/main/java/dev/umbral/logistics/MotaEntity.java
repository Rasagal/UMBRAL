// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import dev.umbral.UmbralContent;
import dev.umbral.access.DoorTarget;
import dev.umbral.block.*;
import dev.umbral.installation.InventoryTransfer;
import dev.umbral.network.*;
import net.minecraft.core.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Physical single-cargo courier. No inventory mutation happens until arrival at a validated endpoint. */
public final class MotaEntity extends Entity {
    public enum Phase { IDLE, TO_STOCK, TO_TARGET, RETURN_STOCK, RETURN_HOME }
    private static final EntityDataAccessor<Boolean> LOADED=SynchedEntityData.defineId(MotaEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> FLYING=SynchedEntityData.defineId(MotaEntity.class,EntityDataSerializers.BOOLEAN);
    private UUID station,owner,job;
    private BlockPos home=BlockPos.ZERO;
    private NetworkLink link;
    private DoorTarget target,stock;
    private ItemStack cargo=ItemStack.EMPTY;
    private Phase phase=Phase.IDLE;
    private List<Vec3> route=List.of();
    private List<Vec3> pickupRoute=List.of(),deliveryRoute=List.of(),homeRoute=List.of();
    private int waypoint,waitTicks,delivered,routeSearches,reusedRoutes;
    private float integrity=12;
    private String outcome="idle",condition="idle";
    public MotaEntity(EntityType<? extends MotaEntity> type,Level level){super(type,level);setNoGravity(true);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){b.define(LOADED,false);b.define(FLYING,false);}
    public boolean carrying(){return entityData.get(LOADED);}
    public boolean flying(){return entityData.get(FLYING);}
    public boolean busy(){return phase!=Phase.IDLE;}
    public Phase phase(){return phase;}
    public UUID station(){return station;}
    public DoorTarget target(){return target;}
    public ItemStack cargo(){return cargo.copy();}
    public String condition(){return condition;}
    public int delivered(){return delivered;}
    public int routeSearches(){return routeSearches;}
    public int reusedRoutes(){return reusedRoutes;}
    public void initialize(DeviceBlockEntity dock){station=dock.identity();owner=dock.owner();home=dock.getBlockPos().immutable();link=dock.link();setPos(MotaRoute.approach(home));}
    public boolean assign(DeviceBlockEntity destination,DoorTarget from){
        return assign(destination,from,List.of(),List.of(),List.of());
    }
    /** Preflight paths are hints only: physical movement still checks every step against the live world. */
    public boolean assign(DeviceBlockEntity destination,DoorTarget from,List<Vec3> pickup,List<Vec3> delivery,List<Vec3> returning){
        if(busy()||!cargo.isEmpty()||destination.link()==null||!destination.link().equals(link)||!destination.ownedBy(owner))return false;
        job=UUID.randomUUID();target=destination.asDoorTarget();stock=from;outcome="queued";condition="queued";delivered=0;routeSearches=reusedRoutes=0;
        pickupRoute=prepared(pickup,position(),from==null?MotaRoute.approach(home):MotaRoute.approach(from.position()));
        deliveryRoute=prepared(delivery,from==null?MotaRoute.approach(home):MotaRoute.approach(from.position()),MotaRoute.approach(destination.getBlockPos()));
        homeRoute=prepared(returning,MotaRoute.approach(destination.getBlockPos()),MotaRoute.approach(home));
        change(Phase.TO_STOCK);return true;
    }
    public void cancel(){if(busy()){outcome="cancelled";forgetRoutes();change(cargo.isEmpty()?Phase.RETURN_HOME:Phase.RETURN_STOCK);}}
    private static List<Vec3> prepared(List<Vec3> points,Vec3 start,Vec3 finish){
        if(points==null||points.isEmpty()||points.size()>MotaRoute.MAX_NODES+4)return List.of();
        for(var point:points)if(point==null||!Double.isFinite(point.x)||!Double.isFinite(point.y)||!Double.isFinite(point.z))return List.of();
        if(points.getFirst().distanceToSqr(start)>=.012||points.getLast().distanceToSqr(finish)>=.012)return List.of();
        return List.copyOf(points);
    }
    private void forgetRoutes(){route=pickupRoute=deliveryRoute=homeRoute=List.of();waypoint=waitTicks=0;}
    private DeviceBlockEntity dock(ServerLevel level){if(!level.isLoaded(home))return null;return level.getBlockEntity(home) instanceof DeviceBlockEntity d&&d.kind()==DeviceKind.DOCK&&d.identity().equals(station)&&d.ownedBy(owner)&&Objects.equals(d.link(),link)?d:null;}
    private DeviceBlockEntity destination(ServerLevel level){
        if(target==null||!target.dimension().equals(level.dimension().identifier())||!level.isLoaded(target.position()))return null;
        return level.getBlockEntity(target.position()) instanceof DeviceBlockEntity d&&d.kind().turret()&&d.identity().equals(target.identity())&&d.ownedBy(owner)&&Objects.equals(d.link(),link)?d:null;
    }
    private void change(Phase next){
        phase=next;route=List.of();waypoint=0;waitTicks=0;
        if(next==Phase.TO_STOCK){route=pickupRoute;pickupRoute=List.of();}
        else if(next==Phase.TO_TARGET){route=deliveryRoute;deliveryRoute=List.of();}
        else if(next==Phase.RETURN_HOME){route=homeRoute;homeRoute=List.of();}
        if(!route.isEmpty()&&route.getFirst().distanceToSqr(position())>=.012)route=List.of();
        if(!route.isEmpty())reusedRoutes++;
        if(next==Phase.IDLE)forgetRoutes();
        if(level() instanceof ServerLevel level)MotaService.invalidate(level);
    }
    private void finish(DeviceBlockEntity dock){
        // A broken/full return chest cannot silently destroy a carried stack.
        dropCargo((ServerLevel)level());dock.dock().result(outcome,delivered);dock.setChanged();target=null;stock=null;job=null;change(Phase.IDLE);condition="idle";
    }
    private void dropCargo(ServerLevel level){var drop=cargo;cargo=ItemStack.EMPTY;entityData.set(LOADED,false);if(!drop.isEmpty())spawnAtLocation(level,drop);}
    @Override public void tick(){
        super.tick();if(!(level() instanceof ServerLevel level))return;
        entityData.set(FLYING,false);setDeltaMovement(Vec3.ZERO);
        var u=station==null?null:MotaRegistry.get(level).unit(station);
        if(u==null||!u.active()||!u.actor().equals(getUUID())){dropCargo(level);if(u!=null&&!u.active())MotaRegistry.get(level).forget(station,getUUID());discard();return;}
        if(u.cancel()){cancel();MotaRegistry.get(level).cancel(station,false);}
        if(!level.isLoaded(home)){condition="paused";forgetRoutes();return;}
        var dock=dock(level);if(dock==null){MotaRegistry.get(level).deactivate(station,getUUID());dropCargo(level);MotaRegistry.get(level).forget(station,getUUID());discard();return;}
        if(!busy()){condition="idle";return;}
        if(!NetworkService.inspect(level,dock).online()){condition="paused";forgetRoutes();return;}
        if(target!=null&&(phase==Phase.TO_STOCK||phase==Phase.TO_TARGET)){
            if(!level.isLoaded(target.position())){condition="paused";forgetRoutes();return;}
            var d=destination(level);
            if(d==null){outcome="target_changed";forgetRoutes();change(cargo.isEmpty()?Phase.RETURN_HOME:Phase.RETURN_STOCK);}
            else if(!NetworkService.inspect(level,d).online()){condition="paused";forgetRoutes();return;}
        }
        if((phase==Phase.TO_STOCK||phase==Phase.RETURN_STOCK)&&stock!=null&&!level.isLoaded(stock.position())){condition="paused";forgetRoutes();return;}
        Vec3 goal=switch(phase){
            case TO_STOCK, RETURN_STOCK -> stock==null?MotaRoute.approach(home):MotaRoute.approach(stock.position());
            case TO_TARGET -> target==null?MotaRoute.approach(home):MotaRoute.approach(target.position());
            default -> MotaRoute.approach(home);
        };
        if(position().distanceToSqr(goal)<.012){
            switch(phase){
                case TO_STOCK -> {
                    var source=MotaService.stock(level,dock,stock);var d=destination(level);
                    if(source==null){outcome="stock_missing";change(Phase.RETURN_HOME);}
                    else if(d==null){outcome="target_changed";change(Phase.RETURN_HOME);}
                    else if(!NetworkService.inspectFresh(level,dock).online()||!NetworkService.inspect(level,d).online()){
                        condition="paused";forgetRoutes();return;
                    }
                    else{
                        cargo=MotaService.withdraw(source,d.turret(),32);entityData.set(LOADED,!cargo.isEmpty());
                        if(cargo.isEmpty()){outcome=d.turret().resupplyMismatch()?"ammo_mismatch":"no_stock";change(Phase.RETURN_HOME);}else change(Phase.TO_TARGET);
                    }
                }
                case TO_TARGET -> {
                    var d=destination(level);
                    if(d!=null){
                        // Both endpoints share this freshly scanned backbone; keep other networks cached.
                        if(!NetworkService.inspectFresh(level,dock).online()||!NetworkService.inspect(level,d).online()){
                            condition="paused";forgetRoutes();return;
                        }
                        boolean matches=!d.turret().resupplyMismatch()&&(!d.turret().metal()||d.turret().preference().accepts(cargo));
                        delivered=d.turret().loadResupply(cargo,level.getGameTime());d.setChanged();d.syncTurret();MotaService.syncMetal(level,d);
                        outcome=delivered>0?"complete":matches?"full":"ammo_mismatch";
                    }else outcome="target_changed";
                    entityData.set(LOADED,!cargo.isEmpty());change(cargo.isEmpty()?Phase.RETURN_HOME:Phase.RETURN_STOCK);
                }
                case RETURN_STOCK -> {var source=MotaService.stock(level,dock,stock);if(source!=null)InventoryTransfer.insert(source,cargo);entityData.set(LOADED,!cargo.isEmpty());change(Phase.RETURN_HOME);}
                case RETURN_HOME -> finish(dock);
                default -> {}
            }return;
        }
        if(route.isEmpty()){
            if(waitTicks++%40!=0)return;
            routeSearches++;route=MotaRoute.find(level,position(),goal);waypoint=0;
            if(route.isEmpty()){condition="blocked";dock.dock().result("blocked",delivered);dock.setChanged();return;}
        }
        // Reach checked corners before turning; the wider endpoint arrival tolerance
        // would otherwise cut an axis entrance diagonally and repeat the same replan.
        while(waypoint<route.size()&&position().distanceToSqr(route.get(waypoint))<.000001)waypoint++;
        if(waypoint>=route.size()){route=List.of();return;}
        Vec3 delta=route.get(waypoint).subtract(position()),step=delta.normalize().scale(Math.min(.12,delta.length()));
        if(!MotaRoute.clear(level,position().add(step))){forgetRoutes();condition="blocked";return;}
        Vec3 before=position();move(MoverType.SELF,step);if(position().distanceToSqr(before)<.000001){forgetRoutes();condition="blocked";return;}
        setDeltaMovement(step);entityData.set(FLYING,true);condition=phase==Phase.TO_STOCK?"collecting":phase==Phase.TO_TARGET?"delivering":"returning";
        if(step.horizontalDistanceSqr()>.00001)setYRot((float)Math.toDegrees(Math.atan2(-step.x,step.z)));
    }
    @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){
        if(source.getEntity() instanceof net.minecraft.world.entity.player.Player p&&!p.getUUID().equals(owner))return false;
        if(isInvulnerableToBase(source)||amount<=0)return false;integrity-=amount;if(integrity<=0)remove(RemovalReason.KILLED);return true;
    }
    @Override public void remove(RemovalReason reason){
        if(level() instanceof ServerLevel level&&reason.shouldDestroy()){
            dropCargo(level);if(station!=null){MotaRegistry.get(level).deactivate(station,getUUID());}
        }super.remove(reason);
    }
    @Override public boolean isPickable(){return true;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean isPushedByFluid(){return false;}
    @Override protected void addAdditionalSaveData(ValueOutput out){
        out.storeNullable("station",UUIDUtil.CODEC,station);out.storeNullable("owner",UUIDUtil.CODEC,owner);out.store("home",BlockPos.CODEC,home);out.storeNullable("link",NetworkLink.CODEC,link);
        out.storeNullable("job",UUIDUtil.CODEC,job);out.storeNullable("target",DoorTarget.CODEC,target);out.storeNullable("stock",DoorTarget.CODEC,stock);
        out.store("cargo",ItemStack.OPTIONAL_CODEC,cargo);out.putString("phase",phase.name());out.putString("outcome",outcome);out.putInt("delivered",delivered);out.putFloat("integrity",integrity);
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        station=in.read("station",UUIDUtil.CODEC).orElse(null);owner=in.read("owner",UUIDUtil.CODEC).orElse(null);home=in.read("home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        link=in.read("link",NetworkLink.CODEC).orElse(null);job=in.read("job",UUIDUtil.CODEC).orElse(null);target=in.read("target",DoorTarget.CODEC).orElse(null);stock=in.read("stock",DoorTarget.CODEC).orElse(null);
        cargo=in.read("cargo",ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);if(cargo.getCount()>32)cargo.setCount(32);
        try{phase=Phase.valueOf(in.getStringOr("phase","RETURN_HOME"));}catch(IllegalArgumentException e){phase=Phase.RETURN_HOME;}
        outcome=in.getStringOr("outcome","cancelled");if(!Set.of("queued","complete","cancelled","stock_missing","no_stock","full","ammo_mismatch","target_changed","idle").contains(outcome))outcome="cancelled";
        delivered=Math.clamp(in.getIntOr("delivered",0),0,32);integrity=Math.clamp(in.getFloatOr("integrity",12),1,12);
        if(!cargo.isEmpty()&&phase==Phase.IDLE)phase=Phase.RETURN_HOME;forgetRoutes();routeSearches=reusedRoutes=0;entityData.set(LOADED,!cargo.isEmpty());setNoGravity(true);
    }
}
