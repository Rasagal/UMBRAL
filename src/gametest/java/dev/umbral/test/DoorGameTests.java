// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.access.DoorMotion;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class DoorGameTests {
    private static final String STRUCTURE = "umbral-test:network";
    private static final BlockPos DOOR = new BlockPos(5, 1, 3);
    private DeviceBlockEntity door(GameTestHelper test) {
        test.setBlock(DOOR, UmbralContent.DOOR);
        test.setBlock(DOOR.above(), UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF, DoubleBlockHalf.UPPER));
        var entity = test.getBlockEntity(DOOR, DeviceBlockEntity.class); entity.initializeOwner(UUID.randomUUID(), "Taller"); return entity;
    }
    private void phase(GameTestHelper test, DeviceBlockEntity door, float position, boolean open) {
        test.setBlock(DOOR, door.getBlockState().setValue(SecurityDoorBlock.OPEN, open));
        door.doorMotion().accept(new DoorMotion.State(50, test.getLevel().getGameTime(), 0, position, position), true);
    }
    @GameTest(structure = STRUCTURE)
    public void collisionFollowsBothLeavesInAllOrientations(GameTestHelper test) {
        var door = door(test); SecurityDoorBlock.open(test.getLevel(), door);
        test.assertTrue(door.getBlockState().getCollisionShape(test.getLevel(), door.getBlockPos()).toAabbs().stream().anyMatch(b -> b.contains(.5,.5,.5)),
                "An opening command must not immediately remove the physical panels");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            test.setBlock(DOOR, door.getBlockState().setValue(DeviceBlock.FACING, facing));
            for (float progress : new float[]{0, .25f, .5f, .75f, 1}) {
                phase(test, door, progress, true);
                double gap = Math.min(12, 13 * progress) / 16;
                for (BlockPos pos : new BlockPos[]{door.getBlockPos(), door.getBlockPos().above()}) {
                    var shape = test.getLevel().getBlockState(pos).getCollisionShape(test.getLevel(), pos);
                    var center = new Vec3(.5, .5, .5);
                    test.assertTrue(shape.toAabbs().stream().noneMatch(b -> b.contains(center)) == (progress > 0), "Center gap follows progress on both halves: " + facing);
                    if (progress < .75f) test.assertTrue(gap < .61, "Incomplete travel leaves insufficient player clearance");
                }
            }
        }
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void repeatedAccessExtendsHoldWithoutRestartingTheLeaves(GameTestHelper test) {
        var door = door(test); long now = test.getLevel().getGameTime();
        phase(test, door, .5f, true);
        door.doorMotion().request(true, now);
        long revision = door.doorMotion().state().revision();
        SecurityDoorBlock.open(test.getLevel(), door);
        test.assertTrue(door.doorMotion().state().revision() == revision, "Repeated valid access must not restart an opening trajectory");
        test.assertTrue(door.doorMotion().state().position(now) == .5f, "No visual position jump");
        test.assertTrue(door.closeAt() == now + 6 + SecurityDoorBlock.HOLD_TICKS, "Full hold interval follows the remaining opening travel");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void entrantDuringClosingReversesBeforeThePassageIsBlocked(GameTestHelper test) {
        var door = door(test); phase(test, door, 1, true); door.setCloseAt(test.getLevel().getGameTime());
        SecurityDoorBlock.autoClose(test.getLevel(), door);
        test.assertFalse(door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Closing starts when clear");
        test.runAfterDelay(4, () -> {
            var occupant = test.spawn(EntityTypes.ARMOR_STAND, DOOR.north());
            occupant.setNoGravity(true); occupant.setPos(Vec3.atBottomCenterOf(door.getBlockPos()).add(0, 0, -.8));
            float before = door.doorMotion().state().position(test.getLevel().getGameTime());
            SecurityDoorBlock.autoClose(test.getLevel(), door);
            test.assertTrue(door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Presence must reverse a closing door");
            test.assertTrue(Math.abs(door.doorMotion().state().position(test.getLevel().getGameTime()) - before) < .0001, "Reversal begins at the existing position");
            test.runAfterDelay(16, () -> {
                test.assertTrue(door.doorMotion().state().position(test.getLevel().getGameTime()) == 1, "Door reopens while the entrant waits");
                test.assertTrue(door.closeAt() > test.getLevel().getGameTime(), "Reopening renews a safe hold interval");
                occupant.discard(); test.succeed();
            });
        });
    }
    @GameTest(structure = STRUCTURE)
    public void presenceOutsideAClosedDoorDoesNotGrantEntry(GameTestHelper test) {
        var door = door(test); var occupant = test.spawn(EntityTypes.ARMOR_STAND, DOOR.north());
        occupant.setNoGravity(true); occupant.setPos(Vec3.atBottomCenterOf(door.getBlockPos()).add(0, 0, -.8));
        test.assertTrue(SecurityDoorBlock.occupied(test.getLevel(), door.getBlockPos()), "Fixture enters the safety sensor");
        SecurityDoorBlock.autoClose(test.getLevel(), door);
        test.assertFalse(door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Sensor is an anti-pinch device, not an admission credential");
        test.assertTrue(door.doorMotion().state().position(test.getLevel().getGameTime()) == 0, "Closed panels stay closed");
        occupant.discard(); test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void savedClosingDoorResumesSafelyAroundAnOccupant(GameTestHelper test) {
        var door = door(test); phase(test, door, .5f, false); door.doorMotion().request(false, test.getLevel().getGameTime());
        var saved = door.saveWithoutMetadata(test.getLevel().registryAccess());
        test.setBlock(DOOR, Blocks.AIR);
        var restored = door(test);
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
        test.assertTrue(restored.doorMotion().state().position(test.getLevel().getGameTime() + 10000) == .5f, "Unloaded travel must not fast-forward");
        var occupant = test.spawn(EntityTypes.ARMOR_STAND, DOOR); occupant.setNoGravity(true);
        occupant.setPos(Vec3.atBottomCenterOf(restored.getBlockPos()));
        SecurityDoorBlock.autoClose(test.getLevel(), restored);
        test.assertTrue(restored.getBlockState().getValue(SecurityDoorBlock.OPEN), "Reloaded closing motion must respect the occupied threshold");
        test.assertTrue(restored.doorMotion().state().target() == 1, "Reopen instead of trapping the occupant");
        occupant.discard(); test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void publicAnimationAndPersistenceKeepPrivateAccessDataSeparate(GameTestHelper test) {
        var door = door(test); SecurityDoorBlock.open(test.getLevel(), door);
        var tag = door.getUpdateTag(test.getLevel().registryAccess());
        test.assertFalse(tag.contains("owner") || tag.contains("network_link") || tag.contains("door_target"), "Public animation must disclose no access metadata");
        var copy = DoorMotion.State.CODEC.parse(NbtOps.INSTANCE, tag.get("door_animation")).getOrThrow();
        test.assertTrue(copy.equals(door.doorMotion().state()), "One shared timeline controls both visible halves");
        var saved = door.saveWithoutMetadata(test.getLevel().registryAccess());
        var restored = new DeviceBlockEntity(door.getBlockPos(), door.getBlockState()); restored.setLevel(test.getLevel());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
        SecurityDoorBlock.autoClose(test.getLevel(), restored);
        test.assertTrue(restored.closeAt() == test.getLevel().getGameTime() + DoorMotion.TRAVEL_TICKS + SecurityDoorBlock.HOLD_TICKS,
                "Opening and remaining hold time resume together");
        test.assertTrue(restored.owner().equals(door.owner()) && restored.identity().equals(door.identity()), "Ownership and physical identity persist");
        test.succeed();
    }
}
