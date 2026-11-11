// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** A physical device reference. Replacement at the same position must require a new binding. */
public record DoorTarget(Identifier dimension, BlockPos position, UUID identity) {
    public DoorTarget { position = position.immutable(); }
    public static final Codec<DoorTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(DoorTarget::dimension),
            BlockPos.CODEC.fieldOf("position").forGetter(DoorTarget::position),
            UUIDUtil.CODEC.fieldOf("identity").forGetter(DoorTarget::identity)
    ).apply(i, DoorTarget::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DoorTarget> STREAM_CODEC = new StreamCodec<>() {
        public DoorTarget decode(RegistryFriendlyByteBuf b) { return new DoorTarget(Identifier.parse(b.readUtf(128)), b.readBlockPos(), b.readUUID()); }
        public void encode(RegistryFriendlyByteBuf b, DoorTarget t) { b.writeUtf(t.dimension.toString(), 128); b.writeBlockPos(t.position); b.writeUUID(t.identity); }
    };
}
