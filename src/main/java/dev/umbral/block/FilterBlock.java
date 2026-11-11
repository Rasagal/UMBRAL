// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.UmbralContent;
import dev.umbral.filter.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.util.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** One kit assembles a 3×3 frame; seven owned structural parts leave a real 1×2 air opening. */
public final class FilterBlock extends DeviceBlock {
    public enum Part implements StringRepresentable {
        BASE(0,0),LEFT_MIDDLE(0,1),LEFT_TOP(0,2),LINTEL(1,2),RIGHT_TOP(2,2),RIGHT_MIDDLE(2,1),RIGHT_BOTTOM(2,0);
        public final int x,y;Part(int x,int y){this.x=x;this.y=y;}
        public String getSerializedName(){return name().toLowerCase(Locale.ROOT);}
    }
    public static final EnumProperty<Part> PART=EnumProperty.create("part",Part.class);
    public static final BooleanProperty SEALED_SIDE=BooleanProperty.create("sealed_side"),SEALED_TOP=BooleanProperty.create("sealed_top");
    public FilterBlock(Properties properties){super(DeviceKind.FILTER,properties);registerDefaultState(defaultBlockState().setValue(PART,Part.BASE).setValue(SEALED_SIDE,false).setValue(SEALED_TOP,false));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(PART,SEALED_SIDE,SEALED_TOP);}
    public static boolean leftColumn(Part part){return part==Part.BASE||part==Part.LEFT_MIDDLE||part==Part.LEFT_TOP;}
    public static boolean rightColumn(Part part){return part==Part.RIGHT_BOTTOM||part==Part.RIGHT_MIDDLE||part==Part.RIGHT_TOP;}
    public static boolean topRow(Part part){return part==Part.LEFT_TOP||part==Part.LINTEL||part==Part.RIGHT_TOP;}

