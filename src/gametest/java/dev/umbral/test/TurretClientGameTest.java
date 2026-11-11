// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.TurretScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public final class TurretClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE=new BlockPos(-3,-60,1),TURRET=new BlockPos(0,-60,0);
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(2);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static DeviceBlockEntity device(ServerLevel level,BlockPos pos){return (DeviceBlockEntity)level.getBlockEntity(pos);}
    private static int ammunition(net.minecraft.client.Minecraft client){return ((DeviceBlockEntity)client.level.getBlockEntity(TURRET)).turret().motion().ammunition();}
    private static void tool(ServerPlayer player,BlockPos pos){UmbralContent.CONFIGURATOR.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false)));}
    private static void waitButton(ClientGameTestContext c,String key){c.waitFor(client->client.gui.screen() instanceof TurretScreen s&&s.children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(Component.translatable(key).getString())));}
    private static void clickRange(ClientGameTestContext c,int value){c.clickScreenButton(c.computeOnClient(client->Component.translatable("turret.umbral.range_button",value).getString()));}
    private static void layout(ClientGameTestContext c){c.runOnClient(client->{
        var s=client.gui.screen();var buttons=s.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();
        for(var b:buttons){check(b.getX()>=0&&b.getY()>=0&&b.getRight()<=s.width&&b.getBottom()<=s.height,"Turret controls fit the viewport");
            for(var other:buttons)if(other!=b)check(b.getRight()<=other.getX()||other.getRight()<=b.getX()||b.getBottom()<=other.getY()||other.getBottom()<=b.getY(),"Turret controls do not overlap");}
    });}
    @Override public void runTest(ClientGameTestContext context){
        context.waitFor(c->c.gui.overlay()==null,1000);context.getInput().resizeWindow(1280,800);
        var reload=context.computeOnClient(client->{client.options.renderDistance().set(4);client.options.simulationDistance().set(5);client.options.guiScale().set(2);client.resizeGui();client.options.setCameraType(CameraType.FIRST_PERSON);
            client.getLanguageManager().setSelected("es_es");client.options.languageCode="es_es";return client.reloadResourcePacks();});
        context.waitFor(c->reload.isDone()&&c.gui.overlay()==null,1000);
        try(var world=context.worldBuilder().create()){
            var connection=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");
            world.getServer().runCommand("tp @a 2.5 -60 -2.5 0 0");connection.waitForClientboundPackets();
            AtomicReference<Husk> target=new AtomicReference<>();
            world.getServer().runOnServer(server->{
                var level=connection.getServerLevel();var player=connection.getServerPlayer();
                for(int x=-6;x<=6;x++)for(int z=-12;z<=6;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),(z%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                for(int x=-5;x<=5;x++)for(int y=-60;y<=-56;y++)level.setBlockAndUpdate(new BlockPos(x,y,5),(x==-5||x==5||y==-56?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                level.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());device(level,CORE).initializeOwner(player.getUUID(),"NEXO · Patio");
                var cameraPos=new BlockPos(-2,-58,4);level.setBlockAndUpdate(cameraPos,UmbralContent.CAMERA.defaultBlockState().setValue(CameraBlock.MOUNT,CameraBlock.Mount.WALL));
                device(level,cameraPos).initializeOwner(player.getUUID(),"VIGÍA · Patio");device(level,cameraPos).bind(player.getUUID(),device(level,CORE));
                var terminalPos=new BlockPos(3,-60,4);level.setBlockAndUpdate(terminalPos,UmbralContent.TERMINAL.defaultBlockState());device(level,terminalPos).initializeOwner(player.getUUID(),"Centro de vigilancia");device(level,terminalPos).bind(player.getUUID(),device(level,CORE));
                ItemStack block=new ItemStack(UmbralContent.TURRET);block.set(DataComponents.CUSTOM_NAME,Component.literal("BALUARTE · Patio"));player.setItemInHand(InteractionHand.MAIN_HAND,block);player.setYRot(0);
                UmbralContent.TURRET.asItem().useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(TURRET.below()).add(0,.5,0),Direction.UP,TURRET.below(),false)));
                check(level.getBlockState(TURRET).is(UmbralContent.TURRET),"Native BlockItem places the ballista");
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));tool(player,CORE);tool(player,TURRET);
                check(device(level,TURRET).link().equals(device(level,CORE).link()),"The existing configurator pairs the turret to the core");
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.getInventory().setItem(1,new ItemStack(Items.ARROW,32));
                player.getAbilities().flying=true;player.onUpdateAbilities();
                var husk=EntityTypes.HUSK.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                husk.setPos(.5,-60,-8.5);husk.setNoAi(true);husk.setPersistenceRequired();husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);husk.setHealth(80);level.addFreshEntity(husk);target.set(husk);
            });
            world.getServer().runCommand("tp @a 2.3 -60 -2.3 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(25);connection.waitForChunksRender();
            context.runOnClient(c->{if(!c.gui.hud.isHidden())c.gui.hud.toggle();});capture(context,"umbral-baluarte-empty-model");
            world.getServer().runCommand("tp @a 2.7 -60 1.0 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(6);capture(context,"umbral-baluarte-magazine");
            context.runOnClient(c->{if(c.gui.hud.isHidden())c.gui.hud.toggle();});
            world.getServer().runCommand("tp @a 0.5 -60 -2.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.getInput().pressKey(o->o.keyUse);
            context.waitForScreen(TurretScreen.class);waitButton(context,"turret.umbral.load");layout(context);capture(context,"umbral-baluarte-empty");
            context.clickScreenButton("turret.umbral.load");context.waitFor(c->ammunition(c)==32);waitButton(context,"turret.umbral.arm");
            world.getServer().runOnServer(server->check(connection.getServerPlayer().getInventory().getItem(1).isEmpty(),"UI transfers actual arrows even in creative"));
            capture(context,"umbral-baluarte-loaded");context.waitTicks(6);clickRange(context,12);context.waitTicks(10);connection.waitForServerboundPackets();
            world.getServer().runOnServer(server->check(device(connection.getServerLevel(),TURRET).turret().range()==16,"Real range button changes the server policy"));
            clickRange(context,16);context.waitTicks(10);clickRange(context,8);context.waitTicks(10);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);context.waitForScreen(null);
            int originalFov=context.computeOnClient(c->c.options.fov().get());
            context.runOnClient(c->c.options.fov().set(35));
            world.getServer().runCommand("tp @a -1.1 -59.5 -2.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(6);
            context.runOnClient(c->{if(!c.gui.hud.isHidden())c.gui.hud.toggle();});capture(context,"umbral-baluarte-detail");
            // Long-lens native views of the same placed model, not independent concept renders.
            world.getServer().runCommand("gamemode spectator @a");context.runOnClient(c->c.options.fov().set(30));
            world.getServer().runCommand("tp @a 0.5 -61.1 -2.8 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(5);capture(context,"umbral-baluarte-front");
            world.getServer().runCommand("tp @a -2.8 -61.1 0.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(5);capture(context,"umbral-baluarte-side");
            world.getServer().runCommand("tp @a 0.5 -61.1 3.8 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(5);capture(context,"umbral-baluarte-back");
            world.getServer().runCommand("tp @a 0.5 -58.0 0.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(5);capture(context,"umbral-baluarte-top");
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runOnServer(server->{var p=connection.getServerPlayer();p.getAbilities().flying=true;p.onUpdateAbilities();});
            context.runOnClient(c->c.options.fov().set(originalFov));
            context.runOnClient(c->{if(c.gui.hud.isHidden())c.gui.hud.toggle();});world.getServer().runCommand("tp @a 0.5 -60 -2.5 0 0");connection.waitForClientboundPackets();
            context.getInput().lookAt(TURRET);context.getInput().pressKey(o->o.keyUse);context.waitForScreen(TurretScreen.class);context.waitTicks(6);
            waitButton(context,"turret.umbral.arm");context.clickScreenButton("turret.umbral.arm");context.waitFor(c->ammunition(c)<32);context.waitTicks(22);capture(context,"umbral-baluarte-control");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);context.waitForScreen(null);
            context.runOnClient(c->c.options.fov().set(35));
            world.getServer().runCommand("tp @a -1.1 -59.5 -2.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.waitTicks(6);
            context.runOnClient(c->{if(!c.gui.hud.isHidden())c.gui.hud.toggle();});
            Path flag=OUTPUT.resolve("baluarte-recording.flag");
            try{Files.createDirectories(OUTPUT);Files.writeString(flag,"UMBRAL · physical ballista combat");}catch(IOException e){throw new RuntimeException(e);}
            try{
                context.waitTicks(100);
                world.getServer().runOnServer(server->target.get().setPos(2.5,-60,-8.5));context.waitTicks(60);
                world.getServer().runOnServer(server->target.get().setPos(-1.5,-60,-8.5));context.waitTicks(60);
                context.runOnClient(c->c.options.fov().set(originalFov));
                world.getServer().runCommand("tp @a 7.5 -58.5 -4.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(new BlockPos(0,-60,-5));
                context.waitTicks(100);capture(context,"umbral-baluarte-combat");
            }finally{try{Files.deleteIfExists(flag);}catch(IOException e){throw new RuntimeException(e);}}
            world.getServer().runOnServer(server->{check(target.get().getHealth()<80&&target.get().isAlive(),"Physical arrows damage the real target during the demonstration");check(device(connection.getServerLevel(),TURRET).turret().count()<30,"The magazine decreases during actual combat");});

            // A real survival player now stands directly between the bow and the hostile.
            world.getServer().runOnServer(server->target.get().setPos(.5,-60,-8.5));context.waitTicks(10);
            world.getServer().runCommand("gamemode survival @a");world.getServer().runCommand("tp @a 0.5 -60 -4.5 0 0");connection.waitForClientboundPackets();
            float before=world.getServer().computeOnServer(server->target.get().getHealth());context.waitTicks(65);
            world.getServer().runOnServer(server->{check(connection.getServerPlayer().getHealth()==20,"Survival player in the trajectory remains unharmed");check(target.get().getHealth()<before,"The arrow still reaches the hostile beyond the player");});
            int beforeWall=world.getServer().computeOnServer(server->{var level=connection.getServerLevel();for(int x=-2;x<=2;x++)for(int y=-60;y<=-57;y++)level.setBlockAndUpdate(new BlockPos(x,y,-2),Blocks.STONE.defaultBlockState());return device(level,TURRET).turret().count();});
            context.waitTicks(40);world.getServer().runOnServer(server->check(device(connection.getServerLevel(),TURRET).turret().count()==beforeWall,"A newly placed wall prevents further launches without wasting arrows"));
            int beforeCut=world.getServer().computeOnServer(server->{var level=connection.getServerLevel();level.setBlockAndUpdate(CORE,Blocks.AIR.defaultBlockState());return device(level,TURRET).turret().count();});
            context.waitFor(c->!((DeviceBlockEntity)c.level.getBlockEntity(TURRET)).turret().motion().armed());
            world.getServer().runOnServer(server->{for(int x=-2;x<=2;x++)for(int y=-60;y<=-57;y++)connection.getServerLevel().setBlockAndUpdate(new BlockPos(x,y,-2),Blocks.AIR.defaultBlockState());});
            world.getServer().runCommand("tp @a 0.5 -60 -2.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(TURRET);context.runOnClient(c->{if(c.gui.hud.isHidden())c.gui.hud.toggle();});context.getInput().pressKey(o->o.keyUse);
            context.waitForScreen(TurretScreen.class);waitButton(context,"turret.umbral.unload");capture(context,"umbral-baluarte-offline");
            context.clickScreenButton("turret.umbral.unload");context.waitFor(c->ammunition(c)==0);connection.waitForClientboundPackets();
            world.getServer().runOnServer(server->{var p=connection.getServerPlayer();int arrows=0;for(int n=0;n<p.getInventory().getContainerSize();n++)if(p.getInventory().getItem(n).is(Items.ARROW))arrows+=p.getInventory().getItem(n).getCount();check(arrows==beforeCut,"Unloading returns exactly the remaining ammunition after combat");});

            context.getInput().resizeWindow(640,480);layout(context);capture(context,"umbral-baluarte-compact");
            context.getInput().resizeWindow(1280,800);context.runOnClient(c->{c.options.guiScale().set(3);c.resizeGui();});layout(context);capture(context,"umbral-baluarte-scale-three");
            int oldRange=world.getServer().computeOnServer(server->device(connection.getServerLevel(),TURRET).turret().range());
            boolean focused=false;for(int n=0;n<8;n++){context.getInput().pressKey(InputConstants.KEY_TAB);focused=context.computeOnClient(c->c.gui.screen().getFocused() instanceof Button b&&b.getMessage().getString().equals(Component.translatable("turret.umbral.range_button",oldRange).getString()));if(focused)break;}
            check(focused,"Range control can be reached with native Tab navigation");context.getInput().pressKey(InputConstants.KEY_RETURN);context.waitTicks(12);connection.waitForServerboundPackets();
            world.getServer().runOnServer(server->check(device(connection.getServerLevel(),TURRET).turret().range()!=oldRange,"Enter activates the focused control"));
            var english=context.computeOnClient(c->{c.options.guiScale().set(2);c.resizeGui();c.getLanguageManager().setSelected("en_us");c.options.languageCode="en_us";return c.reloadResourcePacks();});
            context.waitFor(c->english.isDone()&&c.gui.overlay()==null,1000);layout(context);capture(context,"umbral-baluarte-english");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);context.waitForScreen(null);
        }
        context.getInput().resizeWindow(854,480);
    }
}
