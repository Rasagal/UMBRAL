// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.CameraBlock;
import dev.umbral.block.SecurityDoorBlock;
import dev.umbral.block.TerminalBlock;
import dev.umbral.access.AccessService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import dev.umbral.client.NetworkStatusScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;

/** Real client, integrated server, configurator and packets; screenshots are review artifacts. */
@SuppressWarnings("UnstableApiUsage")
public final class NetworkClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE = new BlockPos(-20, -60, 0);
    private static final BlockPos NODE = new BlockPos(0, -60, 0);
    private static final BlockPos TERMINAL = new BlockPos(3, -60, 0);
    private static final BlockPos BREAK = new BlockPos(-10, -60, 0);
    private static final Path OUTPUT = Path.of("../../visual-checks/1.6.1");
    private static void capture(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUTPUT));
    }

    @Override public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.overlay() == null, 1000);
        context.getInput().resizeWindow(1280, 800);
        var reload = context.computeOnClient(client -> {
            client.options.renderDistance().set(4); client.options.simulationDistance().set(5);
            client.options.guiScale().set(2); client.resizeGui(); client.options.setCameraType(CameraType.FIRST_PERSON);
            client.getLanguageManager().setSelected("es_es");
            client.options.languageCode = "es_es";
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 1000);
        try (var world = context.worldBuilder().create()) {
            var connection = world.getConnection();
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel();
                var player = connection.getServerPlayer();
                for (int x = -23; x <= 6; x++) for (int z = -3; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState());
                }
                place(level, CORE, UmbralContent.CORE, player, "NEXO · Taller");
                place(level, NODE, UmbralContent.NODE, player, "Nodo del patio");
                place(level, TERMINAL, UmbralContent.TERMINAL, player, "Terminal del patio");
                for (int x = -19; x < 0; x++) level.setBlockAndUpdate(new BlockPos(x, -60, 0), UmbralContent.CABLE.defaultBlockState());
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UmbralContent.CONFIGURATOR));
                configure(player, CORE);
                configure(player, NODE);
                configure(player, TERMINAL);
            });
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runOnServer(server -> {
                connection.getServerPlayer().getAbilities().flying = true;
                connection.getServerPlayer().onUpdateAbilities();
            });
            world.getServer().runCommand("tp @a -6 -51 -18 6 26");
            context.runOnClient(client -> client.gui.hud.toggle());
            context.waitTicks(25);
            connection.waitForChunksRender();
            capture(context, "umbral-network-blocks");

            world.getServer().runCommand("tp @a 1.5 -59 -4.5 0 22");
            connection.waitForClientboundPackets();
            context.waitTicks(5);
            capture(context, "umbral-node-terminal-detail");
            world.getServer().runCommand("tp @a -18.5 -59 -3.5 25 24");
            connection.waitForClientboundPackets();
            context.waitTicks(5);
            capture(context, "umbral-core-detail");

            world.getServer().runCommand("tp @a 3.5 -60 -2.5 0 14");
            world.getServer().runOnServer(server -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            context.runOnClient(client -> client.gui.hud.toggle());
            connection.waitForClientboundPackets();
            context.waitFor(client -> client.level.getBlockState(TERMINAL).getValue(DeviceBlock.ONLINE));
            context.getInput().lookAt(TERMINAL);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(NetworkStatusScreen.class);
            capture(context, "umbral-console-connected");

            world.getServer().runOnServer(server -> connection.getServerLevel().setBlockAndUpdate(BREAK, Blocks.AIR.defaultBlockState()));
            context.waitFor(client -> !client.level.getBlockState(TERMINAL).getValue(DeviceBlock.ONLINE));
            context.waitTicks(25); // Allow the console's regular authenticated telemetry refresh.
            capture(context, "umbral-console-cable-cut");

            world.getServer().runOnServer(server -> connection.getServerLevel().setBlockAndUpdate(BREAK, UmbralContent.CABLE.defaultBlockState()));
            context.waitFor(client -> client.level.getBlockState(TERMINAL).getValue(DeviceBlock.ONLINE));
            context.waitTicks(25);
            capture(context, "umbral-console-restored");
            context.clickScreenButton("gui.done");
            context.waitForScreen(null);

            // Review the complete material family together after the functional network circuit.
            world.getServer().runCommand("time set day");
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer();
                for (int x = -23; x <= 6; x++) level.setBlockAndUpdate(new BlockPos(x, -60, 0), Blocks.AIR.defaultBlockState());
                for (int x = -5; x <= 8; x++) for (int z = -4; z <= 9; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -61, z), (z % 3 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                for (int x = -4; x <= 7; x++) for (int y = -60; y <= -56; y++) {
                    if (x == 5 && y < -58) continue;
                    level.setBlockAndUpdate(new BlockPos(x, y, 6), (y == -56 || x == -4 || x == 7 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState());
                }
                var corePos = new BlockPos(-3, -60, 4); place(level, corePos, UmbralContent.CORE, player, "NEXO · Taller");
                var core = (DeviceBlockEntity) level.getBlockEntity(corePos);
                for (var pos : new BlockPos[]{new BlockPos(-1,-60,4), new BlockPos(1,-60,4), new BlockPos(3,-58,5), new BlockPos(4,-59,5), new BlockPos(5,-60,5)}) {
                    Block block = pos.getX() == -1 ? UmbralContent.NODE : pos.getX() == 1 ? UmbralContent.TERMINAL
                            : pos.getX() == 3 ? UmbralContent.CAMERA : pos.getX() == 4 ? UmbralContent.READER : UmbralContent.DOOR;
                    place(level, pos, block, player, "UMBRAL · Taller");
                    var device = (DeviceBlockEntity) level.getBlockEntity(pos); device.bind(player.getUUID(), core);
                    if (block == UmbralContent.CAMERA) level.setBlockAndUpdate(pos, device.getBlockState().setValue(CameraBlock.MOUNT, CameraBlock.Mount.WALL));
                    if (block == UmbralContent.DOOR) level.setBlockAndUpdate(pos.above(), device.getBlockState().setValue(SecurityDoorBlock.HALF, DoubleBlockHalf.UPPER));
                }
                for (int x : new int[]{-2,0}) level.setBlockAndUpdate(new BlockPos(x,-60,4), UmbralContent.CABLE.defaultBlockState());
                var reader = (DeviceBlockEntity) level.getBlockEntity(new BlockPos(4,-59,5));
                reader.targetDoor(player.getUUID(), (DeviceBlockEntity) level.getBlockEntity(new BlockPos(5,-60,5)));
                var wall=new BlockPos(-1,-58,6);player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.TERMINAL));
                UmbralContent.TERMINAL.asItem().useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(wall),Direction.NORTH,wall,false)));
                var mounted=new BlockPos(-1,-58,5);
                if(!level.getBlockState(mounted).is(UmbralContent.TERMINAL)||level.getBlockState(mounted).getValue(TerminalBlock.MOUNT)!=TerminalBlock.Mount.WALL)
                    throw new AssertionError("The terminal BlockItem must create a wall mounting on a vertical face");
                ((DeviceBlockEntity)level.getBlockEntity(mounted)).bind(player.getUUID(),core);
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            });
            world.getServer().runCommand("tp @a 3.5 -58.5 -3.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(1,-59,4)); context.waitTicks(25); connection.waitForChunksRender();
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            capture(context, "umbral-family-metal");
            int originalFov=context.computeOnClient(c->c.options.fov().get());context.runOnClient(c->c.options.fov().set(35));
            world.getServer().runCommand("tp @a -1.5 -59.9 1.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(-3,-60,4)); context.waitTicks(6); capture(context, "umbral-core-metal-detail");
            world.getServer().runCommand("tp @a 1.8 -59.9 1.5 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(0,-60,4)); context.waitTicks(6); capture(context, "umbral-node-terminal-metal-detail");
            world.getServer().runCommand("tp @a 1.9 -60 2.6 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(1,-60,4)); context.waitTicks(6); capture(context, "umbral-terminal-metal-detail");
            world.getServer().runCommand("tp @a 4.9 -59.6 3.1 0 0"); connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(4,-59,5)); context.waitTicks(6); capture(context, "umbral-reader-metal-detail");
            world.getServer().runCommand("tp @a -0.3 -58.5 2.7 0 0");connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(-1,-58,5));context.waitTicks(6);capture(context,"umbral-terminal-wall-detail");
            context.runOnClient(client -> { if (client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            world.getServer().runCommand("tp @a -0.5 -58 3.5 0 0");connection.waitForClientboundPackets();
            context.getInput().lookAt(new BlockPos(-1,-58,5));context.getInput().pressKey(o->o.keyUse);
            context.waitForScreen(NetworkStatusScreen.class);context.clickScreenButton("gui.done");context.waitForScreen(null);
            world.getServer().runCommand("tp @a 4.5 -60 3.5 0 0");connection.waitForClientboundPackets();
            world.getServer().runOnServer(server->{
                var p=connection.getServerPlayer();p.getInventory().clearContent();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.ACCESS_CARD,2));
                var r=(DeviceBlockEntity)connection.getServerLevel().getBlockEntity(new BlockPos(4,-59,5));
                if(AccessService.issue(p,r)!=AccessService.Result.ISSUED)throw new AssertionError("An actual grant supplies the issued-card item model");
                p.getInventory().setItem(2,new ItemStack(UmbralContent.CONFIGURATOR));
                p.getInventory().setItem(3,new ItemStack(UmbralContent.CORE));p.getInventory().setItem(4,new ItemStack(UmbralContent.NODE));
                p.getInventory().setItem(5,new ItemStack(UmbralContent.TERMINAL));p.getInventory().setItem(6,new ItemStack(UmbralContent.DOOR));
                p.getInventory().setItem(7,new ItemStack(UmbralContent.READER));p.getInventory().setItem(8,new ItemStack(UmbralContent.CABLE));
            });
            world.getServer().runCommand("gamemode survival @a");connection.waitForClientboundPackets();context.waitTicks(8);
            context.getInput().lookAt(new BlockPos(2,-60,1));context.getInput().pressKey(InputConstants.KEY_1);context.waitTicks(5);capture(context,"umbral-card-blank-hand");
            context.getInput().pressKey(InputConstants.KEY_2);context.waitTicks(5);capture(context,"umbral-card-issued-hand");
            context.getInput().pressKey(InputConstants.KEY_3);context.waitTicks(5);capture(context,"umbral-configurator-hand");
            context.getInput().pressKey(o->o.keyInventory);context.waitForScreen(InventoryScreen.class);capture(context,"umbral-equipment-inventory");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);context.waitForScreen(null);context.runOnClient(c->c.options.fov().set(originalFov));
        }
        context.getInput().resizeWindow(854, 480);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block, ServerPlayer player, String name) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        ((DeviceBlockEntity) level.getBlockEntity(pos)).initializeOwner(player.getUUID(), name);
    }

    private static void configure(ServerPlayer player, BlockPos position) {
        var hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.NORTH, position, false);
        UmbralContent.CONFIGURATOR.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }
}
