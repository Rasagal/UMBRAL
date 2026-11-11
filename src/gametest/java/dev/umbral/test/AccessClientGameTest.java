// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import dev.umbral.client.ReaderAccessScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class AccessClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE = new BlockPos(-3, -60, -2), DOOR = new BlockPos(0, -60, 0), READER = new BlockPos(2, -59, -1);
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void capture(ClientGameTestContext context, String name) {
        context.waitFor(client -> client.gui.overlay() == null); context.getInput().setCursorPos(2, 2); context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(Path.of("../../visual-checks/1.6.1")));
    }
    private static void useTool(ServerPlayer player, BlockPos pos) {
        UmbralContent.CONFIGURATOR.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false)));
    }
    private static DeviceBlockEntity place(ServerLevel level, BlockPos pos, Block block, ServerPlayer owner, String name) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var device = (DeviceBlockEntity) level.getBlockEntity(pos); device.initializeOwner(owner.getUUID(), name); return device;
    }
    private static ItemStack issued(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.has(UmbralContent.CARD_ID)) return stack;
        }
        throw new AssertionError("Native issue action must put a physical pass into inventory");
    }
    private static void waitButton(ClientGameTestContext context, String key) {
        context.waitFor(client -> client.gui.screen() instanceof ReaderAccessScreen screen && screen.children().stream()
                .anyMatch(c -> c instanceof Button b && b.active && b.getMessage().getString().equals(Component.translatable(key).getString())));
    }
    private static void assertLayout(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var buttons = screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button) c).toList();
            for (var b : buttons) {
                check(b.getX() >= 0 && b.getY() >= 0 && b.getRight() <= screen.width && b.getBottom() <= screen.height, "Access controls fit screen");
                for (var other : buttons) if (other != b) check(b.getRight() <= other.getX() || other.getRight() <= b.getX()
                        || b.getBottom() <= other.getY() || other.getBottom() <= b.getY(), "Access controls do not overlap");
            }
        });
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
            world.getServer().runCommand("tp @a 0.5 -60 -3.5 0 0");
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer();
                for (int x = -5; x <= 5; x++) for (int z = -7; z <= 5; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -61, z), (z % 3 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                for (int x = -4; x <= 4; x++) for (int y = -60; y <= -57; y++) {
                    if (x == 0 && y < -58) continue;
                    var block = y == -57 || x == -4 || x == 4 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE;
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), block.defaultBlockState());
                }
                for (int x : new int[]{-4, 4}) level.setBlockAndUpdate(new BlockPos(x, -58, -1), Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(0, -57, -1), Blocks.SEA_LANTERN.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(0, -60, 4), Blocks.AMETHYST_BLOCK.defaultBlockState());
                place(level, CORE, UmbralContent.CORE, player, "NEXO · Taller");
                place(level, READER, UmbralContent.READER, player, "Lector exterior");
                ItemStack cameraItem = new ItemStack(UmbralContent.CAMERA);
                cameraItem.set(DataComponents.CUSTOM_NAME, Component.literal("VIGÍA · Entrada"));
                player.setItemInHand(InteractionHand.MAIN_HAND, cameraItem);
                BlockPos cameraWall = new BlockPos(-2, -57, 0), cameraPos = cameraWall.north();
                for (Direction surface : new Direction[]{Direction.UP, Direction.DOWN}) {
                    BlockPos support = cameraPos.relative(surface.getOpposite());
                    level.setBlockAndUpdate(support, Blocks.SMOOTH_STONE.defaultBlockState());
                    UmbralContent.CAMERA.asItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(support).add(0, surface.getStepY() * .5, 0), surface, support, false)));
                    var mounted = level.getBlockState(cameraPos);
                    check(mounted.is(UmbralContent.CAMERA) && mounted.getValue(CameraBlock.MOUNT)
                            == (surface == Direction.UP ? CameraBlock.Mount.FLOOR : CameraBlock.Mount.CEILING),
                            "Camera must choose the matching floor or ceiling bracket");
                    level.setBlockAndUpdate(cameraPos, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
                }
                UmbralContent.CAMERA.asItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(cameraWall).add(0, 0, -.5), Direction.NORTH, cameraWall, false)));
                var camera = (DeviceBlockEntity) level.getBlockEntity(cameraPos);
                check(camera != null && camera.getBlockState().getValue(CameraBlock.MOUNT) == CameraBlock.Mount.WALL,
                        "Placing on a wall must attach the camera bracket to that wall");
                check(camera.getBlockState().getValue(DeviceBlock.FACING) == Direction.NORTH, "Wall camera must face out from the wall");
                camera.bind(player.getUUID(), (DeviceBlockEntity) level.getBlockEntity(CORE));
                // Real BlockItem placement exercises headroom, ownership and creation of the upper half.
                ItemStack door = new ItemStack(UmbralContent.DOOR); door.set(DataComponents.CUSTOM_NAME, Component.literal("Acceso al taller"));
                player.setItemInHand(InteractionHand.MAIN_HAND, door);
                UmbralContent.DOOR.asItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(DOOR.below()).add(0, .5, 0), Direction.UP, DOOR.below(), false)));
                check(level.getBlockState(DOOR.above()).is(UmbralContent.DOOR), "Placement creates two door halves");
                check(level.getBlockEntity(DOOR.above()) == null, "Only the lower half stores a device");
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UmbralContent.CONFIGURATOR));
                useTool(player, CORE); useTool(player, DOOR.above()); useTool(player, READER);
                player.setShiftKeyDown(true); useTool(player, DOOR.above()); player.setShiftKeyDown(false); useTool(player, READER);
                check(AccessService.readiness(level, (DeviceBlockEntity) level.getBlockEntity(READER)) == AccessService.Result.READY,
                        "Configurator can pair upper-half clicks and select a physical door");
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var blank = new ItemStack(UmbralContent.ACCESS_CARD, 3); blank.set(DataComponents.CUSTOM_NAME, Component.literal("Visita · Taller"));
                player.getInventory().setItem(1, blank);
            });
            connection.waitForClientboundPackets(); context.waitTicks(25); connection.waitForChunksRender();
            world.getServer().runCommand("tp @a -2.5 -59.6 -5.5 -22 8");
            connection.waitForClientboundPackets(); context.getInput().lookAt(DOOR.above());
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            capture(context, "umbral-access-door-closed");
            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            world.getServer().runCommand("tp @a -0.1 -57.5 -2.4 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(-2, -57, -1)); capture(context, "umbral-vigia-wall-closeup");
            world.getServer().runCommand("tp @a -3.2 -57.6 -2.0 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(-2, -57, -1)); capture(context, "umbral-vigia-wall-bracket");
            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); player.getAbilities().flying = false; player.onUpdateAbilities();
            });
            context.runOnClient(client -> { if (client.gui.hud.isHidden()) client.gui.hud.toggle(); });

            world.getServer().runCommand("tp @a 2.5 -60 -3.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(READER); context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(ReaderAccessScreen.class); waitButton(context, "access.umbral.issue"); assertLayout(context);
            capture(context, "umbral-access-empty"); context.clickScreenButton("access.umbral.issue");
            waitButton(context, "access.umbral.revoke"); capture(context, "umbral-access-issued");
            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var pass = issued(player);
                check(pass.getHoverName().getString().equals("Visita · Taller"), "Named physical card received through UI");
                check(player.getInventory().getItem(1).getCount() == 2, "UI consumes exactly one blank");
                // Keep a single actual credential in the selected hand; do not duplicate it for the interaction test.
                check(player.getInventory().getItem(0) == pass, "Issued pass fills the empty hand slot");
            });
            context.clickScreenButton("gui.done"); context.waitForScreen(null);
            context.getInput().lookAt(READER); context.getInput().pressKey(options -> options.keyUse);
            context.waitFor(client -> client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN));
            context.runOnClient(client -> check(client.level.getBlockState(READER).getValue(CardReaderBlock.FEEDBACK) == CardReaderBlock.Feedback.GRANTED,
                    "Successful card use flashes the reader"));
            world.getServer().runCommand("tp @a -1.5 -60 -3.5 -12 0"); connection.waitForClientboundPackets();
            context.waitFor(client -> ((DeviceBlockEntity) client.level.getBlockEntity(DOOR)).doorMotion().state().position(client.level.getGameTime()) == 1);
            context.getInput().lookAt(DOOR.above());
            context.runOnClient(client -> client.gui.hud.toggle()); capture(context, "umbral-access-door-open");
            context.runOnClient(client -> client.gui.hud.toggle());
            context.waitFor(client -> !client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN));

            // Owner manages the reader with an empty hand, while retaining the issued pass in another slot.
            world.getServer().runOnServer(server -> {
                var inventory = connection.getServerPlayer().getInventory(); inventory.setItem(2, inventory.getItem(0)); inventory.setItem(0, ItemStack.EMPTY);
            });
            world.getServer().runCommand("tp @a 2.5 -60 -3.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(READER); context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(ReaderAccessScreen.class); waitButton(context, "access.umbral.revoke");
            context.clickScreenButton("access.umbral.revoke");
            context.waitFor(client -> client.gui.screen() instanceof ReaderAccessScreen screen && screen.children().stream()
                    .noneMatch(c -> c instanceof Button b && b.getMessage().getString().equals(Component.translatable("access.umbral.revoke").getString())));
            capture(context, "umbral-access-revoked"); context.clickScreenButton("gui.done");
            world.getServer().runOnServer(server -> {
                var inventory = connection.getServerPlayer().getInventory(); inventory.setItem(0, inventory.getItem(2)); inventory.setItem(2, ItemStack.EMPTY);
            });
            connection.waitForClientboundPackets(); context.getInput().lookAt(READER); context.getInput().pressKey(options -> options.keyUse);
            context.waitFor(client -> client.level.getBlockState(READER).getValue(CardReaderBlock.FEEDBACK) == CardReaderBlock.Feedback.DENIED);
            context.runOnClient(client -> check(!client.level.getBlockState(DOOR).getValue(SecurityDoorBlock.OPEN), "Revoked card must leave door closed"));
            capture(context, "umbral-access-denied");

            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var core = (DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CORE);
                var registry = AccessRegistry.get(connection.getServerLevel());
                for (int n = 0; n < 9; n++) registry.issue(player.getUUID(), core.link(), n == 0 ? "Equipo de mantenimiento del patio" : "Turno " + (n + 1));
            });
            connection.waitForClientboundPackets(); context.getInput().lookAt(READER); context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(ReaderAccessScreen.class); waitButton(context, "access.umbral.revoke"); assertLayout(context);
            capture(context, "umbral-access-directory"); context.clickScreenButton(">"); capture(context, "umbral-access-page-two");
            context.getInput().resizeWindow(640, 480); context.waitTicks(5); assertLayout(context); capture(context, "umbral-access-compact");
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.waitTicks(5); assertLayout(context); capture(context, "umbral-access-scale-three");
            world.getServer().runOnServer(server -> connection.getServerLevel().setBlockAndUpdate(CORE, Blocks.AIR.defaultBlockState()));
            context.waitFor(client -> !client.level.getBlockState(READER).getValue(DeviceBlock.ONLINE));
            context.waitTicks(25);
            context.waitFor(client -> client.gui.screen().children().stream().anyMatch(c -> c instanceof Button b
                    && !b.active && b.getMessage().getString().equals(Component.translatable("access.umbral.issue").getString())));
            capture(context, "umbral-access-offline");
            waitButton(context, "access.umbral.revoke"); context.clickScreenButton("access.umbral.revoke");
            context.waitTicks(8);
            world.getServer().runOnServer(server -> {
                var reader = (DeviceBlockEntity) connection.getServerLevel().getBlockEntity(READER);
                check(AccessRegistry.get(connection.getServerLevel()).list(connection.getServerPlayer().getUUID(), reader.link()).size() == 8,
                        "Offline UI still revokes physical credentials");
            });
            context.getInput().pressKey(InputConstants.KEY_ESCAPE); context.waitForScreen(null);
        }
        context.getInput().resizeWindow(854, 480);
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
    }
}
