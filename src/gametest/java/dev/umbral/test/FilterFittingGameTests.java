// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Fit against real vanilla support faces and test the matching physical gap, not an invisible cube. */
public final class FilterFittingGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private FilterGameTests.Setup setup(GameTestHelper t){return new FilterGameTests().setup(t);}
    private BlockPos pos(DeviceBlockEntity root,FilterBlock.Part part){return FilterBlock.partPos(root.getBlockPos(),root.getBlockState().getValue(DeviceBlock.FACING),part);}
    private Direction right(DeviceBlockEntity root){return root.getBlockState().getValue(DeviceBlock.FACING).getClockWise();}
    private void surround(ServerLevel level,DeviceBlockEntity root,boolean sides,boolean roof){
        var right=right(root);for(int y=0;y<3;y++)for(int x:new int[]{-1,3})level.setBlockAndUpdate(root.getBlockPos().relative(right,x).above(y),(sides?Blocks.STONE:Blocks.AIR).defaultBlockState());
        for(int x=0;x<3;x++)level.setBlockAndUpdate(root.getBlockPos().relative(right,x).above(3),(roof?Blocks.STONE:Blocks.AIR).defaultBlockState());
    }
    private boolean covers(ServerLevel level,BlockPos pos,double x,double y,double z){
        var state=level.getBlockState(pos);double X=x,Z=z;
        switch(state.getValue(DeviceBlock.FACING)){case EAST-> {X=16-z;Z=x;}case SOUTH->{X=16-x;Z=16-z;}case WEST->{X=z;Z=16-x;}default->{}}
        final double px=X/16,py=y/16,pz=Z/16;
        return state.getCollisionShape(level,pos).toAabbs().stream().anyMatch(b->b.contains(px,py,pz));
    }
    private void flags(GameTestHelper t,DeviceBlockEntity root,boolean sides,boolean roof){
        var level=t.getLevel();for(var part:FilterBlock.Part.values()){
            var state=level.getBlockState(pos(root,part));t.assertTrue(state.getValue(FilterBlock.SEALED_SIDE)==(sides&&(FilterBlock.leftColumn(part)||FilterBlock.rightColumn(part))),"Correct side fitting: "+part);
            t.assertTrue(state.getValue(FilterBlock.SEALED_TOP)==(roof&&FilterBlock.topRow(part)),"Correct ceiling fitting: "+part);
        }
    }
    private DeviceBlockEntity reorient(GameTestHelper t,FilterGameTests.Setup i,Direction facing){
        t.getLevel().destroyBlock(i.filter().getBlockPos(),false);var p=i.owner();p.setYRot(facing.getOpposite().toYRot());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=i.filter().getBlockPos().below();
        UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));
        var root=(DeviceBlockEntity)t.getLevel().getBlockEntity(i.filter().getBlockPos());t.assertTrue(root!=null&&root.getBlockState().getValue(DeviceBlock.FACING)==facing&&FilterBlock.complete(root),"A real kit places a complete arch facing "+facing);return root;
    }
    @GameTest(structure=STRUCTURE) public void wallAndCeilingCloseGapsInAllFourDirections(GameTestHelper t){
        var i=setup(t);for(var facing:Direction.Plane.HORIZONTAL){
            var root=reorient(t,i,facing);flags(t,root,false,false);surround(t.getLevel(),root,true,true);flags(t,root,true,true);
            root=reorient(t,i,facing);flags(t,root,true,true); // Also place the real kit into an already-built doorway.
            for(var part:FilterBlock.Part.values()){
                if(FilterBlock.leftColumn(part)){t.assertTrue(covers(t.getLevel(),pos(root,part),1,8,8),"Left outer gap physically sealed "+facing);t.assertFalse(covers(t.getLevel(),pos(root,part),1,8,5)||covers(t.getLevel(),pos(root,part),1,8,11),"Thin left adapter leaves its recessed depth free");}
                if(FilterBlock.rightColumn(part)){t.assertTrue(covers(t.getLevel(),pos(root,part),15,8,8),"Right outer gap physically sealed "+facing);t.assertFalse(covers(t.getLevel(),pos(root,part),15,8,5)||covers(t.getLevel(),pos(root,part),15,8,11),"Thin right adapter leaves its recessed depth free");}
                if(FilterBlock.topRow(part)){double x=part==FilterBlock.Part.LEFT_TOP?15:part==FilterBlock.Part.RIGHT_TOP?.5:8;t.assertTrue(covers(t.getLevel(),pos(root,part),x,15,8),"Ceiling gap physically sealed "+facing);t.assertFalse(covers(t.getLevel(),pos(root,part),x,15,5)||covers(t.getLevel(),pos(root,part),x,15,11),"Ceiling adapter has matching thin collision");}
            }
            var opening=root.getBlockPos().relative(right(root));t.assertTrue(t.getLevel().getBlockState(opening).isAir()&&t.getLevel().getBlockState(opening.above()).isAir(),"No caps occupy the central air opening");
            surround(t.getLevel(),root,false,false);flags(t,root,false,false);
        }t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void eachSegmentRetractsWhenItsWallIsRemoved(GameTestHelper t){
        var i=setup(t);var root=i.filter();surround(t.getLevel(),root,true,true);var middle=pos(root,FilterBlock.Part.LEFT_MIDDLE);var wall=middle.relative(right(root).getOpposite());
        t.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());t.assertFalse(t.getLevel().getBlockState(middle).getValue(FilterBlock.SEALED_SIDE),"Only changed segment retracts");t.assertFalse(covers(t.getLevel(),middle,1,8,8),"Retracted panel leaves no invisible collision");
        t.assertTrue(t.getLevel().getBlockState(pos(root,FilterBlock.Part.BASE)).getValue(FilterBlock.SEALED_SIDE),"Other side segments remain attached");t.assertTrue(t.getLevel().getBlockState(pos(root,FilterBlock.Part.LINTEL)).getValue(FilterBlock.SEALED_TOP),"Roof stays attached");
        t.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());t.assertTrue(covers(t.getLevel(),middle,1,8,8),"Replacing wall restores physical fitting");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void freestandingFrameKeepsOriginalNarrowCollision(GameTestHelper t){
        var root=setup(t).filter();flags(t,root,false,false);t.assertFalse(covers(t.getLevel(),pos(root,FilterBlock.Part.LEFT_MIDDLE),1,8,8),"Open left side has no panel");t.assertFalse(covers(t.getLevel(),pos(root,FilterBlock.Part.RIGHT_MIDDLE),15,8,8),"Open right side has no panel");t.assertFalse(covers(t.getLevel(),pos(root,FilterBlock.Part.LINTEL),8,15,8),"Open ceiling has no panel");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void partialSideFacesDoNotGrowFloatingFullHeightPanels(GameTestHelper t){
        var root=setup(t).filter();var middle=pos(root,FilterBlock.Part.LEFT_MIDDLE);var wall=middle.relative(right(root).getOpposite());
        for(var block:new Block[]{Blocks.STONE_SLAB,Blocks.OAK_FENCE,Blocks.COBBLESTONE_WALL}){t.getLevel().setBlockAndUpdate(wall,block.defaultBlockState());t.assertFalse(t.getLevel().getBlockState(middle).getValue(FilterBlock.SEALED_SIDE),"No floating full-height fitting on "+block);}
        t.getLevel().setBlockAndUpdate(wall,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE));t.assertTrue(t.getLevel().getBlockState(middle).getValue(FilterBlock.SEALED_SIDE),"Double slab is a complete supporting face");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void fullGlassFacesCanSupportTheSameMetalFitting(GameTestHelper t){
        var root=setup(t).filter();var wall=pos(root,FilterBlock.Part.RIGHT_MIDDLE).relative(right(root));t.getLevel().setBlockAndUpdate(wall,Blocks.GLASS.defaultBlockState());t.assertTrue(t.getLevel().getBlockState(pos(root,FilterBlock.Part.RIGHT_MIDDLE)).getValue(FilterBlock.SEALED_SIDE),"Support uses collision face, not opacity");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void cablesAndOtherDevicesAreNotMistakenForWalls(GameTestHelper t){
        var i=setup(t);var middle=pos(i.filter(),FilterBlock.Part.LEFT_MIDDLE);var wall=middle.relative(right(i.filter()).getOpposite());
        for(var block:new Block[]{UmbralContent.CABLE,UmbralContent.CORE,UmbralContent.NODE}){t.getLevel().setBlockAndUpdate(wall,block.defaultBlockState());t.assertFalse(t.getLevel().getBlockState(middle).getValue(FilterBlock.SEALED_SIDE),"Fitting never buries neighboring equipment");}t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void ceilingNeedsItsActualBottomFaceAtTheBoundary(GameTestHelper t){
        var root=setup(t).filter();var top=pos(root,FilterBlock.Part.LINTEL);var ceiling=top.above();
        t.getLevel().setBlockAndUpdate(ceiling,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM));t.assertTrue(t.getLevel().getBlockState(top).getValue(FilterBlock.SEALED_TOP),"Bottom slab touches the arch at its lower face");
        t.getLevel().setBlockAndUpdate(ceiling,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP));t.assertFalse(t.getLevel().getBlockState(top).getValue(FilterBlock.SEALED_TOP),"Raised slab leaves an intentional half-block gap");t.assertFalse(covers(t.getLevel(),top,8,15,8),"Retracted ceiling has no invisible collision");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=60) public void oldSavedStatesRefitOnNormalTicksWithoutLosingSettings(GameTestHelper t){
        var i=setup(t);surround(t.getLevel(),i.filter(),true,true);var id=i.filter().identity();var owner=i.filter().owner();var rules=i.filter().filter().rules();
        for(var part:FilterBlock.Part.values()){
            String saved="{\"id\":\"umbral:inventory_scanner\",\"properties\":{\"facing\":\"north\",\"online\":\"false\",\"part\":\""+part.getSerializedName()+"\"}}";
            var decoded=BlockState.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(saved)).getOrThrow();t.assertFalse(decoded.getValue(FilterBlock.SEALED_SIDE)||decoded.getValue(FilterBlock.SEALED_TOP),"Legacy state gets safe new defaults");t.getLevel().setBlock(pos(i.filter(),part),decoded,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        }
        t.runAfterDelay(25,()->{flags(t,i.filter(),true,true);var root=(DeviceBlockEntity)t.getLevel().getBlockEntity(i.filter().getBlockPos());t.assertTrue(root.identity().equals(id)&&root.owner().equals(owner)&&root.filter().rules().equals(rules)&&FilterBlock.complete(root),"Refitting preserves identity, owner, rules and complete assembly");t.succeed();});
    }
    @GameTest(structure=STRUCTURE) public void unloadedNeighborsKeepLastKnownFitWithoutReadingOrLoadingChunks(GameTestHelper t){
        var root=setup(t).filter();var reads=new AtomicInteger();var unavailable=(LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class<?>[]{LevelReader.class},(proxy,method,args)->{if(method.getName().equals("hasChunkAt"))return false;reads.incrementAndGet();throw new AssertionError("Unexpected world access: "+method.getName());});
        var state=root.getBlockState().setValue(FilterBlock.SEALED_SIDE,true);t.assertTrue(FilterBlock.withFittings(unavailable,root.getBlockPos(),state).getValue(FilterBlock.SEALED_SIDE),"Unknown chunk does not retract known wall fitting");
        t.assertFalse(FilterBlock.withFittings(unavailable,root.getBlockPos(),state.setValue(FilterBlock.SEALED_SIDE,false)).getValue(FilterBlock.SEALED_SIDE),"Unknown chunk does not invent a wall");t.assertTrue(reads.get()==0,"No neighboring state reads or chunk loads");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=40) public void dismantlingFittedFrameStillReturnsExactlyOneKit(GameTestHelper t){
        var root=setup(t).filter();surround(t.getLevel(),root,true,true);t.getLevel().destroyBlock(pos(root,FilterBlock.Part.RIGHT_MIDDLE),true);t.runAfterDelay(2,()->{int kits=t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(root.getBlockPos()).inflate(4),e->e.getItem().is(UmbralContent.FILTER.asItem())).stream().mapToInt(e->e.getItem().getCount()).sum();t.assertTrue(kits==1,"Attached trims are part of the one kit, got "+kits);t.assertTrue(t.getLevel().getBlockState(root.getBlockPos().relative(right(root),-1)).is(Blocks.STONE),"Dismantling never removes the supporting wall");t.succeed();});
    }
}
