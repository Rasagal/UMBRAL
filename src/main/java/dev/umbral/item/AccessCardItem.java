// SPDX-License-Identifier: MPL-2.0

package dev.umbral.item;

import dev.umbral.UmbralContent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

public final class AccessCardItem extends Item {
    public AccessCardItem(Properties properties) { super(properties); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                          Consumer<Component> text, TooltipFlag flag) {
        text.accept(Component.translatable(stack.has(UmbralContent.CARD_ID) ? "access.umbral.card_hint" : "access.umbral.blank_hint"));
    }
}
