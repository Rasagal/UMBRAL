// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;

/** A fractional resumed position needs a checked entrance even when its goal shares the same cell. */
public final class MotaSameCellRouteGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(1,1,5),RELAY=new BlockPos(25,1,5),CUT=new BlockPos(12,1,5),
            DOCK=new BlockPos(37,1,5),STOCK=new BlockPos(41,1,5),TARGET=new BlockPos(39,1,2),BUD=new BlockPos(38,2,5);
    private record Setup(DeviceBlockEntity dock,DeviceBlockEntity target,ChestBlockEntity stock,MotaEntity drone){}

    private DeviceBlockEntity put(GameTestHelper t,BlockPos pos,DeviceBlock block,ServerPlayer owner){
        t.setBlock(pos,block);var d=t.getBlockEntity(pos,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;
    }
    private Setup setup(GameTestHelper t){
        for(int x=34;x<=44;x++)for(int z=0;z<=7;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==34||x==44||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var core=put(t,CORE,UmbralContent.CORE,p);var relay=put(t,RELAY,UmbralContent.NODE,p);
        var dock=put(t,DOCK,UmbralContent.DOCK,p);var target=put(t,TARGET,UmbralContent.TURRET,p);
        t.assertTrue(relay.bind(p.getUUID(),core)&&dock.bind(p.getUUID(),core)&&target.bind(p.getUUID(),core),"Real wired relay covers the owned remote endpoints");
        for(int x=2;x<25;x++)t.setBlock(new BlockPos(x,1,5),UmbralContent.CABLE);
        t.setBlock(STOCK,Blocks.CHEST);var stock=t.getBlockEntity(STOCK,ChestBlockEntity.class);stock.setItem(0,new ItemStack(Items.ARROW,40));
        p.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(p,stock.getBlockPos(),core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(p,dock,seal)&&MotaService.install(p,dock,new ItemStack(UmbralContent.MOTA)),"Authorized stock and one actual courier are installed");
        var drone=MotaService.actor(t.getLevel(),dock);
        t.assertTrue(drone!=null&&MotaService.order(t.getLevel(),dock,target).equals("queued"),"The real courier receives current pickup, delivery and home routes");
        return new Setup(dock,target,stock,drone);
    }
    private void watchHome(GameTestHelper t,Setup i,int remaining){
        t.assertTrue(MotaRoute.clear(t.getLevel(),i.drone().position()),"Every observed body position stays outside the hanging bud and solid support");
        if(i.drone().busy()){
            t.assertTrue(remaining>0,"A same-cell return cannot repeat the blocked direct route forever");
            t.runAfterDelay(1,()->watchHome(t,i,remaining-1));return;
        }
        t.assertTrue(i.drone().position().distanceToSqr(MotaRoute.approach(i.dock().getBlockPos()))<.012&&i.drone().cargo().isEmpty(),"Resumed courier physically reaches its actual dock");
        t.assertTrue(i.drone().routeSearches()<=2,"The same-cell corner is resolved without a replan on every tick");
        t.assertTrue(i.stock().getItem(0).getCount()==8&&i.target().turret().count()==32&&!i.target().turret().armed(),"Real pickup and delivery conserve all 40 arrows and never arm the target");
        var drops=t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(t.absolutePos(new BlockPos(35,1,1))).expandTowards(9,5,6),e->e.getItem().is(Items.ARROW));
        t.assertTrue(drops.isEmpty()&&i.dock().dock().result().equals("complete")&&i.dock().dock().delivered()==32,"Recovery creates no dropped cargo and preserves the completed delivery report");
        t.assertTrue(t.getBlockState(BUD).is(Blocks.SMALL_AMETHYST_BUD),"The actual corner obstacle remains in place");t.succeed();
    }

    @GameTest(structure=STRUCTURE,maxTicks=90) public void completedDeliveryResumesItsSameCellHomeReturnAroundAHangingBud(GameTestHelper t){
        var i=setup(t);
        i.drone().setPos(MotaRoute.approach(i.stock().getBlockPos()));i.drone().tick();
        t.assertTrue(i.drone().phase()==MotaEntity.Phase.TO_TARGET&&i.drone().cargo().getCount()==32&&i.stock().getItem(0).getCount()==8,"Actual connected pickup withdraws exactly 32 arrows");
        i.drone().setPos(MotaRoute.approach(i.target().getBlockPos()));i.drone().tick();
        t.assertTrue(i.drone().phase()==MotaEntity.Phase.RETURN_HOME&&i.drone().cargo().isEmpty()&&i.target().turret().count()==32,"Actual connected deposit starts the empty home return");

        t.setBlock(BUD.above(),Blocks.STONE);
        t.setBlock(BUD,Blocks.SMALL_AMETHYST_BUD.defaultBlockState().setValue(AmethystClusterBlock.FACING,Direction.DOWN));
        Vec3 start=Vec3.atLowerCornerOf(t.absolutePos(DOCK.above())).add(.98,.15,.5),goal=MotaRoute.approach(i.dock().getBlockPos());
        i.drone().setPos(start);
        t.assertTrue(BlockPos.containing(start).equals(BlockPos.containing(goal))&&MotaRoute.clear(t.getLevel(),start)&&MotaRoute.clear(t.getLevel(),goal),"Both clear endpoints share a grid cell while the drone is fractionally along its return");
        Vec3 direct=goal.subtract(start).normalize().scale(.12);
        t.assertFalse(MotaRoute.clear(t.getLevel(),start.add(direct)),"The first direct diagonal step hits the real downward bud despite both endpoints being clear");
        t.setBlock(CUT,Blocks.AIR);i.drone().tick();
        t.assertTrue(i.drone().condition().equals("paused")&&i.drone().position().equals(start),"A real signal outage pauses the actor and discards obsolete route hints");
        t.setBlock(CUT,UmbralContent.CABLE);
        t.assertTrue(NetworkService.inspectFresh(t.getLevel(),i.dock()).online(),"Repair restores the original wired relay connection");
        watchHome(t,i,40);
    }
}
