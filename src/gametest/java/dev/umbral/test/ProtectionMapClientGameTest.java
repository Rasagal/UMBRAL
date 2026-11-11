// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.client.ProtectionScreen;
import dev.umbral.protection.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Native map behavior and measured cache work; reflection keeps diagnostics out of the public mod API. */
@SuppressWarnings("UnstableApiUsage")
public final class ProtectionMapClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks",System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1"));
    private static final UUID AREA=UUID.fromString("08e57ccd-c3ca-4643-b62c-6b7457627ef1");
    private static final BlockPos EDIT=new BlockPos(0,-58,0);
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object target,String name){try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static Method method(Object target,String name,Class<?>...parameters){try{var m=target.getClass().getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static Object invoke(Method method,Object target,Object...args){try{return method.invoke(target,args);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static Object map(ClientGameTestContext c){return c.computeOnClient(mc->field(mc.gui.screen(),"map"));}
    private static long number(Object map,String name){return ((Number)field(map,name)).longValue();}
    private static long fingerprint(Object map,String name){
        long hash=1;for(var q:(List<?>)field(map,name)){hash=31*hash+(int)field(q,"color");for(float n:(float[])field(q,"xy"))hash=31*hash+Float.floatToIntBits(n);}return hash;
    }
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static double[] center(ClientGameTestContext c){return c.computeOnClient(mc->{var s=mc.gui.screen();double scale=mc.getWindow().getGuiScale();return new double[]{((int)field(s,"mx")+(int)field(s,"mw")/2d)*scale,((int)field(s,"my")+(int)field(s,"mh")/2d)*scale};});}
    private static long[] metrics(ClientGameTestContext c){return c.computeOnClient(mc->{var m=field(mc.gui.screen(),"map");return new long[]{number(m,"terrainRefreshes"),number(m,"terrainBuilds"),number(m,"projectionBuilds"),number(m,"projectionHits"),number(m,"modelBuilds"),fingerprint(m,"modelQuads")};});}
    private static void projected(Object map){
        invoke(method(map,"projected",double.class,int.class,int.class,int.class,int.class,double.class),map,field(map,"projectedOriginY"),field(map,"projectedX"),field(map,"projectedY"),field(map,"projectedW"),field(map,"projectedH"),field(map,"projectedScale"));
    }
    private static long[] benchmark(Object screen){
        var map=field(screen,"map");var data=(ProtectionProtocol.Snapshot)field(screen,"data");var selected=(UUID)field(screen,"selected");var saved=data.regions().stream().filter(r->r.id().equals(selected)).findFirst().orElseThrow().shape();
        var work=method(map,"model",double.class,int.class,int.class,int.class,int.class,double.class,List.class,UUID.class,ProtectionShape.class);
        Object[] args={field(map,"projectedOriginY"),field(map,"projectedX"),field(map,"projectedY"),field(map,"projectedW"),field(map,"projectedH"),field(map,"projectedScale"),data.regions(),selected,saved};
        for(int n=0;n<500;n++)invoke(work,map,args);
        var bean=ManagementFactory.getThreadMXBean();var allocations=bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()?b:null;
        if(allocations!=null&&!allocations.isThreadAllocatedMemoryEnabled())allocations.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(),beforeBytes=allocations==null?0:allocations.getThreadAllocatedBytes(thread),builds=number(map,"projectionBuilds"),models=number(map,"modelBuilds"),start=System.nanoTime();
        for(int n=0;n<5000;n++)invoke(work,map,args);
        long elapsed=System.nanoTime()-start,bytes=allocations==null?-1:allocations.getThreadAllocatedBytes(thread)-beforeBytes;
        check(number(map,"projectionBuilds")==builds&&number(map,"modelBuilds")==models,"Stationary measured calls perform no projection or wireframe rebuilds");return new long[]{elapsed/5000,bytes<0?-1:bytes/5000};
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);c.runOnClient(mc->{mc.options.guiScale().set(2);mc.options.renderDistance().set(4);mc.resizeGui();});
        var retained=new AtomicReference<Object>();long[] stationary,bench;
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-16;x<16;x++)for(int z=-16;z<16;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                for(int x=-4;x<=3;x++)for(int z=-3;z<=4;z++)for(int y=-60;y<=-55;y++)if(x==-4||z==4||y==-55)l.setBlockAndUpdate(new BlockPos(x,y,z),(y==-55?Blocks.DARK_OAK_PLANKS:Blocks.QUARTZ_BLOCK).defaultBlockState());
                l.setBlockAndUpdate(EDIT,Blocks.REDSTONE_BLOCK.defaultBlockState());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));p.containerMenu.broadcastChanges();ProtectionData.get(l).put(new ProtectionData.Region(AREA,p.getUUID(),l.dimension().identifier(),"Casa de prueba",Optional.empty(),ProtectionShape.rectangle(-6,-60,-6,6,-52,6),List.of(),ProtectionData.ALL,1));
            });
            c.waitFor(mc->mc.player.getMainHandItem().is(UmbralContent.CONFIGURATOR));world.getServer().runOnServer(server->ProtectionProtocol.openTool(conn.getServerPlayer()));
            c.waitForScreen(ProtectionScreen.class);FilterClientGameTest.click(c,"protection.umbral.model");c.waitFor(mc->number(field(mc.gui.screen(),"map"),"projectionBuilds")>0&&number(field(mc.gui.screen(),"map"),"missing")==0&&mc.level.getBlockState(EDIT).is(Blocks.REDSTONE_BLOCK));retained.set(map(c));capture(c,"umbral-map-cache-initial");
            var before=metrics(c);c.waitTicks(65);var after=metrics(c);check(after[0]>before[0]&&after[1]==before[1]&&after[2]==before[2]&&after[4]==before[4]&&after[3]>before[3]&&after[5]==before[5],"Fresh unchanged world samples and telemetry reuse visually identical stationary geometry");stationary=new long[]{after[3]-before[3],after[0]-before[0]};bench=c.computeOnClient(mc->benchmark(mc.gui.screen()));
            var at=center(c);long image=after[5],builds=after[2];double yaw=c.computeOnClient(mc->(double)field(field(mc.gui.screen(),"map"),"yaw"));
            c.getInput().setCursorPos(at[0],at[1]);c.getInput().holdMouse(1);c.getInput().moveCursor(42,18);c.waitTicks(3);c.getInput().releaseMouse(1);c.waitFor(mc->number(field(mc.gui.screen(),"map"),"projectionBuilds")>builds);
            c.runOnClient(mc->check((double)field(field(mc.gui.screen(),"map"),"yaw")!=yaw&&fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=image,"Native orbit changes the rendered model"));capture(c,"umbral-map-cache-orbit");
            var zoomBefore=metrics(c);int cells=c.computeOnClient(mc->(int)field(field(mc.gui.screen(),"map"),"cells"));at=center(c);c.getInput().setCursorPos(at[0],at[1]);c.getInput().scroll(0,-1);c.waitFor(mc->number(field(mc.gui.screen(),"map"),"projectionBuilds")>zoomBefore[2]);c.runOnClient(mc->check((int)field(field(mc.gui.screen(),"map"),"cells")>cells&&fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=zoomBefore[5],"Native zoom updates projected terrain and region outlines"));
            var panBefore=metrics(c);double panCenter=c.computeOnClient(mc->(double)field(field(mc.gui.screen(),"map"),"centerX"));FilterClientGameTest.click(c,"protection.umbral.plan");at=center(c);c.getInput().setCursorPos(at[0],at[1]);c.getInput().holdMouse(1);c.getInput().moveCursor(16,0);c.waitTicks(2);c.getInput().releaseMouse(1);FilterClientGameTest.click(c,"protection.umbral.model");c.waitFor(mc->number(field(mc.gui.screen(),"map"),"projectionBuilds")>panBefore[2]);c.runOnClient(mc->check((double)field(field(mc.gui.screen(),"map"),"centerX")!=panCenter&&fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=panBefore[5],"Panning the plan invalidates the model center when returning to 3D"));
            var resizeBefore=metrics(c);c.getInput().resizeWindow(640,480);c.waitFor(mc->number(field(mc.gui.screen(),"map"),"projectionBuilds")>resizeBefore[2]);c.runOnClient(mc->check(fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=resizeBefore[5],"Resizing changes map coordinates and clipping bounds"));capture(c,"umbral-map-cache-small");c.getInput().resizeWindow(1280,800);c.waitTicks(4);
            var editBefore=metrics(c);world.getServer().runOnServer(server->{var l=conn.getServerLevel();var d=ProtectionData.get(l);var r=d.region(AREA);d.put(r.edited(r.shape().add(ProtectionShape.rectangle(7,-60,-2,9,-54,2)),r.label()));});c.waitFor(mc->number(field(mc.gui.screen(),"map"),"modelBuilds")>editBefore[4]);c.runOnClient(mc->check(fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=editBefore[5],"Live saved-region edits update overlays without changing the view"));capture(c,"umbral-map-cache-region-update");
            var terrainBefore=metrics(c);world.getServer().runOnServer(server->conn.getServerLevel().setBlockAndUpdate(EDIT,Blocks.GOLD_BLOCK.defaultBlockState()));c.waitFor(mc->mc.level.getBlockState(EDIT).is(Blocks.GOLD_BLOCK));c.waitFor(mc->number(field(mc.gui.screen(),"map"),"terrainBuilds")>terrainBefore[1]);c.runOnClient(mc->check(number(field(mc.gui.screen(),"map"),"projectionBuilds")>terrainBefore[2]&&fingerprint(field(mc.gui.screen(),"map"),"modelQuads")!=terrainBefore[5],"Loaded terrain changes invalidate cached projected colors"));capture(c,"umbral-map-cache-terrain-update");
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
        }
        c.waitFor(mc->mc.level==null);c.runOnClient(mc->{var m=retained.get();invoke(method(m,"refresh",int.class,int.class),m,-60,-51);projected(m);check(((List<?>)field(m,"projectedTerrain")).isEmpty()&&((Map<?,?>)field(m,"plan")).isEmpty(),"No terrain from a disconnected world remains visible");});
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runOnServer(server->conn.getServerLevel().setBlockAndUpdate(EDIT,Blocks.DIAMOND_BLOCK.defaultBlockState()));conn.waitForClientboundPackets();c.waitFor(mc->mc.level.getBlockState(EDIT).is(Blocks.DIAMOND_BLOCK));
            c.runOnClient(mc->{var m=retained.get();long before=number(m,"terrainBuilds");invoke(method(m,"refresh",int.class,int.class),m,-60,-51);projected(m);check(number(m,"terrainBuilds")>before&&!((List<?>)field(m,"projectedTerrain")).isEmpty(),"A replacement world at the same coordinates is sampled immediately");});
        }finally{c.getInput().resizeWindow(1280,800);}
        try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("protection-map-cache-proof.json"),"{\"version\":\"1.6.1\",\"stationary_projection_hits\":"+stationary[0]+",\"unchanged_world_refreshes\":"+stationary[1]+",\"cached_model_ns_per_call\":"+bench[0]+",\"cached_model_bytes_per_call\":"+bench[1]+",\"native_orbit_zoom_pan_resize\":true,\"region_and_terrain_updates\":true,\"replacement_world_fresh\":true}");}catch(java.io.IOException e){throw new RuntimeException(e);}
    }
}
