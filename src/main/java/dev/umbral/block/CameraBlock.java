// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.camera.CameraGeometry;
import dev.umbral.camera.CameraMotion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Map;

/** The same optical head uses a bracket appropriate to the surface it was placed on. */
public final class CameraBlock extends DeviceBlock {
    public enum Mount implements StringRepresentable {
        FLOOR, WALL, CEILING;
        public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public static final EnumProperty<Mount> MOUNT = EnumProperty.create("mount", Mount.class);
    private static final Map<Direction, VoxelShape> FLOOR = Shapes.rotateHorizontal(Shapes.or(
            Block.box(5, 0, 8, 11, 1.5, 15), Block.box(6.5, 1.5, 10, 9.5, 6.5, 13)));
    private static final Map<Direction, VoxelShape> WALL = Shapes.rotateHorizontal(Shapes.or(
            Block.box(5, .5, 14.75, 11, 9.5, 16), Block.box(6.5, 2.5, 9.5, 9.5, 6.5, 15)));
    private static final Map<Direction, VoxelShape> CEILING = Shapes.rotateHorizontal(Shapes.or(
            Block.box(5, 15, 11, 11, 16, 16), Block.box(6.5, 8, 13, 9.5, 15, 16)));

    public CameraBlock(Properties properties) {
        super(DeviceKind.CAMERA, properties);
        // Existing cameras without this property retain their original floor mounting.
        registerDefaultState(defaultBlockState().setValue(MOUNT, Mount.FLOOR));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(MOUNT);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Mount mount = face == Direction.UP ? Mount.FLOOR : face == Direction.DOWN ? Mount.CEILING : Mount.WALL;
        return defaultBlockState().setValue(MOUNT, mount).setValue(FACING,
                mount == Mount.WALL ? face : context.getHorizontalDirection().getOpposite());
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape mount = switch (state.getValue(MOUNT)) {
            case FLOOR -> FLOOR.get(state.getValue(FACING));
            case WALL -> WALL.get(state.getValue(FACING));
            case CEILING -> CEILING.get(state.getValue(FACING));
        };
        var pose = level.getBlockEntity(pos) instanceof DeviceBlockEntity device
                ? device.cameraMotion().state().pose(level instanceof Level world ? world.getGameTime() : 0)
                : new CameraMotion.Pose(0, CameraMotion.HOME_PITCH);
        var box = CameraGeometry.headBounds(BlockPos.ZERO, state.getValue(FACING), pose);
        return Shapes.or(mount, Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
    }
}
