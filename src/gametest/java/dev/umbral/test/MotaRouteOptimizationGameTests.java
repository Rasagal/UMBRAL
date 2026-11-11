// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** Reused route hints must still follow the current world and conserve physical cargo. */
public final class MotaRouteOptimizationGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(2,1,5),DOCK=new BlockPos(3,1,5),STOCK=new BlockPos(7,1,5),TARGET=new BlockPos(7,1,2);
    private record Setup(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity dock,DeviceBlockEntity target,ChestBlockEntity stock,MotaEntity drone){}
    private DeviceBlockEntity put(GameTestHelper t,BlockPos p,DeviceBlock b,ServerPlayer owner){t.setBlock(p,b);var d=t.getBlockEntity(p,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;}
    private Setup setup(GameTestHelper t){
        for(int x=0;x<12;x++)for(int z=0;z<8;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var core=put(t,CORE,UmbralContent.CORE,p);var dock=put(t,DOCK,UmbralContent.DOCK,p);var target=put(t,TARGET,UmbralContent.TURRET,p);
        t.assertTrue(dock.bind(p.getUUID(),core)&&target.bind(p.getUUID(),core),"The real endpoints share their authorized installation");
        t.setBlock(STOCK,Blocks.CHEST);var stock=t.getBlockEntity(STOCK,ChestBlockEntity.class);stock.setItem(0,new ItemStack(Items.ARROW,40));
        p.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(p,stock.getBlockPos(),core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(p,dock,seal)&&MotaService.install(p,dock,new ItemStack(UmbralContent.MOTA)),"The physical stock and one courier are installed");
        var drone=MotaService.actor(t.getLevel(),dock);t.assertTrue(drone!=null,"A real courier exists");return new Setup(p,core,dock,target,stock,drone);
    }
    private int dropped(GameTestHelper t){return t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(t.absolutePos(new BlockPos(1,1,1))).expandTowards(10,5,6),e->e.getItem().is(Items.ARROW)).stream().mapToInt(e->e.getItem().getCount()).sum();}
    private void wall(GameTestHelper t,boolean present){for(int x=1;x<=10;x++)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,3),present?Blocks.STONE:Blocks.AIR);}
    private void watchUntilHome(GameTestHelper t,MotaEntity drone,int remaining,Runnable finished){
        t.assertTrue(MotaRoute.clear(t.getLevel(),drone.position()),"Every observed physical position stays in loaded, dry collision-free space");
        if(!drone.busy()){finished.run();return;}
        t.assertTrue(remaining>0,"The actual courier returns home within the regression window");t.runAfterDelay(1,()->watchUntilHome(t,drone,remaining-1,finished));
    }
    private void assertDelivery(GameTestHelper t,Setup i,MotaEntity drone){
        t.assertTrue(drone.cargo().isEmpty()&&drone.position().distanceToSqr(MotaRoute.approach(i.dock.getBlockPos()))<.012,"Delivery finishes with an empty courier physically at its dock");
        t.assertTrue(i.target.turret().count()==32&&i.stock.getItem(0).getCount()==8&&dropped(t)==0&&!i.target.turret().armed(),"Exactly 32 arrows moved, none dropped, duplicated or implicitly armed");
        t.assertTrue(i.dock.dock().result().equals("complete")&&i.dock.dock().delivered()==32,"The dock reports the actual accepted amount");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=550)
    public void obstacleAddedAfterPreflightIsReplannedWithoutCrossingIt(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"All three preflight legs are accepted before the obstacle exists");
        t.assertTrue(i.stock.getItem(0).getCount()==40,"A preflight route never reserves stock");
        t.setBlock(new BlockPos(5,2,5),Blocks.STONE);
        watchUntilHome(t,i.drone,480,()->assertDelivery(t,i,i.drone));
    }
    @GameTest(structure=STRUCTURE,maxTicks=550)
    public void tightInsetCornerIsTraversedWithoutSkippingTheCheckedAxisTurn(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"The preflight path precedes the new tight corner");
        t.setBlock(new BlockPos(5,-1,5),Blocks.STONE);t.setBlock(new BlockPos(5,0,5),Blocks.SAND);
        for(int y=1;y<5;y++)t.setBlock(new BlockPos(5,y,5),Blocks.CACTUS);
        t.assertTrue(MotaRoute.clear(t.getLevel(),Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(4,2,5))).add(.5,.15,.5)),"The real inset cactus leaves a narrow valid axis clearance");
        watchUntilHome(t,i.drone,480,()->{
            t.assertTrue(t.getBlockState(new BlockPos(5,2,5)).is(Blocks.CACTUS),"The tight physical corner remains present throughout traversal");
            assertDelivery(t,i,i.drone);
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=650)
    public void blockedCarriedDeliveryWaitsAndResumesAfterTheWorldChanges(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"The initial delivery has a current route");
        t.startSequence().thenWaitUntil(()->t.assertTrue(i.drone.carrying(),"Waiting for actual physical pickup")).thenExecute(()->{
            wall(t,true);t.runAfterDelay(65,()->{
                t.assertTrue(i.drone.busy()&&i.drone.cargo().is(Items.ARROW)&&i.drone.cargo().getCount()==32&&i.target.turret().count()==0,"The newly sealed passage cannot be crossed using a stale preflight route");
                t.assertTrue(i.drone.condition().equals("blocked")&&MotaRoute.clear(t.getLevel(),i.drone.position())&&i.stock.getItem(0).getCount()==8&&dropped(t)==0,"Blocked navigation retains every carried arrow safely");
                wall(t,false);watchUntilHome(t,i.drone,480,()->assertDelivery(t,i,i.drone));
            });
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=550)
    public void cancellationDiscardsTheForwardHintsAndReturnsCargoAroundAnObstacle(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"The outbound routes are prevalidated");
        t.startSequence().thenWaitUntil(()->t.assertTrue(i.drone.carrying(),"Waiting for actual physical pickup")).thenExecute(()->{
            for(int y=1;y<5;y++)t.setBlock(new BlockPos(5,y,5),Blocks.STONE);i.drone.cancel();
            watchUntilHome(t,i.drone,430,()->{
                t.assertTrue(i.drone.cargo().isEmpty()&&i.target.turret().count()==0&&i.stock.getItem(0).getCount()==40&&dropped(t)==0,"Cancellation returns the actual cargo instead of following a cached delivery leg");
                t.assertTrue(i.drone.position().distanceToSqr(MotaRoute.approach(i.dock.getBlockPos()))<.012&&i.dock.dock().result().equals("cancelled"),"The courier safely navigates the changed home leg and records cancellation");t.succeed();
            });
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=550)
    public void reloadRebuildsNavigationFromSavedCargoAgainstTheCurrentWorld(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"The original actor holds valid route hints");
        t.startSequence().thenWaitUntil(()->t.assertTrue(i.drone.carrying(),"Waiting for actual physical pickup")).thenExecute(()->{
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,t.getLevel().registryAccess());i.drone.save(out);
            var restored=new MotaEntity(UmbralContent.MOTA_ENTITY,t.getLevel());restored.load(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),out.buildResult()));
            t.assertTrue(restored.getUUID().equals(i.drone.getUUID())&&restored.cargo().getCount()==32,"Save preserves the installed identity and exactly the real cargo");
            i.drone.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);for(int z=2;z<=5;z++)t.setBlock(new BlockPos(5,2,z),Blocks.STONE);
            t.assertTrue(!MotaService.install(i.owner,i.dock,new ItemStack(UmbralContent.MOTA))&&dropped(t)==0,"Unloading does not duplicate the installed unit or its cargo");
            t.runAfterDelay(2,()->{t.assertTrue(t.getLevel().addFreshEntity(restored),"The saved actor returns to the real level");watchUntilHome(t,restored,450,()->assertDelivery(t,i,restored));});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=400)
    public void cachedPreflightCannotAuthorizeAReplacementAtTheSameCoordinates(GameTestHelper t){
        var i=setup(t);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"The old physical target has accepted route hints");
        t.setBlock(TARGET,Blocks.AIR);var replacement=put(t,TARGET,UmbralContent.TURRET,i.owner);t.assertTrue(replacement.bind(i.owner.getUUID(),i.core),"The replacement is independently bound");
        watchUntilHome(t,i.drone,330,()->{
            t.assertTrue(replacement.turret().count()==0&&i.stock.getItem(0).getCount()==40&&i.drone.cargo().isEmpty()&&dropped(t)==0,"Reused navigation never substitutes an endpoint identity or withdraws for it");
            t.assertTrue(i.dock.dock().result().equals("target_changed"),"The stale endpoint is reported precisely");t.succeed();
        });
    }
}
