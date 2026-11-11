// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.turret.TurretProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public class BaluarteBlock extends DeviceBlock {
    public BaluarteBlock(Properties properties) { this(DeviceKind.TURRET,properties); }
    protected BaluarteBlock(DeviceKind kind, Properties properties) { super(kind,properties); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) {
        return Shapes.or(Block.box(1,0,1,15,3,15),Block.box(3,3,3,13,8,13),Block.box(1,8,1,15,16,15));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) {
        // Stable collision envelope for the armored housing; the muzzle stays in front of it.
        return Shapes.or(Block.box(1,0,1,15,3,15),Block.box(4,3,4,12,6,12),Block.box(2,6,2,14,16,14));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer server&&level.getBlockEntity(pos) instanceof DeviceBlockEntity turret) {
            if(TurretProtocol.manages(server,turret))TurretProtocol.open(server,turret);
            else player.sendOverlayMessage(Component.translatable("message.umbral.not_owner"));
        }
        return InteractionResult.SUCCESS;
    }
}
