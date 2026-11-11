// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.camera.*;
import dev.umbral.client.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;

/** A physical-head demonstration plus real keyboard, packets, viewing and offline behavior. */
@SuppressWarnings("UnstableApiUsage")
public final class CameraMotionClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE = new BlockPos(-2, -60, -3), TERMINAL = new BlockPos(0, -60, -3), CAMERA = new BlockPos(2, -58, 0);
    private static final BlockPos FLOOR_CAMERA = new BlockPos(5,-60,-5), CEILING_CAMERA = new BlockPos(-2,-58,-5);
    private static final Path OUTPUT = Path.of("../../visual-checks/1.6.1");
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void capture(ClientGameTestContext context, String name) {
        context.getInput().setCursorPos(2, 2); context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));
    }
    private static DeviceBlockEntity place(ServerLevel level, BlockPos pos, Block block, ServerPlayer player, String label) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var device = (DeviceBlockEntity) level.getBlockEntity(pos); device.initializeOwner(player.getUUID(), label); return device;
    }
    private static void neutral(DeviceBlockEntity device,long now) {
        var previous=device.cameraMotion().state();
        device.cameraMotion().restore(new CameraMotion.State(previous.revision()+1,now,0,0,0,0,0,false,25,true,false));
        device.syncMotion();
    }
    @Override public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.overlay() == null, 1000);
        context.getInput().resizeWindow(1280, 800);
        var reload = context.computeOnClient(client -> {
            client.options.renderDistance().set(4); client.options.simulationDistance().set(5);
            client.options.guiScale().set(2); client.resizeGui(); client.options.setCameraType(CameraType.FIRST_PERSON);
            client.getLanguageManager().setSelected("es_es"); client.options.languageCode = "es_es";
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 1000);
        try (var world = context.worldBuilder().create()) {
            var connection = world.getConnection(); world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer();
                for (int x = -5; x <= 7; x++) for (int z = -8; z <= 4; z++) level.setBlockAndUpdate(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int x = 0; x <= 5; x++) for (int y = -60; y <= -56; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                for (int x : new int[]{0, 5}) level.setBlockAndUpdate(new BlockPos(x, -58, 0), Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
                var core = place(level, CORE, UmbralContent.CORE, player, "NEXO · Patio");
                var terminal = place(level, TERMINAL, UmbralContent.TERMINAL, player, "Centro de vigilancia");
                var camera = place(level, CAMERA, UmbralContent.CAMERA, player, "VIGÍA · Patio");
                level.setBlockAndUpdate(CAMERA, camera.getBlockState().setValue(CameraBlock.MOUNT, CameraBlock.Mount.WALL));
                terminal.bind(player.getUUID(), core); camera.bind(player.getUUID(), core);
                var floor=place(level,FLOOR_CAMERA,UmbralContent.CAMERA,player,"VIGÍA · Suelo");floor.bind(player.getUUID(),core);
                var ceiling=place(level,CEILING_CAMERA,UmbralContent.CAMERA,player,"VIGÍA · Techo");
                level.setBlockAndUpdate(CEILING_CAMERA,ceiling.getBlockState().setValue(CameraBlock.MOUNT,CameraBlock.Mount.CEILING));
                level.setBlockAndUpdate(CEILING_CAMERA.above(),Blocks.SMOOTH_STONE.defaultBlockState());ceiling.bind(player.getUUID(),core);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            world.getServer().runCommand("tp @a 0.9 -58.45 -1.2 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(CAMERA); context.waitTicks(25); connection.waitForChunksRender();
            world.getServer().runOnServer(server->{var level=connection.getServerLevel();
                for(var pos:new BlockPos[]{CAMERA,FLOOR_CAMERA,CEILING_CAMERA})neutral((DeviceBlockEntity)level.getBlockEntity(pos),level.getGameTime());
            });connection.waitForClientboundPackets();context.waitTicks(4);
            int originalFov=context.computeOnClient(c->c.options.fov().get());context.runOnClient(c->c.options.fov().set(30));
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            capture(context,"umbral-vigia-model-detail");
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a 5.5 -61.1 -7.8 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(FLOOR_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-front");
            world.getServer().runCommand("tp @a 2.2 -61.1 -4.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(FLOOR_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-side");
            world.getServer().runCommand("tp @a 5.5 -61.1 -1.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(FLOOR_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-back");
            world.getServer().runCommand("tp @a 5.5 -57.8 -4.5 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(FLOOR_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-top");
            world.getServer().runCommand("tp @a 3.9 -60.45 -6.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(FLOOR_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-floor");
            world.getServer().runCommand("tp @a -3.1 -58.45 -6.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(CEILING_CAMERA);context.waitTicks(4);capture(context,"umbral-vigia-model-ceiling");
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runOnServer(server->{
                var level=connection.getServerLevel();level.setBlockAndUpdate(FLOOR_CAMERA,Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(CEILING_CAMERA,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(CEILING_CAMERA.above(),Blocks.AIR.defaultBlockState());
                var p=connection.getServerPlayer();p.getAbilities().flying=true;p.onUpdateAbilities();
                var camera=(DeviceBlockEntity)level.getBlockEntity(CAMERA);
                camera.cameraMotion().command(CameraMotion.Command.AUTO,level.getGameTime(),CameraMotion.MAX_PITCH);camera.syncMotion();
            });
            world.getServer().runCommand("tp @a 0.9 -58.45 -1.2 0 0");connection.waitForClientboundPackets();context.getInput().lookAt(CAMERA);context.waitTicks(5);
            capture(context, "umbral-vigia-servo-right");
            var first = world.getServer().computeOnServer(server -> ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA))
                    .cameraMotion().state().pose(connection.getServerLevel().getGameTime()));
            Path flag = OUTPUT.resolve("vigia-recording.flag");
            try { Files.createDirectories(OUTPUT); Files.writeString(flag, "UMBRAL · physical camera scan"); }
            catch (IOException error) { throw new RuntimeException(error); }
            try { context.waitTicks(320); }
            finally { try { Files.deleteIfExists(flag); } catch (IOException error) { throw new RuntimeException(error); } }
            world.getServer().runOnServer(server -> {
                var pose = ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state().pose(connection.getServerLevel().getGameTime());
                check(Math.abs(pose.yaw() - first.yaw()) > 8, "An online physical head must sweep across its range");
            });
            capture(context, "umbral-vigia-servo-left");
            context.runOnClient(c->c.options.fov().set(originalFov));
            context.runOnClient(client -> { if (client.gui.hud.isHidden()) client.gui.hud.toggle(); });

            world.getServer().runCommand("tp @a 0.5 -60 -5.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(TERMINAL); context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(NetworkStatusScreen.class); context.clickScreenButton("screen.umbral.cameras");
            context.waitForScreen(CameraDirectoryScreen.class); context.waitTicks(15); context.clickScreenButton("screen.umbral.view");
            context.waitForScreen(CameraViewScreen.class); context.waitTicks(8);
            capture(context, "umbral-vigia-scan-controls");
            context.clickScreenButton("motion.umbral.home_hint"); context.waitTicks(100);
            world.getServer().runOnServer(server -> {
                var motion = ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state();
                check(!motion.scan() && Math.abs(motion.pose(connection.getServerLevel().getGameTime()).yaw()) < .001, "Home stops scanning and centers the servo");
            });
            long before = world.getServer().computeOnServer(server -> ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state().revision());
            context.runOnClient(client -> ClientPlayNetworking.send(new CameraProtocol.Steer(UUID.randomUUID(), CameraMotion.Command.LEFT)));
            connection.waitForServerboundPackets();
            world.getServer().runOnServer(server -> check(((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state().revision() == before,
                    "Forged session must not mutate the physical camera"));
            context.getInput().pressKey(InputConstants.KEY_RIGHT); context.waitTicks(35);
            context.getInput().pressKey(InputConstants.KEY_DOWN); context.waitTicks(30);
            connection.waitForClientboundPackets();
            context.runOnClient(client -> {
                var device = (DeviceBlockEntity) client.level.getBlockEntity(CAMERA); var pose = device.cameraMotion().state().pose(client.level.getGameTime());
                check(Math.abs(pose.yaw() - 5) < .01 && Math.abs(pose.pitch() - 11) < .01, "Arrow controls drive the real bounded servo");
                Vec3 expected = CameraGeometry.lens(CAMERA, Direction.NORTH, pose);
                check(client.gameRenderer.mainCamera().position().distanceToSqr(expected) < .000001, "Actual viewpoint follows the rotated optical origin");
                check(Math.abs(client.gameRenderer.mainCamera().xRot() - 11) < .01, "View tilt follows physical tilt");
                check(client.getCameraEntity() != client.player, "The avatar stays separate from its lens");
            });
            capture(context, "umbral-vigia-manual-controls");
            context.getInput().pressKey(InputConstants.KEY_G); context.waitTicks(10);
            world.getServer().runOnServer(server -> check(((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state().range() == 30,
                    "Scan width changes through the authenticated session"));
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.runOnClient(client -> check(client.gui.screen().children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.visible).count() == 2,
                    "Clean mode hides servo controls and retains data/exit controls"));
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.getInput().pressKey(InputConstants.KEY_SPACE); context.waitTicks(12);
            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer();
                check(((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state().scan(), "Space resumes automatic scanning");
                check(player.position().distanceToSqr(new Vec3(.5, -60, -5.5)) < .0001 && player.getMainHandItem().isEmpty(), "Steering does not move the avatar or change its inventory");
                connection.getServerLevel().setBlockAndUpdate(CORE, Blocks.AIR.defaultBlockState());
            });
            context.waitForScreen(CameraDirectoryScreen.class); context.waitTicks(15);
            world.getServer().runOnServer(server -> {
                var motion = ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CAMERA)).cameraMotion().state();
                check(!motion.active(), "Lost network freezes the physical servo");
                check(motion.pose(connection.getServerLevel().getGameTime()).equals(motion.pose(connection.getServerLevel().getGameTime() + 1000)), "Offline head cannot keep sweeping");
            });
            context.runOnClient(client -> check(client.getCameraEntity() == client.player && !client.gui.hud.isHidden(), "Signal loss restores the normal camera and HUD"));
        }
        context.getInput().resizeWindow(854, 480);
    }
}
