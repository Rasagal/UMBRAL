// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.logistics.*;
import dev.umbral.turret.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Persistent, explicit resupply policy without converting, losing or mixing physical items. */
public final class AmmoPreferenceGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(2,1,5),DOCK=new BlockPos(4,1,5),STOCK=new BlockPos(7,1,5),TARGET=new BlockPos(7,1,2);
    private record Setup(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity dock,DeviceBlockEntity target,ChestBlockEntity stock){}
    private DeviceBlockEntity put(GameTestHelper t,BlockPos p,DeviceBlock block,ServerPlayer owner){t.setBlock(p,block);var d=t.getBlockEntity(p,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;}
    private Setup setup(GameTestHelper t){
        for(int x=0;x<12;x++)for(int z=0;z<8;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var core=put(t,CORE,UmbralContent.CORE,p);var dock=put(t,DOCK,UmbralContent.DOCK,p);var target=put(t,TARGET,UmbralContent.CENTELLA,p);
        dock.bind(p.getUUID(),core);target.bind(p.getUUID(),core);t.setBlock(STOCK,Blocks.CHEST);var stock=t.getBlockEntity(STOCK,ChestBlockEntity.class);
        p.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(p,stock.getBlockPos(),core.link());
        t.assertTrue(seal!=null&&MotaService.bindStock(p,dock,seal),"The real reserve chest is authorized");
        t.assertTrue(MotaService.install(p,dock,new ItemStack(UmbralContent.MOTA)),"One real courier is installed");
        return new Setup(p,core,dock,target,stock);
    }
    private TurretState restore(GameTestHelper t,TurretState state,boolean legacy){
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,t.getLevel().registryAccess());state.save(out,0);var tag=out.buildResult();
        if(legacy){tag.remove("turret_ammo_preference");tag.remove("turret_ammo_preference_explicit");}
        var copy=new TurretState(true);copy.load(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),tag));return copy;
    }
    @GameTest(structure=STRUCTURE)
    public void lastGoldRoundKeepsItsPreferenceInsteadOfTakingFirstIron(GameTestHelper t){
        var s=new TurretState(true);s.load(new ItemStack(Items.GOLD_NUGGET),0);s.fired(0);
        var stock=new SimpleContainer(new ItemStack(Items.IRON_NUGGET,48),new ItemStack(Items.GOLD_NUGGET,40));var cargo=MotaService.withdraw(stock,s,32);
        t.assertTrue(s.count()==0&&s.preference()==TurretState.Preference.GOLD,"The last consumed round leaves a remembered gold preference");
        t.assertTrue(cargo.is(Items.GOLD_NUGGET)&&cargo.getCount()==32&&stock.getItem(0).getCount()==48&&stock.getItem(1).getCount()==8,"Resupply skips earlier iron and withdraws exactly the requested real gold");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void emptyPreferenceSurvivesSavingAndReloading(GameTestHelper t){
        var s=new TurretState(true);s.setPreference(TurretState.Preference.GOLD,0);var copy=restore(t,s,false);
        t.assertTrue(copy.count()==0&&copy.preference()==TurretState.Preference.GOLD&&copy.acceptsResupply(new ItemStack(Items.GOLD_NUGGET))&&!copy.acceptsResupply(new ItemStack(Items.IRON_NUGGET)),"An empty saved magazine retains gold, not an arbitrary reserve order");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void legacyLoadedMagazineLearnsAndKeepsItsActualMetal(GameTestHelper t){
        var legacy=new TurretState(true);legacy.load(new ItemStack(Items.GOLD_NUGGET),0);var copy=restore(t,legacy,true);copy.fired(1);
        t.assertTrue(copy.count()==0&&copy.preference()==TurretState.Preference.GOLD,"Old schema-1 saves without preference fields infer their real metal");
        t.assertTrue(restore(t,copy,false).preference()==TurretState.Preference.GOLD,"The migrated preference remains after another empty save");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void explicitAutomaticRemainsAutomaticAfterDeliveryAndReload(GameTestHelper t){
        var s=new TurretState(true);s.setPreference(TurretState.Preference.AUTOMATIC,0);
        var stock=new SimpleContainer(new ItemStack(Items.COPPER_NUGGET,3),new ItemStack(Items.GOLD_NUGGET,8));var cargo=MotaService.withdraw(stock,s,32);s.loadResupply(cargo,1);
        t.assertTrue(s.count()==3&&s.material()==Metal.COPPER&&s.preference()==TurretState.Preference.AUTOMATIC,"Choosing Automatic explicitly permits the first compatible type without changing that choice");
        t.assertTrue(restore(t,s,false).preference()==TurretState.Preference.AUTOMATIC,"Explicit Automatic persists");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void manualOverrideNeverConvertsExistingItemsOrChangesExplicitPreference(GameTestHelper t){
        var s=new TurretState(true);s.setPreference(TurretState.Preference.GOLD,0);var iron=new ItemStack(Items.IRON_NUGGET,4);t.assertTrue(s.load(iron,1)==4&&iron.isEmpty(),"Manual loading remains an explicit player choice");
        var reserve=new SimpleContainer(new ItemStack(Items.GOLD_NUGGET,40),new ItemStack(Items.IRON_NUGGET,40));var cargo=MotaService.withdraw(reserve,s,32);
        t.assertTrue(s.preference()==TurretState.Preference.GOLD&&s.material()==Metal.IRON&&s.resupplyMismatch()&&cargo.isEmpty(),"MOTA waits rather than mixing metals or overwriting the chosen preference");
        var unloaded=s.unload(2);t.assertTrue(unloaded.is(Items.IRON_NUGGET)&&unloaded.getCount()==4,"Unloading returns the unchanged actual iron");
        cargo=MotaService.withdraw(reserve,s,32);t.assertTrue(s.loadResupply(cargo,3)==32&&cargo.isEmpty()&&s.material()==Metal.GOLD&&!s.armed(),"The empty magazine accepts actual gold without implicit arming");
        t.assertTrue(reserve.getItem(0).getCount()==8&&reserve.getItem(1).getCount()==40,"No reserve items were converted or removed incorrectly");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void matchingMetalStillRespectsDifferentItemComponents(GameTestHelper t){
        var s=new TurretState(true);s.setPreference(TurretState.Preference.GOLD,0);var loaded=new ItemStack(Items.GOLD_NUGGET,1);loaded.set(DataComponents.CUSTOM_NAME,Component.literal("Lote A"));s.load(loaded,0);
        var other=new ItemStack(Items.GOLD_NUGGET,40);other.set(DataComponents.CUSTOM_NAME,Component.literal("Lote B"));var stock=new SimpleContainer(other);
        t.assertTrue(MotaService.withdraw(stock,s,32).isEmpty()&&stock.getItem(0).getCount()==40&&s.count()==1,"A metal preference never merges incompatible stack components");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void arrowTurretsKeepTheirExistingSingleAmmunitionPolicy(GameTestHelper t){
        var s=new TurretState();t.assertFalse(s.setPreference(TurretState.Preference.GOLD,0),"The ballista has no metal selector");
        t.assertTrue(s.preference()==TurretState.Preference.AUTOMATIC&&s.acceptsResupply(new ItemStack(Items.ARROW))&&!s.acceptsResupply(new ItemStack(Items.GOLD_NUGGET)),"Arrow-only resupply is unchanged");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void preferenceProtocolRejectsForeignDistantAndReplacedTargets(GameTestHelper t){
        var i=setup(t);i.owner.setPos(Vec3.atCenterOf(i.target.getBlockPos()));var r=new TurretProtocol.Request(i.target.getBlockPos(),i.target.identity(),i.target.link(),91,TurretProtocol.Operation.PREFERENCE);
        var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(i.owner.position());t.assertFalse(TurretProtocol.handle(guest,r).valid(),"A visitor cannot choose another player's ammunition");
        i.owner.setPos(i.owner.position().add(20,0,0));t.assertFalse(TurretProtocol.handle(i.owner,r).valid(),"A stale distant local console is rejected");
        i.owner.setPos(Vec3.atCenterOf(i.target.getBlockPos()));t.assertTrue(TurretProtocol.handle(i.owner,r).preference()==TurretState.Preference.COPPER,"The owner changes the bounded preference enum");
        t.setBlock(TARGET,Blocks.AIR);var replacement=put(t,TARGET,UmbralContent.CENTELLA,i.owner);replacement.bind(i.owner.getUUID(),i.core);
        t.assertFalse(TurretProtocol.handle(i.owner,r).valid(),"The old physical identity cannot change its replacement");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void realResupplyPlanUsesPreferredGoldAndExplainsManualMismatch(GameTestHelper t){
        var i=setup(t);i.stock.setItem(0,new ItemStack(Items.IRON_NUGGET,48));i.stock.setItem(1,new ItemStack(Items.GOLD_NUGGET,40));i.target.turret().setPreference(TurretState.Preference.GOLD,0);
        t.assertTrue(MotaService.plan(t.getLevel(),i.target).count()==32,"The real courier plan finds gold behind the first iron stack");
        i.target.turret().load(new ItemStack(Items.IRON_NUGGET),0);
        t.assertTrue(MotaService.plan(t.getLevel(),i.target).state().equals("ammo_mismatch")&&MotaService.order(t.getLevel(),i.dock,i.target).equals("ammo_mismatch"),"Incompatible manual ammunition produces a specific diagnosis before a job is queued");
        t.assertTrue(i.stock.getItem(0).getCount()==48&&i.stock.getItem(1).getCount()==40,"A rejected plan never reserves or withdraws items");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void changingPreferenceAfterPickupPhysicallyReturnsUnwantedCargo(GameTestHelper t){
        var i=setup(t);i.stock.setItem(0,new ItemStack(Items.IRON_NUGGET,48));i.stock.setItem(1,new ItemStack(Items.GOLD_NUGGET,40));i.target.turret().setPreference(TurretState.Preference.GOLD,0);
        var drone=MotaService.actor(t.getLevel(),i.dock);t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"A valid gold delivery starts");
        t.startSequence().thenWaitUntil(()->t.assertTrue(drone.carrying(),"Waiting for physical gold pickup")).thenExecute(()->{
            t.assertTrue(drone.cargo().is(Items.GOLD_NUGGET)&&drone.cargo().getCount()==32,"Actual gold is in flight");i.target.turret().setPreference(TurretState.Preference.IRON,t.getLevel().getGameTime());
            t.runAfterDelay(180,()->{t.assertTrue(!drone.busy()&&drone.cargo().isEmpty()&&i.target.turret().count()==0&&!i.target.turret().armed(),"Changed preference does not load or arm the destination");
                t.assertTrue(i.stock.getItem(0).getCount()==48&&i.stock.getItem(1).getCount()==40&&i.dock.dock().result().equals("ammo_mismatch"),"Every unwanted gold nugget returns physically, with a precise result");t.succeed();});
        });
    }
}
