// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

public final class TurretService {
    public enum Status { OFFLINE, DISARMED, EMPTY, SEARCHING, TRACKING, BLOCKED, RELOADING;
        public String key(){return "turret.umbral.status."+name().toLowerCase(java.util.Locale.ROOT);}
    }
    public static Status status(ServerLevel level,DeviceBlockEntity turret) {
        var s=turret.turret();
        if(!NetworkService.inspect(level,turret).online())return Status.OFFLINE;
        if(!s.armed())return Status.DISARMED;if(s.count()==0)return Status.EMPTY;
        if(s.cooldown()>0)return Status.RELOADING;if(s.obstructed)return Status.BLOCKED;
        return s.target==null?Status.SEARCHING:Status.TRACKING;
    }
    public static String targetName(DeviceBlockEntity turret) { return turret.turret().target==null?"":turret.turret().target.getType().getDescriptionId(); }
    public static boolean visible(ServerLevel level,Vec3 origin,TurretAim.Shot shot,LivingEntity target) {
        Vec3 p=origin,v=shot.velocity();
        for(int tick=0;tick<shot.ticks();tick++) {
            Vec3 end=p.add(v.scale(Math.min(1,shot.ticks()-tick)));
            for(int n=0;n<=4;n++) {
                BlockPos cell=BlockPos.containing(p.lerp(end,n/4d));
                if(!level.isLoaded(cell)||!level.getFluidState(cell).isEmpty())return false;
            }
            var hit=level.clip(new ClipContext(p,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,target));
            if(hit.getType()!=HitResult.Type.MISS)return false;
            p=end;v=TurretAim.stepVelocity(v);
        }
        return true;
    }
    public static boolean candidate(ServerLevel level,DeviceBlockEntity turret,LivingEntity mob) {
        return candidate(level,mob,level.getPlayerByUUID(turret.owner()),Vec3.atCenterOf(turret.getBlockPos()),turret.getBlockState().getValue(DeviceBlock.FACING),turret.turret().range()*turret.turret().range());
    }
    private static boolean candidate(ServerLevel level,LivingEntity mob,Player owner,Vec3 center,Direction facing,double rangeSquared){
        if(mob.level()!=level||!TurretBolt.eligible(mob,owner))return false;
        double distance=center.distanceToSqr(mob.position());
        if(distance>rangeSquared||distance<2.25)return false;
        float yaw=TurretAim.wrap((float)Math.toDegrees(Math.atan2(center.x-mob.getX(),mob.getZ()-center.z))-facing.toYRot());
        return Math.abs(yaw)<=60;
    }
    private static TurretAim.Shot solution(Vec3 origin,Direction facing,LivingEntity target) {
        double time=Math.sqrt(origin.distanceToSqr(target.position()))/1.8;
        Vec3 lead=target.getDeltaMovement().scale(Math.min(time,8));if(lead.length()>1.5)lead=lead.normalize().scale(1.5);
        return TurretAim.solve(origin,target.position().add(0,target.getBbHeight()*.55,0).add(lead),facing);
    }
    private record Target(LivingEntity entity,double distance,int order){}
    private record Acquisition(LivingEntity entity,TurretAim.Shot shot){}
    private static final Comparator<Target> NEAREST=Comparator.comparingDouble(Target::distance).thenComparingInt(Target::order);
    private static Acquisition acquire(ServerLevel level,DeviceBlockEntity turret,Player owner,Vec3 center,Vec3 origin,Direction facing){
        // Retain the same nearest 64 candidates, with stable ties, without sorting a dense mob crowd.
        double rangeSquared=turret.turret().range()*turret.turret().range();
        var candidates=level.getEntitiesOfClass(LivingEntity.class,new AABB(turret.getBlockPos()).inflate(turret.turret().range()),m->candidate(level,m,owner,center,facing,rangeSquared));
        if(candidates.isEmpty())return null;
        if(candidates.size()==1){var target=candidates.getFirst();var shot=solution(origin,facing,target);return shot!=null&&visible(level,origin,shot,target)?new Acquisition(target,shot):null;}
        Target[] ordered;
        if(candidates.size()<=64){
            ordered=new Target[candidates.size()];
            for(int n=0;n<ordered.length;n++){var mob=candidates.get(n);ordered[n]=new Target(mob,mob.distanceToSqr(center),n);}
        }else{
            var nearest=new PriorityQueue<Target>(64,NEAREST.reversed());int order=0;
            for(var mob:candidates){
                double distance=mob.distanceToSqr(center);
                if(nearest.size()<64)nearest.add(new Target(mob,distance,order));
                else if(distance<nearest.peek().distance()){nearest.poll();nearest.add(new Target(mob,distance,order));}
                order++;
            }
            ordered=nearest.toArray(Target[]::new);
        }
        Arrays.sort(ordered,NEAREST);
        for(var target:ordered){var shot=solution(origin,facing,target.entity());if(shot!=null&&visible(level,origin,shot,target.entity()))return new Acquisition(target.entity(),shot);}
        return null;
    }
    public static void tick(ServerLevel level,DeviceBlockEntity turret) {
        var s=turret.turret();long now=level.getGameTime();s.tick(now);
        if(s.metal())dev.umbral.logistics.MotaService.syncMetal(level,turret);
        if(now>=s.blockedUntil)s.obstructed=false;
        if(!NetworkService.inspect(level,turret).online()) {s.setArmed(false,now);s.target=null;s.stop(now);return;}
        if(!s.armed()||s.count()==0) {s.target=null;s.obstructed=false;s.stop(now);return;}
        Direction facing=turret.getBlockState().getValue(DeviceBlock.FACING);var owner=level.getPlayerByUUID(turret.owner());
        Vec3 center=Vec3.atCenterOf(turret.getBlockPos());var pose=s.motion();
        Vec3 origin=TurretAim.muzzle(turret.getBlockPos(),facing,pose.yawAt(now),pose.pitchAt(now));
        double rangeSquared=s.range()*s.range();TurretAim.Shot shot=null;boolean traced=false;
        if((now+turret.getBlockPos().asLong())%5==0) {
            if(s.target!=null){
                if(!candidate(level,s.target,owner,center,facing,rangeSquared)){s.target=null;s.stop(now);}
                else {
                    shot=solution(origin,facing,s.target);
                    if(shot==null||!visible(level,origin,shot,s.target)){
                        s.target=null;s.obstructed=true;s.blockedUntil=now+40;s.stop(now);
                    }else traced=true;
                }
            }
            if(s.target==null) {
                var acquired=acquire(level,turret,owner,center,origin,facing);
                if(acquired!=null){s.target=acquired.entity();shot=acquired.shot();traced=true;}
            }
            if(s.target!=null) {
                s.obstructed=false;
                if(shot!=null)s.aim(shot.yaw(),shot.pitch(),now);
            }
        }
        if(s.target==null||s.cooldown()>0||!candidate(level,s.target,owner,center,facing,rangeSquared))return;
        if(shot==null)shot=solution(origin,facing,s.target);if(shot==null)return;
        var motion=s.motion();if(Math.abs(shot.yaw()-motion.yawAt(now))>1.5||Math.abs(shot.pitch()-motion.pitchAt(now))>1.5)return;
        // aim() starts from the current pose at this tick, so this tick's trajectory remains exact.
        if(!traced&&!visible(level,origin,shot,s.target)) {s.obstructed=true;s.blockedUntil=now+40;s.target=null;s.stop(now);return;}
        if(!NetworkService.inspectFresh(level,turret).online()){s.setArmed(false,now);s.target=null;s.stop(now);return;}
        TurretBolt bolt=s.metal()?new NuggetBolt(UmbralContent.NUGGET_BOLT,level):new TurretBolt(UmbralContent.TURRET_BOLT,level);
        bolt.prepare(level.getPlayerByUUID(turret.owner()),s.ammunition(),origin,shot.velocity());
        if(!level.addFreshEntity(bolt))return;
        s.fired(now);if(turret.getBlockState().getBlock() instanceof dev.umbral.block.CentellaBlock)
            level.setBlock(turret.getBlockPos(),turret.getBlockState().setValue(dev.umbral.block.CentellaBlock.METAL,s.material()),2);
        level.playSound(null,turret.getBlockPos(),SoundEvents.CROSSBOW_SHOOT,SoundSource.BLOCKS,.7f,.9f);
    }
    private TurretService() { }
}
