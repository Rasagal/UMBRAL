// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.authlib.GameProfile;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.client.*;
import dev.umbral.filter.*;
import dev.umbral.installation.NexoProtocol;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real NEXO → setup → FILTRO UI → physical card and inventory-gated entry. */
@SuppressWarnings("UnstableApiUsage")
public final class AccessSetupClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static final BlockPos CORE=new BlockPos(-5,-60,1),FILTER=new BlockPos(-1,-60,0),DOOR=new BlockPos(0,-60,3),READER=new BlockPos(1,-60,2);
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static DeviceBlockEntity d(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(5);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void click(ClientGameTestContext c,String key){FilterClientGameTest.click(c,key);}
    private static void choose(ClientGameTestContext c,String prefix){
        c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().startsWith(prefix)));
        String text=c.computeOnClient(mc->mc.gui.screen().children().stream().filter(v->v instanceof Button b&&b.active&&b.getMessage().getString().startsWith(prefix)).map(v->((Button)v).getMessage().getString()).findFirst().orElseThrow());c.clickScreenButton(text);c.waitTicks(9);
    }
    private static void layout(ClientGameTestContext c){FilterClientGameTest.layout(c);}
    private static void lostReviewReply(ClientGameTestContext c){
        // Simulate the timeout left by a dropped reply, then recover through real QUERY/REVIEW packets.
        c.runOnClient(mc->{try{var screen=(AccessSetupScreen)mc.gui.screen();var clock=AccessSetupScreen.class.getDeclaredField("ticks");clock.setAccessible(true);int now=clock.getInt(screen);
            for(var entry:Map.<String,Object>of("pending",true,"reviewed",false,"sentAt",now-61,"lastUpdate",now-81).entrySet()){var field=AccessSetupScreen.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(screen,entry.getValue());}
        }catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
        c.waitTicks(2);c.waitFor(mc->mc.gui.screen() instanceof AccessSetupScreen&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("setup.umbral.save").getString())),240);
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(5);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        AtomicReference<ServerPlayer> guest=new AtomicReference<>();
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-7;x<=5;x++)for(int z=-5;z<=6;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                FilterClientGameTest.put(p,CORE,UmbralContent.CORE,"Casa principal");FilterClientGameTest.put(p,DOOR,UmbralContent.DOOR,"Puerta del patio");l.setBlockAndUpdate(DOOR.above(),d(l,DOOR).getBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));FilterClientGameTest.put(p,READER,UmbralContent.READER,"Lector del patio");
                p.setYRot(0);p.setPos(-.5,-60,-2.5);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.FILTER));var floor=FILTER.below();UmbralContent.FILTER.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false)));check(FilterBlock.complete(d(l,FILTER)),"Real kit creates the setup's complete scanner");d(l,FILTER).rename(p.getUUID(),"FILTRO Acceso");p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);p.teleportTo(-4.5,-60,-1.5);NexoProtocol.open(p,d(l,CORE));
            });c.waitForScreen(NexoScreen.class);click(c,"nexo.umbral.setup");c.waitForScreen(AccessSetupScreen.class);layout(c);capture(c,"umbral-setup-door");
            choose(c,"Puerta del patio");layout(c);capture(c,"umbral-setup-reader");choose(c,"Lector del patio");layout(c);capture(c,"umbral-setup-filter");choose(c,"FILTRO Acceso");layout(c);capture(c,"umbral-setup-review");
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();check(d(l,DOOR).link()==null&&d(l,READER).link()==null&&d(l,FILTER).link()==null,"Selecting and reviewing never mutates the installation");});
            lostReviewReply(c);
            c.getInput().resizeWindow(640,480);c.waitTicks(5);layout(c);capture(c,"umbral-setup-review-small");
            var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-setup-review-english-small");
            click(c,"setup.umbral.save");world.getServer().runOnServer(s->{var l=conn.getServerLevel();var link=d(l,CORE).link();check(link.equals(d(l,DOOR).link())&&link.equals(d(l,READER).link())&&link.equals(d(l,FILTER).link()),"One explicit save pairs all three owned endpoints");check(d(l,READER).doorTarget().equals(d(l,DOOR).asDoorTarget())&&d(l,FILTER).filter().door().equals(d(l,DOOR).asDoorTarget()),"Reader and inspection refer to the exact selected door");check(!d(l,FILTER).filter().armed()&&AccessRegistry.get(l).list(conn.getServerPlayer().getUUID(),link).isEmpty(),"Setup neither arms inspection nor creates a pass");});capture(c,"umbral-setup-saved-small");
            c.getInput().resizeWindow(1280,800);var es=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);
            click(c,"setup.umbral.configure_filter");c.waitForScreen(FilterScreen.class);click(c,"filter.umbral.preset");click(c,"filter.umbral.mode.inform");click(c,"filter.umbral.intent.access");click(c,"filter.umbral.arm");layout(c);capture(c,"umbral-setup-inspection-active");
            c.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);c.waitTicks(4);c.runOnClient(mc->check(mc.gui.screen() instanceof AccessSetupScreen,"FILTRO returns to access setup, found "+mc.gui.screen()));c.waitForScreen(AccessSetupScreen.class);
            c.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);c.waitTicks(4);c.runOnClient(mc->check(mc.gui.screen() instanceof NexoScreen,"Access setup returns to NEXO, found "+mc.gui.screen()));c.waitForScreen(NexoScreen.class);
            world.getServer().runOnServer(s->{var l=conn.getServerLevel();var p=conn.getServerPlayer();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.ACCESS_CARD));check(AccessService.issue(p,d(l,READER))==AccessService.Result.ISSUED,"Owner can issue a real physical pass at the prepared reader");ItemStack card=ItemStack.EMPTY;for(int slot=0;slot<36;slot++)if(p.getInventory().getItem(slot).has(UmbralContent.CARD_ID)){card=p.getInventory().getItem(slot).copy();break;}check(!card.isEmpty(),"Issued physical card is present");
                var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"VisitanteAcceso"),false);var visitor=new ServerPlayer(s,l,cookie.gameProfile(),cookie.clientInformation()){@Override public GameType gameMode(){return GameType.SURVIVAL;}@Override public boolean isClientAuthoritative(){return false;}};var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);s.getPlayerList().placeNewPlayer(connection,visitor,cookie);guest.set(visitor);visitor.setPos(.5,-60,.5);visitor.getInventory().setItem(0,new ItemStack(Items.TNT,3));check(AccessService.present(visitor,d(l,READER),card)==AccessService.Result.SCAN_BLOCKED,"Prepared inspection blocks a visitor carrying TNT");visitor.getInventory().setItem(0,ItemStack.EMPTY);check(AccessService.present(visitor,d(l,READER),card)==AccessService.Result.GRANTED,"The same physical card opens the door after the inventory is clear");s.getPlayerList().remove(visitor);guest.set(null);
            });
            try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("access-setup-proof.json"),"{\"version\":\"1.6.1\",\"native_nexo_entry\":true,\"review_timeout_recovered\":true,\"review_read_only\":true,\"owned_endpoints_paired\":3,\"small_es_en\":true,\"filter_explicit_activation\":true,\"card_with_tnt\":\"SCAN_BLOCKED\",\"clear_card\":\"GRANTED\"}");}catch(Exception e){throw new RuntimeException(e);}
        }finally{c.getInput().resizeWindow(1280,800);}
    }
}
