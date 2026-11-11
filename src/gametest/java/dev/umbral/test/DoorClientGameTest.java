// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public final class DoorClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE = new BlockPos(-3, -60, -2), DOOR = new BlockPos(0, -60, 0), READER = new BlockPos(2, -59, -1);
    private static final Path OUTPUT = Path.of("../../visual-checks/1.6.1");
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void capture(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));
    }
    private static DeviceBlockEntity place(ServerLevel level, BlockPos pos, Block block, ServerPlayer player, String name) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var device = (DeviceBlockEntity) level.getBlockEntity(pos); device.initializeOwner(player.getUUID(), name); return device;
    }
    private static float progress(net.minecraft.client.Minecraft client) {
        return ((DeviceBlockEntity) client.level.getBlockEntity(DOOR)).doorMotion().state().position(client.level.getGameTime());
    }
    @Override public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.overlay() == null, 1000);
        context.getInput().resizeWindow(1280, 800);
        var reload = context.computeOnClient(client -> {
            client.options.renderDistance().set(4); client.options.simulationDistance().set(5); client.options.guiScale().set(2); client.resizeGui();
            client.options.setCameraType(CameraType.FIRST_PERSON); client.getLanguageManager().setSelected("es_es"); client.options.languageCode = "es_es";
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 1000);
        try (var world = context.worldBuilder().create()) {
            var connection = world.getConnection(); world.getServer().runCommand("gamemode creative @a"); world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer();
                for (int x = -5; x <= 5; x++) for (int z = -7; z <= 8; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -61, z), (z % 3 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                for (int x = -4; x <= 4; x++) for (int y = -60; y <= -57; y++) {
                    if (x == 0 && y < -58) continue;
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), (y == -57 || x == -4 || x == 4 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                }
                for (int x : new int[]{-4, 4}) level.setBlockAndUpdate(new BlockPos(x, -58, -1), Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
                // A workshop behind the closed windows makes their real transparency visible.
                for (int x = -3; x <= 3; x++) for (int y = -60; y <= -57; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, 6), (y == -57 || x == -3 || x == 3 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(0, -59, 5), Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(-1, -59, 5), Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(1, -59, 5), Blocks.SEA_LANTERN.defaultBlockState());
                var core = place(level, CORE, UmbralContent.CORE, player, "NEXO · Taller");
                var door = place(level, DOOR, UmbralContent.DOOR, player, "Acceso al taller");
                level.setBlockAndUpdate(DOOR.above(), door.getBlockState().setValue(SecurityDoorBlock.HALF, DoubleBlockHalf.UPPER));
                var reader = place(level, READER, UmbralContent.READER, player, "Lector exterior");
                door.bind(player.getUUID(), core); reader.bind(player.getUUID(), core); reader.targetDoor(player.getUUID(), door);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            });
            world.getServer().runCommand("tp @a 0.5 -60 -3.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(DOOR.above()); context.waitTicks(25); connection.waitForChunksRender();
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            capture(context, "umbral-door-closed");
            world.getServer().runCommand("tp @a 1.65 -60 -2.2 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(DOOR.above()); context.waitTicks(10); connection.waitForChunksRender();
            capture(context, "umbral-door-metal-detail");
            world.getServer().runCommand("tp @a 0.5 -60 3.5 180 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(DOOR.above()); context.waitTicks(10); connection.waitForChunksRender();
            capture(context, "umbral-door-interior");
            world.getServer().runCommand("tp @a 0.5 -60 -3.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(DOOR.above()); context.waitTicks(10);
            Path flag = OUTPUT.resolve("door-recording.flag");
            try { Files.createDirectories(OUTPUT); Files.writeString(flag, "UMBRAL · sliding security door"); }
            catch (IOException error) { throw new RuntimeException(error); }
            try {
                context.waitTicks(12);
                context.getInput().pressKey(options -> options.keyUse);
                context.waitFor(client -> client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN) && progress(client) > .2 && progress(client) < .7);
                context.runOnClient(client -> {
                    var shape = client.level.getBlockState(DOOR).getCollisionShape(client.level, DOOR);
                    check(shape.toAabbs().stream().anyMatch(b -> b.intersects(new AABB(.2,1/32d,.35,.8,1,.65))), "Panels still obstruct a player's body above the threshold at the beginning of travel");
                    check(client.level.getBlockEntity(DOOR.above()) == null, "One controller animates both door halves");
                });
                capture(context, "umbral-door-opening");
                context.waitFor(client -> progress(client) == 1); capture(context, "umbral-door-open");
                context.runOnClient(client -> check(client.level.getBlockState(DOOR).getCollisionShape(client.level, DOOR).toAabbs().stream()
                        .noneMatch(b -> b.intersects(new AABB(.2,1/32d,.35,.8,1,.65))), "A fully retracted pair of leaves clears the body-sized passage above the fixed threshold"));
                context.runOnClient(client -> check(client.level.getBlockState(DOOR).getCollisionShape(client.level, DOOR).toAabbs().stream()
                        .anyMatch(b -> b.contains(.5,1/64d,.5)), "The thin fixed threshold still seals the floor while the leaves are open"));
                context.waitFor(client -> !client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN) && progress(client) > .2 && progress(client) < .8);
                capture(context, "umbral-door-closing");
                context.waitFor(client -> progress(client) == 0); context.waitTicks(10);
                capture(context, "umbral-door-closed-again");
            } finally { try { Files.deleteIfExists(flag); } catch (IOException error) { throw new RuntimeException(error); } }

            // Cross with actual player movement once the physical opening has cleared.
            context.getInput().pressKey(options -> options.keyUse); context.waitFor(client -> progress(client) == 1);
            context.getInput().holdKey(options -> options.keyUp); context.waitTicks(25); context.getInput().releaseKey(options -> options.keyUp);
            connection.waitForServerboundPackets();
            world.getServer().runOnServer(server -> check(connection.getServerPlayer().getZ() > 1, "The real player can walk through the fully open doorway"));
            world.getServer().runCommand("tp @a 0.5 -60 -3.5 0 0"); connection.waitForClientboundPackets(); context.getInput().lookAt(DOOR.above());

            // An entrant arriving during closing reverses the live animation, then holds it open.
            world.getServer().runOnServer(server -> ((DeviceBlockEntity) connection.getServerLevel().getBlockEntity(DOOR)).setCloseAt(connection.getServerLevel().getGameTime()));
            context.waitFor(client -> !client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN) && progress(client) > .3 && progress(client) < .8);
            AtomicReference<ArmorStand> occupant = new AtomicReference<>();
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var stand = new ArmorStand(level, .5, -60, -.3);
                stand.setNoGravity(true); level.addFreshEntity(stand); occupant.set(stand);
            });
            context.waitFor(client -> client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN) && progress(client) == 1);
            context.waitTicks(100); capture(context, "umbral-door-safety-hold");
            context.runOnClient(client -> check(client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN), "Safety presence keeps the real doorway open beyond the timer"));
            world.getServer().runOnServer(server -> {
                occupant.get().discard(); connection.getServerLevel().setBlockAndUpdate(CORE, Blocks.AIR.defaultBlockState());
            });
            context.waitFor(client -> progress(client) == 0);
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer(); var door = (DeviceBlockEntity) level.getBlockEntity(DOOR);
                check(!door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Automatic closing completes without network connectivity");
                check(player.getMainHandItem().isEmpty(), "Door animation does not alter the player's inventory");
            });
            context.runOnClient(client -> { if (client.gui.hud.isHidden()) client.gui.hud.toggle(); });
        }
        context.getInput().resizeWindow(854, 480);
    }
}
