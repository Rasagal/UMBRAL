// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import dev.umbral.turret.TurretState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Brief shared advice never authorizes a transfer or survives a relevant installation mutation. */
public final class MotaAdvisoryGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(2,1,5),DOCK=new BlockPos(4,1,5),STOCK=new BlockPos(7,1,5),TARGET=new BlockPos(7,1,2),SECOND=new BlockPos(9,1,2);
    private static final class CountingChest extends ChestBlockEntity {
        int reads;
        CountingChest(BlockPos pos,BlockState state){super(pos,state);}
        @Override public ItemStack getItem(int slot){reads++;return super.getItem(slot);}
    }
    private record Setup(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity dock,DeviceBlockEntity target,DeviceBlockEntity second,CountingChest stock){}
    private DeviceBlockEntity put(GameTestHelper t,BlockPos p,DeviceBlock block,ServerPlayer owner){t.setBlock(p,block);var d=t.getBlockEntity(p,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;}
    private Setup setup(GameTestHelper t){
        for(int x=0;x<12;x++)for(int z=0;z<8;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var core=put(t,CORE,UmbralContent.CORE,p);var dock=put(t,DOCK,UmbralContent.DOCK,p);var target=put(t,TARGET,UmbralContent.CENTELLA,p);var second=put(t,SECOND,UmbralContent.CENTELLA,p);
        dock.bind(p.getUUID(),core);target.bind(p.getUUID(),core);second.bind(p.getUUID(),core);target.turret().setPreference(TurretState.Preference.GOLD,0);second.turret().setPreference(TurretState.Preference.GOLD,0);
        t.setBlock(STOCK,Blocks.CHEST);var at=t.absolutePos(STOCK);var stock=new CountingChest(at,t.getLevel().getBlockState(at));t.getLevel().setBlockEntity(stock);
        p.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(p,stock.getBlockPos(),core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(p,dock,seal)&&MotaService.install(p,dock,new ItemStack(UmbralContent.MOTA)),"The fixture has a physically authorized chest and one real courier");return new Setup(p,core,dock,target,second,stock);
    }
    @GameTest(structure=STRUCTURE) public void usefulStockScanStopsAtThirtyTwoAndIsSharedByMatchingTurrets(GameTestHelper t){
        var s=setup(t);s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,64));s.stock.setItem(26,new ItemStack(Items.IRON_NUGGET,64));s.stock.reads=0;
        t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==32&&s.stock.reads==1,"A sufficient first slot ends the advisory inventory scan immediately");
        t.assertTrue(MotaService.plan(t.getLevel(),s.second).count()==32&&MotaService.plan(t.getLevel(),s.target).count()==32&&s.stock.reads==1,"Matching turret advice and repeated monitors reuse the same bounded stock reading in this tick");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void cachedPositiveAdviceCannotQueueAfterExternalStockRemoval(GameTestHelper t){
        var s=setup(t);s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,40));t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==32,"Positive advice is initially real");s.stock.setItem(0,ItemStack.EMPTY);
        t.assertTrue(MotaService.order(t.getLevel(),s.dock,s.target).equals("no_stock"),"The actual command ignores cached advice and freshly checks the physical chest");
        t.assertTrue(!MotaService.actor(t.getLevel(),s.dock).busy()&&s.target.turret().count()==0&&!s.target.turret().armed()&&MotaService.plan(t.getLevel(),s.target).count()==0,"A rejected order creates no job, ammunition or implicit arming and refreshes advice");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void externalStockAdditionsAreVisibleOnTheNextTick(GameTestHelper t){
        var s=setup(t);t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==0,"An empty physical chest initially offers no ammunition");s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,7));
        t.runAfterDelay(1,()->{t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==7&&s.stock.getItem(0).getCount()==7&&s.target.turret().count()==0,"The next-tick advisory refresh sees outside inventory changes without moving items");t.succeed();});
    }
    @GameTest(structure=STRUCTURE) public void physicalWithdrawalInvalidatesAdviceAndConservesItems(GameTestHelper t){
        var s=setup(t);s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,40));t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==32,"A real stock reading is cached");var cargo=MotaService.withdraw(s.stock,s.target.turret(),32);
        t.assertTrue(cargo.is(Items.GOLD_NUGGET)&&cargo.getCount()==32&&s.stock.getItem(0).getCount()==8&&MotaService.plan(t.getLevel(),s.target).count()==8,"A physical transfer invalidates the reading immediately and preserves all forty real nuggets");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void changingStockBindingAndRecoveringUnitInvalidatesSameTickAdvice(GameTestHelper t){
        var s=setup(t);s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,40));t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==32,"The first authorized reserve is ready");
        var other=new BlockPos(7,1,3);t.setBlock(other,Blocks.CHEST);var chest=t.getBlockEntity(other,ChestBlockEntity.class);chest.setItem(0,new ItemStack(Items.GOLD_NUGGET,5));var seal=MotaService.seal(s.owner,chest.getBlockPos(),s.core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(s.owner,s.dock,seal)&&MotaService.plan(t.getLevel(),s.target).count()==5,"Explicitly rebinding a reserve immediately changes the advised physical quantity");
        t.assertTrue(MotaService.recover(s.owner,s.dock)&&MotaService.plan(t.getLevel(),s.target).state().equals("no_unit"),"Recovering the real idle courier immediately removes stale ready advice");t.assertTrue(s.stock.getItem(0).getCount()==40&&chest.getItem(0).getCount()==5,"Neither advice nor recovery touches reserve ammunition");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void preferenceAndPhysicalReplacementCannotReuseAnotherTargetPlan(GameTestHelper t){
        var s=setup(t);s.stock.setItem(0,new ItemStack(Items.GOLD_NUGGET,4));s.stock.setItem(1,new ItemStack(Items.IRON_NUGGET,12));t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==4,"Gold advice starts with the selected type");
        s.target.turret().setPreference(TurretState.Preference.IRON,0);t.assertTrue(MotaService.plan(t.getLevel(),s.target).count()==12,"The changed turret revision selects another stock pattern immediately");
        var old=s.target;t.setBlock(TARGET,Blocks.AIR);var replacement=put(t,TARGET,UmbralContent.CENTELLA,s.owner);replacement.bind(s.owner.getUUID(),s.core);replacement.turret().setPreference(TurretState.Preference.GOLD,0);
        t.assertTrue(MotaService.plan(t.getLevel(),replacement).count()==4&&MotaService.order(t.getLevel(),s.dock,old).equals("offline"),"A new physical identity gets its own advice and an old reference cannot launch a job");t.assertTrue(!MotaService.actor(t.getLevel(),s.dock).busy()&&replacement.turret().count()==0,"No stale target plan creates a transfer");t.succeed();
    }
}
