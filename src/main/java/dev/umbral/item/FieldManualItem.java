// SPDX-License-Identifier: MPL-2.0

package dev.umbral.item;

import dev.umbral.manual.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public final class FieldManualItem extends Item {
    public FieldManualItem(Properties properties){super(properties);}
    @Override public InteractionResult use(Level level,Player player,InteractionHand hand){if(player instanceof ServerPlayer p)ManualProtocol.open(p,ManualCatalog.Entry.START);return InteractionResult.SUCCESS;}
}
