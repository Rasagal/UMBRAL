// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

public final class CopperCableBlock extends Block {
    public static final Map<Direction, BooleanProperty> CONNECTIONS = new EnumMap<>(Direction.class);
    private static final VoxelShape HUB = Block.box(7.25, .75, 7.25, 8.75, 2.25, 8.75);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);
    static {
        for (Direction direction : Direction.values()) CONNECTIONS.put(direction, BooleanProperty.create(direction.getName()));
        ARMS.put(Direction.NORTH, Block.box(7.25, .75, 0, 8.75, 2.25, 8));
        ARMS.put(Direction.SOUTH, Block.box(7.25, .75, 8, 8.75, 2.25, 16));
        ARMS.put(Direction.WEST, Block.box(0, .75, 7.25, 8, 2.25, 8.75));
        ARMS.put(Direction.EAST, Block.box(8, .75, 7.25, 16, 2.25, 8.75));
        ARMS.put(Direction.DOWN, Block.box(7.25, 0, 7.25, 8.75, 1.5, 8.75));
        ARMS.put(Direction.UP, Block.box(7.25, 1.5, 7.25, 8.75, 16, 8.75));
    }

    public CopperCableBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (BooleanProperty property : CONNECTIONS.values()) state = state.setValue(property, false);
        registerDefaultState(state);
    }

    private boolean connects(BlockState state) {
        if(state.getBlock() instanceof ElectricFenceBlock&&!state.getValue(ElectricFenceBlock.FEEDER))return false;
        if(state.getBlock() instanceof FilterBlock&&!FilterBlock.base(state))return false;
        if (state.getBlock() instanceof SecurityDoorBlock
                && state.getValue(SecurityDoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) return false;
        return state.getBlock() instanceof CopperCableBlock || state.getBlock() instanceof DeviceBlock;
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        NetworkService.invalidate(level);
        if(level instanceof ServerLevel server)server.scheduleTick(pos,this,1);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        CONNECTIONS.values().forEach(builder::add);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) {
            state = state.setValue(CONNECTIONS.get(direction), connects(context.getLevel().getBlockState(context.getClickedPos().relative(direction))));
        }
        NetworkService.invalidate(context.getLevel());
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks,
                                               BlockPos pos, Direction direction, BlockPos neighborPos,
                                               BlockState neighborState, RandomSource random) {
        if (level instanceof ServerLevel server) {NetworkService.invalidate(server);server.scheduleTick(pos,this,1);}
        return state.setValue(CONNECTIONS.get(direction), connects(neighborState));
    }

    /** Direct placement and structure/command placement must show the same loaded connections. */
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        var current=state;for(var direction:Direction.values()){
            var neighbor=pos.relative(direction);
            current=current.setValue(CONNECTIONS.get(direction),level.hasChunkAt(neighbor)&&connects(level.getBlockState(neighbor)));
        }
        if(current!=state)level.setBlock(pos,current,Block.UPDATE_ALL);
    }

    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        NetworkService.invalidate(level);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = HUB;
        for (Direction direction : Direction.values()) if (state.getValue(CONNECTIONS.get(direction))) shape = Shapes.or(shape, ARMS.get(direction));
        return shape;
    }
}
