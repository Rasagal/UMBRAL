// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Physical, non-recoverable nugget; inherits BALUARTE's PvE and unloaded-boundary checks. */
public final class NuggetBolt extends TurretBolt implements ItemSupplier {
    private static final EntityDataAccessor<Integer> METAL=SynchedEntityData.defineId(NuggetBolt.class,EntityDataSerializers.INT);
    public NuggetBolt(EntityType<? extends NuggetBolt> type,Level level){super(type,level);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){super.defineSynchedData(b);b.define(METAL,Metal.COPPER.ordinal());}
    @Override public void prepare(Entity owner,ItemStack stack,Vec3 position,Vec3 velocity){
        super.prepare(owner,stack,position,velocity);Metal m=Metal.of(stack);if(m==Metal.EMPTY){discard();return;}
        entityData.set(METAL,m.ordinal());setBaseDamage(m.damage);
    }
    @Override public ItemStack getItem(){return new ItemStack(Metal.values()[Math.clamp(entityData.get(METAL),1,3)].item);}
    @Override protected void addAdditionalSaveData(ValueOutput out){super.addAdditionalSaveData(out);out.putInt("umbral_metal",entityData.get(METAL));}
    @Override protected void readAdditionalSaveData(ValueInput in){super.readAdditionalSaveData(in);int n=Math.clamp(in.getIntOr("umbral_metal",1),1,3);entityData.set(METAL,n);setBaseDamage(Metal.values()[n].damage);}
}
