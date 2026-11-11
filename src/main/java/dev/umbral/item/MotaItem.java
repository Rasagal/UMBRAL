// SPDX-License-Identifier: MPL-2.0

package dev.umbral.item;

import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.logistics.MotaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class MotaItem extends Item {
    public MotaItem(Properties p){super(p);}
    @Override public InteractionResult useOn(UseOnContext c){
        if(c.getPlayer() instanceof ServerPlayer p&&c.getLevel().getBlockEntity(c.getClickedPos()) instanceof DeviceBlockEntity dock){
            boolean ok=MotaService.install(p,dock,c.getItemInHand());p.sendOverlayMessage(Component.translatable(ok?"mota.umbral.installed":"mota.umbral.install_failed"));return InteractionResult.SUCCESS;
        }return InteractionResult.PASS;
    }
}
