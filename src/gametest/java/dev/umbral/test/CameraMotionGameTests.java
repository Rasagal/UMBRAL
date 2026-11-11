// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.camera.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class CameraMotionGameTests {
    private static final String STRUCTURE = "umbral-test:network";
    private static final BlockPos CORE = new BlockPos(1, 1, 1), TERMINAL = new BlockPos(3, 1, 1), CAMERA = new BlockPos(6, 2, 3);
    private record Installation(ServerPlayer owner, DeviceBlockEntity core, DeviceBlockEntity terminal, DeviceBlockEntity camera) { }
    private DeviceBlockEntity place(GameTestHelper test, BlockPos pos, Block block, UUID owner) {
        test.setBlock(pos, block); var device = test.getBlockEntity(pos, DeviceBlockEntity.class); device.initializeOwner(owner, "Patio"); return device;
    }
    private Installation installation(GameTestHelper test) {
        var owner = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        var core = place(test, CORE, UmbralContent.CORE, owner.getUUID());
        var terminal = place(test, TERMINAL, UmbralContent.TERMINAL, owner.getUUID());
        var camera = place(test, CAMERA, UmbralContent.CAMERA, owner.getUUID());
        terminal.bind(owner.getUUID(), core); camera.bind(owner.getUUID(), core); owner.setPos(Vec3.atCenterOf(terminal.getBlockPos()));
        return new Installation(owner, core, terminal, camera);
    }
    private CameraProtocol.Feed open(Installation i) {
        return CameraProtocol.open(i.owner, new CameraProtocol.OpenRequest(i.terminal.getBlockPos(), i.camera.getBlockPos(), 1));
    }
    @GameTest(structure = STRUCTURE)
    public void opticalTransformMatchesPanTiltAndMountOrientation(GameTestHelper test) {
        var neutral = CameraGeometry.lens(BlockPos.ZERO, Direction.NORTH, new CameraMotion.Pose(0, 0));
        test.assertTrue(neutral.distanceToSqr(new Vec3(.5, .625, .04)) < .000001, "Neutral optical origin stays in the front aperture");
        var east = CameraGeometry.lens(BlockPos.ZERO, Direction.EAST, new CameraMotion.Pose(0, 0));
        test.assertTrue(east.distanceToSqr(new Vec3(.96, .625, .5)) < .000001, "Cardinal orientation rotates the actual origin");
        var pan = CameraGeometry.lens(BlockPos.ZERO, Direction.NORTH, new CameraMotion.Pose(30, 0));
        test.assertTrue(Math.abs(pan.x - .73) < .00001, "Positive pan moves the lens toward the east");
        var tilt = CameraGeometry.lens(BlockPos.ZERO, Direction.NORTH, new CameraMotion.Pose(0, 8));
        test.assertTrue(tilt.y < neutral.y && CameraGeometry.direction(Direction.NORTH, new CameraMotion.Pose(0, 8)).y < 0, "Positive tilt points both physical lens and view down");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void ownerCanSteerOnlyThroughTheirCurrentLiveSession(GameTestHelper test) {
        var i = installation(test); var feed = open(i);
        test.assertTrue(feed.state() == CameraAccess.State.LIVE, "Fixture opens an authorized session");
        var foreign = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL); foreign.setPos(i.owner.position());
        test.assertFalse(CameraProtocol.steer(foreign, new CameraProtocol.Steer(feed.session(), CameraMotion.Command.RIGHT)), "Knowing a session token does not confer control");
        test.assertFalse(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(UUID.randomUUID(), CameraMotion.Command.RIGHT)), "Random session fails");
        test.assertTrue(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(feed.session(), CameraMotion.Command.RIGHT)), "Live owner may steer");
        test.assertFalse(i.camera.cameraMotion().state().scan(), "Manual command stops automatic scanning");
        long revision = i.camera.cameraMotion().state().revision();
        test.assertFalse(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(feed.session(), CameraMotion.Command.LEFT)), "Repeated actions respect the tick rate limit");
        test.assertTrue(revision == i.camera.cameraMotion().state().revision(), "Rejected action does not mutate the servo");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void staleSessionCannotSteerAfterReopening(GameTestHelper test) {
        var i = installation(test); var old = open(i); var current = open(i);
        test.assertFalse(old.session().equals(current.session()), "Reopening creates a fresh session");
        test.assertFalse(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(old.session(), CameraMotion.Command.AUTO)), "Closed session cannot mutate scan settings");
        test.assertTrue(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(current.session(), CameraMotion.Command.HOME)), "Current session remains usable");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void changedDeviceAndNetworkInvalidateSteering(GameTestHelper test) {
        var i = installation(test); var feed = open(i);
        test.setBlock(CAMERA, Blocks.AIR);
        var replacement = place(test, CAMERA, UmbralContent.CAMERA, i.owner.getUUID()); replacement.bind(i.owner.getUUID(), i.core);
        test.assertFalse(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(feed.session(), CameraMotion.Command.AUTO)), "Replacement cannot inherit old control");
        feed = open(i); test.setBlock(CORE, Blocks.AIR);
        test.assertFalse(CameraProtocol.steer(i.owner, new CameraProtocol.Steer(feed.session(), CameraMotion.Command.RIGHT)), "Cut network invalidates an otherwise live lease");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void servoRespectsPhysicalWallsAndDoesNotLoadChunks(GameTestHelper test) {
        var i = installation(test); var level = test.getLevel(); var pos = i.camera.getBlockPos(); var state = i.camera.getBlockState();
        test.assertTrue(CameraGeometry.clearToMove(level, pos, state, new CameraMotion.Pose(30, 8)), "Open space permits the full range");
        test.setBlock(CAMERA.east(), Blocks.STONE);
        test.assertFalse(CameraGeometry.clearToMove(level, pos, state, new CameraMotion.Pose(30, 8)), "A side wall stops a head that would cross it");
        test.assertTrue(CameraGeometry.clearToMove(level, pos, state, new CameraMotion.Pose(-25, 8)), "Turning away from the obstacle remains possible");
        BlockPos remote = new BlockPos(25_000_000, 100, 25_000_000);
        test.assertFalse(level.isLoaded(remote), "Remote fixture starts unloaded");
        test.assertFalse(CameraGeometry.clearToMove(level, remote, state, new CameraMotion.Pose(30, 8)), "Unloaded cells cannot be assumed empty");
        test.assertFalse(level.isLoaded(remote), "Geometry checks must not load chunks");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void motionPersistsButPublicUpdatesExcludePrivateDeviceData(GameTestHelper test) {
        var i = installation(test); var motion = i.camera.cameraMotion(); long now = test.getLevel().getGameTime();
        motion.powered(true, now); motion.command(CameraMotion.Command.RIGHT, now, 20);
        var saved = i.camera.saveWithoutMetadata(test.getLevel().registryAccess());
        var restored = new DeviceBlockEntity(i.camera.getBlockPos(), i.camera.getBlockState()); restored.setLevel(test.getLevel());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
        test.assertTrue(restored.owner().equals(i.owner.getUUID()) && restored.link().equals(i.core.link()), "Private network settings persist");
        test.assertFalse(restored.cameraMotion().state().active(), "Reloaded servo waits for a new signal check");
        test.assertTrue(restored.cameraMotion().state().pose(now + 10000).equals(motion.state().pose(now)), "Reload cannot fast-forward unloaded motion");
        var tag = i.camera.getUpdateTag(test.getLevel().registryAccess());
        test.assertFalse(tag.contains("owner") || tag.contains("network_link") || tag.contains("label"), "Public animation packets contain no private ownership or network metadata");
        var copy = CameraMotion.State.CODEC.parse(NbtOps.INSTANCE, tag.get("camera_motion")).getOrThrow();
        test.assertTrue(copy.equals(motion.state()), "Public packet describes the exact same timeline as the server");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void opticalWallStillBlocksAViewAfterTheLensMoves(GameTestHelper test) {
        var i = installation(test); long now = test.getLevel().getGameTime();
        i.camera.cameraMotion().restore(new CameraMotion.State(9, now, 0, 20, 12, 20, 12, false, 25, true, false));
        test.setBlock(CAMERA.north(), Blocks.STONE);
        test.assertTrue(CameraAccess.check(i.owner, i.terminal.getBlockPos(), i.camera.getBlockPos()).state() == CameraAccess.State.OBSTRUCTED, "Pan and tilt cannot bypass the opaque front wall");
        test.setBlock(CAMERA.north(), Blocks.GLASS);
        test.assertTrue(CameraAccess.check(i.owner, i.terminal.getBlockPos(), i.camera.getBlockPos()).state() == CameraAccess.State.LIVE, "Transparent glass remains valid for surveillance");
        test.succeed();
    }
}
