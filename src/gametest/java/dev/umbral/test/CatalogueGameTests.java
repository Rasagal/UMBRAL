// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.access.AccessRegistry;
import dev.umbral.block.*;
import dev.umbral.installation.*;
import dev.umbral.logistics.*;
import dev.umbral.safety.*;
import dev.umbral.turret.*;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Real server circuits for the new catalogue, including physical transport and interruption. */
public final class CatalogueGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(2,1,5),DOCK=new BlockPos(4,1,5),STOCK=new BlockPos(7,1,5),TARGET=new BlockPos(7,1,2);
    private record Setup(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity dock,DeviceBlockEntity target,ChestBlockEntity stock){}
    private DeviceBlockEntity place(GameTestHelper t,BlockPos p,DeviceBlock b,ServerPlayer owner){t.setBlock(p,b);var d=t.getBlockEntity(p,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),b==UmbralContent.TURRET?"Patio":"Taller");return d;}
    private Setup setup(GameTestHelper t,boolean metal){
        for(int x=0;x<12;x++)for(int z=0;z<8;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var owner=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var core=place(t,CORE,UmbralContent.CORE,owner);var dock=place(t,DOCK,UmbralContent.DOCK,owner);var target=place(t,TARGET,metal?UmbralContent.CENTELLA:UmbralContent.TURRET,owner);
        dock.bind(owner.getUUID(),core);target.bind(owner.getUUID(),core);t.setBlock(STOCK,Blocks.CHEST);var stock=t.getBlockEntity(STOCK,ChestBlockEntity.class);
        owner.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(owner,stock.getBlockPos(),core.link());t.assertTrue(seal!=null&&MotaService.bindStock(owner,dock,seal),"Authorized vanilla chest is bound");
        return new Setup(owner,core,dock,target,stock);
    }
    private NexoProtocol.Request request(DeviceBlockEntity root,DeviceBlockEntity target,NexoProtocol.Operation op){return new NexoProtocol.Request(root.getBlockPos(),root.identity(),root.link(),91,op,target.getBlockPos(),target.identity());}
    private MotaEntity install(GameTestHelper t,Setup i){var item=new ItemStack(UmbralContent.MOTA);t.assertTrue(MotaService.install(i.owner,i.dock,item)&&item.isEmpty(),"One actual crafted unit is consumed");var drone=MotaService.actor(t.getLevel(),i.dock);t.assertTrue(drone!=null,"Physical courier exists");return drone;}
    private int dropped(GameTestHelper t,Item item){return t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(1,1,1))),Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(11,6,7)))),e->e.getItem().is(item)).stream().mapToInt(e->e.getItem().getCount()).sum();}
    private void afterPickup(GameTestHelper t,MotaEntity drone,Runnable action){t.startSequence().thenWaitUntil(()->t.assertTrue(drone.carrying(),"Waiting for actual pickup")).thenExecute(action);}
    @GameTest(structure=STRUCTURE)
    public void centellaTransfersEachMetalWithoutMixingOrImplicitArming(GameTestHelper t){
        var i=setup(t,true);for(var metal:List.of(Metal.COPPER,Metal.IRON,Metal.GOLD)){
            var input=new ItemStack(metal.item,7);input.set(DataComponents.CUSTOM_NAME,Component.literal("Reserva"));t.assertTrue(i.target.turret().load(input,0)==7&&input.isEmpty(),"Real feedstock transferred");
            t.assertTrue(i.target.turret().material()==metal&&!i.target.turret().armed(),"Material matches, never implicitly armed");
            var other=new ItemStack(metal==Metal.GOLD?Items.IRON_NUGGET:Items.GOLD_NUGGET,4);t.assertTrue(i.target.turret().load(other,0)==0&&other.getCount()==4,"Mixed magazine refused without consuming");
            var returned=i.target.turret().unload(0);t.assertTrue(returned.getCount()==7&&returned.getHoverName().getString().equals("Reserva"),"Components and count returned");
        }t.assertTrue(i.target.turret().load(new ItemStack(Items.ARROW,5),0)==0,"CENTELLA excludes arrows");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void metalDamageCadenceAndFinalRoundCooldownPersist(GameTestHelper t){
        var i=setup(t,true);double previous=0;for(var m:List.of(Metal.COPPER,Metal.IRON,Metal.GOLD)){
            var s=i.target.turret();s.load(new ItemStack(m.item),0);var bolt=new NuggetBolt(UmbralContent.NUGGET_BOLT,t.getLevel());bolt.prepare(i.owner,new ItemStack(m.item),Vec3.atCenterOf(i.target.getBlockPos()),new Vec3(0,0,-1.8));
            var boltSave=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,t.getLevel().registryAccess());bolt.save(boltSave);double damage=boltSave.buildResult().getDoubleOr("damage",0);
            t.assertTrue(damage>previous,"Actual projectile damage increases with material");previous=damage;s.fired(0);
            var data=i.target.saveWithoutMetadata(t.getLevel().registryAccess());var copy=new DeviceBlockEntity(i.target.getBlockPos(),i.target.getBlockState());copy.setLevel(t.getLevel());copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),data));
            t.assertTrue(copy.turret().cooldown()==m.reload&&copy.turret().count()==0,"Last-round cadence survives even when magazine becomes empty");
        }t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void aGoldNuggetPhysicallyHitsAMonster(GameTestHelper t){
        var i=setup(t,true);var mob=t.spawn(EntityTypes.HUSK,new BlockPos(7,1,0));mob.setNoAi(true);t.setBlock(new BlockPos(7,1,0),Blocks.AIR);t.setBlock(new BlockPos(7,2,0),Blocks.AIR);
        i.target.turret().load(new ItemStack(Items.GOLD_NUGGET,3),0);i.target.turret().setArmed(true,t.getLevel().getGameTime());
        t.runAfterDelay(25,()->{t.assertTrue(mob.getHealth()<mob.getMaxHealth(),"A real nugget damages the hostile");t.assertTrue(i.target.turret().count()==2,"One gold round consumed before its reload");t.succeed();});
    }
    @GameTest(structure=STRUCTURE)
    public void nexoCommandsValidateOwnerDistanceNetworkAndPhysicalIdentity(GameTestHelper t){
        var i=setup(t,false);var cmd=request(i.core,i.target,NexoProtocol.Operation.ARM);i.target.turret().load(new ItemStack(Items.ARROW,10),0);
        var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(i.owner.position());t.assertFalse(NexoProtocol.handle(guest,cmd).valid(),"Foreign root rejected");
        i.owner.setPos(i.owner.position().add(20,0,0));t.assertFalse(NexoProtocol.handle(i.owner,cmd).valid(),"Out of reach root rejected");i.owner.setPos(Vec3.atCenterOf(i.core.getBlockPos()));
        t.assertTrue(NexoProtocol.handle(i.owner,cmd).result().equals("armed"),"Owner can issue authorized remote command");
        t.setBlock(TARGET,Blocks.AIR);var next=place(t,TARGET,UmbralContent.TURRET,i.owner);next.bind(i.owner.getUUID(),i.core);next.turret().load(new ItemStack(Items.ARROW,4),0);
        t.assertTrue(NexoProtocol.handle(i.owner,cmd).result().equals("invalid")&&!next.turret().armed(),"Old target identity cannot arm its replacement");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void nexoReportsActualLowMagazineAndDoesNotTeleportPlayerInventory(GameTestHelper t){
        var i=setup(t,false);i.target.turret().load(new ItemStack(Items.ARROW,5),0);i.owner.getInventory().setItem(0,new ItemStack(Items.REDSTONE,16));
        var snapshot=NexoProtocol.snapshot(i.owner,i.core,0,false,"ready");var row=snapshot.rows().stream().filter(r->r.identity().equals(i.target.identity())).findFirst().orElseThrow();
        t.assertTrue(row.amount()==5&&row.reading()==NexoProtocol.Reading.LOW_AMMO,"Telemetry uses real inventory");
        var mine=place(t,new BlockPos(5,1,2),UmbralContent.PULSE_MINE,i.owner);mine.bind(i.owner.getUUID(),i.core);
        t.assertTrue(NexoProtocol.handle(i.owner,request(i.core,mine,NexoProtocol.Operation.LOAD)).result().equals("invalid")&&mine.safety().count()==0,"A remote interface cannot teleport items from the player's inventory");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void sensorRecognizesOwnerAndRevocableBearerPasses(GameTestHelper t){
        var i=setup(t,false);var sensor=place(t,new BlockPos(5,1,5),UmbralContent.SENSOR,i.owner);sensor.bind(i.owner.getUUID(),i.core);var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(Vec3.atBottomCenterOf(t.absolutePos(new BlockPos(5,1,2))));
        t.assertTrue(SafetyService.authorized(t.getLevel(),sensor,i.owner)&&SafetyService.detects(t.getLevel(),sensor,guest),"Owner recognized, unauthorized forward player detected");
        var grant=AccessRegistry.get(t.getLevel()).issue(i.owner.getUUID(),i.core.link(),"Visita");var card=new ItemStack(UmbralContent.ACCESS_CARD);card.set(UmbralContent.CARD_ID,grant.id());guest.getInventory().setItem(0,card);
        t.assertFalse(SafetyService.detects(t.getLevel(),sensor,guest),"A valid physical pass is recognized");AccessRegistry.get(t.getLevel()).revoke(grant.id(),i.owner.getUUID(),i.core.link());
        t.assertTrue(SafetyService.detects(t.getLevel(),sensor,guest),"Revocation applies to the next scan");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void sensorSeesGlassButNotStoneAndProducesRedstone(GameTestHelper t){
        var i=setup(t,false);var sensor=place(t,new BlockPos(5,1,5),UmbralContent.SENSOR,i.owner);sensor.bind(i.owner.getUUID(),i.core);sensor.safety().arm(true);var mob=t.spawn(EntityTypes.HUSK,new BlockPos(5,1,2));mob.setNoAi(true);
        for(int y=1;y<=4;y++)for(int x=3;x<=7;x++)t.setBlock(new BlockPos(x,y,4),Blocks.GLASS);
        t.assertTrue(SafetyService.detects(t.getLevel(),sensor,mob),"Glass permits optical detection");
        t.runAfterDelay(10,()->{
            t.assertTrue(t.getLevel().getSignal(sensor.getBlockPos(),Direction.NORTH)==15,"A real alarm produces redstone");
            for(int y=1;y<=4;y++)for(int x=3;x<=7;x++)t.setBlock(new BlockPos(x,y,4),Blocks.STONE);
            t.assertFalse(SafetyService.detects(t.getLevel(),sensor,mob),"An opaque wall blocks detection");
            t.runAfterDelay(45,()->{t.assertTrue(t.getLevel().getSignal(sensor.getBlockPos(),Direction.NORTH)==0,"Alarm decays after lost visibility");t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void pulseConsumesOneRealChargeAndProtectsPlayersAnimalsAndBlocks(GameTestHelper t){
        var i=setup(t,false);var mine=place(t,new BlockPos(5,1,3),UmbralContent.PULSE_MINE,i.owner);mine.bind(i.owner.getUUID(),i.core);mine.safety().load(new ItemStack(Items.REDSTONE,3));mine.safety().arm(true);
        var mob=t.spawn(EntityTypes.HUSK,new BlockPos(5,1,3));mob.setNoAi(true);var cow=t.spawn(EntityTypes.COW,new BlockPos(6,1,3));cow.setNoAi(true);float health=i.owner.getHealth();i.owner.setPos(Vec3.atCenterOf(mine.getBlockPos()).add(-1,0,0));
        t.runAfterDelay(15,()->{
            t.assertTrue(mob.getHealth()<mob.getMaxHealth()&&mine.safety().count()==2,"One pulse consumed one real charge");t.assertTrue(cow.getHealth()==cow.getMaxHealth()&&i.owner.getHealth()==health,"Animals and players protected");
            t.assertTrue(t.getBlockState(new BlockPos(5,0,3)).is(Blocks.STONE)&&t.getBlockState(new BlockPos(5,1,3)).is(UmbralContent.PULSE_MINE),"No explosion or block damage");t.succeed();
        });
    }
    @GameTest(structure=STRUCTURE)
    public void mineLoadUnloadCreativeOverflowAndRemovalConserveCharges(GameTestHelper t){
        var i=setup(t,false);var mine=place(t,new BlockPos(5,1,3),UmbralContent.PULSE_MINE,i.owner);mine.bind(i.owner.getUUID(),i.core);i.owner.setPos(Vec3.atCenterOf(mine.getBlockPos()));
        i.owner.getInventory().setItem(0,new ItemStack(Items.REDSTONE,20));NexoProtocol.handle(i.owner,request(mine,mine,NexoProtocol.Operation.LOAD));t.assertTrue(mine.safety().count()==16&&i.owner.getInventory().getItem(0).getCount()==4,"Actual bounded charge transfer");
        i.owner.setGameMode(GameType.CREATIVE);for(int n=0;n<i.owner.getInventory().getContainerSize();n++)i.owner.getInventory().setItem(n,new ItemStack(Items.STONE,64));
        NexoProtocol.handle(i.owner,request(mine,mine,NexoProtocol.Operation.UNLOAD));t.assertTrue(mine.safety().count()==0&&dropped(t,Items.REDSTONE)==16,"Full creative inventory does not destroy returned redstone");
        mine.safety().load(new ItemStack(Items.REDSTONE,5));t.setBlock(new BlockPos(5,1,3),Blocks.AIR);mine.preRemoveSideEffects(mine.getBlockPos(),UmbralContent.PULSE_MINE.defaultBlockState());
        t.assertTrue(dropped(t,Items.REDSTONE)==21,"Repeated removal returns charges exactly once");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void safetySignalLossDisarmsAndSettingsSurviveSave(GameTestHelper t){
        var i=setup(t,false);var mine=place(t,new BlockPos(5,1,3),UmbralContent.PULSE_MINE,i.owner);mine.bind(i.owner.getUUID(),i.core);mine.safety().load(new ItemStack(Items.REDSTONE,6));mine.safety().arm(true);
        var tag=mine.saveWithoutMetadata(t.getLevel().registryAccess());var copy=new DeviceBlockEntity(mine.getBlockPos(),mine.getBlockState());copy.setLevel(t.getLevel());copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),tag));
        t.assertTrue(copy.safety().count()==6&&copy.safety().armed(),"Actual charges and intentional arming survive save");t.setBlock(CORE,Blocks.AIR);SafetyService.tick(t.getLevel(),mine);t.assertFalse(mine.safety().armed(),"Loss disarms");
        var replacement=place(t,CORE,UmbralContent.CORE,i.owner);mine.bind(i.owner.getUUID(),replacement);SafetyService.tick(t.getLevel(),mine);t.assertFalse(mine.safety().armed(),"Repair does not rearm");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void aStockSealPersistsAndReplacementRequiresAuthorizationAgain(GameTestHelper t){
        var i=setup(t,false);var reference=i.dock.dock().stock();var saved=i.stock.saveWithoutMetadata(t.getLevel().registryAccess());var copy=new ChestBlockEntity(i.stock.getBlockPos(),i.stock.getBlockState());copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),saved));
        t.assertTrue(copy.components().get(UmbralContent.STOCK_SEAL).identity().equals(reference.identity()),"Physical seal persists on the vanilla chest");
        t.setBlock(STOCK,Blocks.AIR);t.setBlock(STOCK,Blocks.CHEST);t.assertTrue(MotaService.stock(t.getLevel(),i.dock,reference)==null,"New chest at same coordinates does not inherit authorization");
        var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(i.owner.position());t.assertTrue(MotaService.seal(guest,i.stock.getBlockPos(),i.core.link())==null,"Foreign user cannot authorize stock for this network");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void motaCollectsCarriesAndDeliversExactlyThirtyTwoPhysicalArrows(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,48));var drone=install(t,i);
        t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"Loaded, unobstructed route accepted");t.assertTrue(i.stock.getItem(0).getCount()==48,"Acceptance alone cannot withdraw stock");
        t.runAfterDelay(260,()->{
            t.assertTrue(!drone.busy()&&drone.cargo().isEmpty(),"Physical route completed and returned home");t.assertTrue(i.target.turret().count()==32&&i.stock.getItem(0).getCount()==16,"Exactly 32 arrows moved, none created or lost");
            t.assertTrue(i.dock.dock().result().equals("complete")&&i.dock.dock().delivered()==32&&!i.target.turret().armed(),"Only actual delivery is reported; no implicit arming");t.succeed();
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void motaOnlyFillsRemainingCapacityAndKeepsCustomComponents(GameTestHelper t){
        var i=setup(t,true);var ammo=new ItemStack(Items.IRON_NUGGET,60);ammo.set(DataComponents.CUSTOM_NAME,Component.literal("Lote"));i.target.turret().load(ammo,0);var reserve=new ItemStack(Items.IRON_NUGGET,20);reserve.set(DataComponents.CUSTOM_NAME,Component.literal("Lote"));i.stock.setItem(0,reserve);var drone=install(t,i);
        t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("queued"),"Partial capacity has an accepted route");
        t.runAfterDelay(260,()->{t.assertTrue(!drone.busy()&&i.target.turret().count()==64&&i.stock.getItem(0).getCount()==16,"Only four actual compatible nuggets moved");t.assertTrue(i.target.turret().ammunition().getHoverName().getString().equals("Lote"),"No item-component loss");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void cancellingAfterPickupReturnsActualCargo(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            t.assertTrue(drone.carrying()&&drone.cargo().getCount()==32,"Fixture catches MOTA after real withdrawal");drone.cancel();
            t.runAfterDelay(180,()->{t.assertTrue(!drone.busy()&&i.stock.getItem(0).getCount()==40&&i.target.turret().count()==0,"Cancelled cargo is physically returned with no deposit");t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void losingSignalPausesCargoWithoutDuplicatingUnitOrRearming(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            t.assertTrue(drone.carrying(),"Cargo has been picked up");t.setBlock(CORE,Blocks.AIR);var before=drone.position();
            t.runAfterDelay(30,()->{t.assertTrue(drone.position().distanceToSqr(before)<.001&&drone.cargo().getCount()==32,"Offline pauses without losing physical cargo");t.assertFalse(MotaService.install(i.owner,i.dock,new ItemStack(UmbralContent.MOTA)),"Missing signal cannot create another unit");t.assertFalse(i.dock.unbind(i.owner.getUUID()),"Installed unit prevents retuning its station");drone.cancel();t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void replacingDestinationDuringFlightReturnsCargo(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            t.assertTrue(drone.carrying(),"Pickup occurred");t.setBlock(TARGET,Blocks.AIR);var replacement=place(t,TARGET,UmbralContent.TURRET,i.owner);replacement.bind(i.owner.getUUID(),i.core);
            t.runAfterDelay(180,()->{t.assertTrue(!drone.busy()&&i.stock.getItem(0).getCount()==40&&replacement.turret().count()==0,"New destination is never a substitute for an authorized physical target");t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE)
    public void blockedRoutesRefuseBeforeWithdrawingAnything(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));install(t,i);
        for(int x=1;x<=10;x++)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,3),Blocks.STONE);
        t.assertTrue(MotaService.order(t.getLevel(),i.dock,i.target).equals("blocked")&&i.stock.getItem(0).getCount()==40,"No route means no reservation or fake removal");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void cargoAndJobSaveRestoreWithoutGeneratingAnotherCourier(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,t.getLevel().registryAccess());drone.save(out);var clone=new MotaEntity(UmbralContent.MOTA_ENTITY,t.getLevel());clone.load(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),out.buildResult()));
            t.assertTrue(clone.getUUID().equals(drone.getUUID())&&clone.cargo().getCount()==32&&clone.phase()==drone.phase(),"Save keeps actor identity, phase and actual cargo");
            drone.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            t.assertTrue(MotaService.active(t.getLevel(),i.dock)&&!MotaService.install(i.owner,i.dock,new ItemStack(UmbralContent.MOTA)),"Unloaded actor remains installed; no replacement spawn");
            t.assertTrue(dropped(t,Items.ARROW)==0,"Saving/unloading never drops or duplicates cargo");
            t.runAfterDelay(2,()->{t.assertTrue(t.getLevel().addFreshEntity(clone),"Saved actor re-enters the actual level");
                t.runAfterDelay(220,()->{t.assertTrue(!clone.busy()&&clone.cargo().isEmpty()&&i.target.turret().count()==32&&i.stock.getItem(0).getCount()==8,"Restored actor completes exactly one delivery from its saved cargo");t.succeed();});
            });
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=300)
    public void breakingStationReturnsUnitAndCarriedCargoExactlyOnce(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            t.assertTrue(drone.carrying(),"Pickup occurred");t.setBlock(DOCK,Blocks.AIR);i.dock.preRemoveSideEffects(i.dock.getBlockPos(),UmbralContent.DOCK.defaultBlockState());
            t.assertTrue(dropped(t,UmbralContent.MOTA)==1&&dropped(t,Items.ARROW)==32&&i.stock.getItem(0).getCount()==8,"Station removal returns exactly one unit and its actual cargo");t.succeed();
        });
    }
    @GameTest(structure=STRUCTURE)
    public void idleUnitCanBeRecoveredAndCannotBeInstalledTwice(GameTestHelper t){
        var i=setup(t,false);var drone=install(t,i);var extra=new ItemStack(UmbralContent.MOTA);t.assertFalse(MotaService.install(i.owner,i.dock,extra),"Second installation refused");t.assertTrue(extra.getCount()==1,"Refusal does not consume a unit");
        t.assertTrue(MotaService.recover(i.owner,i.dock)&&drone.isRemoved()&&!MotaService.active(t.getLevel(),i.dock),"Recovery returns the real idle unit");
        int count=0;for(int n=0;n<i.owner.getInventory().getContainerSize();n++)if(i.owner.getInventory().getItem(n).is(UmbralContent.MOTA))count+=i.owner.getInventory().getItem(n).getCount();t.assertTrue(count==1&&!MotaService.recover(i.owner,i.dock),"One return and no repeated recovery");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void boundedRoutesNeverLoadMissingTerrain(GameTestHelper t){
        var i=setup(t,false);BlockPos edge=i.core.getBlockPos().atY(100);int n=0;while(t.getLevel().isLoaded(edge)&&n++<2048)edge=edge.east();
        t.assertFalse(t.getLevel().isLoaded(edge),"Fixture finds boundary");t.assertTrue(MotaRoute.find(t.getLevel(),new Vec3(edge.getX()-1,100,edge.getZ()),Vec3.atCenterOf(edge)).isEmpty(),"Unloaded route is refused");t.assertFalse(t.getLevel().isLoaded(edge),"No forced chunk load");t.succeed();
    }

    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void targetFilledDuringFlightReturnsEveryUndeliverableItem(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,48));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{i.target.turret().load(new ItemStack(Items.ARROW,60),t.getLevel().getGameTime());
            t.runAfterDelay(220,()->{t.assertTrue(!drone.busy()&&i.target.turret().count()==64&&i.stock.getItem(0).getCount()==44&&i.dock.dock().delivered()==4,"Only four deposited; remaining 28 actually returned");t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=500)
    public void fullReturnChestLeavesCargoAtHomeInsteadOfDeletingIt(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{for(int n=1;n<i.stock.getContainerSize();n++)i.stock.setItem(n,new ItemStack(Items.STONE,64));i.stock.getItem(0).setCount(64);i.target.turret().load(new ItemStack(Items.ARROW,64),0);
            t.runAfterDelay(220,()->{t.assertTrue(!drone.busy()&&drone.cargo().isEmpty()&&i.target.turret().count()==64&&i.stock.getItem(0).getCount()==64&&dropped(t,Items.ARROW)==32,"Unaccepted cargo is dropped once at home, not deleted");t.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE)
    public void cancellationIntentPersistsWhileTheActorIsUnloaded(GameTestHelper t){
        var i=setup(t,false);var drone=install(t,i);drone.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        var result=NexoProtocol.handle(i.owner,request(i.core,i.dock,NexoProtocol.Operation.CANCEL));t.assertTrue(result.result().equals("cancel_pending"),"Unloaded cancellation is accepted without loading the actor");
        var registry=MotaRegistry.get(t.getLevel());var saved=MotaRegistry.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,registry).getOrThrow();var restored=MotaRegistry.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,saved).getOrThrow();
        t.assertTrue(restored.unit(i.dock.identity()).cancel()&&restored.unit(i.dock.identity()).active(),"Pending cancellation and installed identity survive saved data");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void journalGroupsRepeatedWarningsPersistsAndDoesNotLeakOtherNetworks(GameTestHelper t){
        var i=setup(t,false);var journal=NexoJournal.get(t.getLevel());i.target.turret().load(new ItemStack(Items.ARROW,5),0);
        for(int n=0;n<50;n++)journal.observe(t.getLevel(),i.target);
        var warnings=journal.events(i.owner.getUUID(),i.core.link());t.assertTrue(warnings.size()==1&&warnings.getFirst().code().equals("low_ammo")&&warnings.getFirst().amount()==5,"Repeated polls are grouped as one real warning");
        var saved=NexoJournal.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,journal).getOrThrow();var restored=NexoJournal.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,saved).getOrThrow();restored.observe(t.getLevel(),i.target);
        t.assertTrue(restored.events(i.owner.getUUID(),i.core.link()).size()==1,"Save/load does not duplicate an unchanged condition");
        t.assertTrue(journal.events(UUID.randomUUID(),i.core.link()).isEmpty(),"Foreign owners cannot read this journal");
        i.target.turret().unload(0);journal.observe(t.getLevel(),i.target);t.assertTrue(journal.events(i.owner.getUUID(),i.core.link()).getFirst().code().equals("empty"),"A changed condition adds a new incident");
        t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void nexoProposalUsesAuthorizedStockButCreatesNoReservationOrDelivery(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,48));install(t,i);i.target.turret().load(new ItemStack(Items.ARROW,4),0);
        var row=NexoProtocol.snapshot(i.owner,i.core,0,false,"ready").rows().stream().filter(r->r.identity().equals(i.target.identity())).findFirst().orElseThrow();
        t.assertTrue(row.refill()==32&&!row.source().isEmpty()&&row.transport().equals("ready"),"Proposal uses actual compatible stock at a physically authorized source");
        t.assertTrue(i.stock.getItem(0).getCount()==48&&i.target.turret().count()==4&&!MotaService.actor(t.getLevel(),i.dock).busy(),"Observing/proposing never executes or reserves a job");t.succeed();
    }

    @GameTest(structure=STRUCTURE,maxTicks=300)
    public void removedStationWithUnloadedCargoCleansItsRegistryAndReturnsOnlyOnce(GameTestHelper t){
        var i=setup(t,false);i.stock.setItem(0,new ItemStack(Items.ARROW,40));var drone=install(t,i);MotaService.order(t.getLevel(),i.dock,i.target);
        afterPickup(t,drone,()->{
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,t.getLevel().registryAccess());drone.save(out);
            var restored=new MotaEntity(UmbralContent.MOTA_ENTITY,t.getLevel());restored.load(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),out.buildResult()));
            drone.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);t.setBlock(DOCK,Blocks.AIR);i.dock.preRemoveSideEffects(i.dock.getBlockPos(),UmbralContent.DOCK.defaultBlockState());
            t.assertTrue(dropped(t,UmbralContent.MOTA)==1&&MotaRegistry.get(t.getLevel()).unit(i.dock.identity())==null,"Destroyed station returns one unit and releases its registry slot");
            t.runAfterDelay(2,()->{t.assertTrue(t.getLevel().addFreshEntity(restored),"The saved actor loads after its station was removed");
                t.runAfterDelay(3,()->{t.assertTrue(restored.isRemoved()&&dropped(t,Items.ARROW)==32&&dropped(t,UmbralContent.MOTA)==1&&i.stock.getItem(0).getCount()==8,"Orphan drops only its actual cargo, not another unit");t.succeed();});
            });
        });
    }

}
