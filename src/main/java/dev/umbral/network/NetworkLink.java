// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** A remembered selection, not an authorization token: the server rechecks ownership on every bind. */
public record NetworkLink(Identifier dimension, BlockPos core, UUID network) {
    public static final Codec<NetworkLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(NetworkLink::dimension),
            BlockPos.CODEC.fieldOf("core").forGetter(NetworkLink::core),
            UUIDUtil.CODEC.fieldOf("network").forGetter(NetworkLink::network)
    ).apply(instance, NetworkLink::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkLink> STREAM_CODEC = new StreamCodec<>() {
        @Override public NetworkLink decode(RegistryFriendlyByteBuf buffer) {
            return new NetworkLink(Identifier.parse(buffer.readUtf(128)), buffer.readBlockPos(), buffer.readUUID());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, NetworkLink link) {
            buffer.writeUtf(link.dimension.toString(), 128);
            buffer.writeBlockPos(link.core);
            buffer.writeUUID(link.network);
        }
    };
}
