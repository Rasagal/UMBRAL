// SPDX-License-Identifier: MPL-2.0

package dev.umbral.mixin;

import dev.umbral.protection.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class ProtectionBlockItemMixin {
    @Inject(method="place",at=@At("HEAD"),cancellable=true)
    private void umbral$multiple(BlockPlaceContext c,CallbackInfoReturnable<net.minecraft.world.InteractionResult> ci){
        if(c.getPlayer()==null)return;var block=((BlockItem)(Object)this).getBlock();var pos=c.getClickedPos();
        if(!ProtectionPolicy.player(c.getPlayer(),pos,ProtectionData.BUILD,"place")){ci.setReturnValue(net.minecraft.world.InteractionResult.FAIL);return;}
        if((block instanceof net.minecraft.world.level.block.DoorBlock||block instanceof dev.umbral.block.SecurityDoorBlock||block instanceof net.minecraft.world.level.block.DoublePlantBlock)&&!ProtectionPolicy.player(c.getPlayer(),pos.above(),ProtectionData.BUILD,"place"))ci.setReturnValue(net.minecraft.world.InteractionResult.FAIL);
        if(block instanceof net.minecraft.world.level.block.BedBlock&&!ProtectionPolicy.player(c.getPlayer(),pos.relative(c.getHorizontalDirection()),ProtectionData.BUILD,"place"))ci.setReturnValue(net.minecraft.world.InteractionResult.FAIL);
    }
    @Inject(method="place",at=@At("RETURN"))
    private void umbral$recent(BlockPlaceContext c,CallbackInfoReturnable<net.minecraft.world.InteractionResult> ci){if(c.getPlayer() instanceof net.minecraft.server.level.ServerPlayer p&&ci.getReturnValue().consumesAction())ProtectionSuggestions.placed(p,c.getClickedPos());}
    @Inject(method="canPlace",at=@At("HEAD"),cancellable=true)
    private void umbral$place(BlockPlaceContext context,BlockState state,CallbackInfoReturnable<Boolean> ci){if(context.getPlayer()!=null&&!ProtectionPolicy.player(context.getPlayer(),context.getClickedPos(),ProtectionData.BUILD,"place"))ci.setReturnValue(false);}
}
