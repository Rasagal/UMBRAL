// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.google.gson.*;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.camera.CameraMotion;
import dev.umbral.network.NetworkService;
import dev.umbral.protection.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in measurements with the actual renderer and settings captured from the running client. */
@SuppressWarnings("UnstableApiUsage")
public final class PerformanceClientGameTest implements FabricClientGameTest {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicReference<List<Double>> TICKS=new AtomicReference<>();
    private static final AtomicReference<List<DeviceBlockEntity>> FEED=new AtomicReference<>(List.of());
    private static final com.sun.management.ThreadMXBean ALLOCATOR=allocator();
    private static boolean registered;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static double p(List<Double> values,double q){var a=new ArrayList<>(values);a.sort(Double::compare);return a.get(Math.min(a.size()-1,(int)Math.ceil(q*a.size())-1));}
    private static void statistics(JsonObject row,String name,List<Double> values){row.addProperty(name+"_samples",values.size());if(values.isEmpty())return;row.addProperty(name+"_median",p(values,.5));row.addProperty(name+"_p95",p(values,.95));row.addProperty(name+"_max",Collections.max(values));}
    private static com.sun.management.ThreadMXBean allocator(){var bean=ManagementFactory.getThreadMXBean();if(bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()){if(!b.isThreadAllocatedMemoryEnabled())b.setThreadAllocatedMemoryEnabled(true);return b;}return null;}
    private static long allocated(){return ALLOCATOR==null?-1:ALLOCATOR.getThreadAllocatedBytes(Thread.currentThread().threadId());}
    private static DeviceBlockEntity put(ServerLevel l,BlockPos at,DeviceBlock block,UUID owner){l.setBlockAndUpdate(at,block.defaultBlockState());var d=(DeviceBlockEntity)l.getBlockEntity(at);d.initializeOwner(owner,block.kind().id+" "+at.toShortString());return d;}
    private static JsonObject environment(Minecraft mc){
        var result=new JsonObject();
        var device=com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
        var gpu=new JsonObject();
        gpu.addProperty("name",device.name());gpu.addProperty("vendor",device.vendorName());
        gpu.addProperty("driver",device.driverInfo());gpu.addProperty("backend",device.backendName());
        gpu.addProperty("device_type",device.type().name());
        gpu.addProperty("source","RenderSystem.getDevice().getDeviceInfo() from the active Minecraft renderer");
        result.add("gpu",gpu);
        var runtime=new JsonObject();
        for(String key:List.of("java.version","java.runtime.version","java.vendor","java.vm.name","os.name","os.version","os.arch"))runtime.addProperty(key,System.getProperty(key));
        runtime.addProperty("available_processors",Runtime.getRuntime().availableProcessors());
        runtime.addProperty("max_heap_bytes",Runtime.getRuntime().maxMemory());
        runtime.addProperty("minecraft",net.minecraft.SharedConstants.getCurrentVersion().name());
        result.add("runtime",runtime);
        var settings=new JsonObject();
        settings.addProperty("framebuffer_width",mc.getWindow().getWidth());settings.addProperty("framebuffer_height",mc.getWindow().getHeight());
        settings.addProperty("window_width",mc.getWindow().getScreenWidth());settings.addProperty("window_height",mc.getWindow().getScreenHeight());
        settings.addProperty("render_distance",mc.options.renderDistance().get());settings.addProperty("simulation_distance",mc.options.simulationDistance().get());
        settings.addProperty("graphics_preset",mc.options.graphicsPreset().get().name());settings.addProperty("vsync",mc.options.enableVsync().get());
        settings.addProperty("fps_limit",mc.options.framerateLimit().get());settings.addProperty("gui_scale",mc.getWindow().getGuiScale());
        settings.addProperty("fov",mc.options.fov().get());settings.addProperty("language",mc.options.languageCode);
        settings.addProperty("mipmap_levels",mc.options.mipmapLevels().get());settings.addProperty("clouds",mc.options.cloudStatus().get().name());
        settings.addProperty("inactivity_fps_limit",mc.options.inactivityFpsLimit().get().name());
        settings.addProperty("entity_distance_scaling",mc.options.entityDistanceScaling().get());settings.addProperty("entity_shadows",mc.options.entityShadows().get());
        settings.addProperty("ambient_occlusion",mc.options.ambientOcclusion().get());settings.addProperty("particles",mc.options.particles().get().name());
        settings.addProperty("texture_filtering",mc.options.textureFiltering().get().name());settings.addProperty("biome_blend_radius",mc.options.biomeBlendRadius().get());
        settings.addProperty("cutout_leaves",mc.options.cutoutLeaves().get());settings.addProperty("weather_radius",mc.options.weatherRadius().get());
        result.add("settings",settings);
        var mods=new JsonObject();
        net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream().sorted(Comparator.comparing(m->m.getMetadata().getId())).forEach(m->mods.addProperty(m.getMetadata().getId(),m.getMetadata().getVersion().getFriendlyString()));
        result.add("loaded_mods",mods);
        result.addProperty("server","integrated");
        return result;
    }
    private static JsonObject sample(ClientGameTestContext c,String label,int count){
        c.waitTicks(40);var ticks=new CopyOnWriteArrayList<Double>();TICKS.set(ticks);var frames=new ArrayList<Double>();var fps=new ArrayList<Double>();
        var limits=new TreeSet<Integer>();var reasons=new TreeSet<String>();int[] windowSamples=new int[4];var swapIntervals=new TreeSet<Integer>();
        long start=System.nanoTime();
        for(int i=0;i<24;i++){c.waitTicks(5);c.runOnClient(mc->{if(mc.getFrameTimeNs()>0)frames.add(mc.getFrameTimeNs()/1e6);fps.add((double)mc.getFps());
            limits.add(mc.getFramerateLimitTracker().getFramerateLimit());reasons.add(mc.getFramerateLimitTracker().getThrottleReason().name());
            if(mc.getWindow().isFocused())windowSamples[0]++;if(mc.getWindow().isIconified())windowSamples[1]++;if(mc.isWindowActive())windowSamples[2]++;
            if((org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle())&org.lwjgl.sdl.SDLVideo.SDL_WINDOW_INPUT_FOCUS)!=0)windowSamples[3]++;
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()){var interval=stack.mallocInt(1);if(org.lwjgl.sdl.SDLVideo.SDL_GL_GetSwapInterval(interval))swapIntervals.add(interval.get(0));}
        });}
        TICKS.set(null);var row=new JsonObject();row.addProperty("case",label);row.addProperty("devices",count);row.addProperty("sample_elapsed_ms",(System.nanoTime()-start)/1e6);statistics(row,"server_tick_ms",ticks);statistics(row,"sampled_render_cpu_ms",frames);statistics(row,"reported_fps",fps);
        row.add("effective_fps_limits",JSON.toJsonTree(limits));row.add("throttle_reasons",JSON.toJsonTree(reasons));row.addProperty("window_focused_samples",windowSamples[0]);row.addProperty("window_iconified_samples",windowSamples[1]);row.addProperty("client_active_samples",windowSamples[2]);row.addProperty("sdl_focused_samples",windowSamples[3]);row.add("sdl_gl_swap_intervals",JSON.toJsonTree(swapIntervals));return row;
    }
    private static final class MapScreen extends Screen {
        final ProtectionMap map=new ProtectionMap();final UUID region=UUID.fromString("1312bb92-a936-46c5-a360-ef28b845c6c0");final ProtectionShape shape=ProtectionShape.rectangle(-15,-60,-15,15,-45,15);
        final List<Double> ns=new ArrayList<>(),bytes=new ArrayList<>();boolean measuring;int frames;
        MapScreen(){super(Component.literal("UMBRAL performance fixture"));map.center(shape,0,0);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void tick(){map.tick();}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
            long before=allocated(),start=System.nanoTime();map.render(g,8,8,width-16,height-16,true,-60,-44,List.of(),region,shape,false,List.of());long elapsed=System.nanoTime()-start,after=allocated();frames++;
            if(measuring){ns.add(elapsed/1e3);if(before>=0&&after>=before)bytes.add((double)(after-before));}
        }
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.graphicsPreset().set(net.minecraft.client.GraphicsPreset.FANCY);mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(5);mc.options.simulationDistance().set(5);mc.options.enableVsync().set(false);mc.options.framerateLimit().set(240);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);c.runOnClient(mc->check(mc.options.renderDistance().get()==5&&mc.options.simulationDistance().get()==5&&!mc.options.enableVsync().get()&&mc.options.framerateLimit().get()==240,"Pinned benchmark settings survive preset application and reload"));
        if(!registered){registered=true;ServerTickEvents.END_SERVER_TICK.register(s->{
            for(var d:FEED.get())if(d.getLevel() instanceof ServerLevel l&&!d.isRemoved()&&d.kind().turret()&&d.turret().count()<32)d.turret().load(new ItemStack(d.kind()==DeviceKind.CENTELLA?Items.IRON_NUGGET:Items.ARROW,64),l.getGameTime());
            var out=TICKS.get();if(out!=null){long[] times=s.getTickTimesNanos();long n=times[Math.floorMod(s.getTickCount()-1,times.length)];if(n>0)out.add(n/1e6);}
        });}
        // A stationary measurement should not switch to the AFK frame cap mid-run.
        // This affects only the disposable benchmark client's ordinary game options.
        c.runOnClient(mc->mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED));
        String version=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("umbral").orElseThrow().getMetadata().getVersion().getFriendlyString();var output=new JsonObject();output.addProperty("version",version);output.addProperty("started_at_utc",java.time.Instant.now().toString());output.add("environment",c.computeOnClient(PerformanceClientGameTest::environment));output.addProperty("methodology","40 client-tick warmup; 120 client-tick sample per view, 24 samples of Minecraft pre-presentation CPU frame timer (not whole-frame latency); previous completed server tick ring samples exclude waits; inactivity cap set to MINIMIZED, effective cap and focus recorded per view; compare only runs with matching actual hardware, settings and workload; allocated bytes are not retained RAM");var measurements=new JsonArray();output.add("measurements",measurements);
        var captures=Path.of("../../performance",version);try{Files.createDirectories(captures);}catch(Exception e){throw new RuntimeException(e);}
        for(int count:new int[]{0,16,64,128}){
            try(var world=c.worldBuilder().create()){
                var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");var targets=new ArrayList<DeviceBlockEntity>();var roots=new ArrayList<BlockPos>();
                world.getServer().runOnServer(s->{var l=conn.getServerLevel();var owner=conn.getServerPlayer();for(int x=-18;x<=18;x++)for(int z=-14;z<=16;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                    DeviceBlockEntity core=null;if(count>0){core=put(l,new BlockPos(0,-60,4),UmbralContent.CORE,owner.getUUID());roots.add(core.getBlockPos());l.setBlockAndUpdate(core.getBlockPos().below(),UmbralContent.CABLE.defaultBlockState());}
                    for(int i=1;i<count;i++){
                        var at=new BlockPos(-8+(i-1)%16,-60,1+(i-1)/16);if(at.equals(new BlockPos(0,-60,4)))at=at.above(3);
                        var block=switch(i%4){case 0->UmbralContent.TURRET;case 1->UmbralContent.CENTELLA;case 2->UmbralContent.CAMERA;default->UmbralContent.NODE;};var d=put(l,at,block,owner.getUUID());check(d.bind(owner.getUUID(),core),"Load fixture stays within the real installation limit");roots.add(at);l.setBlockAndUpdate(at.below(),UmbralContent.CABLE.defaultBlockState());
                        if(d.kind().turret()){d.turret().load(new ItemStack(d.kind()==DeviceKind.CENTELLA?Items.IRON_NUGGET:Items.ARROW,64),l.getGameTime());d.turret().setArmed(true,l.getGameTime());targets.add(d);}
                        if(d.kind()==DeviceKind.CAMERA){d.cameraMotion().command(CameraMotion.Command.AUTO,l.getGameTime(),20);d.syncMotion();}
                    }
                    for(int i=0;i<48;i++){var mob=EntityTypes.HUSK.create(l,EntitySpawnReason.COMMAND);check(mob!=null,"Benchmark hostile creates");mob.setNoAi(true);mob.setPos(-7.5+i%16,-60,-7.5+i/16);mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000);mob.setHealth(10000);l.addFreshEntity(mob);}
                    owner.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);owner.teleportTo(.5,-60,-18.5);NetworkService.invalidate(l);
                });FEED.set(List.copyOf(targets));conn.waitForClientboundPackets();conn.waitForChunksRender();c.getInput().lookAt(0,12);c.waitTicks(30);
                var front=sample(c,"loaded_front",count);world.getServer().runOnServer(s->{long fired=targets.stream().filter(d->d.turret().motion().shotAt()>=0).count();check(count==0||fired>0,"Loaded combat fixture must really fire");front.addProperty("turrets_that_fired",fired);});measurements.add(front);c.takeScreenshot(TestScreenshotOptions.of("load-"+count+"-front").disableCounterPrefix().withDestinationDir(captures));
                c.getInput().lookAt(180,0);measurements.add(sample(c,"loaded_behind",count));
                if(count==128){c.runOnClient(mc->{var d=(DeviceBlockEntity)mc.level.getBlockEntity(roots.stream().filter(p->p.getX()==-8).findFirst().orElseThrow());DeviceRenderer renderer=(DeviceRenderer)mc.getBlockEntityRenderDispatcher().<DeviceBlockEntity,DeviceRenderer.State>getRenderer(d);
                    for(int n=0;n<300;n++){var state=renderer.createRenderState();renderer.extractRenderState(d,state,0,mc.player.position(),null);}var times=new ArrayList<Double>();var bytes=new ArrayList<Double>();
                    for(int batch=0;batch<31;batch++){long before=allocated(),start=System.nanoTime();for(int n=0;n<100;n++){var state=renderer.createRenderState();renderer.extractRenderState(d,state,0,mc.player.position(),null);}long elapsed=System.nanoTime()-start,after=allocated();times.add(elapsed/100e3);bytes.add((after-before)/100d);}
                    var row=new JsonObject();row.addProperty("case","wired_device_state_extraction");statistics(row,"operation_us",times);statistics(row,"allocated_bytes",bytes);measurements.add(row);
                });}
                FEED.set(List.of());
            }finally{TICKS.set(null);FEED.set(List.of());}
        }
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runOnServer(s->{var l=conn.getServerLevel();for(int x=-15;x<=15;x++)for(int z=-15;z<=15;z++)for(int y=-60;y<=-46;y++)if(((x+z+y)&1)==0)l.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState());conn.getServerPlayer().teleportTo(.5,-43,-18.5);});conn.waitForClientboundPackets();conn.waitForChunksRender();
            var screen=c.computeOnClient(mc->{var view=new MapScreen();mc.gui.setScreen(view);return view;});c.waitTicks(80);c.runOnClient(mc->screen.measuring=true);c.waitTicks(120);c.runOnClient(mc->{screen.measuring=false;var row=new JsonObject();row.addProperty("case","stationary_3d_map");statistics(row,"extraction_us",screen.ns);statistics(row,"allocated_bytes",screen.bytes);measurements.add(row);});c.takeScreenshot(TestScreenshotOptions.of("stationary-map").disableCounterPrefix().withDestinationDir(captures));c.runOnClient(mc->mc.gui.setScreen(null));
        }
        try{Files.writeString(captures.getParent().resolve("client-"+version+".json"),JSON.toJson(output));}catch(Exception e){throw new RuntimeException(e);}
    }
}
