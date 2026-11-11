// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import dev.umbral.block.CameraBlock;
import dev.umbral.block.DeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One optical transform for the animated mesh, server obstruction tests and rendered video. */
public final class CameraGeometry {
    public static Vec3 rotate(Vec3 local, Direction facing, CameraMotion.Pose pose) {
        double pitch = Math.toRadians(pose.pitch()), yaw = Math.toRadians(180 - facing.toYRot() - pose.yaw());
        double y = local.y * Math.cos(pitch) + local.z * Math.sin(pitch);
        double z = local.z * Math.cos(pitch) - local.y * Math.sin(pitch);
        return new Vec3(local.x * Math.cos(yaw) + z * Math.sin(yaw), y, z * Math.cos(yaw) - local.x * Math.sin(yaw));
    }
    public static Vec3 pivot(BlockPos pos) { return new Vec3(pos.getX() + .5, pos.getY() + .625, pos.getZ() + .5); }
    public static Vec3 lens(BlockPos pos, Direction facing, CameraMotion.Pose pose) {
        return pivot(pos).add(rotate(new Vec3(0, 0, -.46), facing, pose));
    }
    public static Vec3 direction(Direction facing, CameraMotion.Pose pose) { return rotate(new Vec3(0, 0, -1), facing, pose); }
    public static float maxPitch(BlockState state) {
        return state.getValue(CameraBlock.MOUNT) == CameraBlock.Mount.CEILING ? 8 : CameraMotion.MAX_PITCH;
    }
    public static AABB headBounds(BlockPos pos, Direction facing, CameraMotion.Pose pose) {
        return bounds(pos, facing, pose, -5, -3.5, -8, 5, 5, 6.5);
    }
    private static AABB bounds(BlockPos pos, Direction facing, CameraMotion.Pose pose,
                               double x0, double y0, double z0, double x1, double y1, double z1) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        Vec3 pivot = pivot(pos);
        for (double x : new double[]{x0 / 16, x1 / 16})
            for (double y : new double[]{y0 / 16, y1 / 16})
                for (double z : new double[]{z0 / 16, z1 / 16}) {
                    Vec3 p = pivot.add(rotate(new Vec3(x, y, z), facing, pose));
                    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                    maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
                }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    public static boolean clearToMove(ServerLevel level, BlockPos pos, BlockState state, CameraMotion.Pose pose) {
        Direction facing = state.getValue(DeviceBlock.FACING);
        // Test body and hood separately: the empty space below the rear visor is not a solid corner.
        return clearBounds(level, pos, bounds(pos, facing, pose, -4.9, -3.5, -7.2, 4.9, 3.5, 5.5))
                && clearBounds(level, pos, bounds(pos, facing, pose, -5, 2.5, -8, 5, 5, 6.5));
    }
    private static boolean clearBounds(ServerLevel level, BlockPos pos, AABB boxBounds) {
        AABB bounds = boxBounds.deflate(.0001);
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ), BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            if (p.equals(pos)) continue;
            if (!level.isLoaded(p)) return false;
            for (AABB box : level.getBlockState(p).getCollisionShape(level, p).toAabbs())
                if (box.move(p).intersects(bounds)) return false;
        }
        return true;
    }
    public static CameraAccess.State lensState(ServerLevel level, BlockPos pos, Direction facing, CameraMotion.Pose pose) {
        Vec3 pivot = pivot(pos), end = lens(pos, facing, pose).add(direction(facing, pose).scale(.18));
        // Trace beyond the lens as it pans into adjacent cells. Never read an unloaded chunk.
        for (int step = 0; step <= 32; step++) {
            BlockPos p = BlockPos.containing(pivot.lerp(end, step / 32.0));
            if (!level.isLoaded(p)) return CameraAccess.State.UNLOADED;
            if (!p.equals(pos) && level.getBlockState(p).isSolidRender()) return CameraAccess.State.OBSTRUCTED;
        }
        // A full opaque front wall remains obstructing even at the neutral recessed position.
        BlockPos front = BlockPos.containing(end);
        if (front.equals(pos)) front = pos.relative(facing);
        if (!level.isLoaded(front)) return CameraAccess.State.UNLOADED;
        return level.getBlockState(front).isSolidRender() ? CameraAccess.State.OBSTRUCTED : CameraAccess.State.LIVE;
    }
    private CameraGeometry() { }
}
