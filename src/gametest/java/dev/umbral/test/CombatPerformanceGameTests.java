// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.access.AccessRegistry;
import dev.umbral.block.*;
import dev.umbral.network.NetworkService;
import dev.umbral.safety.SafetyService;
import dev.umbral.turret.TurretService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/** Launch freshness and presence scans retain their safety policy when caches and crowds grow. */
public final class CombatPerformanceGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private record Network(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity endpoint){}

    private DeviceBlockEntity put(GameTestHelper t,BlockPos pos,DeviceBlock block,ServerPlayer owner){
        t.setBlock(pos,block);var d=t.getBlockEntity(pos,DeviceBlockEntity.class);d.initializeOwner(owner.getUUID(),"");return d;
    }
    private ServerPlayer owner(GameTestHelper t){return (ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);}
    private Network wireless(GameTestHelper t,int x){
        var p=owner(t);var core=put(t,new BlockPos(x,1,1),UmbralContent.CORE,p);var endpoint=put(t,new BlockPos(x+2,1,1),UmbralContent.TERMINAL,p);
        t.assertTrue(endpoint.bind(p.getUUID(),core),"Owned endpoint is bound");return new Network(p,core,endpoint);
    }
    private Network relayed(GameTestHelper t,DeviceBlock type,int z){
        var p=owner(t);var core=put(t,new BlockPos(1,1,z),UmbralContent.CORE,p);var relay=put(t,new BlockPos(25,1,z),UmbralContent.NODE,p);var endpoint=put(t,new BlockPos(41,1,z),type,p);
        t.assertTrue(relay.bind(p.getUUID(),core)&&endpoint.bind(p.getUUID(),core),"Remote endpoint uses an actual wired relay");
        for(int x=2;x<25;x++)t.setBlock(new BlockPos(x,1,z),UmbralContent.CABLE);
        return new Network(p,core,endpoint);
    }
    private void silentCut(GameTestHelper t,int z){
        // Simulate a bulk world edit that updates a loaded section without vanilla block callbacks.
        var pos=t.absolutePos(new BlockPos(12,1,z));var chunk=t.getLevel().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        t.assertTrue(chunk!=null,"Cut is entirely inside a loaded chunk");
        chunk.getSection(chunk.getSectionIndex(pos.getY())).setBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15,Blocks.AIR.defaultBlockState());
        t.assertTrue(t.getLevel().getBlockState(pos).isAir(),"Physical backbone has actually been cut");
    }
    private void room(GameTestHelper t){
        for(int x=0;x<=11;x++)for(int z=0;z<=7;z++){
            t.setBlock(new BlockPos(x,0,z),Blocks.STONE);t.setBlock(new BlockPos(x,6,z),Blocks.STONE);
            if(x==0||x==11||z==0||z==7)for(int y=1;y<6;y++)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
    }
    private Husk hostile(GameTestHelper t,BlockPos pos){var mob=t.spawn(EntityTypes.HUSK,pos);mob.setNoAi(true);mob.setPersistenceRequired();return mob;}

    @GameTest(structure=STRUCTURE) public void freshInspectionDoesNotEvictAnotherInstallationsCache(GameTestHelper t){
        var a=wireless(t,1);var b=wireless(t,30);
        t.assertTrue(NetworkService.inspect(t.getLevel(),a.endpoint()).online()&&NetworkService.inspect(t.getLevel(),b.endpoint()).online(),"Both independent installations are cached online");
        long scans=NetworkService.topologyScans(t.getLevel());
        t.assertTrue(NetworkService.inspectFresh(t.getLevel(),a.endpoint()).online(),"Affected installation is freshly validated");
        t.assertTrue(NetworkService.topologyScans(t.getLevel())==scans+1,"Fresh inspection performs one actual topology scan");
        t.assertTrue(NetworkService.inspect(t.getLevel(),b.endpoint()).online()&&NetworkService.topologyScans(t.getLevel())==scans+1,"Unrelated installation still uses its unchanged cached topology");
        t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void targetedInvalidationLeavesOtherNetworksCached(GameTestHelper t){
        var a=wireless(t,1);var b=wireless(t,30);NetworkService.inspect(t.getLevel(),a.endpoint());NetworkService.inspect(t.getLevel(),b.endpoint());long scans=NetworkService.topologyScans(t.getLevel());
        NetworkService.invalidate(t.getLevel(),a.core().link());
        t.assertTrue(NetworkService.inspect(t.getLevel(),b.endpoint()).online()&&NetworkService.topologyScans(t.getLevel())==scans,"Targeted invalidation preserves another network's cache");
        t.assertTrue(NetworkService.inspect(t.getLevel(),a.endpoint()).online()&&NetworkService.topologyScans(t.getLevel())==scans+1,"Only the invalidated network is rebuilt");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void freshInspectionSeesACableCutHiddenFromTelemetryCache(GameTestHelper t){
        var i=relayed(t,UmbralContent.TERMINAL,1);
        t.assertTrue(NetworkService.inspect(t.getLevel(),i.endpoint()).online(),"Wired relay initially covers the remote endpoint");
        silentCut(t,1);
        t.assertTrue(NetworkService.inspect(t.getLevel(),i.endpoint()).online(),"Fixture deliberately retains a stale telemetry snapshot");long scans=NetworkService.topologyScans(t.getLevel());
        t.assertFalse(NetworkService.inspectFresh(t.getLevel(),i.endpoint()).online(),"Launch validation reads the actual severed backbone");
        t.assertTrue(NetworkService.topologyScans(t.getLevel())==scans+1,"Freshness comes from one new scan rather than trusting the cache");
        t.assertFalse(NetworkService.inspect(t.getLevel(),i.endpoint()).online(),"Updated topology also corrects following telemetry");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void freshInspectionRejectsDetachedAndReplacedPhysicalDevices(GameTestHelper t){
        var i=wireless(t,1);var data=i.endpoint().saveWithoutMetadata(t.getLevel().registryAccess());var copy=new DeviceBlockEntity(i.endpoint().getBlockPos(),i.endpoint().getBlockState());copy.setLevel(t.getLevel());
        copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),data));
        t.assertTrue(NetworkService.inspect(t.getLevel(),copy).online(),"Serialization telemetry remains compatible");
        t.assertFalse(NetworkService.inspectFresh(t.getLevel(),copy).online(),"Identical saved credentials cannot substitute a detached device at launch");
        t.setBlock(new BlockPos(3,1,1),Blocks.AIR);var replacement=put(t,new BlockPos(3,1,1),UmbralContent.TERMINAL,i.owner());replacement.bind(i.owner().getUUID(),i.core());
        t.assertFalse(NetworkService.inspectFresh(t.getLevel(),i.endpoint()).online(),"Removed device cannot act through its same-position replacement");
        t.assertTrue(NetworkService.inspectFresh(t.getLevel(),replacement).online(),"Actual owned and explicitly bound replacement can operate");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void pulseFreshnessRejectsSilentCableCutBeforeSpendingACharge(GameTestHelper t){
        int phaseZ=0;long now=t.getLevel().getGameTime();
        while(phaseZ<8&&Math.floorMod(now+t.absolutePos(new BlockPos(41,1,phaseZ)).asLong(),5)!=0)phaseZ++;
        t.assertTrue(phaseZ<8,"Fixture finds this tick's staggered scan phase inside the structure");
        var i=relayed(t,UmbralContent.PULSE_MINE,phaseZ);var mine=i.endpoint();mine.safety().load(new ItemStack(Items.REDSTONE,3));mine.safety().arm(true);
        var target=hostile(t,new BlockPos(41,1,phaseZ));float health=target.getHealth();
        t.assertTrue(NetworkService.inspect(t.getLevel(),mine).online(),"Mine starts armed with cached relay coverage");silentCut(t,phaseZ);
        t.assertTrue(NetworkService.inspect(t.getLevel(),mine).online(),"Silent cut has not invalidated telemetry");
        SafetyService.tick(t.getLevel(),mine);
        t.assertTrue(mine.safety().count()==3&&mine.safety().cooldown()==0&&target.getHealth()==health,"Offline launch cannot spend charges or damage the nearby hostile");
        t.assertFalse(mine.safety().armed(),"Failed fresh connectivity validation disarms the mine");t.succeed();
    }

    @GameTest(structure=STRUCTURE,maxTicks=80) public void pulseRequiresNearPresenceThenPreservesFullVisibleSplash(GameTestHelper t){
        room(t);var p=owner(t);var core=put(t,new BlockPos(2,1,5),UmbralContent.CORE,p);var mine=put(t,new BlockPos(5,1,4),UmbralContent.PULSE_MINE,p);mine.bind(p.getUUID(),core);mine.safety().load(new ItemStack(Items.REDSTONE,3));mine.safety().arm(true);
        var splash=hostile(t,new BlockPos(7,1,4));var hidden=hostile(t,new BlockPos(5,1,2));var cow=t.spawn(EntityTypes.COW,new BlockPos(4,1,4));cow.setNoAi(true);
        for(int y=1;y<=3;y++)t.setBlock(new BlockPos(5,y,3),Blocks.STONE);
        t.runAfterDelay(6,()->{
            t.assertTrue(mine.safety().count()==3&&splash.getHealth()==splash.getMaxHealth(),"Visible hostiles in splash range alone do not trigger a pulse");
            var trigger=hostile(t,new BlockPos(5,1,4));
            t.runAfterDelay(6,()->{
                t.assertTrue(mine.safety().count()==2&&trigger.getHealth()<trigger.getMaxHealth()&&splash.getHealth()<splash.getMaxHealth(),"One nearby trigger spends exactly one charge and damages every visible splash target");
                t.assertTrue(hidden.getHealth()==hidden.getMaxHealth()&&cow.getHealth()==cow.getMaxHealth(),"Opaque cover and animal exclusions are preserved");
                t.assertTrue(t.getBlockState(new BlockPos(5,0,4)).is(Blocks.STONE),"Pulse never damages blocks");t.succeed();
            });
        });
    }

    @GameTest(structure=STRUCTURE,maxTicks=60) public void staggeredSensorsDetectWithinOneScanPeriodAndRespectPhysicalPasses(GameTestHelper t){
        room(t);var p=owner(t);var core=put(t,new BlockPos(2,1,5),UmbralContent.CORE,p);var first=put(t,new BlockPos(5,1,5),UmbralContent.SENSOR,p);var second=put(t,new BlockPos(6,1,5),UmbralContent.SENSOR,p);
        first.bind(p.getUUID(),core);second.bind(p.getUUID(),core);first.safety().arm(true);second.safety().arm(true);
        t.assertTrue(Math.floorMod(first.getBlockPos().asLong(),5)!=Math.floorMod(second.getBlockPos().asLong(),5),"Adjacent sensors have distinct staggered phases");
        var guest=owner(t);guest.setPos(Vec3.atBottomCenterOf(t.absolutePos(new BlockPos(5,1,2))));var grant=AccessRegistry.get(t.getLevel()).issue(p.getUUID(),core.link(),"Visitante");var card=new ItemStack(UmbralContent.ACCESS_CARD);card.set(UmbralContent.CARD_ID,grant.id());guest.getInventory().setItem(0,card);
        t.assertFalse(SafetyService.detects(t.getLevel(),first,guest)||SafetyService.detects(t.getLevel(),second,guest),"Physical valid pass is still recognized independently of presence early exit");
        hostile(t,new BlockPos(5,1,2));
        t.runAfterDelay(6,()->{t.assertTrue(first.safety().alarm()&&second.safety().alarm(),"Every phase detects visible hostile presence within five ticks");t.assertTrue(t.getLevel().getSignal(first.getBlockPos(),Direction.NORTH)==15&&t.getLevel().getSignal(second.getBlockPos(),Direction.NORTH)==15,"Both actual sensor blocks emit alarm redstone");t.succeed();});
    }

    @GameTest(structure=STRUCTURE,maxTicks=60) public void crowdedAcquisitionKeepsTheClosestVisibleHostile(GameTestHelper t){
        room(t);var p=owner(t);var core=put(t,new BlockPos(2,1,5),UmbralContent.CORE,p);var turret=put(t,new BlockPos(5,1,5),UmbralContent.TURRET,p);turret.bind(p.getUUID(),core);
        for(int n=0;n<70;n++){var mob=t.spawn(EntityTypes.ZOMBIE,new BlockPos(5,1,1));mob.setNoAi(true);mob.setPersistenceRequired();}
        hostile(t,new BlockPos(5,1,3));var state=turret.turret();state.load(new ItemStack(Items.ARROW,8),t.getLevel().getGameTime());state.setArmed(true,t.getLevel().getGameTime());state.fired(t.getLevel().getGameTime());
        t.runAfterDelay(6,()->{t.assertTrue(TurretService.targetName(turret).equals(EntityTypes.HUSK.getDescriptionId()),"Closest visible hostile survives the bounded nearest-64 selection despite being inserted after the crowd");t.assertTrue(state.count()==7,"Tracking during cooldown spends no second round");t.succeed();});
    }
}
