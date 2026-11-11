// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.camera.CameraProtocol;
import dev.umbral.camera.CameraGeometry;
import dev.umbral.client.CameraDirectoryScreen;
import dev.umbral.client.CameraViewScreen;
import dev.umbral.client.NetworkStatusScreen;
import dev.umbral.client.ManualScreen;
import dev.umbral.manual.ManualCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class CameraClientGameTest implements FabricClientGameTest {
    private static final BlockPos CORE = new BlockPos(0, -60, 0);
    private static final BlockPos TERMINAL = new BlockPos(2, -60, -2);
    private static final BlockPos NODE = new BlockPos(24, -60, 0);
    private static final BlockPos CAMERA = new BlockPos(26, -57, 3);
    private static final BlockPos CUT = new BlockPos(12, -60, 0);

    private static void assertThat(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void capture(ClientGameTestContext context, String name) {
        context.waitFor(client -> client.gui.overlay() == null);
        context.getInput().setCursorPos(2, 2);
        context.waitTicks(2);
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(Path.of("../../visual-checks/1.6.1")));
    }
    private static void waitForViewButton(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.screen() instanceof CameraDirectoryScreen screen
                && screen.children().stream().anyMatch(child -> child instanceof Button button && button.active
                && button.getMessage().getString().equals(Component.translatable("screen.umbral.view").getString())));
    }
    private static void assertRestored(ClientGameTestContext context) {
        context.runOnClient(client -> {
            assertThat(client.getCameraEntity() == client.player, "View must return to the original player");
            assertThat(client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "Original perspective must be restored");
            assertThat(!client.gui.hud.isHidden(), "Original HUD visibility must be restored");
        });
    }

    @Override public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.overlay() == null, 1000);
        var reload = context.computeOnClient(client -> {
            client.options.renderDistance().set(4); client.options.simulationDistance().set(5);
            client.getLanguageManager().setSelected("es_es"); client.options.languageCode = "es_es";
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 1000);
        try (var world = context.worldBuilder().create()) {
            var connection = world.getConnection();
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel(); var player = connection.getServerPlayer();
                for (int x = -3; x <= 31; x++) for (int z = -13; z <= 6; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState());
                }
                var core = place(level, CORE, UmbralContent.CORE, player.getUUID(), "NEXO · Patio");
                var terminal = place(level, TERMINAL, UmbralContent.TERMINAL, player.getUUID(), "Control de vigilancia");
                var node = place(level, NODE, UmbralContent.NODE, player.getUUID(), "Antena del patio");
                for (int x = 1; x < 24; x++) level.setBlockAndUpdate(new BlockPos(x, -60, 0), UmbralContent.CABLE.defaultBlockState());
                for (int y = -60; y < -57; y++) level.setBlockAndUpdate(new BlockPos(26, y, 3), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                var camera = place(level, CAMERA, UmbralContent.CAMERA, player.getUUID(), "VIGÍA · Acceso norte");
                terminal.bind(player.getUUID(), core); node.bind(player.getUUID(), core); camera.bind(player.getUUID(), core);
                // A distinct courtyard visible from the lens, far from the terminal's own position.
                for (int x = 22; x <= 30; x++) for (int y = -60; y <= -57; y++) {
                    boolean frame = x == 22 || x == 30 || y == -57;
                    if (frame) level.setBlockAndUpdate(new BlockPos(x, y, -9), Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
                }
                for (int x : new int[]{22, 30}) level.setBlockAndUpdate(new BlockPos(x, -56, -9), Blocks.SEA_LANTERN.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(26, -60, -8), Blocks.AMETHYST_BLOCK.defaultBlockState());
                player.getAbilities().flying = true; player.onUpdateAbilities();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            });
            world.getServer().runCommand("tp @a 27.5 -56 0.5 0 0");
            connection.waitForClientboundPackets();
            context.getInput().lookAt(CAMERA);
            context.runOnClient(client -> client.gui.hud.toggle());
            context.waitTicks(25); connection.waitForChunksRender();
            capture(context, "umbral-vigia-model");
            context.runOnClient(client -> client.gui.hud.toggle());

            world.getServer().runCommand("tp @a 2.5 -60 -4.5 0 12");
            connection.waitForClientboundPackets();
            context.getInput().lookAt(TERMINAL);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(NetworkStatusScreen.class);
            capture(context, "umbral-terminal-interface");
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            Vec3 originalPosition = world.getServer().computeOnServer(server -> connection.getServerPlayer().position());
            context.clickScreenButton("screen.umbral.cameras");
            context.waitForScreen(CameraDirectoryScreen.class); waitForViewButton(context);
            capture(context, "umbral-camera-directory");
            context.clickScreenButton("?"); context.waitForScreen(ManualScreen.class);
            context.runOnClient(client->assertThat(((ManualScreen)client.gui.screen()).entry()==ManualCatalog.Entry.VIGIA,"Camera directory help opens the shared VIGÍA chapter"));
            context.clickScreenButton("manual.umbral.back");context.waitForScreen(CameraDirectoryScreen.class);waitForViewButton(context);
            context.clickScreenButton("screen.umbral.view");context.waitForScreen(CameraViewScreen.class);
            context.clickScreenButton("manual.umbral.camera_help");context.waitForScreen(ManualScreen.class);assertRestored(context);
            connection.waitForServerboundPackets();world.getServer().runOnServer(server->assertThat(!CameraProtocol.isViewing(connection.getServerPlayer().getUUID()),"Reading camera help releases the live server lease"));
            capture(context,"umbral-manual-camera-context");context.clickScreenButton("manual.umbral.back");context.waitForScreen(CameraDirectoryScreen.class);waitForViewButton(context);
            context.clickScreenButton("screen.umbral.view");
            context.waitForScreen(CameraViewScreen.class);
            context.clickScreenButton("motion.umbral.home_hint");
            context.waitTicks(100); connection.waitForChunksRender(false);
            context.runOnClient(client -> {
                assertThat(client.getCameraEntity() instanceof Marker, "Rendering must use a separate local lens");
                var device = (DeviceBlockEntity) client.level.getBlockEntity(CAMERA);
                Vec3 expected = CameraGeometry.lens(CAMERA, device.getBlockState().getValue(dev.umbral.block.DeviceBlock.FACING),
                        device.cameraMotion().state().pose(client.level.getGameTime()));
                assertThat(client.gameRenderer.mainCamera().position().distanceToSqr(expected) < 0.0001, "Viewpoint must match the physical lens");
                assertThat(client.options.getCameraType() == CameraType.FIRST_PERSON, "Live view must use the lens perspective");
            });
            capture(context, "umbral-camera-entry");
            assertControlsFit(context);
            context.waitTicks(100); // The entry reminder clears while the real view lease continues renewing.
            capture(context, "umbral-camera-live");
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.waitFor(client -> client.gui.screen() instanceof CameraViewScreen screen && screen.children().stream()
                    .anyMatch(child -> child instanceof Button button && button.getMessage().getString()
                            .equals(Component.translatable("hud.umbral.show_data").getString())));
            context.runOnClient(client -> {
                assertThat(client.getCameraEntity() instanceof Marker, "Clean view must keep the same camera viewpoint");
                assertThat(client.gui.screen().children().stream().noneMatch(child->child instanceof Button button&&button.visible&&button.getMessage().getString().equals(Component.translatable("manual.umbral.camera_help").getString())),"F1 also hides camera help");
                assertThat(client.gui.hud.isHidden(), "F1 must not reveal the player's vanilla HUD inside the camera view");
            });
            capture(context, "umbral-camera-clean");
            // The mouse button restores the same controls as the keyboard shortcut.
            context.clickScreenButton("hud.umbral.show_data");
            context.runOnClient(client -> assertThat(client.gui.screen().children().stream()
                    .anyMatch(child -> child instanceof Button button && button.getMessage().getString()
                            .equals(Component.translatable("hud.umbral.hide_data").getString())), "Mouse must restore view details"));
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(3); assertControlsFit(context);
            capture(context, "umbral-camera-compact");
            context.getInput().resizeWindow(854, 480);
            context.waitTicks(3);
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.waitTicks(80); // A signal interruption must still revoke a session in clean mode.
            world.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer();
                assertThat(CameraProtocol.isViewing(player.getUUID()), "Keep-alives should renew the view lease");
                assertThat(player.position().distanceToSqr(originalPosition) < 0.0001, "Watching must not move the server-side player");
                assertThat(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "Watching must not change game mode");
                assertThat(player.getMainHandItem().isEmpty(), "Watching must not change the player's inventory");
                connection.getServerLevel().setBlockAndUpdate(CUT, Blocks.AIR.defaultBlockState());
            });
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            context.waitTicks(25); capture(context, "umbral-camera-signal-lost");
            world.getServer().runOnServer(server -> {
                assertThat(!CameraProtocol.isViewing(connection.getServerPlayer().getUUID()), "Signal loss must revoke the server session");
                connection.getServerLevel().setBlockAndUpdate(CUT, UmbralContent.CABLE.defaultBlockState());
            });

            waitForViewButton(context); context.clickScreenButton("screen.umbral.view");
            context.waitForScreen(CameraViewScreen.class);
            world.getServer().runOnServer(server -> connection.getServerPlayer().hurtTime = 40);
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            world.getServer().runOnServer(server -> connection.getServerPlayer().hurtTime = 0);
            waitForViewButton(context); context.clickScreenButton("screen.umbral.view");
            context.waitForScreen(CameraViewScreen.class);
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.clickScreenButton("screen.umbral.leave_camera");
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            connection.waitForServerboundPackets();
            world.getServer().runOnServer(server -> assertThat(!CameraProtocol.isViewing(connection.getServerPlayer().getUUID()), "Manual exit must release the session"));

            context.waitTicks(20); waitForViewButton(context); context.clickScreenButton("screen.umbral.view");
            context.waitForScreen(CameraViewScreen.class);
            world.getServer().runOnServer(server -> connection.getServerLevel().setBlockAndUpdate(CAMERA, Blocks.AIR.defaultBlockState()));
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            context.waitTicks(25);
            world.getServer().runOnServer(server -> {
                var core = (DeviceBlockEntity) connection.getServerLevel().getBlockEntity(CORE);
                assertThat(CameraDirectory.get(connection.getServerLevel()).cameras(connection.getServerPlayer().getUUID(), core.link()).isEmpty(),
                        "Physical removal must clean discovery data");
            });
            capture(context, "umbral-camera-removed");
            // A crafted request cannot open an arbitrary position after the camera was removed.
            context.runOnClient(client -> ClientPlayNetworking.send(new CameraProtocol.OpenRequest(TERMINAL, CAMERA, 1234)));
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            world.getServer().runOnServer(server -> assertThat(!CameraProtocol.isViewing(connection.getServerPlayer().getUUID()), "Unlisted coordinates must not open a session"));
            assertRestored(context);

            // Exercise real pagination, disabled controls and layout changes with actual paired devices.
            world.getServer().runOnServer(server -> {
                var level = connection.getServerLevel();
                var core = (DeviceBlockEntity) level.getBlockEntity(CORE);
                for (int i = 0; i < 7; i++) {
                    BlockPos position = new BlockPos(-8 + i * 2, -58, 6);
                    String name = i == 0 ? "Acceso principal del almacén de cobre y hierro" : "VIGÍA · Sector " + (i + 1);
                    var camera = place(level, position, UmbralContent.CAMERA, connection.getServerPlayer().getUUID(), name);
                    camera.bind(connection.getServerPlayer().getUUID(), core);
                    if (i == 0) level.setBlockAndUpdate(position.north(), Blocks.STONE.defaultBlockState());
                }
            });
            connection.waitForClientboundPackets(); context.waitTicks(30);
            context.clickScreenButton("screen.umbral.back");
            context.waitForScreen(NetworkStatusScreen.class);
            context.clickScreenButton("screen.umbral.cameras");
            context.waitForScreen(CameraDirectoryScreen.class); waitForViewButton(context);
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
            context.waitTicks(5);
            assertControlsFit(context);
            context.runOnClient(client -> {
                var buttons = client.gui.screen().children().stream().filter(child -> child instanceof Button)
                        .map(child -> (Button) child).filter(button -> button.getMessage().getString()
                                .equals(Component.translatable("screen.umbral.view").getString())).toList();
                assertThat(buttons.size() == 4, "Wide directory must show four camera cards");
                assertThat(buttons.stream().anyMatch(button -> !button.active), "Obstructed lens must have a disabled view button");
            });
            capture(context, "umbral-directory-wide");
            context.clickScreenButton(">");
            context.runOnClient(client -> assertThat(client.gui.screen().children().stream()
                    .anyMatch(child -> child instanceof Button button && button.active && button.getMessage().getString().equals("<")),
                    "Next page must allow returning to the previous page"));
            capture(context, "umbral-directory-page-two");
            context.clickScreenButton("<");
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(5); assertControlsFit(context);
            capture(context, "umbral-directory-compact");
            context.clickScreenButton("screen.umbral.back");
            context.waitForScreen(NetworkStatusScreen.class); assertControlsFit(context);
            capture(context, "umbral-terminal-compact");
            context.clickScreenButton("screen.umbral.cameras");
            context.waitForScreen(CameraDirectoryScreen.class); waitForViewButton(context);
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.waitTicks(5); assertControlsFit(context);
            capture(context, "umbral-directory-scale-three");
            // Keyboard activation must remain native even though every button has custom rendering.
            context.runOnClient(client -> client.gui.screen().setFocused(null));
            context.getInput().pressKey(43); // SDL_SCANCODE_TAB
            context.runOnClient(client -> assertThat(client.gui.screen().getFocused() instanceof Button button && button.active,
                    "Tab must focus an available button"));
            context.getInput().pressKey(40); // SDL_SCANCODE_RETURN
            context.waitForScreen(CameraViewScreen.class);
            assertControlsFit(context);
            capture(context, "umbral-live-scale-three");
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.getInput().pressKey(41); // SDL_SCANCODE_ESCAPE
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            var english = context.computeOnClient(client -> {
                client.getLanguageManager().setSelected("en_us"); client.options.languageCode = "en_us";
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> english.isDone() && client.gui.overlay() == null);
            context.runOnClient(client -> { client.options.guiScale().set(1); client.resizeGui(); });
            waitForViewButton(context);
            context.waitTicks(3); assertControlsFit(context);
            capture(context, "umbral-directory-scale-one-english");
            // The first listed lens is deliberately obstructed; choose the first enabled control.
            context.runOnClient(client -> client.gui.screen().setFocused(null));
            context.getInput().pressKey(43); // SDL_SCANCODE_TAB
            context.getInput().pressKey(40); // SDL_SCANCODE_RETURN
            context.waitForScreen(CameraViewScreen.class); assertControlsFit(context);
            capture(context, "umbral-camera-english");
            context.getInput().pressKey(InputConstants.KEY_F1);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitForScreen(CameraDirectoryScreen.class); assertRestored(context);
            context.getInput().resizeWindow(854, 480);
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        }
    }

    private static void assertControlsFit(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            var buttons = screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child).toList();
            for (var button : buttons) {
                assertThat(button.getX() >= 0 && button.getY() >= 0 && button.getRight() <= screen.width && button.getBottom() <= screen.height,
                        "Controls must remain on screen at every tested scale");
                for (var other : buttons) if (other != button) {
                    assertThat(button.getRight() <= other.getX() || other.getRight() <= button.getX()
                                    || button.getBottom() <= other.getY() || other.getBottom() <= button.getY(),
                            "Controls must not overlap after resizing");
                }
            }
        });
    }

    private static DeviceBlockEntity place(ServerLevel level, BlockPos position, Block block, UUID owner, String label) {
        level.setBlockAndUpdate(position, block.defaultBlockState());
        var device = (DeviceBlockEntity) level.getBlockEntity(position);
        device.initializeOwner(owner, label);
        return device;
    }
}
