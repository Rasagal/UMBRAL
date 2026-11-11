// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.network.NetworkService;
import dev.umbral.turret.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.*;
import java.util.UUID;

public final class TurretGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(3,1,5),TURRET=new BlockPos(5,1,4);
    private record Installation(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity turret){}
    private Installation installation(GameTestHelper test){return installation(test,GameType.SURVIVAL);}
    private Installation installation(GameTestHelper test,GameType mode){
        // Closed arenas prevent nearby concurrently running tests from supplying unintended targets.
        for(int x=0;x<=11;x++)for(int z=0;z<=7;z++){
            test.setBlock(new BlockPos(x,0,z),Blocks.STONE);test.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)test.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        var owner=(ServerPlayer)test.makeMockServerPlayer(mode);
        var core=place(test,CORE,UmbralContent.CORE,owner.getUUID());var turret=place(test,TURRET,UmbralContent.TURRET,owner.getUUID());
        turret.bind(owner.getUUID(),core);owner.setPos(Vec3.atCenterOf(turret.getBlockPos()).add(2,0,0));return new Installation(owner,core,turret);
    }
    private DeviceBlockEntity place(GameTestHelper test,BlockPos pos,net.minecraft.world.level.block.Block block,UUID owner){
        test.setBlock(pos,block);var d=test.getBlockEntity(pos,DeviceBlockEntity.class);d.initializeOwner(owner,"Patio");return d;
    }
    private TurretProtocol.Request request(Installation i,TurretProtocol.Operation op){return new TurretProtocol.Request(i.turret.getBlockPos(),i.turret.identity(),i.turret.link(),73,op);}
    private Husk hostile(GameTestHelper test,BlockPos pos){var m=test.spawn(EntityTypes.HUSK,pos);m.setNoAi(true);m.setPersistenceRequired();return m;}
    private void arm(Installation i,int count){i.turret.turret().load(new ItemStack(Items.ARROW,count),i.turret.getLevel().getGameTime());i.turret.turret().setArmed(true,i.turret.getLevel().getGameTime());}
    @GameTest(structure=STRUCTURE)
    public void magazineLoadsActualArrowsAndPreservesTheirName(GameTestHelper test){
        var i=installation(test);ItemStack stack=new ItemStack(Items.ARROW,64);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Patio"));i.owner.getInventory().setItem(0,stack);
        var result=TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.LOAD));
        test.assertTrue(result.ammunition()==64&&i.owner.getInventory().getItem(0).isEmpty(),"Loading transfers real items, including in the selected hand");
        test.assertTrue(i.turret.turret().ammunition().getHoverName().getString().equals("Patio"),"Ammunition components are preserved");
        test.assertFalse(i.turret.turret().armed(),"Loading never implicitly arms");
        TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.UNLOAD));
        test.assertTrue(i.turret.turret().count()==0&&i.owner.getInventory().getItem(0).getCount()==64,"Unloading returns exactly the same count");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void wrongArrowsAndMismatchedStacksCannotOverwriteMagazine(GameTestHelper test){
        var i=installation(test);var s=i.turret.turret();
        test.assertTrue(s.load(new ItemStack(Items.SPECTRAL_ARROW,8),0)==0&&s.load(new ItemStack(Items.TIPPED_ARROW,8),0)==0,"Special arrows are not accepted by this magazine");
        s.load(new ItemStack(Items.ARROW,63),0);ItemStack more=new ItemStack(Items.ARROW,5);test.assertTrue(s.load(more,0)==1&&more.getCount()==4,"Capacity leaves excess in the source stack");
        var named=new ItemStack(Items.ARROW,5);named.set(DataComponents.CUSTOM_NAME,Component.literal("Otro"));test.assertTrue(s.load(named,0)==0&&named.getCount()==5,"Incompatible item components are not lost or merged");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void foreignDistantAndStaleMenusCannotControlTheTurret(GameTestHelper test){
        var i=installation(test);arm(i,8);var guest=(ServerPlayer)test.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(i.owner.position());
        test.assertFalse(TurretProtocol.handle(guest,request(i,TurretProtocol.Operation.UNLOAD)).valid(),"Foreign users cannot unload");
        i.owner.setPos(i.owner.position().add(20,0,0));test.assertFalse(TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.DISARM)).valid(),"Out of range menu rejected");
        i.owner.setPos(Vec3.atCenterOf(i.turret.getBlockPos()));var old=request(i,TurretProtocol.Operation.UNLOAD);
        test.setBlock(TURRET,Blocks.AIR);var replacement=place(test,TURRET,UmbralContent.TURRET,i.owner.getUUID());replacement.bind(i.owner.getUUID(),i.core);
        replacement.turret().load(new ItemStack(Items.ARROW,5),0);test.assertFalse(TurretProtocol.handle(i.owner,old).valid(),"Replaced physical identity invalidates old controls");
        test.assertTrue(replacement.turret().count()==5,"Rejection cannot change the new magazine");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void disconnectedTurretRejectsArmingAndDoesNotRearmOnRestore(GameTestHelper test){
        var i=installation(test);arm(i,8);test.setBlock(CORE,Blocks.AIR);TurretService.tick(test.getLevel(),i.turret);
        test.assertFalse(i.turret.turret().armed(),"Signal loss disarms immediately on the next tick");
        test.assertTrue(TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.ARM)).result()==TurretProtocol.Result.OFFLINE,"Offline arming is refused");
        var next=place(test,CORE,UmbralContent.CORE,i.owner.getUUID());i.turret.bind(i.owner.getUUID(),next);TurretService.tick(test.getLevel(),i.turret);
        test.assertFalse(i.turret.turret().armed(),"Repair and pairing never grant automatic rearming");test.assertTrue(i.turret.turret().count()==8,"No ammunition spent while offline");test.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void actualArrowDamagesAMonsterAndConsumesExactlyOneRound(GameTestHelper test){
        var i=installation(test);var target=hostile(test,new BlockPos(5,1,1));arm(i,8);
        test.runAfterDelay(25,()->{
            test.assertTrue(target.getHealth()<target.getMaxHealth(),"A real arrow must physically damage the monster");
            test.assertTrue(i.turret.turret().count()==7,"Exactly one round spent before the 30-tick reload finishes");test.succeed();
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void cardinalOrientationAlsoFiresEast(GameTestHelper test){
        var i=installation(test);test.setBlock(TURRET,i.turret.getBlockState().setValue(DeviceBlock.FACING,Direction.EAST));var target=hostile(test,new BlockPos(9,1,4));arm(i,4);
        test.runAfterDelay(25,()->{test.assertTrue(target.getHealth()<target.getMaxHealth(),"The east-facing head and ballistic launch agree");test.assertTrue(i.turret.turret().count()==3,"One eastward shot");test.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void wallBlocksTheWholeFlightWithoutSpendingArrows(GameTestHelper test){
        var i=installation(test);var target=hostile(test,new BlockPos(5,1,1));
        for(int x=3;x<=7;x++)for(int y=1;y<=4;y++)test.setBlock(new BlockPos(x,y,2),Blocks.STONE);
        arm(i,8);test.runAfterDelay(35,()->{
            test.assertTrue(i.turret.turret().count()==8&&target.getHealth()==target.getMaxHealth(),"Occlusion must prevent launch and ammo consumption");
            for(int x=3;x<=7;x++)for(int y=1;y<=4;y++)test.setBlock(new BlockPos(x,y,2),Blocks.AIR);
            test.runAfterDelay(25,()->{test.assertTrue(i.turret.turret().count()==7&&target.getHealth()<target.getMaxHealth(),"Clearing the path permits a physical shot");test.succeed();});
        });
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void namedMonstersPlayersAnimalsAndNeutralMobsAreProtected(GameTestHelper test){
        var i=installation(test);var named=hostile(test,new BlockPos(5,1,1));named.setCustomName(Component.literal("Residente"));
        var cow=test.spawn(EntityTypes.COW,new BlockPos(3,1,1));var neutral=test.spawn(EntityTypes.ENDERMAN,new BlockPos(7,1,1));neutral.setNoAi(true);
        var piglin=test.spawn(EntityTypes.PIGLIN,new BlockPos(8,1,1));piglin.setNoAi(true);var spider=test.spawn(EntityTypes.SPIDER,new BlockPos(9,1,1));spider.setNoAi(true);
        test.assertFalse(TurretBolt.eligible(i.owner,null)||TurretBolt.eligible(cow,null)||TurretBolt.eligible(neutral,null)||TurretBolt.eligible(named,null),"Projectile target policy protects all four groups");
        test.assertFalse(TurretBolt.eligible(piglin,null)||TurretBolt.eligible(spider,null),"Potentially neutral piglins and ordinary spiders are also excluded");
        arm(i,8);test.runAfterDelay(35,()->{test.assertTrue(i.turret.turret().count()==8,"No protected entity causes a launch");test.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void aPhysicalArrowPassesAnAnimalWithoutHarmingIt(GameTestHelper test){
        var i=installation(test);var cow=test.spawn(EntityTypes.COW,new BlockPos(5,1,2));cow.setNoAi(true);float health=cow.getHealth();var target=hostile(test,new BlockPos(5,1,1));arm(i,3);
        test.runAfterDelay(25,()->{test.assertTrue(cow.getHealth()==health,"The projectile must not hurt an animal in the trajectory");test.assertTrue(target.getHealth()<target.getMaxHealth(),"The protected animal does not disable the bolt's damage against a hostile");test.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void rangeConeAndUnarmedStatePreventUnwantedLaunches(GameTestHelper test){
        var i=installation(test);var target=hostile(test,new BlockPos(5,1,1));
        test.setBlock(TURRET,i.turret.getBlockState().setValue(DeviceBlock.FACING,Direction.SOUTH));
        test.assertFalse(TurretService.candidate(test.getLevel(),i.turret,target),"Rear targets are outside the 120-degree sector");
        test.setBlock(TURRET,i.turret.getBlockState().setValue(DeviceBlock.FACING,Direction.NORTH));
        test.setBlock(TURRET,i.turret.getBlockState().setValue(DeviceBlock.FACING,Direction.EAST));
        target.setPos(Vec3.atCenterOf(i.turret.getBlockPos()).add(17,0,0));test.assertFalse(TurretService.candidate(test.getLevel(),i.turret,target),"Beyond maximum range");
        target.setPos(Vec3.atCenterOf(i.turret.getBlockPos()).add(10,0,0));
        TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.RANGE));
        test.assertTrue(i.turret.turret().range()==16&&TurretService.candidate(test.getLevel(),i.turret,target),"Sixteen-block range accepts a target ten blocks away");
        TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.RANGE));
        test.assertTrue(i.turret.turret().range()==8&&!TurretService.candidate(test.getLevel(),i.turret,target),"Eight-block range excludes that same target");
        i.turret.turret().load(new ItemStack(Items.ARROW,4),0);test.runAfterDelay(25,()->{test.assertTrue(i.turret.turret().count()==4&&!i.turret.turret().armed(),"Default unarmed state cannot shoot");test.succeed();});
    }
    @GameTest(structure=STRUCTURE)
    public void saveRestoresMagazineComponentsPoseAndRemainingReload(GameTestHelper test){
        var i=installation(test);arm(i,7);i.turret.turret().fired(test.getLevel().getGameTime());
        var data=i.turret.saveWithoutMetadata(test.getLevel().registryAccess());var copy=new DeviceBlockEntity(i.turret.getBlockPos(),i.turret.getBlockState());copy.setLevel(test.getLevel());
        copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,test.getLevel().registryAccess(),data));
        test.assertTrue(copy.identity().equals(i.turret.identity())&&copy.owner().equals(i.owner.getUUID())&&copy.link().equals(i.turret.link()),"Identity and authority survive persistence");
        test.assertTrue(copy.turret().count()==6&&copy.turret().cooldown()==30&&copy.turret().armed(),"Magazine and remaining reload survive without catch-up");
        var tag=i.turret.getUpdateTag(test.getLevel().registryAccess());
        test.assertFalse(tag.contains("owner")||tag.contains("network_link")||tag.contains("turret_ammunition"),"Public animation excludes private inventory and permissions");
        var pose=TurretMotion.CODEC.parse(NbtOps.INSTANCE,tag.get("turret_motion")).getOrThrow();test.assertTrue(pose.ammunition()==6,"Only the public ammunition count is exposed");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void removingTurretReturnsAmmunitionOnlyOnce(GameTestHelper test){
        var i=installation(test);arm(i,13);test.setBlock(TURRET,Blocks.AIR);
        var box=new AABB(i.turret.getBlockPos()).inflate(2);var drops=test.getLevel().getEntitiesOfClass(ItemEntity.class,box,e->e.getItem().is(Items.ARROW));
        test.assertTrue(drops.stream().mapToInt(e->e.getItem().getCount()).sum()==13,"Removal drops exactly the stored ammunition");
        i.turret.preRemoveSideEffects(i.turret.getBlockPos(),UmbralContent.TURRET.defaultBlockState());
        test.assertTrue(test.getLevel().getEntitiesOfClass(ItemEntity.class,box,e->e.getItem().is(Items.ARROW)).stream().mapToInt(e->e.getItem().getCount()).sum()==13,"A repeated removal callback cannot duplicate items");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void fullCreativeInventoryDoesNotDiscardUnloadedAmmunition(GameTestHelper test){
        var i=installation(test,GameType.CREATIVE);arm(i,11);
        for(int n=0;n<i.owner.getInventory().getContainerSize();n++)i.owner.getInventory().setItem(n,new ItemStack(Items.STONE,64));
        TurretProtocol.handle(i.owner,request(i,TurretProtocol.Operation.UNLOAD));
        int drops=test.getLevel().getEntitiesOfClass(ItemEntity.class,i.owner.getBoundingBox().inflate(3),e->e.getItem().is(Items.ARROW)).stream().mapToInt(e->e.getItem().getCount()).sum();
        test.assertTrue(drops==11&&i.turret.turret().count()==0&&!i.turret.turret().armed(),"Creative overflow is physically returned, not silently discarded");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anotherOwnerCannotBreakAndRebindingInvalidatesOldMenu(GameTestHelper test){
        var i=installation(test);var guest=(ServerPlayer)test.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(i.owner.position());
        var breaker=test.makeMockPlayer(GameType.SURVIVAL);
        test.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(test.getLevel(),breaker,i.turret.getBlockPos(),i.turret.getBlockState(),i.turret),"Physical ownership protects the new device too");
        arm(i,6);var old=request(i,TurretProtocol.Operation.UNLOAD);var core=place(test,new BlockPos(8,1,5),UmbralContent.CORE,i.owner.getUUID());i.turret.bind(i.owner.getUUID(),core);
        test.assertFalse(TurretProtocol.handle(i.owner,old).valid(),"An old network request cannot mutate the new binding");test.assertFalse(i.turret.turret().armed(),"Rebinding disarms");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void projectileStopsAtAnUnloadedBoundaryWithoutLoadingIt(GameTestHelper test){
        var i=installation(test);BlockPos edge=new BlockPos(i.turret.getBlockPos().getX(),180,i.turret.getBlockPos().getZ());
        int steps=0;while(test.getLevel().isLoaded(edge)&&steps++<2048)edge=edge.east();
        test.assertFalse(test.getLevel().isLoaded(edge),"Fixture finds an unloaded boundary with read-only checks");
        var bolt=new TurretBolt(UmbralContent.TURRET_BOLT,test.getLevel());
        bolt.prepare(null,new ItemStack(Items.ARROW),new Vec3(edge.getX()-.05,180,edge.getZ()+.5),new Vec3(1.5,0,0));bolt.tick();
        test.assertTrue(bolt.isRemoved(),"The projectile stops before stepping into an unloaded chunk");
        test.assertFalse(test.getLevel().isLoaded(edge),"The shot must not create a chunk ticket or force a load");test.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void projectilePersistenceKeepsItsAgeAndDisallowsRecovery(GameTestHelper test){
        var i=installation(test);var bolt=new TurretBolt(UmbralContent.TURRET_BOLT,test.getLevel());
        bolt.prepare(i.owner,new ItemStack(Items.ARROW),Vec3.atCenterOf(i.turret.getBlockPos()).add(0,4,0),Vec3.ZERO);bolt.setNoGravity(true);bolt.tick();
        test.assertTrue(bolt.pickup==net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED,"Even a player owner cannot make the bolt recoverable");
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,test.getLevel().registryAccess());bolt.save(out);
        var copy=new TurretBolt(UmbralContent.TURRET_BOLT,test.getLevel());copy.load(TagValueInput.create(ProblemReporter.DISCARDING,test.getLevel().registryAccess(),out.buildResult()));
        copy.setOwner(i.owner);test.assertTrue(copy.pickup==net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED,"Restoring or redirecting the projectile cannot enable pickup");
        for(int n=0;n<198;n++)copy.tick();test.assertFalse(copy.isRemoved(),"Lifetime does not expire early after reload");copy.tick();
        test.assertTrue(copy.isRemoved(),"The saved lifetime ends without being reset by reload");test.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void losingSightStopsTrackingTheMonsterBehindTheWall(GameTestHelper test){
        var i=installation(test);var target=hostile(test,new BlockPos(5,1,1));arm(i,8);
        test.runAfterDelay(25,()->{
            int ammunition=i.turret.turret().count();float yaw=i.turret.turret().motion().yawAt(test.getLevel().getGameTime());
            for(int x=2;x<=9;x++)for(int y=1;y<=4;y++)test.setBlock(new BlockPos(x,y,2),Blocks.STONE);
            target.setPos(Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(8,1,1))));
            test.runAfterDelay(10,()->{
                test.assertTrue(TurretService.targetName(i.turret).isEmpty(),"The hidden monster must no longer be reported as a visible target");
                test.assertTrue(Math.abs(i.turret.turret().motion().yawAt(test.getLevel().getGameTime())-yaw)<.1,"The servo does not follow new movement through an opaque wall");
                test.assertTrue(i.turret.turret().count()==ammunition,"No launch occurs after line of sight is lost");test.succeed();
            });
        });
    }
}
