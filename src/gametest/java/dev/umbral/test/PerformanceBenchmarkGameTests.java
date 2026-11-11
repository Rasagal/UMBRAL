// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.installation.*;
import dev.umbral.filter.*;
import dev.umbral.logistics.*;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Opt-in, isolated real-server service benchmarks. Normal check never runs this class. */
public final class PerformanceBenchmarkGameTests {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final int WARMUP=64,SAMPLES=81;
    private static final com.sun.management.ThreadMXBean ALLOCATOR=allocator();
    private static volatile long consumed;
    private record Trial(String name,LongSupplier operation,int operations){}
    private record Fixture(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity target,DeviceBlockEntity other,List<DeviceBlockEntity> devices,List<InstallationDirectory.Entry> entries){}
    private static DeviceBlockEntity put(GameTestHelper t,BlockPos at,DeviceBlock block,ServerPlayer p){
        t.setBlock(at,block);var d=t.getBlockEntity(at,DeviceBlockEntity.class);d.initializeOwner(p.getUUID(),block.kind().id+" "+at.toShortString());return d;
    }
    private Fixture setup(GameTestHelper t){
        var owner=(ServerPlayer)t.makeMockServerPlayer(GameType.CREATIVE);
        for(int x=0;x<48;x++)for(int z=0;z<24;z++)t.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        var core=put(t,new BlockPos(10,1,10),UmbralContent.CORE,owner);var devices=new ArrayList<DeviceBlockEntity>();devices.add(core);
        var reserved=new HashSet<BlockPos>();reserved.add(new BlockPos(10,1,10));
        for(var at:List.of(new BlockPos(5,1,5),new BlockPos(15,1,5),new BlockPos(5,1,15),new BlockPos(15,1,15))){
            var dock=put(t,at,UmbralContent.DOCK,owner);t.assertTrue(dock.bind(owner.getUUID(),core),"Benchmark dock pairs");devices.add(dock);reserved.add(at);reserved.add(at.east());
            t.setBlock(at.east(),Blocks.CHEST);var stock=t.getBlockEntity(at.east(),ChestBlockEntity.class);for(int slot=0;slot<stock.getContainerSize();slot++)stock.setItem(slot,new ItemStack(Items.ARROW,64));
            owner.setPos(Vec3.atCenterOf(dock.getBlockPos()).add(0,0,-1));var seal=MotaService.seal(owner,stock.getBlockPos(),core.link());t.assertTrue(seal!=null&&MotaService.bindStock(owner,dock,seal),"Benchmark reserve authorized");
            t.assertTrue(MotaService.install(owner,dock,new ItemStack(UmbralContent.MOTA)),"Benchmark has real installed couriers");
        }
        for(int z=4;z<=16&&devices.size()<128;z++)for(int x=4;x<=16&&devices.size()<128;x++){
            var at=new BlockPos(x,1,z);if(reserved.contains(at))continue;var d=put(t,at,UmbralContent.TURRET,owner);t.assertTrue(d.bind(owner.getUUID(),core),"Benchmark endpoint pairs");d.turret().load(new ItemStack(Items.ARROW,4),0);devices.add(d);
        }
        t.assertTrue(devices.size()==128,"A complete 128-device installation is loaded");
        var other=put(t,new BlockPos(40,1,10),UmbralContent.CORE,owner);
        for(int y=2;y<=7;y++){t.setBlock(new BlockPos(10,y,10),UmbralContent.CABLE);t.setBlock(new BlockPos(40,y,10),UmbralContent.CABLE);}
        for(int x=2;x<=33;x++)for(int z=2;z<=10;z++)t.setBlock(new BlockPos(x,7,z),UmbralContent.CABLE);
        for(int x=38;x<=47;x++)for(int z=2;z<=10;z++)t.setBlock(new BlockPos(x,7,z),UmbralContent.CABLE);
        owner.setPos(Vec3.atCenterOf(core.getBlockPos()).add(0,0,-2));NetworkService.invalidate(t.getLevel());
        t.assertTrue(NetworkService.inspect(t.getLevel(),core).online()&&NetworkService.inspect(t.getLevel(),other).online(),"Independent backbones are online");
        var entries=InstallationDirectory.get(t.getLevel()).entries(owner.getUUID(),core.link());t.assertTrue(entries.size()==128,"Directory has all loaded endpoints");
        return new Fixture(owner,core,devices.getLast(),other,List.copyOf(devices),entries);
    }
    private static InstallationDirectory directory(List<InstallationDirectory.Entry> real,int count){
        var data=new JsonArray();for(var e:real.subList(0,Math.min(real.size(),count)))data.add(InstallationDirectory.Entry.CODEC.encodeStart(JsonOps.INSTANCE,e).getOrThrow());
        for(int i=data.size();i<count;i++){
            int network=i/128;var id=new UUID(0,network+1000);var link=new NetworkLink(real.getFirst().link().dimension(),new BlockPos(100000+network*32,80,100000),id);
            var e=new InstallationDirectory.Entry(new BlockPos(100000+i,80,100000),new UUID(2,i+1),new UUID(1,network+1),link,DeviceKind.SENSOR,"Remote "+i);
            data.add(InstallationDirectory.Entry.CODEC.encodeStart(JsonOps.INSTANCE,e).getOrThrow());
        }
        return InstallationDirectory.CODEC.parse(JsonOps.INSTANCE,data).getOrThrow();
    }
    private static double percentile(List<Double> values,double p){var copy=new ArrayList<>(values);copy.sort(Double::compare);return copy.get(Math.min(copy.size()-1,(int)Math.ceil(copy.size()*p)-1));}
    private static com.sun.management.ThreadMXBean allocator(){var bean=ManagementFactory.getThreadMXBean();if(bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()){if(!b.isThreadAllocatedMemoryEnabled())b.setThreadAllocatedMemoryEnabled(true);return b;}return null;}
    private static long allocated(){return ALLOCATOR==null?-1:ALLOCATOR.getThreadAllocatedBytes(Thread.currentThread().threadId());}
    private static long fresh(ServerLevel level,DeviceBlockEntity core,DeviceBlockEntity other){
        NetworkService.inspect(level,other);long scans=scanCount(level);
        try{Method m=NetworkService.class.getMethod("inspectFresh",ServerLevel.class,DeviceBlockEntity.class);m.invoke(null,level,core);}catch(NoSuchMethodException old){NetworkService.invalidate(level);NetworkService.inspect(level,core);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        NetworkService.inspect(level,other);return scanCount(level)-scans;
    }
    private static long scanCount(ServerLevel level){try{return ((Number)NetworkService.class.getMethod("topologyScans",ServerLevel.class).invoke(null,level)).longValue();}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    @GameTest(structure="umbral-test:network",maxTicks=6000)
    public void isolatedServiceBenchmarks(GameTestHelper t){
        var fixture=setup(t);var level=t.getLevel();var trials=new ArrayList<Trial>();
        trials.add(new Trial("measurement_control",()->consumed,1));
        for(int size:new int[]{128,1024,4096}){
            var d=directory(fixture.entries,size);
            trials.add(new Trial("directory_unchanged_"+size,()->{for(var device:fixture.devices)d.remember(device);return d.entries(fixture.owner.getUUID(),fixture.core.link()).size();},128));
            trials.add(new Trial("directory_query_"+size,()->d.entries(fixture.owner.getUUID(),fixture.core.link()).size(),1));
        }
        // Remote saved entries model unloaded installations; only this installation is loaded.
        level.getDataStorage().set(InstallationDirectory.TYPE,directory(fixture.entries,4096));
        trials.add(new Trial("nexo_snapshot_128_loaded_4096_registered",()->{var s=NexoProtocol.snapshot(fixture.owner,fixture.core,0,false,"ready");if(s.rows().size()!=128)throw new AssertionError("Lost benchmark rows");return s.rows().stream().mapToLong(NexoProtocol.Row::refill).sum();},1));
        trials.add(new Trial("plan_123_turrets_4096_registered",()->{long total=0;for(var device:fixture.devices)if(device.kind().turret())total+=MotaService.plan(level,device).count();return total;},123));
        trials.add(new Trial("fresh_network_preserves_other_network",()->fresh(level,fixture.core,fixture.other),1));
        var origin=Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(2,20,3))).add(.5,.15,.5);
        for(int distance:new int[]{4,8,12})trials.add(new Trial("route_open_"+distance,()->{var path=MotaRoute.find(level,origin,origin.add(distance,0,0));if(path.isEmpty())throw new AssertionError("Open benchmark route missing");return path.size();},1));
        // A closed room with a clear but unreachable destination outside it.
        for(int x=2;x<=12;x++)for(int y=15;y<=21;y++)for(int z=13;z<=22;z++)if(x==2||x==12||y==15||y==21||z==13||z==22)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        var inside=Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(6,17,17))).add(.5,.15,.5);var outside=inside.add(10,0,0);
        trials.add(new Trial("route_blocked_room",()->{var path=MotaRoute.find(level,inside,outside);if(!path.isEmpty())throw new AssertionError("Blocked benchmark route escaped");return path.size();},1));
        // A full wall crosses the search bounds; the start-side component exceeds the node budget.
        for(int x=-32;x<=32;x+=16)for(int z=-32;z<=32;z+=16){t.setBlock(new BlockPos(x,40,z),Blocks.STONE);t.setBlock(new BlockPos(x,40,z),Blocks.AIR);}
        for(int y=30;y<=50;y++)for(int z=-24;z<=31;z++)t.setBlock(new BlockPos(8,y,z),Blocks.STONE);
        var broad=Vec3.atLowerCornerOf(t.absolutePos(new BlockPos(2,40,3))).add(.5,.15,.5);
        trials.add(new Trial("route_unreachable_large",()->{var path=MotaRoute.find(level,broad,broad.add(10,0,0));if(!path.isEmpty())throw new AssertionError("Search crossed a wall spanning all search bounds");return path.size();},1));
        var contents=new ArrayList<ItemStack>();var rules=new ArrayList<FilterRule>();
        var kinds=List.of(Items.STONE,Items.DIRT,Items.GLASS,Items.COPPER_NUGGET,Items.IRON_NUGGET,Items.GOLD_NUGGET,Items.ARROW,Items.REDSTONE,Items.COAL,Items.STICK,Items.COBBLESTONE,Items.SAND,Items.OAK_PLANKS,Items.IRON_INGOT,Items.GOLD_INGOT,Items.COPPER_INGOT);
        for(var kind:kinds)rules.add(new FilterRule(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(kind).toString(),1));
        for(int n=0;n<27;n++)contents.add(new ItemStack(kinds.get(n%kinds.size()),64));
        for(int n=0;n<36;n++){var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));fixture.owner.getInventory().setItem(n,box);}
        trials.add(new Trial("filter_1008_stacks_16_exact_rules",()->{var r=FilterInventory.inspect(fixture.owner,rules);if(r.outcome()!=Inspection.Outcome.MATCH||r.matches().size()!=16)throw new AssertionError("Nested inventory benchmark changed its result");return r.matches().size();},1));
        var output=new JsonObject();output.addProperty("methodology","Isolated real Minecraft server; one timed batch per tick; "+WARMUP+" warmup and "+SAMPLES+" samples per case; elapsed synchronous service latency includes scheduling/GC and is not whole-server MSPT");output.addProperty("java",System.getProperty("java.version"));output.addProperty("version",net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("umbral").orElseThrow().getMetadata().getVersion().getFriendlyString());output.addProperty("processors",Runtime.getRuntime().availableProcessors());output.addProperty("heap_max_bytes",Runtime.getRuntime().maxMemory());var measurements=new JsonArray();output.add("measurements",measurements);
        class Runner implements Runnable{
            int trial,iteration;final List<Double> times=new ArrayList<>(),allocations=new ArrayList<>();
            public void run(){
                if(trial==trials.size()){try{var path=Path.of("../../performance/server-"+output.get("version").getAsString()+".json");Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(output));}catch(Exception e){throw new RuntimeException(e);}t.succeed();return;}
                var test=trials.get(trial);long before=allocated(),start=System.nanoTime();long value=test.operation.getAsLong();long elapsed=System.nanoTime()-start,after=allocated();consumed^=value;
                if(iteration++>=WARMUP){times.add(elapsed/1e6);if(before>=0&&after>=before)allocations.add((double)(after-before));}
                if(iteration==WARMUP+SAMPLES){var row=new JsonObject();row.addProperty("name",test.name);row.addProperty("operations_per_batch",test.operations);row.addProperty("samples",SAMPLES);row.addProperty("median_batch_ms",percentile(times,.5));row.addProperty("p95_batch_ms",percentile(times,.95));row.addProperty("median_operation_us",percentile(times,.5)*1000/test.operations);row.addProperty("median_allocated_bytes_per_batch",allocations.isEmpty()?-1:percentile(allocations,.5));row.addProperty("last_result",value);measurements.add(row);System.out.println("UMBRAL_PERF "+row);trial++;iteration=0;times.clear();allocations.clear();}
                t.runAfterDelay(1,new Runnable(){public void run(){Runner.this.run();}});
            }
        }
        t.runAfterDelay(40,new Runner());
    }
}
