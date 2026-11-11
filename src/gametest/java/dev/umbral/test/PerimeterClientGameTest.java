// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.airlock.*;
import dev.umbral.block.*;
import dev.umbral.client.*;
import dev.umbral.fence.*;
import dev.umbral.filter.*;
import dev.umbral.installation.*;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

@SuppressWarnings("UnstableApiUsage")
public final class PerimeterClientGameTest implements FabricClientGameTest {
    static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    static final BlockPos CORE=new BlockPos(-5,-60,1),NODE=new BlockPos(-3,-60,1),FILTER=new BlockPos(-1,-60,0),IN=new BlockPos(0,-60,3),OUT=new BlockPos(0,-60,-3),READER=new BlockPos(1,-60,-4),NEXO=new BlockPos(-3,-60,-2),FENCE=new BlockPos(3,-60,2);
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static DeviceBlockEntity d(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    static DeviceBlockEntity put(ServerPlayer p,BlockPos pos,DeviceBlock block,String name){var l=(ServerLevel)p.level();l.setBlockAndUpdate(pos,block.defaultBlockState());var d=d(l,pos);d.initializeOwner(p.getUUID(),name);return d;}
    static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.runOnClient(mc->mc.gui.toastManager().clear());c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    static void click(ClientGameTestContext c,String key){FilterClientGameTest.click(c,key);}
    static void aim(ClientGameTestContext c,Vec3 v){FilterClientGameTest.aim(c,v);}
    static void layout(ClientGameTestContext c){FilterClientGameTest.layout(c);}
    static void selectDoor(ClientGameTestContext c,int index,String name){for(int n=0;n<17;n++){String label=c.computeOnClient(mc->mc.gui.screen().children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList().get(index).getMessage().getString());if(label.contains(name))return;c.clickScreenButton(label);c.waitTicks(1);}throw new AssertionError("Door missing from native console: "+name);}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.resizeGui();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        var visitor=new AtomicReference<ServerPlayer>();var monitor=new AtomicReference<ServerLevel>();var violation=new AtomicBoolean();
        ServerTickEvents.END_SERVER_TICK.register(server->{var l=monitor.get();if(l==null)return;var a=d(l,OUT);var b=d(l,IN);if(a!=null&&b!=null&&a.doorMotion().state().position(l.getGameTime())>.001&&b.doorMotion().state().position(l.getGameTime())>.001)violation.set(true);});
        var flag=OUTPUT.resolve("perimeter-recording.flag");
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");
            String player=c.computeOnClient(mc->mc.player.getGameProfile().name());
            world.getServer().runCommand("gamerule minecraft:advance_time false");world.getServer().runCommand("time set noon");
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();var l=conn.getServerLevel();
                for(int x=-8;x<=11;x++)for(int z=-7;z<=7;z++){l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());for(int y=-60;y<=-55;y++)l.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
                var core=put(p,CORE,UmbralContent.CORE,"Núcleo");l.setBlockAndUpdate(CORE.east(),UmbralContent.CABLE.defaultBlockState());put(p,NODE,UmbralContent.NODE,"Repetidor").bind(p.getUUID(),core);put(p,NEXO,UmbralContent.NEXO,"NEXO · Acceso").bind(p.getUUID(),core);
                p.setPos(-.5,-60,-2);p.setYRot(0);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=FILTER.below();UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));var f=d(l,FILTER);check(FilterBlock.complete(f),"Full physical arch");f.bind(p.getUUID(),core);f.filter().rule("minecraft:tnt",1);
                for(var pos:List.of(OUT,IN)){var door=put(p,pos,UmbralContent.DOOR,pos.equals(OUT)?"01 · Exterior":"02 · Interior");l.setBlockAndUpdate(pos.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));door.bind(p.getUUID(),core);}
                var r=put(p,READER,UmbralContent.READER,"Lector exterior");r.bind(p.getUUID(),core);r.targetDoor(p.getUUID(),d(l,OUT));
                for(int x=3;x<=9;x++)put(p,new BlockPos(x,-60,2),UmbralContent.FENCE,x==3?"CERCO · Patio":"");for(int z=-2;z<2;z++)put(p,new BlockPos(9,-60,z),UmbralContent.FENCE,"");
                check(FenceService.pair(p,d(l,FENCE),core).equals("fence_paired"),"Whole physical fence pairs");check(java.util.stream.IntStream.rangeClosed(3,9).filter(x->d(l,new BlockPos(x,-60,2)).getBlockState().getValue(ElectricFenceBlock.SUPPORT)).count()==3,"Seven front sections have three slim supports");
                for(int x=-3;x<=3;x++)l.setBlockAndUpdate(new BlockPos(x,-60,5),UmbralContent.CABLE.defaultBlockState());for(int z=2;z<5;z++)l.setBlockAndUpdate(new BlockPos(-3,-60,z),UmbralContent.CABLE.defaultBlockState());for(int z=3;z<5;z++)l.setBlockAndUpdate(new BlockPos(3,-60,z),UmbralContent.CABLE.defaultBlockState());
                var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"Visitante"),false);var guest=new ServerPlayer(server,l,cookie.gameProfile(),cookie.clientInformation()){@Override public GameType gameMode(){return GameType.SURVIVAL;}@Override public boolean isClientAuthoritative(){return false;}};var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);server.getPlayerList().placeNewPlayer(connection,guest,cookie);guest.setPos(.5,-60,-5);
                var grant=AccessRegistry.get(l).issue(p.getUUID(),core.link(),"Visita");var card=new ItemStack(UmbralContent.ACCESS_CARD);card.set(UmbralContent.CARD_ID,grant.id());guest.getInventory().setItem(0,card);visitor.set(guest);p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);monitor.set(l);
            });
            world.getServer().runCommand("tp "+player+" 5 -60 -1.4 0 0");conn.waitForClientboundPackets();c.runOnClient(mc->{mc.options.fov().set(55);if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});aim(c,new Vec3(6,-59.3,2.5));c.waitTicks(12);capture(c,"umbral-cerco-off");
            world.getServer().runOnServer(s->NexoProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),FENCE)));c.waitForScreen(NexoScreen.class);FilterClientGameTest.selectNexo(c,"CERCO · Patio");click(c,"nexo.umbral.action.arm");c.waitTicks(23);capture(c,"umbral-cerco-nexo");c.clickScreenButton("?");c.waitForScreen(ManualScreen.class);capture(c,"umbral-cerco-manual");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(NexoScreen.class);c.getInput().resizeWindow(640,480);c.waitTicks(3);layout(c);capture(c,"umbral-cerco-nexo-small");c.getInput().resizeWindow(1280,800);c.waitTicks(3);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);capture(c,"umbral-cerco-powered");world.getServer().runOnServer(s->NexoProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),FENCE)));c.waitForScreen(NexoScreen.class);FilterClientGameTest.selectNexo(c,"CERCO · Patio");click(c,"nexo.umbral.action.fence_target");click(c,"nexo.umbral.action.fence_control");world.getServer().runOnServer(s->check(d(conn.getServerLevel(),FENCE).fence().redstone(),"Native control selects redstone"));c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);c.waitTicks(10);world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(!d(l,FENCE).getBlockState().getValue(ElectricFenceBlock.ENERGIZED),"Signal absent: safe inactive");l.setBlockAndUpdate(FENCE.west(),Blocks.REDSTONE_BLOCK.defaultBlockState());});c.waitTicks(10);capture(c,"umbral-cerco-redstone");
            world.getServer().runOnServer(s->check(NetworkService.inspect(conn.getServerLevel(),d(conn.getServerLevel(),FENCE)).status()==NetworkService.Status.WIRED,"Physical cable actually reaches the feeder"));world.getServer().runCommand("tp "+player+" 3.7 -60 5.7 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(3.5,-59.35,2.5));capture(c,"umbral-cerco-feeder");world.getServer().runCommand("tp "+player+" 5 -60 -1.4 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(6,-59.3,2.5));
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(d(l,new BlockPos(9,-60,-2)).getBlockState().getValue(ElectricFenceBlock.ENERGIZED),"Signal reaches corner and end");var mob=EntityTypes.HUSK.create(l,net.minecraft.world.entity.EntitySpawnReason.COMMAND);check(mob!=null,"Hostile created");mob.setPos(4.5,-60,2.15);mob.setNoAi(true);l.addFreshEntity(mob);});c.waitTicks(10);world.getServer().runOnServer(s->check(conn.getServerLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Husk.class,new AABB(3,-60,1,6,-57,3)).stream().anyMatch(m->m.getHealth()<m.getMaxHealth()),"Physical contact hurts hostile"));capture(c,"umbral-cerco-contact");
            world.getServer().runCommand("tp "+player+" -2.5 -60 -2.5 0 0");conn.waitForClientboundPackets();world.getServer().runOnServer(s->NexoProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),NEXO)));c.waitForScreen(NexoScreen.class);FilterClientGameTest.selectNexo(c,"FILTRO");click(c,"nexo.umbral.action.configure_airlock");c.waitForScreen(AirlockScreen.class);selectDoor(c,0,"01 · Exterior");selectDoor(c,1,"02 · Interior");click(c,"airlock.umbral.save");click(c,"airlock.umbral.enable");layout(c);capture(c,"umbral-airlock-console");
            c.getInput().resizeWindow(640,480);c.waitTicks(4);layout(c);capture(c,"umbral-airlock-small");var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-airlock-english-small");c.getInput().resizeWindow(1280,800);var es=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(NexoScreen.class);c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runCommand("tp "+player+" 3.8 -60 -4.8 0 0");conn.waitForClientboundPackets();aim(c,new Vec3(.5,-58.8,.5));
            try{Files.createDirectories(OUTPUT);Files.writeString(flag,"CERCO and interlocked physical access");}catch(Exception e){throw new RuntimeException(e);}
            world.getServer().runOnServer(s->{var p=visitor.get();check(AccessService.present(p,d(conn.getServerLevel(),READER),p.getInventory().getItem(0))==AccessService.Result.AIRLOCK_QUEUED,"Real outside reader starts one turn");});c.waitTicks(15);capture(c,"umbral-airlock-entering");world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,.5));c.waitTicks(35);world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(AirlockService.closed(l,d(l,OUT))&&d(l,IN).getBlockState().getValue(SecurityDoorBlock.OPEN),"Inner only opens after exterior fully closed and current scan");});capture(c,"umbral-airlock-admitted");
            world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,5));c.waitTicks(35);world.getServer().runOnServer(s->{var p=visitor.get();p.setPos(.5,-60,-5);p.getInventory().setItem(2,new ItemStack(Items.TNT,3));check(AccessService.present(p,d(conn.getServerLevel(),READER),p.getInventory().getItem(0))==AccessService.Result.AIRLOCK_QUEUED,"Second turn accepts same physical bearer pass");});c.waitTicks(15);world.getServer().runOnServer(s->visitor.get().setPos(.5,-60,.5));c.waitTicks(35);world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(d(l,FILTER).airlock().returning&&!d(l,IN).getBlockState().getValue(SecurityDoorBlock.OPEN),"TNT subject returns outside, inner stays closed");});capture(c,"umbral-airlock-restricted");
            world.getServer().runCommand("tp "+player+" -2.5 -60 -2.5 0 0");conn.waitForClientboundPackets();world.getServer().runOnServer(s->AirlockProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),NEXO),d(conn.getServerLevel(),FILTER)));c.waitForScreen(AirlockScreen.class);capture(c,"umbral-airlock-alert-console");c.clickScreenButton("?");c.waitForScreen(ManualScreen.class);capture(c,"umbral-airlock-manual");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(AirlockScreen.class);
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);world.getServer().runOnServer(s->{visitor.get().setPos(.5,-60,-5);AccessProtocol.open(conn.getServerPlayer(),d(conn.getServerLevel(),READER));});c.waitForScreen(ReaderAccessScreen.class);layout(c);click(c,"airlock.umbral.clear_guard");c.waitForScreen(net.minecraft.client.gui.screens.ConfirmScreen.class);capture(c,"umbral-airlock-recovery");c.clickScreenButton("gui.yes");c.waitForScreen(ReaderAccessScreen.class);c.waitTicks(10);world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(d(l,OUT).airlockLink()==null&&d(l,IN).airlockLink()==null&&!d(l,FILTER).airlock().enabled(),"Native owner recovery releases both door interlocks");check(d(l,IN).filterGuard()!=null,"FILTRO policy is explicitly separate");});
            check(!violation.get(),"No server tick ever had both physical door motions open");
            world.getServer().runOnServer(s->s.getPlayerList().remove(visitor.get()));monitor.set(null);
            try{Files.writeString(OUTPUT.resolve("perimeter-proof.json"),"{\"version\":\"1.6.1\",\"fence_sections\":11,\"front_mesh_sections\":7,\"front_slim_supports\":3,\"native_nexo_arm_target_redstone\":true,\"live_hostile_shock\":true,\"airlock_native_configure_enable\":true,\"valid_pass_clear\":\"ADMITTED\",\"tnt_return_outside\":true,\"both_doors_ever_open\":false,\"small_es_en\":true,\"native_owner_recovery\":true}");}catch(Exception e){throw new RuntimeException(e);}
        }finally{monitor.set(null);try{Files.deleteIfExists(flag);}catch(Exception ignored){}c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});}
    }
}
