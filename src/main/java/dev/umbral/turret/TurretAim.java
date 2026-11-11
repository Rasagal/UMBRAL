// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Discrete vanilla-arrow drag/gravity, shared by the solver and preflight trace. */
public final class TurretAim {
    public static final double DRAG=.99F, GRAVITY=.05;
    public record Shot(Vec3 velocity,float yaw,float pitch,double ticks) { }
    public static float wrap(float value) { return (value%360+540)%360-180; }
    public static Vec3 rotate(Vec3 local,Direction facing,float yaw,float pitch) {
        double p=Math.toRadians(pitch),a=Math.toRadians(180-facing.toYRot()-yaw);
        double y=local.y*Math.cos(p)+local.z*Math.sin(p),z=local.z*Math.cos(p)-local.y*Math.sin(p);
        return new Vec3(local.x*Math.cos(a)+z*Math.sin(a),y,z*Math.cos(a)-local.x*Math.sin(a));
    }
    public static Vec3 muzzle(BlockPos pos,Direction facing,float yaw,float pitch) {
        return new Vec3(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5).add(rotate(new Vec3(0,.09375,-.65),facing,yaw,pitch));
    }
    public static Shot solve(Vec3 origin,Vec3 destination,Direction facing) {
        Vec3 d=destination.subtract(origin);float yaw=wrap((float)Math.toDegrees(Math.atan2(-d.x,d.z))-facing.toYRot());
        if(Math.abs(yaw)>60)return null;
        double low=.0001,high=24;
        if(velocity(d,high).length()>1.8)return null;
        for(int n=0;n<40;n++){
            double mid=(low+high)/2;if(velocity(d,mid).length()>1.8)low=mid;else high=mid;
        }
        double time=(low+high)/2;Vec3 v=velocity(d,time);
        float pitch=(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()));
        return pitch < -35||pitch > 20?null:new Shot(v,yaw,pitch,time);
    }
    private static Vec3 velocity(Vec3 delta,double time){
        int whole=(int)time;double fraction=time-whole,power=Math.pow(DRAG,whole);
        double a=(1-power)/(1-DRAG)+fraction*power;
        return new Vec3(delta.x/a,(delta.y+GRAVITY/(1-DRAG)*(time-a))/a,delta.z/a);
    }
    public static Vec3 stepVelocity(Vec3 v) { return v.scale(DRAG).add(0,-GRAVITY,0); }
    private TurretAim() { }
}
