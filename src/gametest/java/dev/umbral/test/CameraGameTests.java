// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.camera.CameraAccess;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.camera.CameraProtocol;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

public final class CameraGameTests {
    private static final String STRUCTURE = "umbral-test:network";
    private static final BlockPos CORE = new BlockPos(1, 1, 1);
    private static final BlockPos TERMINAL = new BlockPos(3, 1, 1);
    private static final BlockPos CAMERA = new BlockPos(10, 2, 3);

    private DeviceBlockEntity place(GameTestHelper test, BlockPos pos, Block block, UUID owner, String label) {
        test.setBlock(pos, block);
        var device = test.getBlockEntity(pos, DeviceBlockEntity.class);
        device.initializeOwner(owner, label);
        return device;
    }
    private record Installation(ServerPlayer player, DeviceBlockEntity core, DeviceBlockEntity terminal, DeviceBlockEntity camera) { }
    private Installation installation(GameTestHelper test) {
        var player = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        UUID owner = player.getUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner, "Patio");
        var terminal = place(test, TERMINAL, UmbralContent.TERMINAL, owner, "Control");
        var camera = place(test, CAMERA, UmbralContent.CAMERA, owner, "Entrada norte");
        terminal.bind(owner, core); camera.bind(owner, core);
        player.setPos(net.minecraft.world.phys.Vec3.atCenterOf(terminal.getBlockPos()));
        return new Installation(player, core, terminal, camera);
    }
    private CameraAccess.State state(Installation installation) {
        return CameraAccess.check(installation.player, installation.terminal.getBlockPos(), installation.camera.getBlockPos()).state();
    }

    @GameTest(structure = STRUCTURE)
    public void pairedCameraAppearsOnlyOnItsOwnNetwork(GameTestHelper test) {
        var i = installation(test);
        var listing = CameraProtocol.list(i.player, new CameraProtocol.ListRequest(i.terminal.getBlockPos(), 7));
        test.assertTrue(listing.entries().size() == 1, "Paired camera should appear in its network's directory");
        test.assertTrue(listing.entries().getFirst().label().equals("Entrada norte"), "Camera label should be preserved");
        test.assertTrue(state(i) == CameraAccess.State.LIVE, "Nearby authorized network should offer a live view");
        var otherCore = place(test, new BlockPos(7, 1, 1), UmbralContent.CORE, i.player.getUUID(), "Another network");
        i.camera.bind(i.player.getUUID(), otherCore);
        listing = CameraProtocol.list(i.player, new CameraProtocol.ListRequest(i.terminal.getBlockPos(), 8));
        test.assertTrue(listing.entries().isEmpty(), "Rebinding must remove the camera from its previous network");
        test.assertTrue(state(i) == CameraAccess.State.ACCESS_DENIED, "A camera outside the directory must not be probed by coordinates");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void foreignAndDistantRequestsRevealNoDirectory(GameTestHelper test) {
        var i = installation(test);
        var foreign = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        foreign.setPos(i.player.position());
        var denied = CameraProtocol.list(foreign, new CameraProtocol.ListRequest(i.terminal.getBlockPos(), 1));
        test.assertTrue(denied.state() == CameraAccess.State.ACCESS_DENIED && denied.entries().isEmpty(), "Foreign directory requests must disclose no devices");
        test.assertTrue(CameraAccess.check(foreign, i.terminal.getBlockPos(), i.camera.getBlockPos()).state() == CameraAccess.State.ACCESS_DENIED, "Foreign view request must fail");
        i.player.setPos(i.player.position().add(9, 0, 0));
        test.assertTrue(state(i) == CameraAccess.State.ACCESS_DENIED, "An owner outside terminal range must not view");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void cameraRequiresSignalEvenWhenTerminalIsOnline(GameTestHelper test) {
        var i = installation(test);
        test.setBlock(CAMERA, Blocks.AIR);
        var distant = place(test, new BlockPos(30, 2, 3), UmbralContent.CAMERA, i.player.getUUID(), "Patio remoto");
        distant.bind(i.player.getUUID(), i.core);
        test.assertTrue(CameraAccess.check(i.player, i.terminal.getBlockPos(), distant.getBlockPos()).state() == CameraAccess.State.CAMERA_OFFLINE,
                "A live terminal must not authorize an out-of-coverage camera");
        i.terminal.unbind(i.player.getUUID());
        test.assertTrue(CameraAccess.check(i.player, i.terminal.getBlockPos(), distant.getBlockPos()).state() == CameraAccess.State.TERMINAL_OFFLINE,
                "The terminal must also be linked and online");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void opaqueWallBlocksViewButGlassDoesNot(GameTestHelper test) {
        var i = installation(test);
        test.setBlock(CAMERA.north(), Blocks.STONE);
        test.assertTrue(state(i) == CameraAccess.State.OBSTRUCTED, "A lens against an opaque wall must be unavailable");
        test.setBlock(CAMERA.north(), Blocks.GLASS);
        test.assertTrue(state(i) == CameraAccess.State.LIVE, "Transparent glass should allow viewing");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void unpairingAndRemovalCleanTheDirectory(GameTestHelper test) {
        var i = installation(test);
        var directory = CameraDirectory.get(test.getLevel());
        i.camera.unbind(i.player.getUUID());
        test.assertTrue(directory.cameras(i.player.getUUID(), i.core.link()).isEmpty(), "Unpairing must remove its directory entry");
        i.camera.bind(i.player.getUUID(), i.core);
        test.setBlock(CAMERA, Blocks.AIR);
        test.assertTrue(directory.cameras(i.player.getUUID(), i.core.link()).isEmpty(), "Removing a camera must remove its entry immediately");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void directorySurvivesCodecRoundTrip(GameTestHelper test) {
        var i = installation(test);
        var directory = new CameraDirectory();
        directory.remember(i.camera);
        var serialized = CameraDirectory.CODEC.encodeStart(NbtOps.INSTANCE, directory).getOrThrow();
        var restored = CameraDirectory.CODEC.parse(NbtOps.INSTANCE, serialized).getOrThrow();
        var entries = restored.cameras(i.player.getUUID(), i.core.link());
        test.assertTrue(entries.size() == 1 && entries.getFirst().position().equals(i.camera.getBlockPos()), "Camera position and network must persist");
        test.assertTrue(entries.getFirst().label().equals(i.camera.label()), "Camera name must persist");
        test.assertTrue(restored.cameras(UUID.randomUUID(), i.core.link()).isEmpty(), "Persisted data must remain scoped to the owner");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void unavailableCameraLookupDoesNotLoadItsChunk(GameTestHelper test) {
        var i = installation(test);
        var far = i.camera.getBlockPos().offset(1_000_000, 0, 1_000_000);
        test.assertFalse(test.getLevel().isLoaded(far), "Test requires an initially unloaded chunk");
        // Seed persisted discovery metadata without placing a block or loading a chunk.
        var remembered = new DeviceBlockEntity(far, UmbralContent.CAMERA.defaultBlockState());
        remembered.setLevel(test.getLevel()); remembered.initializeOwner(i.player.getUUID(), "Remote remembered camera");
        remembered.bind(i.player.getUUID(), i.core);
        test.assertTrue(CameraAccess.check(i.player, i.terminal.getBlockPos(), far).state() == CameraAccess.State.UNLOADED, "An unloaded camera should be distinct from a removed one");
        test.assertFalse(test.getLevel().isLoaded(far), "Directory and permission checks must never force chunk loading");
        CameraDirectory.get(test.getLevel()).remove(far);
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void cameraLimitCanBeReclaimedAndDamageInterruptsViewing(GameTestHelper test) {
        var i = installation(test);
        i.camera.unbind(i.player.getUUID());
        for (int index = 0; index < CameraDirectory.MAX_CAMERAS; index++) {
            var camera = place(test, new BlockPos(6 + index % 16, 2 + index / 16, 5), UmbralContent.CAMERA, i.player.getUUID(), "Camera " + index);
            test.assertTrue(camera.bind(i.player.getUUID(), i.core), "Cameras within the limit should pair");
        }
        test.assertFalse(i.camera.bind(i.player.getUUID(), i.core), "A 65th camera must not silently disappear from the directory");
        test.getBlockEntity(new BlockPos(6, 2, 5), DeviceBlockEntity.class).unbind(i.player.getUUID());
        test.assertTrue(i.camera.bind(i.player.getUUID(), i.core), "Removing a pairing should free a slot");
        i.player.hurtTime = 10;
        test.assertTrue(state(i) == CameraAccess.State.INTERRUPTED, "An injured player must leave surveillance");
        test.succeed();
    }
}
