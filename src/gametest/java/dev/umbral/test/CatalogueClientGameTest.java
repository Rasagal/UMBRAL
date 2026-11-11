// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.client.*;
import dev.umbral.logistics.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public final class CatalogueClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE=new BlockPos(-4,-60,2),DOCK=new BlockPos(-3,-60,0),STOCK=new BlockPos(-3,-60,3),BALUARTE=new BlockPos(0,-60,0),CENTELLA=new BlockPos(3,-60,0),NEXO=new BlockPos(-1,-58,5),SENSOR=new BlockPos(5,-58,5),MINE=new BlockPos(5,-60,-5);
    private static final Path OUTPUT=Path.of("../../visual-checks/1.6.1");
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static DeviceBlockEntity d(ServerLevel level,BlockPos pos){return (DeviceBlockEntity)level.getBlockEntity(pos);}
    private static void tool(ServerPlayer p,BlockPos pos){UmbralContent.CONFIGURATOR.useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false)));}
    private static void place(ServerPlayer p,BlockPos pos,DeviceBlock block,String label,Direction face){
        var item=new ItemStack(block);item.set(DataComponents.CUSTOM_NAME,Component.literal(label));p.setItemInHand(InteractionHand.MAIN_HAND,item);
        var support=pos.relative(face.getOpposite());block.asItem().useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(support).add(new Vec3(face.getStepX(),face.getStepY(),face.getStepZ()).scale(.5)),face,support,false)));
        check(p.level().getBlockState(pos).is(block),"Native item places "+label);
    }
    private static void aim(ClientGameTestContext c,Vec3 target){var angles=c.computeOnClient(mc->{var v=target.subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-v.x,v.z)),(float)-Math.toDegrees(Math.atan2(v.y,v.horizontalDistance()))};});c.getInput().lookAt(angles[0],angles[1]);}
    private static void capture(ClientGameTestContext c,String name){c.getInput().setCursorPos(2,2);c.waitTicks(2);c.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));}
    private static void button(ClientGameTestContext c,String key){c.waitFor(mc->mc.gui.screen()!=null&&mc.gui.screen().children().stream().anyMatch(v->v instanceof Button b&&b.active&&b.getMessage().getString().equals(Component.translatable(key).getString())));c.clickScreenButton(key);c.waitTicks(6);}
    private static void select(ClientGameTestContext c,String label){
        FilterClientGameTest.selectNexo(c,label);
    }
    private static void layout(ClientGameTestContext c){c.runOnClient(mc->{var screen=mc.gui.screen();var buttons=screen.children().stream().filter(v->v instanceof Button).map(v->(Button)v).toList();for(var a:buttons){check(a.getX()>=0&&a.getY()>=0&&a.getRight()<=screen.width&&a.getBottom()<=screen.height,"NEXO controls fit viewport");for(var b:buttons)if(a!=b)check(a.getRight()<=b.getX()||b.getRight()<=a.getX()||a.getBottom()<=b.getY()||b.getBottom()<=a.getY(),"NEXO buttons never overlap");}});}
    @Override public void runTest(ClientGameTestContext c){
        c.waitFor(mc->mc.gui.overlay()==null,1000);c.getInput().resizeWindow(1280,800);
        var reload=c.computeOnClient(mc->{mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.resizeGui();mc.options.setCameraType(CameraType.FIRST_PERSON);mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->reload.isDone()&&mc.gui.overlay()==null,1000);
        try(var world=c.worldBuilder().create()){
            var conn=world.getConnection();world.getServer().runCommand("gamemode creative @a");world.getServer().runCommand("time set day");AtomicReference<Husk> metalTarget=new AtomicReference<>();
            world.getServer().runOnServer(server->{
                var level=conn.getServerLevel();var p=conn.getServerPlayer();
                for(int x=-10;x<=10;x++)for(int z=-12;z<=7;z++)level.setBlockAndUpdate(new BlockPos(x,-61,z),(z%3==0?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                for(int x=-7;x<=7;x++)for(int y=-60;y<=-55;y++)level.setBlockAndUpdate(new BlockPos(x,y,6),(y==-55||x==-7||x==7?Blocks.POLISHED_DEEPSLATE:Blocks.SMOOTH_STONE).defaultBlockState());
                level.setBlockAndUpdate(CORE,UmbralContent.CORE.defaultBlockState());d(level,CORE).initializeOwner(p.getUUID(),"Núcleo del patio");p.setPos(-1,-60,2);p.setYRot(0);
                place(p,DOCK,UmbralContent.DOCK,"MOTA · Taller",Direction.UP);place(p,BALUARTE,UmbralContent.TURRET,"BALUARTE · Patio",Direction.UP);place(p,CENTELLA,UmbralContent.CENTELLA,"CENTELLA · Acceso",Direction.UP);
                place(p,NEXO,UmbralContent.NEXO,"NEXO · Centro",Direction.NORTH);place(p,SENSOR,UmbralContent.SENSOR,"Sensor · Patio",Direction.NORTH);place(p,MINE,UmbralContent.PULSE_MINE,"Mina · Acceso",Direction.UP);
                p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));tool(p,CORE);for(var pos:new BlockPos[]{DOCK,BALUARTE,CENTELLA,NEXO,SENSOR,MINE})tool(p,pos);
                level.setBlockAndUpdate(STOCK,Blocks.CHEST.defaultBlockState());((ChestBlockEntity)level.getBlockEntity(STOCK)).setItem(0,new ItemStack(Items.ARROW,48));p.setShiftKeyDown(true);tool(p,STOCK);p.setShiftKeyDown(false);tool(p,DOCK);
                check(d(level,DOCK).dock().stock()!=null,"Real configurator authorizes the chest");p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.MOTA));UmbralContent.MOTA.useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(DOCK),Direction.UP,DOCK,false)));
                check(p.getMainHandItem().isEmpty()&&MotaService.actor(level,d(level,DOCK))!=null,"Native MOTA item installs one physical courier");
                d(level,BALUARTE).turret().load(new ItemStack(Items.ARROW,4),level.getGameTime());d(level,BALUARTE).syncTurret();p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);p.getInventory().setItem(1,new ItemStack(Items.GOLD_NUGGET,8));p.getInventory().setItem(2,new ItemStack(Items.REDSTONE,4));
                var mob=EntityTypes.HUSK.create(level,EntitySpawnReason.COMMAND);mob.setPos(3.5,-60,-9);mob.setNoAi(true);mob.setPersistenceRequired();mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);mob.setHealth(80);level.addFreshEntity(mob);metalTarget.set(mob);
            });
            world.getServer().runCommand("tp @a 3.5 -60 -2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(CENTELLA);c.waitTicks(20);conn.waitForChunksRender();c.getInput().pressKey(o->o.keyUse);c.waitForScreen(TurretScreen.class);
            button(c,"centella.umbral.load");world.getServer().runOnServer(s->check(d(conn.getServerLevel(),CENTELLA).turret().count()==8&&conn.getServerPlayer().getInventory().getItem(1).isEmpty(),"UI loads actual gold nuggets"));capture(c,"umbral-centella-control");button(c,"turret.umbral.arm");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            c.waitTicks(30);world.getServer().runOnServer(s->check(metalTarget.get().getHealth()<80,"Native nugget projectile hits the target"));
            c.runOnClient(mc->{mc.options.fov().set(35);if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});world.getServer().runCommand("tp @a 5.8 -59.5 -2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(CENTELLA);c.waitTicks(5);capture(c,"umbral-centella-detail");
            world.getServer().runOnServer(s->d(conn.getServerLevel(),CENTELLA).turret().setArmed(false,conn.getServerLevel().getGameTime()));
            world.getServer().runCommand("gamemode spectator @a");
            for(var view:new String[][]{{"front","3.5 -61.1 -3"},{"side","6.7 -61.1 0.5"},{"back","3.5 -61.1 3.8"},{"top","3.5 -57.7 0.5"}}){
                world.getServer().runCommand("tp @a "+view[1]+" 0 0");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,Vec3.atLowerCornerOf(CENTELLA).add(.5,.6,.5));c.waitTicks(5);capture(c,"umbral-centella-"+view[0]);
            }
            world.getServer().runCommand("gamemode creative @a");
            c.runOnClient(mc->{mc.options.fov().set(70);if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});world.getServer().runCommand("tp @a -0.5 -59 2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(NEXO);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(NexoScreen.class);select(c,"BALUARTE · Patio");layout(c);capture(c,"umbral-nexo-low-ammunition");
            button(c,"nexo.umbral.action.replenish");world.getServer().runOnServer(s->check(MotaService.actor(conn.getServerLevel(),d(conn.getServerLevel(),DOCK)).busy(),"Real NEXO button starts a physical job"));capture(c,"umbral-nexo-accepted");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            world.getServer().runCommand("tp @a -6 -58.5 -3 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(new BlockPos(-1,-60,1));c.runOnClient(mc->{if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});
            var flag=OUTPUT.resolve("catalogue-recording.flag");try{Files.createDirectories(OUTPUT);Files.writeString(flag,"UMBRAL · actual MOTA delivery");}catch(java.io.IOException e){throw new RuntimeException(e);}
            try{c.waitFor(mc->java.util.stream.StreamSupport.stream(mc.level.entitiesForRendering().spliterator(),false).anyMatch(e->e instanceof MotaEntity mota&&mota.carrying()));capture(c,"umbral-mota-carrying");c.waitTicks(170);capture(c,"umbral-mota-delivery");}finally{try{Files.deleteIfExists(flag);}catch(java.io.IOException e){throw new RuntimeException(e);}}
            world.getServer().runOnServer(s->{var level=conn.getServerLevel();check(d(level,BALUARTE).turret().count()==36&&((ChestBlockEntity)level.getBlockEntity(STOCK)).getItem(0).getCount()==16,"Actual client job conserves all 48 stock arrows and four initial rounds");check(!MotaService.actor(level,d(level,DOCK)).busy(),"MOTA returns idle");});
            c.runOnClient(mc->{if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});world.getServer().runCommand("tp @a -0.5 -59 2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(NEXO);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(NexoScreen.class);select(c,"MOTA · Taller");capture(c,"umbral-nexo-delivered");button(c,"nexo.umbral.history");layout(c);capture(c,"umbral-nexo-history");button(c,"nexo.umbral.devices");
            c.runOnClient(mc->{mc.options.guiScale().set(3);mc.resizeGui();});layout(c);capture(c,"umbral-nexo-gui3");c.getInput().resizeWindow(640,480);c.waitTicks(5);layout(c);capture(c,"umbral-nexo-small");
            var english=c.computeOnClient(mc->{mc.getLanguageManager().setSelected("en_us");mc.options.languageCode="en_us";return mc.reloadResourcePacks();});c.waitFor(mc->english.isDone()&&mc.gui.overlay()==null,1000);layout(c);capture(c,"umbral-nexo-english-small");
            c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);c.getInput().resizeWindow(1280,800);var spanish=c.computeOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();mc.getLanguageManager().setSelected("es_es");mc.options.languageCode="es_es";return mc.reloadResourcePacks();});c.waitFor(mc->spanish.isDone()&&mc.gui.overlay()==null,1000);
            world.getServer().runCommand("tp @a 5.5 -60 -3 0 0");conn.waitForClientboundPackets();aim(c,Vec3.atLowerCornerOf(MINE).add(.5,.13,.5));c.getInput().pressKey(o->o.keyUse);c.waitForScreen(NexoScreen.class);select(c,"Mina · Acceso");button(c,"nexo.umbral.action.load");button(c,"nexo.umbral.action.arm");capture(c,"umbral-pulse-control");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            AtomicReference<Husk> pulseTarget=new AtomicReference<>();world.getServer().runOnServer(s->{var level=conn.getServerLevel();var mob=EntityTypes.HUSK.create(level,EntitySpawnReason.COMMAND);mob.setPos(5.5,-60,-4.5);mob.setNoAi(true);level.addFreshEntity(mob);pulseTarget.set(mob);});c.waitTicks(12);
            world.getServer().runOnServer(s->{check(pulseTarget.get().getHealth()<20&&d(conn.getServerLevel(),MINE).safety().count()==3,"Real pulse consumes one charge and affects a monster");check(conn.getServerPlayer().getInventory().getItem(2).isEmpty(),"Loading consumed the player's real redstone");});
            world.getServer().runCommand("tp @a 5.5 -59 2.5 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(SENSOR);c.getInput().pressKey(o->o.keyUse);c.waitForScreen(NexoScreen.class);select(c,"Sensor · Patio");button(c,"nexo.umbral.action.arm");capture(c,"umbral-sensor-control");c.getInput().pressKey(InputConstants.KEY_ESCAPE);c.waitForScreen(null);
            c.runOnClient(mc->{mc.options.fov().set(35);if(!mc.gui.hud.isHidden())mc.gui.hud.toggle();});world.getServer().runCommand("tp @a -2.2 -59 1.5 0 0");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,Vec3.atLowerCornerOf(NEXO).add(.5,.5,.84));c.waitTicks(6);capture(c,"umbral-nexo-model");world.getServer().runCommand("tp @a 4 -59 1.5 0 0");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,Vec3.atLowerCornerOf(SENSOR).add(.5,.5,.7));c.waitTicks(6);capture(c,"umbral-sensor-model");
            world.getServer().runCommand("tp @a -5 -59.5 -2 0 0");conn.waitForClientboundPackets();c.waitTicks(4);aim(c,Vec3.atLowerCornerOf(DOCK).add(.5,1.6,.5));c.waitTicks(6);capture(c,"umbral-mota-model");c.getInput().lookAt(DOCK);c.waitTicks(4);capture(c,"umbral-mota-dock-model");world.getServer().runCommand("tp @a 4 -58.8 -6.5 0 0");conn.waitForClientboundPackets();aim(c,Vec3.atLowerCornerOf(MINE).add(.5,.13,.5));c.waitTicks(5);capture(c,"umbral-pulse-model");
            c.runOnClient(mc->mc.options.fov().set(70));world.getServer().runCommand("tp @a 8 -57.5 -7 0 0");conn.waitForClientboundPackets();c.getInput().lookAt(new BlockPos(0,-59,2));c.waitTicks(5);capture(c,"umbral-complete-catalogue");
            c.runOnClient(mc->{if(mc.gui.hud.isHidden())mc.gui.hud.toggle();});
        }
    }
}
