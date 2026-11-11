// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Actual kits over buried cable, closed floor covers and the unchanged six-way grid. */
public final class WiredPlacementGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos ROOT=new BlockPos(4,2,4);
    private static boolean covers(GameTestHelper t,BlockPos p,double x,double y,double z){
        return t.getBlockState(p).getCollisionShape(t.getLevel(),t.absolutePos(p)).toAabbs().stream().anyMatch(b->b.contains(x,y,z));
    }
    private void kit(GameTestHelper t,Direction facing,boolean baseSupport,boolean rightSupport){
        for(int x=0;x<10;x++)for(int z=0;z<10;z++)t.setBlock(new BlockPos(x,1,z),Blocks.STONE);
        t.setBlock(ROOT.below(),baseSupport?UmbralContent.CABLE:Blocks.AIR);
        if(!rightSupport)t.setBlock(ROOT.relative(facing.getClockWise(),2).below(),Blocks.AIR);
        var p=t.makeMockServerPlayer(GameType.SURVIVAL);p.setYRot(facing.getOpposite().toYRot());
        p.setPos(Vec3.atLowerCornerOf(t.absolutePos(ROOT)).add(.5,0,-2));p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER,2));
        var floor=t.absolutePos(ROOT.below());
        UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.05,0),Direction.UP,floor,false)));
        if(!baseSupport||!rightSupport){
            t.assertTrue(t.getBlockState(ROOT).isAir()&&p.getMainHandItem().getCount()==2,"Unsupported kit leaves the world and inventory intact");t.succeed();return;
        }
        var d=t.getBlockEntity(ROOT,DeviceBlockEntity.class);
        t.assertTrue(FilterBlock.complete(d)&&d.getBlockState().getValue(DeviceBlock.FACING)==facing,"Real kit assembles over its downward input in "+facing);
        t.assertTrue(p.getMainHandItem().getCount()==1,"Only one kit consumed");
        var corePos=ROOT.relative(facing.getClockWise().getOpposite(),2).below();t.setBlock(corePos,UmbralContent.CORE);
        var core=t.getBlockEntity(corePos,DeviceBlockEntity.class);core.initializeOwner(p.getUUID(),"");d.bind(p.getUUID(),core);
        t.setBlock(ROOT.relative(facing.getClockWise().getOpposite()).below(),UmbralContent.CABLE);NetworkService.invalidate(t.getLevel());
        t.assertTrue(NetworkService.inspect(t.getLevel(),d).status()==NetworkService.Status.WIRED,"Controller is reached through the cable under its own plate");
        var right=ROOT.relative(facing.getClockWise(),2);
        for(var foot:new BlockPos[]{ROOT,right})for(double x:new double[]{.01,.5,.99})for(double z:new double[]{.01,.5,.99}){
            t.assertTrue(covers(t,foot,x,.01,z),"Whole foot seals the cell above the cable");
        }
        t.assertTrue(t.getBlockState(ROOT.relative(facing.getClockWise())).isAir(),"Central passage remains actual air with normal floor underneath");
        t.setBlock(ROOT.below(),Blocks.AIR);NetworkService.invalidate(t.getLevel());
        t.assertTrue(FilterBlock.complete(d)&&covers(t,ROOT,.01,.01,.99),"Cutting the wire never removes the permanent floor cover");
        t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void scannerOverBuriedCableNorth(GameTestHelper t){kit(t,Direction.NORTH,true,true);}
    @GameTest(structure=STRUCTURE) public void scannerOverBuriedCableEast(GameTestHelper t){kit(t,Direction.EAST,true,true);}
    @GameTest(structure=STRUCTURE) public void scannerOverBuriedCableSouth(GameTestHelper t){kit(t,Direction.SOUTH,true,true);}
    @GameTest(structure=STRUCTURE) public void scannerOverBuriedCableWest(GameTestHelper t){kit(t,Direction.WEST,true,true);}
    @GameTest(structure=STRUCTURE) public void scannerStillRejectsAirUnderController(GameTestHelper t){kit(t,Direction.NORTH,false,true);}
    @GameTest(structure=STRUCTURE) public void scannerStillRequiresSolidRightFoot(GameTestHelper t){kit(t,Direction.NORTH,true,false);}
    @GameTest(structure=STRUCTURE) public void doorThresholdSealsEveryFacingWithoutBlockingOpenPassage(GameTestHelper t){
        for(var facing:Direction.Plane.HORIZONTAL){
            var state=UmbralContent.DOOR.defaultBlockState().setValue(DeviceBlock.FACING,facing);
            for(float progress:new float[]{0,.5f,1}){
                var shape=SecurityDoorBlock.passageShape(state,progress);
                for(double x:new double[]{.01,.5,.99})for(double z:new double[]{.01,.5,.99})t.assertTrue(shape.toAabbs().stream().anyMatch(b->b.contains(x,.01,z)),"Fixed threshold seals the whole cell at every animation phase");
            }
            var open=SecurityDoorBlock.passageShape(state,1);
            t.assertFalse(open.toAabbs().stream().anyMatch(b->b.contains(.5,.04,.5)),"Threshold is only 1/32 block high");
            t.assertFalse(open.toAabbs().stream().anyMatch(b->b.contains(.5,.9,.5)),"Open doorway has no invisible obstruction");
        }t.succeed();
    }
}