    /** Use the actual complete touching face. A partial side face must not grow a floating panel. */
    private static boolean support(LevelReader level,BlockPos pos,Direction direction,boolean previous){
        var neighbor=pos.relative(direction);
        if(!level.hasChunkAt(neighbor))return previous; // Never load chunks or infer a missing wall.
        var state=level.getBlockState(neighbor);
        if(state.getBlock() instanceof DeviceBlock||state.getBlock() instanceof CopperCableBlock)return false;
        return state.isFaceSturdy(level,neighbor,direction.getOpposite());
    }
    public static BlockState withFittings(LevelReader level,BlockPos pos,BlockState state){
        var part=state.getValue(PART);var right=state.getValue(FACING).getClockWise();
        boolean side=(leftColumn(part)||rightColumn(part))&&support(level,pos,leftColumn(part)?right.getOpposite():right,state.getValue(SEALED_SIDE));
        boolean top=topRow(part)&&support(level,pos,Direction.UP,state.getValue(SEALED_TOP));
        return state.setValue(SEALED_SIDE,side).setValue(SEALED_TOP,top);
    }
    /** Reconcile saved arches too; neighbors may change while their chunks are unloaded. */
    public static void refreshFrame(Level level,BlockPos root,Direction facing){
        for(var part:Part.values()){
            var pos=partPos(root,facing,part);if(!level.isLoaded(pos))continue;
            var state=level.getBlockState(pos);
            if(!(state.getBlock() instanceof FilterBlock)||state.getValue(PART)!=part||state.getValue(FACING)!=facing)continue;
            var updated=withFittings(level,pos,state);
            if(updated!=state)level.setBlock(pos,updated,Block.UPDATE_ALL);
        }
    }
    public static boolean base(BlockState s){return s.getBlock() instanceof FilterBlock&&s.getValue(PART)==Part.BASE;}
    public static BlockPos partPos(BlockPos base,Direction facing,Part part){return base.relative(facing.getClockWise(),part.x).above(part.y);}
    public static BlockPos basePos(BlockPos pos,BlockState state){var part=state.getValue(PART);return pos.relative(state.getValue(FACING).getClockWise(),-part.x).below(part.y);}
    public static DeviceBlockEntity controller(Level level,BlockPos pos){
        var state=level.getBlockState(pos);if(!(state.getBlock() instanceof FilterBlock))return null;var root=basePos(pos,state);
        return level.isLoaded(root)&&level.getBlockEntity(root) instanceof DeviceBlockEntity d&&base(d.getBlockState())?d:null;
    }
    public static boolean complete(DeviceBlockEntity root){
        if(root.getLevel()==null||!base(root.getBlockState()))return false;var level=root.getLevel();var facing=root.getBlockState().getValue(FACING);
        for(var part:Part.values()){
            var pos=partPos(root.getBlockPos(),facing,part);if(!level.isLoaded(pos))return false;var state=level.getBlockState(pos);
            if(!(state.getBlock() instanceof FilterBlock)||state.getValue(PART)!=part||state.getValue(FACING)!=facing||!(level.getBlockEntity(pos) instanceof DeviceBlockEntity d)||!Objects.equals(d.owner(),root.owner())||!d.filter().assembly().equals(root.identity()))return false;
        }return true;
    }
    public static AABB passage(DeviceBlockEntity root){
        var center=root.getBlockPos().relative(root.getBlockState().getValue(FACING).getClockWise());var facing=root.getBlockState().getValue(FACING);return new AABB(center).expandTowards(0,1,0).inflate(facing.getAxis()==Direction.Axis.X?.4:.1,.1,facing.getAxis()==Direction.Axis.Z?.4:.1);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        var state=super.getStateForPlacement(c);var root=c.getClickedPos();var facing=state.getValue(FACING);var level=c.getLevel();var p=c.getPlayer();
        if(root.getY()+2>level.getMaxY())return null;
        for(int x=0;x<3;x++)for(int y=0;y<3;y++){
            var pos=root.relative(facing.getClockWise(),x).above(y);if(!level.hasChunkAt(pos)||!level.getBlockState(pos).canBeReplaced(c))return null;
            if(p!=null&&(!p.mayUseItemAt(pos,Direction.UP,c.getItemInHand())||!level.mayInteract(p,pos)))return null;
            if(p instanceof ServerPlayer sp&&!dev.umbral.protection.ProtectionPolicy.permits((ServerLevel)level,pos,sp.getUUID(),dev.umbral.protection.ProtectionData.BUILD))return null;
        }
        boolean controllerSupported=Block.canSupportCenter(level,root.below(),Direction.UP)||level.getBlockState(root.below()).getBlock() instanceof CopperCableBlock;
        if(!controllerSupported||!Block.canSupportCenter(level,root.relative(facing.getClockWise(),2).below(),Direction.UP))return null;
        return withFittings(level,root,state);
    }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity by,ItemStack stack){
        super.setPlacedBy(level,pos,state,by,stack);if(!(by instanceof Player p)||!(level instanceof ServerLevel))return;
        if(!(level.getBlockEntity(pos) instanceof DeviceBlockEntity root))return;
        root.filter().assembly(root.identity());root.setChanged();
        for(var part:Part.values()){
            var at=partPos(pos,state.getValue(FACING),part);if(level.getBlockEntity(at) instanceof DeviceBlockEntity d){d.initializeOwner(p.getUUID(),root.label());d.filter().assembly(root.identity());d.setChanged();}
        }
        refreshFrame(level,pos,state.getValue(FACING));
        for(var part:Part.values()){var at=partPos(pos,state.getValue(FACING),part);level.getBlockState(at).updateNeighbourShapes(level,at,Block.UPDATE_ALL);level.updateNeighborsAt(at,this);}
    }
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction direction,BlockPos neighborPos,BlockState neighbor,RandomSource random){
        var root=basePos(pos,state);var part=state.getValue(PART);var facing=state.getValue(FACING);int index=part.ordinal();
        for(int n:new int[]{index-1,index+1})if(n>=0&&n<Part.values().length){var at=partPos(root,facing,Part.values()[n]);if(level.hasChunkAt(at)&&at.equals(neighborPos)&&(!(neighbor.getBlock() instanceof FilterBlock)||neighbor.getValue(PART)!=Part.values()[n]||neighbor.getValue(FACING)!=facing))return Blocks.AIR.defaultBlockState();}
        return super.updateShape(withFittings(level,pos,state),level,ticks,pos,direction,neighborPos,neighbor,random);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide()&&player.preventsBlockDrops()&&!base(state)){var root=basePos(pos,state);if(level.isLoaded(root)&&base(level.getBlockState(root)))level.setBlock(root,Blocks.AIR.defaultBlockState(),35);}
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        var part=state.getValue(PART);VoxelShape shape=switch(part){
            case BASE -> Shapes.or(Block.box(0,0,0,16,.5,16),Block.box(6,0,3,16,2,13),Block.box(8.5,2,5.5,14.5,16,10.5));
            case LEFT_MIDDLE -> Block.box(8.5,0,5.5,14.5,16,10.5);
            case LEFT_TOP -> Shapes.or(Block.box(8.5,0,5.5,14.5,16,10.5),Block.box(8,8,4.5,16,14,11.5));
            case LINTEL -> Block.box(0,8,4.5,16,14,11.5);
            case RIGHT_TOP -> Shapes.or(Block.box(1.5,0,5.5,7.5,16,10.5),Block.box(0,8,4.5,8,14,11.5));
            case RIGHT_MIDDLE -> Block.box(1.5,0,5.5,7.5,16,10.5);
            case RIGHT_BOTTOM -> Shapes.or(Block.box(0,0,0,16,.5,16),Block.box(0,0,3,10,2,13),Block.box(1.5,2,5.5,7.5,16,10.5));
        };
        if(state.getValue(SEALED_SIDE)){
            if(leftColumn(part))shape=Shapes.or(shape,Block.box(0,0,6.75,8.5,16,9.25));
            else if(rightColumn(part))shape=Shapes.or(shape,Block.box(7.5,0,6.75,16,16,9.25));
        }
        if(state.getValue(SEALED_TOP)&&topRow(part)){
            double x0=part==Part.LEFT_TOP?8:0,x1=part==Part.RIGHT_TOP?8:16;
            shape=Shapes.or(shape,Block.box(x0,14,6.75,x1,16,9.25));
        }
        return Shapes.rotateHorizontal(shape).get(state.getValue(FACING));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player p,BlockHitResult hit){if(p instanceof ServerPlayer sp){var root=controller(level,pos);if(root!=null)FilterProtocol.open(sp,root,root);}return InteractionResult.SUCCESS;}
    @Override protected boolean isSignalSource(BlockState state){return base(state);}
    @Override protected int getSignal(BlockState state,BlockGetter level,BlockPos pos,Direction direction){return base(state)&&level.getBlockEntity(pos) instanceof DeviceBlockEntity d&&d.getLevel()!=null&&d.filter().alarm(d.getLevel().getGameTime())?15:0;}
}
