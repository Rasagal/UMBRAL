// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

/** Vanilla arrow physics and damage; the projectile itself enforces the same target policy. */
public class TurretBolt extends Arrow {
    private int age;
    public TurretBolt(EntityType<? extends Arrow> type,Level level) { super(type,level); }
    @Override public void setOwner(Entity owner){super.setOwner(owner);pickup=Pickup.DISALLOWED;}
    public static boolean eligible(Entity entity,Entity owner) {
        return entity instanceof LivingEntity living && living.isAlive()&&!living.isSpectator()
                && !(living instanceof net.minecraft.world.entity.player.Player)
                && living instanceof Enemy && !(living instanceof NeutralMob) && !living.hasCustomName()
                && living.getType()!=EntityTypes.PIGLIN && living.getType()!=EntityTypes.SPIDER
                && (owner==null||!owner.isAlliedTo(living));
    }
    public void prepare(Entity owner,ItemStack arrow,Vec3 position,Vec3 velocity) {
        setOwner(owner);pickup=Pickup.DISALLOWED;setPickupItemStack(arrow.copyWithCount(1));
        setPos(position);setDeltaMovement(velocity);setBaseDamage(2);setCritArrow(false);
        setYRot((float)Math.toDegrees(Math.atan2(velocity.x,velocity.z)));
        setXRot((float)Math.toDegrees(Math.atan2(velocity.y,velocity.horizontalDistance())));
    }
    @Override protected boolean canHitEntity(Entity entity) { return eligible(entity,getOwner())&&super.canHitEntity(entity); }
    @Override protected boolean tryPickup(net.minecraft.world.entity.player.Player player) { return false; }
    @Override public void tick() {
        if(!level().isClientSide()){
            Vec3 start=position(),velocity=getDeltaMovement();
            if(velocity.lengthSqr()>64){discard();return;}
            int steps=Math.max(1,(int)Math.ceil(velocity.length()*4));
            for(int n=0;n<=steps;n++)if(!level().isLoaded(BlockPos.containing(start.add(velocity.scale((double)n/steps))))){discard();return;}
        }
        super.tick();if(!level().isClientSide()&&++age>=200)discard();
    }
    @Override protected void addAdditionalSaveData(ValueOutput out) { super.addAdditionalSaveData(out);out.putInt("umbral_age",age); }
    @Override protected void readAdditionalSaveData(ValueInput in) { super.readAdditionalSaveData(in);age=Math.clamp(in.getIntOr("umbral_age",0),0,200);pickup=Pickup.DISALLOWED; }
}
