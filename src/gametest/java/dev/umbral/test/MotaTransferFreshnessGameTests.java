// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;

/** Inventory transfers revalidate the real backbone even when same-tick telemetry remains cached. */
public final class MotaTransferFreshnessGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(1,1,5),RELAY=new BlockPos(25,1,5),CUT=new BlockPos(12,1,5),
            DOCK=new BlockPos(37,1,5),STOCK=new BlockPos(41,1,5),TARGET=new BlockPos(39,1,2);
    private record Setup(DeviceBlockEntity dock,DeviceBlockEntity target,ChestBlockEntity stock,MotaEntity drone){}
    private DeviceBlockEntity put(GameTestHelper t,BlockPos pos,DeviceBlock block,ServerPlayer owner){
        t.setBlock(pos,block);var d=t.getBlockEntity(pos,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;
    }
    private Setup setup(GameTestHelper t){
        for(int x=34;x<=44;x++)for(int z=0;z<=7;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==34||x==44||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var owner=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);
        var core=put(t,CORE,UmbralContent.CORE,owner);var relay=put(t,RELAY,UmbralContent.NODE,owner);
        var dock=put(t,DOCK,UmbralContent.DOCK,owner);var target=put(t,TARGET,UmbralContent.TURRET,owner);
        t.assertTrue(relay.bind(owner.getUUID(),core)&&dock.bind(owner.getUUID(),core)&&target.bind(owner.getUUID(),core),"Actual devices belong to the same owned installation");
        for(int x=2;x<25;x++)t.setBlock(new BlockPos(x,1,5),UmbralContent.CABLE);
        t.assertTrue(dock.getBlockPos().distSqr(core.getBlockPos())>16*16&&target.getBlockPos().distSqr(core.getBlockPos())>16*16,"Neither transfer endpoint can use the core's own wireless radius");
        t.assertTrue(NetworkService.inspectFresh(t.getLevel(),dock).online()&&NetworkService.inspect(t.getLevel(),target).online(),"A real wired relay covers the remote transfer endpoints");
        t.setBlock(STOCK,Blocks.CHEST);var stock=t.getBlockEntity(STOCK,ChestBlockEntity.class);stock.setItem(0,new ItemStack(Items.ARROW,40));
        owner.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(owner,stock.getBlockPos(),core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(owner,dock,seal)&&MotaService.install(owner,dock,new ItemStack(UmbralContent.MOTA)),"Authorized stock and one real courier are installed");
        var drone=MotaService.actor(t.getLevel(),dock);t.assertTrue(drone!=null&&MotaService.order(t.getLevel(),dock,target).equals("queued"),"The real courier accepts all three physical routes");
        t.assertTrue(stock.getItem(0).getCount()==40&&drone.cargo().isEmpty(),"Ordering does not withdraw or manufacture ammunition");
        return new Setup(dock,target,stock,drone);
    }
    private long warmAndCutWithoutCallbacks(GameTestHelper t,Setup i){
        t.assertTrue(NetworkService.inspectFresh(t.getLevel(),i.dock).online()&&NetworkService.inspect(t.getLevel(),i.target).online(),"Both endpoint readings are freshly cached before the same-tick cut");
        long scans=NetworkService.topologyScans(t.getLevel());var pos=t.absolutePos(CUT);
        var chunk=t.getLevel().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);t.assertTrue(chunk!=null,"The edited cable section is already loaded");
        // A bulk world editor may bypass neighbor/block callbacks and therefore cache invalidation.
        chunk.getSection(chunk.getSectionIndex(pos.getY())).setBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15,Blocks.AIR.defaultBlockState());
        t.assertTrue(t.getLevel().getBlockState(pos).isAir(),"The actual backbone cable has been removed");
        t.assertTrue(NetworkService.inspect(t.getLevel(),i.dock).online()&&NetworkService.inspect(t.getLevel(),i.target).online()&&NetworkService.topologyScans(t.getLevel())==scans,"Ordinary same-tick telemetry deliberately still trusts the old topology");
        return scans;
    }
    private int dropped(GameTestHelper t){return t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(t.absolutePos(new BlockPos(35,1,1))).expandTowards(9,5,6),e->e.getItem().is(Items.ARROW)).stream().mapToInt(e->e.getItem().getCount()).sum();}
    private void watchUntilHome(GameTestHelper t,Setup i,int remaining,Runnable completed){
        t.assertTrue(MotaRoute.clear(t.getLevel(),i.drone.position()),"Physical continuation stays in loaded, collision-free space");
        if(!i.drone.busy()){completed.run();return;}
        t.assertTrue(remaining>0,"The reconnected courier eventually returns home");t.runAfterDelay(1,()->watchUntilHome(t,i,remaining-1,completed));
    }
    @GameTest(structure=STRUCTURE,maxTicks=450)
    public void silentCutImmediatelyBeforePickupCannotWithdrawFromCachedCoverage(GameTestHelper t){
        var i=setup(t);i.drone.setPos(MotaRoute.approach(i.stock.getBlockPos()));
        long scans=warmAndCutWithoutCallbacks(t,i);i.drone.tick();
        t.assertTrue(NetworkService.topologyScans(t.getLevel())==scans+1,"The pickup performs one fresh scan of its actual backbone");
        t.assertTrue(i.drone.phase()==MotaEntity.Phase.TO_STOCK&&i.drone.condition().equals("paused")&&i.drone.cargo().isEmpty(),"Offline pickup pauses before removing any stock");
        t.assertTrue(i.stock.getItem(0).getCount()==40&&i.target.turret().count()==0&&dropped(t)==0,"A stale telemetry cache cannot authorize a withdrawal, deposit or dropped item");
        t.setBlock(CUT,UmbralContent.CABLE);
        watchUntilHome(t,i,400,()->{
            t.assertTrue(i.drone.cargo().isEmpty()&&i.drone.position().distanceToSqr(MotaRoute.approach(i.dock.getBlockPos()))<.012,"Reconnected pickup completes physically and returns home");
            t.assertTrue(i.stock.getItem(0).getCount()==8&&i.target.turret().count()==32&&dropped(t)==0&&!i.target.turret().armed(),"Exactly the original 40 arrows remain: eight in stock and 32 safely delivered");
            t.assertTrue(i.dock.dock().result().equals("complete")&&i.dock.dock().delivered()==32,"Only the later real delivery is reported");t.succeed();
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=450)
    public void silentCutImmediatelyBeforeDepositKeepsCargoForASafeReconnectedReturn(GameTestHelper t){
        var i=setup(t);i.drone.setPos(MotaRoute.approach(i.stock.getBlockPos()));i.drone.tick();
        t.assertTrue(i.drone.phase()==MotaEntity.Phase.TO_TARGET&&i.drone.cargo().is(Items.ARROW)&&i.drone.cargo().getCount()==32&&i.stock.getItem(0).getCount()==8,"Cargo was obtained exclusively by the real connected pickup");
        i.drone.setPos(MotaRoute.approach(i.target.getBlockPos()));long scans=warmAndCutWithoutCallbacks(t,i);i.drone.tick();
        t.assertTrue(NetworkService.topologyScans(t.getLevel())==scans+1,"The deposit refreshes its own backbone rather than accepting cached signal");
        t.assertTrue(i.drone.phase()==MotaEntity.Phase.TO_TARGET&&i.drone.condition().equals("paused")&&i.drone.cargo().getCount()==32,"Rejected deposit retains the complete actual cargo and pending phase");
        t.assertTrue(i.target.turret().count()==0&&i.stock.getItem(0).getCount()==8&&dropped(t)==0&&!i.target.turret().armed(),"A silent physical cut cannot deposit, discard or duplicate ammunition");
        i.drone.cancel();t.setBlock(CUT,UmbralContent.CABLE);
        watchUntilHome(t,i,400,()->{
            t.assertTrue(i.drone.cargo().isEmpty()&&i.drone.position().distanceToSqr(MotaRoute.approach(i.dock.getBlockPos()))<.012,"The cancelled courier physically returns to its actual dock after reconnection");
            t.assertTrue(i.stock.getItem(0).getCount()==40&&i.target.turret().count()==0&&dropped(t)==0,"All 32 carried arrows return to the original eight without any illegal deposit or loss");
            t.assertTrue(i.dock.dock().result().equals("cancelled")&&i.dock.dock().delivered()==0,"The dock records cancellation and zero delivered items");t.succeed();
        });
    }
}
