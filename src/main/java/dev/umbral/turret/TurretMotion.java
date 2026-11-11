// SPDX-License-Identifier: MPL-2.0

package dev.umbral.turret;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A bounded five-tick servo trajectory; public data contains no owner, network or target. */
public record TurretMotion(long revision, long started, float fromYaw, float fromPitch, float yaw, float pitch,
                           int duration, long shotAt, boolean armed, int ammunition) {
    public static final Codec<TurretMotion> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("revision").forGetter(TurretMotion::revision), Codec.LONG.fieldOf("started").forGetter(TurretMotion::started),
            Codec.floatRange(-60,60).fieldOf("from_yaw").forGetter(TurretMotion::fromYaw), Codec.floatRange(-35,20).fieldOf("from_pitch").forGetter(TurretMotion::fromPitch),
            Codec.floatRange(-60,60).fieldOf("yaw").forGetter(TurretMotion::yaw), Codec.floatRange(-35,20).fieldOf("pitch").forGetter(TurretMotion::pitch),
            Codec.intRange(0,5).fieldOf("duration").forGetter(TurretMotion::duration), Codec.LONG.fieldOf("shot_at").forGetter(TurretMotion::shotAt),
            Codec.BOOL.fieldOf("armed").forGetter(TurretMotion::armed), Codec.intRange(0,64).fieldOf("ammunition").forGetter(TurretMotion::ammunition)
    ).apply(i, TurretMotion::new));
    public static TurretMotion initial() { return new TurretMotion(0,0,0,0,0,0,0,-1000,false,0); }
    private float blend(double now) { double t=duration==0?1:Math.clamp((now-started)/duration,0,1); return (float)(t*t*(3-2*t)); }
    public float yawAt(double now) { return fromYaw+(yaw-fromYaw)*blend(now); }
    public float pitchAt(double now) { return fromPitch+(pitch-fromPitch)*blend(now); }
}
