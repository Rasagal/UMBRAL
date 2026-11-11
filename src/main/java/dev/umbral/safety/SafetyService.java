// SPDX-License-Identifier: MPL-2.0

package dev.umbral.safety;

import dev.umbral.UmbralContent;
import dev.umbral.access.AccessRegistry;
import dev.umbral.block.*;
import dev.umbral.network.NetworkService;
import dev.umbral.turret.TurretBolt;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.effect.*;
import net.minecraft.world.phys.*;
import net.minecraft.sounds.*;
import java.util.ArrayList;

public final class SafetyService {
    public static boolean authorized(ServerLevel level,DeviceBlockEntity sensor,Player player){
        if(sensor.ownedBy(player.getUUID())||player.isSpectator())return true;
        var owner=level.getPlayerByUUID(sensor.owner());if(owner!=null&&owner.isAlliedTo(player))return true;
        if(sensor.link()==null)return false;
        for(int n=0;n<player.getInventory().getContainerSize();n++){
            var stack=player.getInventory().getItem(n);var id=stack.get(UmbralContent.CARD_ID);
            if(stack.is(UmbralContent.ACCESS_CARD)&&id!=null&&AccessRegistry.get(level).accepts(id,sensor.owner(),sensor.link()))return true;
        }return false;
    }
    public static boolean visible(ServerLevel level,Vec3 from,Vec3 to){
        // Optical sensor sees through glass, but never reads a cell in an unloaded chunk.
        int n=Math.max(1,(int)Math.ceil(from.distanceTo(to)*8));
        for(int i=0;i<=n;i++){var pos=BlockPos.containing(from.lerp(to,(double)i/n));if(!level.isLoaded(pos)||level.getBlockState(pos).isSolidRender())return false;}
        return true;
    }
    public static boolean detects(ServerLevel level,DeviceBlockEntity sensor,LivingEntity entity){
        if(!entity.isAlive()||entity.isSpectator())return false;
        Vec3 from=Vec3.atCenterOf(sensor.getBlockPos()),to=entity.position().add(0,entity.getBbHeight()*.5,0),delta=to.subtract(from);
        if(delta.lengthSqr()>sensor.safety().range()*sensor.safety().range())return false;
        Direction facing=sensor.getBlockState().getValue(DeviceBlock.FACING);
        if(delta.normalize().dot(new Vec3(facing.getStepX(),facing.getStepY(),facing.getStepZ()))<.5)return false;
        boolean eligible=entity instanceof Player p?!authorized(level,sensor,p):TurretBolt.eligible(entity,level.getPlayerByUUID(sensor.owner()));
        if(!eligible)return false;
        return visible(level,from,to);
    }
    public static void tick(ServerLevel level,DeviceBlockEntity device){
        if(!level.isLoaded(device.getBlockPos())||level.getBlockEntity(device.getBlockPos())!=device)return;
        var s=device.safety();s.tick();long now=level.getGameTime();
        if(!NetworkService.inspect(level,device).online())s.arm(false);
        if(s.armed()&&Math.floorMod(now+device.getBlockPos().asLong(),5)==0){
            if(device.kind()==DeviceKind.SENSOR){
                if(level.hasEntities(EntityTypeTest.forClass(LivingEntity.class),new AABB(device.getBlockPos()).inflate(s.range()),e->detects(level,device,e)))s.detect();
            }else if(s.count()>0&&s.cooldown()==0){
                Vec3 center=Vec3.atCenterOf(device.getBlockPos()),origin=center.add(0,.15,0);var owner=level.getPlayerByUUID(device.owner());
                // No splash scan or distant sight traces until a visible hostile can actually trigger it.
                var trigger=new ArrayList<LivingEntity>(1);
                level.getEntities(EntityTypeTest.forClass(LivingEntity.class),new AABB(device.getBlockPos()).inflate(1.5),e->e.distanceToSqr(center)<2.25&&TurretBolt.eligible(e,owner)&&visible(level,origin,e.position().add(0,.8,0)),trigger,1);
                if(!trigger.isEmpty()){
                    if(NetworkService.inspectFresh(level,device).online()){
                        var first=trigger.getFirst();
                        var nearby=level.getEntitiesOfClass(LivingEntity.class,new AABB(device.getBlockPos()).inflate(3),e->e.distanceToSqr(center)<=9&&TurretBolt.eligible(e,owner)&&(e==first||visible(level,origin,e.position().add(0,.8,0))));
                        s.pulse();dev.umbral.installation.NexoJournal.get(level).observe(level,device);for(var target:nearby){target.hurtServer(level,level.damageSources().magic(),6);target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,40,1));}
                        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,center.x,center.y+.15,center.z,20,1,.15,1,.05);
                        level.playSound(null,device.getBlockPos(),SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.BLOCKS,.7f,.7f);
                    }else s.arm(false);
                }
            }
        }
        var state=device.getBlockState();
        if(state.getValue(SafetyBlock.ARMED)!=s.armed()||state.getValue(SafetyBlock.ALARM)!=s.alarm()){
            level.setBlock(device.getBlockPos(),state.setValue(SafetyBlock.ARMED,s.armed()).setValue(SafetyBlock.ALARM,s.alarm()),3);
            level.updateNeighborsAt(device.getBlockPos(),state.getBlock());device.setChanged();
        }
        if(s.cooldown()>0)device.setChanged();
    }
    private SafetyService(){}
}
