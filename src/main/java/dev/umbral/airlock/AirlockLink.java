// SPDX-License-Identifier: MPL-2.0

package dev.umbral.airlock;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.access.DoorTarget;
/** Persisted on both doors so the interlock survives a missing scanner or a restart. */
public record AirlockLink(DoorTarget controller,DoorTarget other,boolean outer){
    public static final Codec<AirlockLink> CODEC=RecordCodecBuilder.create(i->i.group(DoorTarget.CODEC.fieldOf("controller").forGetter(AirlockLink::controller),DoorTarget.CODEC.fieldOf("other").forGetter(AirlockLink::other),Codec.BOOL.fieldOf("outer").forGetter(AirlockLink::outer)).apply(i,AirlockLink::new));
}
