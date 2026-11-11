// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class TurretState {
    public enum Preference {
        AUTOMATIC, COPPER, IRON, GOLD;
        public String id(){return name().toLowerCase(java.util.Locale.ROOT);}
        public String key(){return "centella.umbral.preference."+id();}
        public static Preference of(Metal material){return switch(material){case COPPER->COPPER;case IRON->IRON;case GOLD->GOLD;default->AUTOMATIC;};}
        public boolean accepts(ItemStack stack){return this==AUTOMATIC||of(Metal.of(stack))==this;}
    }
    public static final int CAPACITY=64, RELOAD_TICKS=30;
    private final boolean metal;
    private int reloadDuration=RELOAD_TICKS;
    public TurretState() { this(false); }
    public TurretState(boolean metal) { this.metal=metal; }
    public boolean metal() { return metal; }
    public boolean accepts(ItemStack stack) { return !stack.isEmpty() && (metal ? Metal.of(stack)!=Metal.EMPTY : stack.is(Items.ARROW)); }
    public Metal material() { return metal ? Metal.of(ammunition) : Metal.EMPTY; }
    private ItemStack ammunition=ItemStack.EMPTY;
    private Preference preference=Preference.AUTOMATIC;
    private boolean preferenceExplicit;
    private boolean armed, resume;
    private int range=12, cooldown;
    private TurretMotion motion=TurretMotion.initial();
    LivingEntity target;
    boolean obstructed;
    long blockedUntil;
    public ItemStack ammunition() { return ammunition.copy(); }
    public Preference preference(){return metal?preference:Preference.AUTOMATIC;}
    public String preferenceId(){return preference().id();}
    /** Resupply never mixes components or silently substitutes a different chosen metal. */
    public boolean acceptsResupply(ItemStack stack){return accepts(stack)&&(!metal||preference.accepts(stack))&&(ammunition.isEmpty()||ItemStack.isSameItemSameComponents(ammunition,stack));}
    public boolean resupplyMismatch(){return metal&&!ammunition.isEmpty()&&!preference.accepts(ammunition);}
    public boolean setPreference(Preference value,long now){
        if(!metal||value==null)return false;
        boolean changed=preference!=value||!preferenceExplicit;preference=value;preferenceExplicit=true;if(changed)touch(now);return changed;
    }
    public void cyclePreference(long now){if(metal)setPreference(Preference.values()[(preference.ordinal()+1)%Preference.values().length],now);}
    public int count() { return ammunition.getCount(); }
    public boolean armed() { return armed; }
    public int range() { return range; }
    public int cooldown() { return cooldown; }
    public TurretMotion motion() { return motion; }
    public void accept(TurretMotion value, boolean replaced) { if(replaced||value.revision()>=motion.revision()) motion=value; }
    public void touch(long now) {
        motion=new TurretMotion(motion.revision()+1,motion.started(),motion.fromYaw(),motion.fromPitch(),motion.yaw(),motion.pitch(),motion.duration(),motion.shotAt(),armed,count());
    }
    public boolean setArmed(boolean value,long now) {
        if(armed==value)return false; armed=value; target=null; touch(now); return true;
    }
    public void cycleRange(long now) { range=range==8?12:range==12?16:8;target=null;touch(now); }
    public int load(ItemStack source,long now) {return load(source,now,true);}
    private int load(ItemStack source,long now,boolean learnPreference) {
        if(!accepts(source)||(!ammunition.isEmpty()&&!ItemStack.isSameItemSameComponents(ammunition,source)))return 0;
        int n=Math.min(Math.min(CAPACITY,source.getMaxStackSize())-count(),source.getCount()); if(n<=0)return 0;
        if(ammunition.isEmpty())ammunition=source.copyWithCount(n);else ammunition.grow(n);
        // A new/legacy turret remembers deliberate manual loads until the player
        // explicitly chooses a resupply preference (including Automatic).
        if(metal&&learnPreference&&!preferenceExplicit)preference=Preference.of(Metal.of(ammunition));
        source.shrink(n);touch(now);return n;
    }
    public int loadResupply(ItemStack source,long now){return acceptsResupply(source)?load(source,now,false):0;}
    public ItemStack unload(long now) { ItemStack result=ammunition;ammunition=ItemStack.EMPTY;setArmed(false,now);touch(now);return result; }
    public void fired(long now) {
        reloadDuration=metal?material().reload:RELOAD_TICKS;ammunition.shrink(1);cooldown=reloadDuration;
        motion=new TurretMotion(motion.revision()+1,motion.started(),motion.fromYaw(),motion.fromPitch(),motion.yaw(),motion.pitch(),motion.duration(),now,armed,count());
    }
    public void tick(long now) {
        if(resume) {
            if(cooldown>0)motion=new TurretMotion(motion.revision()+1,now,motion.yaw(),motion.pitch(),motion.yaw(),motion.pitch(),0,now-(reloadDuration-cooldown),armed,count());
            resume=false;
        }
        if(cooldown>0)cooldown--;
    }
    public void aim(float yaw,float pitch,long now) {
        yaw=Math.clamp(yaw,-60,60);pitch=Math.clamp(pitch,-35,20);
        float fromYaw=motion.yawAt(now),fromPitch=motion.pitchAt(now);
        float toYaw=fromYaw+Math.clamp(yaw-fromYaw,-20,20),toPitch=fromPitch+Math.clamp(pitch-fromPitch,-10,10);
        if(Math.abs(toYaw-motion.yaw())<.01&&Math.abs(toPitch-motion.pitch())<.01)return;
        motion=new TurretMotion(motion.revision()+1,now,fromYaw,fromPitch,toYaw,toPitch,5,motion.shotAt(),armed,count());
    }
    public void stop(long now) {
        if(motion.duration()==0||now>=motion.started()+motion.duration())return;
        float y=motion.yawAt(now),p=motion.pitchAt(now);
        motion=new TurretMotion(motion.revision()+1,now,y,p,y,p,0,motion.shotAt(),armed,count());
    }
    public void save(ValueOutput out,long now) {
        out.putInt("turret_schema",1);out.putInt("turret_reload_duration",reloadDuration);out.store("turret_ammunition",ItemStack.OPTIONAL_CODEC,ammunition);
        out.putLong("turret_revision",motion.revision());
        out.putBoolean("turret_armed",armed);out.putInt("turret_range",range);out.putInt("turret_cooldown",cooldown);
        out.putFloat("turret_yaw",motion.yawAt(now));out.putFloat("turret_pitch",motion.pitchAt(now));
        if(metal){out.putString("turret_ammo_preference",preference.id());out.putBoolean("turret_ammo_preference_explicit",preferenceExplicit);}
    }
    public void load(ValueInput in) {
        if(in.getIntOr("turret_schema",0)!=1)return;
        ammunition=in.read("turret_ammunition",ItemStack.OPTIONAL_CODEC).filter(s->s.isEmpty()||accepts(s)&&s.getCount()<=Math.min(CAPACITY,s.getMaxStackSize())).orElse(ItemStack.EMPTY);
        String savedPreference=in.getStringOr("turret_ammo_preference","");
        try{preference=metal?(savedPreference.isEmpty()?Preference.of(Metal.of(ammunition)):Preference.valueOf(savedPreference.toUpperCase(java.util.Locale.ROOT))):Preference.AUTOMATIC;}
        catch(IllegalArgumentException e){preference=Preference.AUTOMATIC;}
        preferenceExplicit=metal&&in.getBooleanOr("turret_ammo_preference_explicit",false);
        armed=in.getBooleanOr("turret_armed",false);int r=in.getIntOr("turret_range",12);range=r==8||r==16?r:12;
        reloadDuration=Math.clamp(in.getIntOr("turret_reload_duration",RELOAD_TICKS),20,40);
        cooldown=Math.clamp(in.getIntOr("turret_cooldown",0),0,reloadDuration);
        float y=in.getFloatOr("turret_yaw",0),p=in.getFloatOr("turret_pitch",0);
        if(!Float.isFinite(y))y=0;if(!Float.isFinite(p))p=0;
        y=Math.clamp(y,-60,60);p=Math.clamp(p,-35,20);
        motion=new TurretMotion(Math.max(0,in.getLongOr("turret_revision",0)),0,y,p,y,p,0,-1000,armed,count());resume=true;target=null;
    }
}
