// SPDX-License-Identifier: MPL-2.0

package dev.umbral.logistics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.network.NetworkLink;
import net.minecraft.core.UUIDUtil;
import java.util.UUID;

/** A persistent physical seal on the vanilla container, invalidated by replacing that container. */
public record StockSeal(UUID identity,UUID owner,NetworkLink link) {
    public static final Codec<StockSeal> CODEC=RecordCodecBuilder.create(i->i.group(
            UUIDUtil.CODEC.fieldOf("identity").forGetter(StockSeal::identity),UUIDUtil.CODEC.fieldOf("owner").forGetter(StockSeal::owner),
            NetworkLink.CODEC.fieldOf("link").forGetter(StockSeal::link)).apply(i,StockSeal::new));
}
