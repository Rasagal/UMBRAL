// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Regression: logical adjacency and the cable's six visual arms must agree. */
public final class CableConnectionGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static boolean arm(GameTestHelper t,BlockPos p,Direction d){return t.getBlockState(p).getValue(CopperCableBlock.CONNECTIONS.get(d));}
    private static final double LOW_CENTER=1.5/16,LOW_BOTTOM=.75/16,LOW_TOP=2.25/16;
    private static boolean covers(GameTestHelper t,BlockPos p,double x,double y,double z){return t.getBlockState(p).getCollisionShape(t.getLevel(),t.absolutePos(p)).toAabbs().stream().anyMatch(b->b.contains(x,y,z));}
    @GameTest(structure=STRUCTURE)
    public void structurePlacementBuildsContinuousCableInsteadOfHalfConnections(GameTestHelper t){
        var a=new BlockPos(3,2,3);var b=a.east();var c=b.east();t.setBlock(a,UmbralContent.CABLE);t.setBlock(b,UmbralContent.CABLE);t.setBlock(c,UmbralContent.NODE);
        t.runAfterDelay(3,()->{t.assertTrue(arm(t,a,Direction.EAST)&&arm(t,b,Direction.WEST)&&arm(t,b,Direction.EAST),"Both arms join across the cell boundary, including the node endpoint");t.succeed();});
    }
    @GameTest(structure=STRUCTURE)
    public void cableFirstAndDeviceFirstConvergeAndRemovalClearsTheArm(GameTestHelper t){
        var a=new BlockPos(4,3,4);t.setBlock(a,UmbralContent.CABLE);for(var d:Direction.values())t.setBlock(a.relative(d),UmbralContent.NODE);
        t.runAfterDelay(3,()->{for(var d:Direction.values())t.assertTrue(arm(t,a,d),"All six attached apparatus directions are visible");t.setBlock(a.east(),Blocks.AIR);t.runAfterDelay(3,()->{t.assertFalse(arm(t,a,Direction.EAST),"Removing the apparatus removes its cable arm");for(var d:Direction.values())if(d!=Direction.EAST)t.assertTrue(arm(t,a,d),"Other leads remain intact");t.succeed();});});
    }
    @GameTest(structure=STRUCTURE)
    public void doorUpperHalfAndOrdinaryBlocksNeverGrowFalseLeads(GameTestHelper t){
        var a=new BlockPos(4,3,4);t.setBlock(a.east().below(),UmbralContent.DOOR);t.setBlock(a.east(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));t.setBlock(a.west(),Blocks.STONE);t.setBlock(a,UmbralContent.CABLE);
        t.runAfterDelay(3,()->{t.assertTrue(t.getBlockState(a.east()).is(UmbralContent.DOOR)&&t.getBlockState(a.east()).getValue(SecurityDoorBlock.HALF)==DoubleBlockHalf.UPPER,"The real upper door remains present");t.assertFalse(arm(t,a,Direction.EAST)||arm(t,a,Direction.WEST),"Upper door has no device identity and stone is not a cable port");t.succeed();});
    }
    @GameTest(structure=STRUCTURE)
    public void thinnerCableHasNoOldPipeSizedInvisibleCollision(GameTestHelper t){var pos=new BlockPos(4,3,4);t.setBlock(pos,UmbralContent.CABLE);t.setBlock(pos.north(),UmbralContent.CABLE);t.setBlock(pos.south(),UmbralContent.CABLE);t.runAfterDelay(3,()->{var shape=t.getBlockState(pos).getCollisionShape(t.getLevel(),t.absolutePos(pos));t.assertTrue(shape.bounds().getXsize()==1.5/16&&shape.bounds().getYsize()==1.5/16&&shape.bounds().getZsize()==1,"Thin line retains continuous full-cell reach");t.assertTrue(shape.bounds().minY==LOW_BOTTOM&&shape.bounds().maxY==LOW_TOP,"The actual cable runs just above the floor, with matching collision");t.assertFalse(covers(t,pos,.5,.5,.5),"No invisible collision remains at the former half-block height");t.assertFalse(covers(t,pos,.5,.01,.5),"The intended small floor clearance remains empty");t.succeed();});}

    @GameTest(structure=STRUCTURE)
    public void horizontalTurnsAndJunctionsRemainLowAcrossCellBoundaries(GameTestHelper t){
        var p=new BlockPos(4,3,4);t.setBlock(p,UmbralContent.CABLE);
        for(var direction:new Direction[]{Direction.NORTH,Direction.EAST,Direction.WEST})t.setBlock(p.relative(direction),UmbralContent.CABLE);
        t.runAfterDelay(3,()->{
            for(var direction:new Direction[]{Direction.NORTH,Direction.EAST,Direction.WEST}){
                t.assertTrue(arm(t,p,direction)&&arm(t,p.relative(direction),direction.getOpposite()),"Both ends of the low T junction connect");
                double x=.5+direction.getStepX()*.4999,z=.5+direction.getStepZ()*.4999;
                t.assertTrue(covers(t,p,x,LOW_CENTER,z)&&covers(t,p.relative(direction),1-x,LOW_CENTER,1-z),"The same low conductor meets on both sides of the cell boundary");
            }
            t.assertTrue(covers(t,p,.5,LOW_CENTER,.5),"All branches meet at the low hub");
            var bounds=t.getBlockState(p).getCollisionShape(t.getLevel(),t.absolutePos(p)).bounds();
            t.assertTrue(bounds.minY==LOW_BOTTOM&&bounds.maxY==LOW_TOP,"T junction has no raised elbow or floating section");
            t.setBlock(p.east(),Blocks.AIR);
            t.runAfterDelay(3,()->{t.assertFalse(arm(t,p,Direction.EAST),"Removing a branch changes the T junction into a bend");t.assertTrue(covers(t,p,.5,LOW_CENTER,.0001)&&covers(t,p,.0001,LOW_CENTER,.5)&&covers(t,p,.5,LOW_CENTER,.5),"The remaining bend stays continuous at floor height");t.assertFalse(covers(t,p,.5,.5,.5),"Changing junctions never restores the old raised hub");t.succeed();});
        });
    }

    @GameTest(structure=STRUCTURE)
    public void verticalStacksJoinLowBranchesWithoutBoundaryGaps(GameTestHelper t){
        var bottom=new BlockPos(4,2,4);var middle=bottom.above();var top=middle.above();
        for(var p:new BlockPos[]{bottom,middle,top})t.setBlock(p,UmbralContent.CABLE);
        t.setBlock(bottom.east(),UmbralContent.CABLE);t.setBlock(middle.north(),UmbralContent.CABLE);t.setBlock(top.west(),UmbralContent.CABLE);
        t.runAfterDelay(3,()->{
            for(var lower:new BlockPos[]{bottom,middle}){
                t.assertTrue(arm(t,lower,Direction.UP)&&arm(t,lower.above(),Direction.DOWN),"Vertical adjacency is preserved after lowering the horizontal run");
                t.assertTrue(covers(t,lower,.5,.9999,.5)&&covers(t,lower.above(),.5,.0001,.5),"The vertical conductor reaches both sides of every horizontal block boundary");
            }
            t.assertTrue(covers(t,bottom,.9999,LOW_CENTER,.5)&&covers(t,middle,.5,LOW_CENTER,.0001)&&covers(t,top,.0001,LOW_CENTER,.5),"Every floor branch joins the same continuous riser at its own low height");
            t.assertTrue(covers(t,middle,.5,.5,.5),"A real vertical run still occupies the middle of its block");
            t.assertFalse(covers(t,top,.5,.5,.5),"The top elbow ends at the low branch instead of adding a raised stub");
            t.succeed();
        });
    }

}
