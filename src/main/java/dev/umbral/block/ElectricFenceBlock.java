// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;
import dev.umbral.fence.FenceService;
import dev.umbral.installation.NexoProtocol;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** CERCO: continuous metal sections, insulated post and one deliberately paired control feeder. */
public final class ElectricFenceBlock extends DeviceBlock {
    public static final BooleanProperty ENERGIZED=BooleanProperty.create("energized"),FEEDER=BooleanProperty.create("feeder"),SUPPORT=BooleanProperty.create("support");
    public static final Map<Direction,BooleanProperty> CONNECTIONS=Map.of(Direction.NORTH,BlockStateProperties.NORTH,Direction.EAST,BlockStateProperties.EAST,Direction.SOUTH,BlockStateProperties.SOUTH,Direction.WEST,BlockStateProperties.WEST);
    public ElectricFenceBlock(Properties p){super(DeviceKind.FENCE,p);var s=defaultBlockState().setValue(ENERGIZED,false).setValue(FEEDER,false).setValue(SUPPORT,true);for(var property:CONNECTIONS.values())s=s.setValue(property,false);registerDefaultState(s);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(ENERGIZED,FEEDER,SUPPORT);CONNECTIONS.values().forEach(b::add);}
    private static boolean connects(LevelReader level,BlockPos pos,Direction dir){var at=pos.relative(dir);if(!level.hasChunkAt(at))return false;var s=level.getBlockState(at);return s.getBlock() instanceof ElectricFenceBlock||!(s.getBlock() instanceof DeviceBlock)&&s.isFaceSturdy(level,at,dir.getOpposite());}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){var state=super.getStateForPlacement(c);for(var d:CONNECTIONS.keySet())state=state.setValue(CONNECTIONS.get(d),connects(c.getLevel(),c.getClickedPos(),d));return refresh(c.getClickedPos(),state);}
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity p,ItemStack stack){super.setPlacedBy(level,pos,state,p,stack);if(!level.isClientSide()){for(var d:CONNECTIONS.keySet())state=state.setValue(CONNECTIONS.get(d),connects(level,pos,d));level.setBlock(pos,refresh(pos,state),Block.UPDATE_ALL);}}
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction dir,BlockPos at,BlockState neighbor,RandomSource random){return CONNECTIONS.containsKey(dir)?refresh(pos,state.setValue(CONNECTIONS.get(dir),connects(level,pos,dir))):state;}
    /** Keep end/corner stakes, with a support every three cells along a straight run. */
    public static BlockState refresh(BlockPos pos,BlockState state){
        boolean n=state.getValue(CONNECTIONS.get(Direction.NORTH)),s=state.getValue(CONNECTIONS.get(Direction.SOUTH)),e=state.getValue(CONNECTIONS.get(Direction.EAST)),w=state.getValue(CONNECTIONS.get(Direction.WEST));
        boolean ns=n&&s&&!e&&!w,ew=e&&w&&!n&&!s;
        boolean support=state.getValue(FEEDER)||!ns&&!ew||Math.floorMod(ns?pos.getZ():pos.getX(),3)==0;
        return state.setValue(SUPPORT,support);
    }
    public static VoxelShape body(BlockState state,boolean collision){
        double h=collision?24:19.5;var shape=state.getValue(SUPPORT)?Block.box(6.95,0,6.95,9.05,h,9.05):Shapes.empty();
        if(state.getValue(FEEDER))shape=Shapes.or(shape,Block.box(5.65,1.1,5.22,10.35,5.3,10.35));
        for(var d:CONNECTIONS.keySet())if(state.getValue(CONNECTIONS.get(d)))shape=Shapes.or(shape,switch(d){case NORTH->Block.box(7.75,0,0,8.25,h,8);case SOUTH->Block.box(7.75,0,8,8.25,h,16);case WEST->Block.box(0,0,7.75,8,h,8.25);default->Block.box(8,0,7.75,16,h,8.25);});
        return shape;
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return body(s,false);}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return body(s,true);}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player p,BlockHitResult hit){if(p instanceof ServerPlayer sp&&level.getBlockEntity(pos) instanceof DeviceBlockEntity d)NexoProtocol.open(sp,d);return InteractionResult.SUCCESS;}
    @Override public void animateTick(BlockState state,Level level,BlockPos pos,RandomSource random){if(state.getValue(ENERGIZED)&&random.nextInt(8)==0)level.addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,pos.getX()+.5,pos.getY()+.45+random.nextFloat()*.7,pos.getZ()+.5,0,.015,0);}
}
