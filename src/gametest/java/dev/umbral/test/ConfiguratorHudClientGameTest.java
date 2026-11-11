// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.ConfiguratorHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.UUID;

/** Actual tool gestures, owner-only inventory synchronization and compact HUD evidence. */
@SuppressWarnings("UnstableApiUsage")
public final class ConfiguratorHudClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks",System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1"));
    private static final BlockPos CORE=new BlockPos(0,-60,0),DOOR=new BlockPos(2,-60,0),READER=new BlockPos(4,-60,0),DOCK=new BlockPos(6,-60,0),STOCK=new BlockPos(6,-60,2),FOREIGN=new BlockPos(8,-60,0);
    private static final UUID FOREIGN_OWNER=UUID.fromString("a012aa72-27a1-47b3-a82b-74b03000f039");
    private static DeviceBlockEntity device(ServerLevel l,BlockPos p){return (DeviceBlockEntity)l.getBlockEntity(p);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.runOnClient(mc->mc.gui.toastManager().clear());c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void aim(ClientGameTestContext c,BlockPos pos,double height,double depth){
        FilterClientGameTest.aim(c,Vec3.atLowerCornerOf(pos).add(.5,height,depth));
        c.waitFor(mc->mc.hitResult instanceof BlockHitResult hit&&hit.getBlockPos().equals(pos));
    }
    private static void use(ClientGameTestContext c,BlockPos pos,double height,double depth,boolean sneak){
        aim(c,pos,height,depth);if(sneak){c.getInput().holdShift();c.waitTicks(3);}c.getInput().pressKey(o->o.keyUse);c.waitTicks(5);if(sneak)c.getInput().releaseShift();
    }
    private static void hudHas(ClientGameTestContext c,String key,Object...args){c.waitFor(mc->ConfiguratorHud.visible(mc)&&ConfiguratorHud.lines(mc).stream().anyMatch(line->line.getString().equals(Component.translatable("configurator.umbral."+key,args).getString())));}
    private static void layout(ClientGameTestContext c){
        c.runOnClient(mc->{check(ConfiguratorHud.visible(mc),"Held tool HUD is visible");int width=Math.min(280,mc.getWindow().getGuiScaledWidth()-16),height=22+ConfiguratorHud.lines(mc).size()*10;
            check(width>=120&&height<=mc.getWindow().getGuiScaledHeight()/2-4,"Selection HUD fits the upper half of the small viewport");
            for(var line:ConfiguratorHud.lines(mc))check(mc.font.width(line)<=width-18,"Context and selections remain readable without truncation: "+line.getString());});
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var es=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->es.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var l=conn.getServerLevel();var p=conn.getServerPlayer();for(int x=-3;x<=11;x++)for(int z=-5;z<=5;z++)l.setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.SMOOTH_STONE.defaultBlockState());
                for(var at:new BlockPos[]{CORE,DOOR,READER,DOCK,FOREIGN}){
                    var block=at.equals(CORE)?UmbralContent.CORE:at.equals(DOOR)?UmbralContent.DOOR:at.equals(READER)?UmbralContent.READER:at.equals(DOCK)?UmbralContent.DOCK:UmbralContent.NODE;
                    l.setBlockAndUpdate(at,block.defaultBlockState());device(l,at).initializeOwner(at.equals(FOREIGN)?FOREIGN_OWNER:p.getUUID(),at.equals(CORE)?"Casa":"");
                }
                l.setBlockAndUpdate(DOOR.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));
                for(var at:new BlockPos[]{DOOR,READER,DOCK})check(device(l,at).bind(p.getUUID(),device(l,CORE)),"Fixture equipment belongs to one installation");
                l.setBlockAndUpdate(STOCK,Blocks.CHEST.defaultBlockState());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));
            });
            world.getServer().runCommand("tp @a 0.5 -60 -2.5 0 0");conn.waitForClientboundPackets();conn.waitForChunksRender();
            hudHas(c,"network_none");use(c,CORE,.5,.5,false);c.waitFor(mc->mc.player.getMainHandItem().has(UmbralContent.SELECTED_NETWORK));hudHas(c,"network",Component.literal(CORE.toShortString()));layout(c);capture(c,"umbral-configurator-core");
            world.getServer().runCommand("tp @a 2.5 -60 -2.5 0 0");conn.waitForClientboundPackets();use(c,DOOR,.6,.5,true);c.waitFor(mc->mc.player.getMainHandItem().has(UmbralContent.SELECTED_DOOR));hudHas(c,"door",Component.literal(DOOR.toShortString()));
            world.getServer().runCommand("tp @a 4.5 -60 -2.5 0 0");conn.waitForClientboundPackets();aim(c,READER,.5,.9);hudHas(c,"use",Component.translatable("configurator.umbral.assign_door"));capture(c,"umbral-configurator-door");use(c,READER,.5,.9,false);c.waitFor(mc->!mc.player.getMainHandItem().has(UmbralContent.SELECTED_DOOR));
            world.getServer().runOnServer(server->check(device(conn.getServerLevel(),READER).doorTarget().equals(device(conn.getServerLevel(),DOOR).asDoorTarget()),"Actual tool click pairs the selected real door"));
            world.getServer().runCommand("tp @a 6.5 -60 0.5 0 0");conn.waitForClientboundPackets();use(c,STOCK,.5,.5,true);c.waitFor(mc->mc.player.getMainHandItem().has(UmbralContent.SELECTED_STOCK));hudHas(c,"stock",Component.literal(STOCK.toShortString()));capture(c,"umbral-configurator-stock");
            world.getServer().runCommand("tp @a 6.5 -60 -2.5 0 0");conn.waitForClientboundPackets();aim(c,DOCK,.12,.5);hudHas(c,"use",Component.translatable("configurator.umbral.assign_stock"));use(c,DOCK,.12,.5,false);c.waitFor(mc->!mc.player.getMainHandItem().has(UmbralContent.SELECTED_STOCK));
            world.getServer().runOnServer(server->check(device(conn.getServerLevel(),DOCK).dock().stock()!=null&&device(conn.getServerLevel(),DOCK).dock().stock().position().equals(STOCK),"Actual tool assigns the sealed stock"));
            // Keep both optional selections at once; each receives its own unclipped line.
            world.getServer().runCommand("tp @a 2.5 -60 -2.5 0 0");conn.waitForClientboundPackets();use(c,DOOR,.6,.5,true);
            world.getServer().runCommand("tp @a 6.5 -60 0.5 0 0");conn.waitForClientboundPackets();use(c,STOCK,.5,.5,true);c.waitFor(mc->mc.player.getMainHandItem().has(UmbralContent.SELECTED_DOOR)&&mc.player.getMainHandItem().has(UmbralContent.SELECTED_STOCK));
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();var ops=conn.getServerLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);var old=p.getMainHandItem();var restored=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,old).getOrThrow()).getOrThrow();check(ItemStack.isSameItemSameComponents(old,restored),"Inventory persistence preserves all selection identities");p.setItemInHand(InteractionHand.MAIN_HAND,restored);});conn.waitForClientboundPackets();
            world.getServer().runCommand("tp @a 8.5 -60 -2.5 0 0");conn.waitForClientboundPackets();use(c,FOREIGN,.3,.5,false);
            world.getServer().runOnServer(server->check(device(conn.getServerLevel(),FOREIGN).ownedBy(FOREIGN_OWNER)&&device(conn.getServerLevel(),FOREIGN).link()==null&&conn.getServerPlayer().getMainHandItem().get(UmbralContent.SELECTED_NETWORK).equals(device(conn.getServerLevel(),CORE).link()),"HUD selections never authorize foreign device access"));
            c.getInput().lookAt(180,-45);c.waitTicks(3);layout(c);capture(c,"umbral-configurator-all-selections");
            c.getInput().pressKey(InputConstants.KEY_F1);c.waitFor(mc->!ConfiguratorHud.visible(mc));capture(c,"umbral-configurator-f1-hidden");c.getInput().pressKey(InputConstants.KEY_F1);c.waitFor(ConfiguratorHud::visible);
            c.getInput().pressKey(o->o.keyInventory);c.waitFor(mc->mc.gui.screen()!=null);c.runOnClient(mc->check(!ConfiguratorHud.visible(mc),"Open screens suppress the tool HUD"));c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            c.getInput().resizeWindow(640,480);c.waitTicks(6);layout(c);capture(c,"umbral-configurator-small-es");
            var en=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->en.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-configurator-small-en");
            c.getInput().holdShift();c.waitTicks(3);c.getInput().pressKey(o->o.keyUse);c.waitTicks(5);c.getInput().releaseShift();c.waitFor(mc->{var held=mc.player.getMainHandItem();return !held.has(UmbralContent.SELECTED_NETWORK)&&!held.has(UmbralContent.SELECTED_DOOR)&&!held.has(UmbralContent.SELECTED_STOCK);});hudHas(c,"network_none");capture(c,"umbral-configurator-cleared");
            try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("configurator-proof.json"),"{\"version\":\""+System.getenv().getOrDefault("UMBRAL_CAPTURE_VERSION","1.6.1")+"\",\"actual_core_selection\":true,\"actual_door_assignment\":true,\"actual_stock_assignment\":true,\"all_components_persisted\":true,\"foreign_owner_denied\":true,\"f1_hidden\":true,\"screens_hidden\":true,\"small_es_en_unclipped\":true,\"actual_clear\":true}");}catch(java.io.IOException error){throw new RuntimeException(error);}
        }finally{c.getInput().releaseShift();c.getInput().resizeWindow(1280,800);var reset=c.computeOnClient(mc->{if(mc.gui.hud.isHidden())mc.gui.hud.toggle();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reset.isDone()&&mc.gui.overlay()==null,1000);}
    }
}
