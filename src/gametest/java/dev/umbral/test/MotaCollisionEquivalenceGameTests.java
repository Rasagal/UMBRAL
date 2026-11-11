// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.logistics.MotaRoute;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.phys.*;
import java.lang.reflect.*;
import java.util.*;

/** Differential coverage: optimized per-search collision facts must match the live vanilla oracle. */
public final class MotaCollisionEquivalenceGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos SHAPE=new BlockPos(5,3,4);
    private static final double[] OFFSETS={-1.1,-1.040001,-1.04,-1.039999,-.977501,-.9775,-.977499,0,.977499,.9775,.977501,1.039999,1.04,1.040001,1.1};
    private static final double[] HEIGHTS={-.660001,-.66,-.659999,0,.15,.5,.999999,1,1.15,1.499999,1.5,1.500001};
    private record Cached(Object target,Method feet,Method box){
        static Cached create(ServerLevel level){
            try{
                var type=Class.forName("dev.umbral.logistics.MotaRoute$SearchCollision");
                var ctor=type.getDeclaredConstructor(ServerLevel.class,MotaRoute.Metrics.class);ctor.setAccessible(true);
                var feet=type.getDeclaredMethod("clear",double.class,double.class,double.class);feet.setAccessible(true);
                var box=type.getDeclaredMethod("clear",AABB.class);box.setAccessible(true);
                return new Cached(ctor.newInstance(level,new MotaRoute.Metrics()),feet,box);
            }catch(ReflectiveOperationException e){throw new AssertionError("The real search collision evaluator is available for differential checks",e);}
        }
        private boolean invoke(Method method,Object...args){try{return (boolean)method.invoke(target,args);}catch(ReflectiveOperationException e){throw new AssertionError("Evaluating the production search collision evaluator",e);}}
        boolean feet(Vec3 at){return invoke(feet,at.x,at.y,at.z);}
        boolean box(AABB at){return invoke(box,at);}
    }
    private record Fixture(String name,BlockState state,boolean sand){}
    private void emptyFixture(GameTestHelper t){
        for(int x=1;x<=10;x++)for(int z=1;z<=6;z++)for(int y=1;y<=6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.AIR);
    }
    private void put(GameTestHelper t,Fixture fixture){
        emptyFixture(t);t.setBlock(SHAPE.below(2),Blocks.STONE);t.setBlock(SHAPE.below(),fixture.sand?Blocks.SAND:Blocks.STONE);
        // Preserve the deliberately selected trapdoor/fence connections while the world stays fixed.
        t.getLevel().setBlock(t.absolutePos(SHAPE),fixture.state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
    }
    private static AABB feetBox(Vec3 p){return new AABB(p.x-MotaRoute.HALF_WIDTH,p.y,p.z-MotaRoute.HALF_WIDTH,p.x+MotaRoute.HALF_WIDTH,p.y+MotaRoute.HEIGHT,p.z+MotaRoute.HALF_WIDTH);}
    /** Original swept-box rule, including unloaded/fluid checks before vanilla's collision halo. */
    private static boolean legacyBox(ServerLevel level,AABB box){
        for(var p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(!level.isLoaded(p)||!level.getFluidState(p).isEmpty())return false;
        return !level.getBlockCollisions(null,box).iterator().hasNext();
    }
    private void feet(GameTestHelper t,Cached cache,Vec3 at,String label){
        boolean expected=MotaRoute.clear(t.getLevel(),at),actual=cache.feet(at);
        t.assertTrue(expected==actual,label+" feet="+at+" live="+expected+" cached="+actual);
    }
    private void box(GameTestHelper t,Cached cache,AABB at,String label){
        boolean expected=legacyBox(t.getLevel(),at),actual=cache.box(at);
        t.assertTrue(expected==actual,label+" swept="+at+" live="+expected+" cached="+actual);
    }
    private void sample(GameTestHelper t,String label){
        var cache=Cached.create(t.getLevel());var origin=Vec3.atLowerCornerOf(t.absolutePos(SHAPE));int clear=0,blocked=0;
        // A second, reversed pass reuses populated block/shape/chunk caches against an unchanged world.
        for(int pass=0;pass<2;pass++)for(int i=0;i<OFFSETS.length;i++)for(int j=0;j<OFFSETS.length;j++)for(double y:HEIGHTS){
            double x=OFFSETS[pass==0?i:OFFSETS.length-1-i],z=OFFSETS[pass==0?j:OFFSETS.length-1-j];var at=origin.add(.5+x,y,.5+z);
            feet(t,cache,at,label+" pass="+pass);if(MotaRoute.clear(t.getLevel(),at))clear++;else blocked++;
            if(i%3==0&&j%3==0){var b=feetBox(at);box(t,cache,new AABB(b.minX,b.minY,b.minZ,b.maxX,b.maxY+1,b.maxZ),label+" vertical");}
        }
        t.assertTrue(clear>0&&blocked>0,label+" samples include both genuinely clear and blocked physical positions");
    }
    @GameTest(structure=STRUCTURE)
    public void cachedShapesMatchVanillaAtFullPartialAndProtrudingBoundaries(GameTestHelper t){
        var fixtures=List.of(
                new Fixture("full stone",Blocks.STONE.defaultBlockState(),false),
                new Fixture("bottom slab",Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM),false),
                new Fixture("top slab",Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP),false),
                new Fixture("lower trapdoor",Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN,false).setValue(TrapDoorBlock.HALF,Half.BOTTOM),false),
                new Fixture("upper trapdoor",Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN,false).setValue(TrapDoorBlock.HALF,Half.TOP),false),
                new Fixture("open north trapdoor",Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN,true).setValue(TrapDoorBlock.FACING,Direction.NORTH),false),
                new Fixture("open east trapdoor",Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN,true).setValue(TrapDoorBlock.FACING,Direction.EAST),false),
                new Fixture("connected tall fence",Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.NORTH,true).setValue(FenceBlock.SOUTH,true).setValue(FenceBlock.WEST,true).setValue(FenceBlock.EAST,true),false),
                new Fixture("tall wall",Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(WallBlock.NORTH,WallSide.TALL).setValue(WallBlock.EAST,WallSide.LOW),false),
                new Fixture("piston head",Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING,Direction.UP),false),
                new Fixture("inset cactus",Blocks.CACTUS.defaultBlockState(),true),
                new Fixture("upward amethyst",Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING,Direction.UP),false),
                new Fixture("side amethyst",Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING,Direction.EAST),false));
        for(var fixture:fixtures){put(t,fixture);sample(t,fixture.name);}
        t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void cachedFluidChecksMatchVanillaForSourcesAndWaterloggedShapes(GameTestHelper t){
        var fixtures=List.of(new Fixture("source water",Blocks.WATER.defaultBlockState(),false),new Fixture("source lava",Blocks.LAVA.defaultBlockState(),false),
                new Fixture("waterlogged bottom slab",Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED,true),false),
                new Fixture("waterlogged fence",Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.WATERLOGGED,true),false),
                new Fixture("waterlogged amethyst",Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.WATERLOGGED,true),false));
        for(var fixture:fixtures){put(t,fixture);sample(t,fixture.name);}
        t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void cachedUnloadedCellsAndCollisionHaloNeverLoadMissingTerrain(GameTestHelper t){
        var level=t.getLevel();var edge=t.absolutePos(new BlockPos(5,100,4));int steps=0;
        while(level.isLoaded(edge)&&steps++<8192)edge=edge.east(16);
        edge=new BlockPos(edge.getX()&~15,edge.getY(),edge.getZ());
        t.assertFalse(level.isLoaded(edge),"The real boundary chunk starts unloaded");
        var positions=List.of(edge,edge.east(16),edge.west(16),edge.north(16),edge.south(16));var loaded=new ArrayList<Boolean>();for(var p:positions)loaded.add(level.isLoaded(p));
        var cache=Cached.create(level);var origin=Vec3.atLowerCornerOf(edge);
        for(int pass=0;pass<2;pass++)for(double x:new double[]{-2.1,-1.54,-1.1,-.540001,-.54,-.539999,-.01,0,.54,1.1,15.5,16.5})for(double z:new double[]{-.1,.5,1.1}){
            var at=origin.add(x,.15,z);feet(t,cache,at,"unloaded boundary pass="+pass);var b=feetBox(at);box(t,cache,new AABB(b.minX,b.minY,b.minZ,b.maxX,b.maxY+1,b.maxZ),"unloaded vertical");
        }
        for(int n=0;n<positions.size();n++)t.assertTrue(level.isLoaded(positions.get(n))==loaded.get(n),"Neither live nor cached differential checks changed chunk loading at "+positions.get(n));
        t.succeed();
    }
}
