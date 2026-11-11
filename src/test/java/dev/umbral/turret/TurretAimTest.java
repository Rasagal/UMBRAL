// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurretAimTest {
    @Test void solvedArrowsReachTheirTargetWithDiscreteVanillaPhysics(){
        for(int distance:new int[]{3,8,12,16})for(double height:new double[]{-.4,0,2}){
            Vec3 origin=new Vec3(.5,2,.5),target=origin.add(0,height,-distance);
            var shot=TurretAim.solve(origin,target,Direction.NORTH);assertNotNull(shot);
            Vec3 p=origin,v=shot.velocity();for(int t=0;t<shot.ticks();t++){p=p.add(v.scale(Math.min(1,shot.ticks()-t)));v=v.scale(.99F).add(0,-.05,0);}
            assertEquals(target.x,p.x,1e-8);assertEquals(target.y,p.y,1e-8);assertEquals(target.z,p.z,1e-8);
            assertEquals(1.8,shot.velocity().length(),1e-8);assertTrue(shot.pitch()>=-35&&shot.pitch()<=20);
        }
    }
    @Test void rearAndImpossibleVerticalShotsAreRejected(){
        assertNull(TurretAim.solve(Vec3.ZERO,new Vec3(0,0,10),Direction.NORTH));
        assertNull(TurretAim.solve(Vec3.ZERO,new Vec3(12,0,-1),Direction.NORTH));
        assertNull(TurretAim.solve(Vec3.ZERO,new Vec3(0,20,-.1),Direction.NORTH));
    }
    @Test void muzzleFollowsFacingAndTilt(){
        Vec3 n=TurretAim.muzzle(BlockPos.ZERO,Direction.NORTH,0,0),e=TurretAim.muzzle(BlockPos.ZERO,Direction.EAST,0,0);
        assertEquals(.5,n.x,1e-8);assertEquals(-.15,n.z,1e-8);assertEquals(.59375,n.y,1e-8);
        assertEquals(1.15,e.x,1e-8);assertEquals(.5,e.z,1e-8);
        assertTrue(TurretAim.muzzle(BlockPos.ZERO,Direction.NORTH,0,20).y<n.y);
    }
    @Test void interpolationDoesNotOvershootOrAdvanceWhileWorldTimeIsStopped(){
        var m=new TurretMotion(1,100,0,0,20,-10,5,-1000,true,64);
        assertEquals(0,m.yawAt(99));assertEquals(10,m.yawAt(102.5));assertEquals(20,m.yawAt(105));assertEquals(20,m.yawAt(10000));
        assertEquals(-5,m.pitchAt(102.5));assertEquals(m.yawAt(103),m.yawAt(103));
    }
}
