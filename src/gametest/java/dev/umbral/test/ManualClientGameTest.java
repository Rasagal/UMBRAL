// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.client.*;
import dev.umbral.manual.*;
import dev.umbral.manual.ManualCatalog.Entry;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import dev.umbral.installation.NexoProtocol;
import dev.umbral.protection.ProtectionProtocol;
import dev.umbral.turret.*;
import dev.umbral.access.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual first join, book use, help buttons, navigation, live server recipes and native renders. */
@SuppressWarnings("UnstableApiUsage")
public final class ManualClientGameTest implements FabricClientGameTest {
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object value,String name){try{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(3);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void layout(ClientGameTestContext c){c.runOnClient(mc->{var s=mc.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=s.width&&a.getBottom()<=s.height,"Manual controls fit viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"Manual buttons never overlap");}});}
    private static void click(ClientGameTestContext c,String key){c.clickScreenButton(key);c.waitTicks(4);}
    private static boolean has(ClientGameTestContext c,String label){return c.computeOnClient(mc->mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(label)));}
    private static void chapter(ClientGameTestContext c,Entry entry){
        c.runOnClient(mc->{var box=(EditBox)mc.gui.screen().children().getFirst();box.setValue(ManualScreen.title(entry).getString());});
        String name=c.computeOnClient(mc->ManualScreen.title(entry).getString());for(int n=0;!has(c,name)&&n<12;n++){check(has(c,">"),"Chapter is reachable through the actual search index: "+entry);click(c,">");}
        c.clickScreenButton(name);c.waitTicks(3);c.runOnClient(mc->check(((ManualScreen)mc.gui.screen()).entry()==entry,"Correct chapter opened: "+entry));
    }
    private static void recipe(ClientGameTestContext c){click(c,"manual.umbral.tab.recipe");c.waitFor(mc->mc.gui.screen() instanceof ManualScreen s&&(boolean)field(s,"received"),300);}
    private static void help(ClientGameTestContext c,Entry expected,Class<?> back){
        c.waitFor(mc->back.isInstance(mc.gui.screen()));click(c,"?");c.waitForScreen(ManualScreen.class);c.runOnClient(mc->check(((ManualScreen)mc.gui.screen()).entry()==expected,"Context help opens the device chapter"));layout(c);click(c,"manual.umbral.back");c.waitFor(mc->back.isInstance(mc.gui.screen()));
    }
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var language=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.options.renderDistance().set(4);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->language.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();int count=0;for(int n=0;n<p.getInventory().getContainerSize();n++)if(p.getInventory().getItem(n).is(UmbralContent.FIELD_MANUAL))count++;check(count==1&&ManualDelivery.data(conn.getServerLevel()).received(p.getUUID()),"Real JOIN callback delivers exactly one manual");ManualDelivery.joined(p);for(int x=-6;x<7;x++)for(int z=-6;z<7;z++)conn.getServerLevel().setBlockAndUpdate(new BlockPos(x,-61,z),Blocks.POLISHED_DEEPSLATE.defaultBlockState());});
            world.getServer().runCommand("tp @a 0.5 -60 -2.5 180 -25");conn.waitForClientboundPackets();c.waitTicks(10);c.runOnClient(mc->check(mc.gui.screen()==null,"First join never forces a tutorial screen"));capture(c,"umbral-manual-item");
            c.runOnClient(mc->mc.gui.setScreen(new InventoryScreen(mc.player)));capture(c,"umbral-manual-inventory");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            c.getInput().lookAt(180,-30);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(ManualScreen.class);layout(c);capture(c,"umbral-manual-home");
            Path flag=OUTPUT.resolve("manual-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"Native UMBRAL manual navigation and active world recipes");}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{
                chapter(c,Entry.FIRST_BASE);capture(c,"umbral-manual-first-base");click(c,"manual.umbral.tab.steps");capture(c,"umbral-manual-steps");recipe(c);capture(c,"umbral-manual-matrix-recipe");
                click(c,"manual.umbral.tab.visual");chapter(c,Entry.VIGIA);capture(c,"umbral-manual-camera-mounts");chapter(c,Entry.CENTELLA);capture(c,"umbral-manual-turrets");chapter(c,Entry.REPLENISHING);capture(c,"umbral-manual-logistics");
                for(var e:Entry.values()){chapter(c,e);layout(c);recipe(c);c.runOnClient(mc->check(((Optional<?>)field(mc.gui.screen(),"recipe")).isPresent(),"Native server display received for "+e));click(c,"manual.umbral.tab.visual");}
                c.runOnClient(mc->((EditBox)mc.gui.screen().children().getFirst()).setValue("CAMARA techo"));check(has(c,c.computeOnClient(mc->ManualScreen.title(Entry.VIGIA).getString())),"Accent-insensitive multiword search finds VIGÍA");capture(c,"umbral-manual-search");
            }finally{try{Files.deleteIfExists(flag);}catch(java.io.IOException e){throw new RuntimeException(e);}}
            chapter(c,Entry.BOOK);capture(c,"umbral-manual-book-model");recipe(c);capture(c,"umbral-manual-book-recipe");
            // Actual data-pack override, reload and removal: never fall back to a stale static ingredient list.
            var pack=world.getServer().computeOnServer(server->server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("umbral_manual_test"));
            try{
                Files.createDirectories(pack.resolve("data/umbral/recipe"));Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"description\":\"UMBRAL manual recipe test\",\"min_format\":"+SharedConstants.DATA_PACK_FORMAT_MAJOR+",\"max_format\":"+SharedConstants.DATA_PACK_FORMAT_MAJOR+"},\"filter\":{\"block\":[{\"namespace\":\"umbral\",\"path\":\"recipe/field_manual.json\"}]}}");
                Files.writeString(pack.resolve("data/umbral/recipe/field_manual.json"),"{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"misc\",\"ingredients\":[\"minecraft:paper\"],\"result\":{\"id\":\"umbral:copper_cable\",\"count\":4}}");
                var load=world.getServer().computeOnServer(server->{var repository=server.getPackRepository();repository.reload();var selected=new ArrayList<>(repository.getSelectedIds());check(repository.getAvailableIds().contains("file/umbral_manual_test"),"Test data pack discovered");selected.add("file/umbral_manual_test");return server.reloadResources(selected);});await(c,load);
                chapter(c,Entry.BOOK);recipe(c);c.runOnClient(mc->{var display=(RecipeDisplay)((Optional<?>)field(mc.gui.screen(),"recipe")).orElseThrow();var shaped=(ShapelessCraftingRecipeDisplay)display;var context=SlotDisplayContext.fromLevel(mc.level);check(shaped.ingredients().size()==1&&shaped.ingredients().getFirst().resolveForFirstStack(context).is(Items.PAPER)&&shaped.result().resolveForFirstStack(context).is(UmbralContent.CABLE.asItem())&&shaped.result().resolveForFirstStack(context).getCount()==4,"Recipe UI reflects active data-pack ingredients and result count");});capture(c,"umbral-manual-recipe-datapack");
                Files.delete(pack.resolve("data/umbral/recipe/field_manual.json"));var missing=world.getServer().computeOnServer(server->server.reloadResources(server.getPackRepository().getSelectedIds()));await(c,missing);chapter(c,Entry.BOOK);recipe(c);c.runOnClient(mc->check(((Optional<?>)field(mc.gui.screen(),"recipe")).isEmpty(),"Removed world recipe is reported unavailable, never invented"));capture(c,"umbral-manual-recipe-unavailable");
            }catch(java.io.IOException e){throw new RuntimeException(e);}finally{
                var restore=world.getServer().computeOnServer(server->{var selected=new ArrayList<>(server.getPackRepository().getSelectedIds());selected.remove("file/umbral_manual_test");return server.reloadResources(selected);});await(c,restore);
            }
            chapter(c,Entry.EXPANSION);click(c,"manual.umbral.tab.steps");c.runOnClient(mc->{mc.options.guiScale().set(3);mc.resizeGui();});layout(c);capture(c,"umbral-manual-gui3");c.getInput().resizeWindow(640,480);c.waitTicks(3);layout(c);capture(c,"umbral-manual-small-steps");recipe(c);layout(c);capture(c,"umbral-manual-small-recipe");click(c,"manual.umbral.tab.visual");chapter(c,Entry.VIGIA);layout(c);capture(c,"umbral-manual-small-mounts");
            var english=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->english.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-manual-english-small");
            c.getInput().resizeWindow(1280,800);c.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();});click(c,"manual.umbral.back");c.waitForScreen(null);
            // Context buttons use the same catalogue and return to the existing device screen.
            var corePos=new BlockPos(0,-60,0);var turretPos=new BlockPos(2,-60,0);var readerPos=new BlockPos(-2,-60,0);
            world.getServer().runOnServer(server->{var p=conn.getServerPlayer();var level=conn.getServerLevel();level.setBlockAndUpdate(corePos,UmbralContent.CORE.defaultBlockState());var core=(DeviceBlockEntity)level.getBlockEntity(corePos);core.initializeOwner(p.getUUID(),"Núcleo");level.setBlockAndUpdate(turretPos,UmbralContent.CENTELLA.defaultBlockState());var turret=(DeviceBlockEntity)level.getBlockEntity(turretPos);turret.initializeOwner(p.getUUID(),"CENTELLA");turret.bind(p.getUUID(),core);level.setBlockAndUpdate(readerPos,UmbralContent.READER.defaultBlockState());var reader=(DeviceBlockEntity)level.getBlockEntity(readerPos);reader.initializeOwner(p.getUUID(),"Lector");reader.bind(p.getUUID(),core);NetworkService.invalidate(level);NetworkProtocol.sendStatus(p,core,true);});help(c,Entry.CORE,NetworkStatusScreen.class);capture(c,"umbral-manual-context-core");
            world.getServer().runOnServer(server->NexoProtocol.open(conn.getServerPlayer(),(DeviceBlockEntity)conn.getServerLevel().getBlockEntity(corePos)));help(c,Entry.NEXO,NexoScreen.class);
            world.getServer().runOnServer(server->ServerPlayNetworking.send(conn.getServerPlayer(),TurretProtocol.snapshot(conn.getServerPlayer(),(DeviceBlockEntity)conn.getServerLevel().getBlockEntity(turretPos),0,true,TurretProtocol.Result.READY)));help(c,Entry.CENTELLA,TurretScreen.class);
            world.getServer().runOnServer(server->ServerPlayNetworking.send(conn.getServerPlayer(),AccessProtocol.snapshot(conn.getServerPlayer(),(DeviceBlockEntity)conn.getServerLevel().getBlockEntity(readerPos),0,true,AccessService.Result.READY)));help(c,Entry.READER,ReaderAccessScreen.class);
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);world.getServer().runOnServer(server->{var p=conn.getServerPlayer();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));ProtectionProtocol.openTool(p);});help(c,Entry.FIRST_BASE,ProtectionScreen.class);
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);world.getServer().runOnServer(server->{var p=conn.getServerPlayer();p.getInventory().clearContent();ManualDelivery.joined(p);for(int n=0;n<p.getInventory().getContainerSize();n++)check(!p.getInventory().getItem(n).is(UmbralContent.FIELD_MANUAL),"A lost gift is not duplicated by reconnect callback");});
        }
    }
    private static void await(ClientGameTestContext c,CompletableFuture<Void> future){c.waitFor(mc->future.isDone(),1000);future.join();c.waitTicks(4);}
}
