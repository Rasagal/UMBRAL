// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A bounded servo timeline shared by the server, the physical model and the live lens. */
public final class CameraMotion {
    public static final float MAX_YAW = 30, MIN_PITCH = -8, MAX_PITCH = 20, HOME_PITCH = 8;
    public static final int PAUSE_TICKS = 40;
    public record Pose(float yaw, float pitch) { }
    public record State(long revision, long started, int duration, float fromYaw, float fromPitch,
                        float yaw, float pitch, boolean scan, int range, boolean active, boolean blocked) {
        public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("revision").forGetter(State::revision), Codec.LONG.fieldOf("started").forGetter(State::started),
                Codec.intRange(0, 600).fieldOf("duration").forGetter(State::duration),
                Codec.floatRange(-MAX_YAW, MAX_YAW).fieldOf("from_yaw").forGetter(State::fromYaw),
                Codec.floatRange(MIN_PITCH, MAX_PITCH).fieldOf("from_pitch").forGetter(State::fromPitch),
                Codec.floatRange(-MAX_YAW, MAX_YAW).fieldOf("yaw").forGetter(State::yaw),
                Codec.floatRange(MIN_PITCH, MAX_PITCH).fieldOf("pitch").forGetter(State::pitch),
                Codec.BOOL.fieldOf("scan").forGetter(State::scan), Codec.intRange(15, 30).fieldOf("range").forGetter(State::range),
                Codec.BOOL.fieldOf("active").forGetter(State::active), Codec.BOOL.fieldOf("blocked").forGetter(State::blocked)
        ).apply(i, State::new));
        public Pose pose(double time) {
            if (!active) return new Pose(fromYaw, fromPitch);
            double t = duration == 0 ? 1 : Math.max(0, Math.min(1, (time - started) / duration));
            float smooth = (float) (t * t * (3 - 2 * t));
            return new Pose(fromYaw + (yaw - fromYaw) * smooth, fromPitch + (pitch - fromPitch) * smooth);
        }
        public boolean moving(double time) { return active && time < started + duration; }
    }
    public enum Command { LEFT, RIGHT, UP, DOWN, HOME, AUTO, RANGE }
    private State state = new State(0, 0, 0, 0, HOME_PITCH, 0, HOME_PITCH, false, 25, false, false);
    public State state() { return state; }
    public void restore(State state) { this.state = state; }
    public State saved(double time) {
        Pose p = state.pose(time);
        return new State(state.revision, 0, 0, p.yaw, p.pitch, p.yaw, p.pitch, state.scan, state.range, false, false);
    }
    public void initialize(long now) { change(now, 0, HOME_PITCH, true, 25, false, false); }
    public boolean powered(boolean active, long now) {
        if (active == state.active) return false;
        Pose p = state.pose(now);
        change(now, active && state.scan ? nextEnd(p.yaw) : p.yaw, p.pitch, state.scan, state.range, active, false);
        return true;
    }
    public boolean advance(long now) {
        if (!state.active || !state.scan || now < state.started + state.duration + PAUSE_TICKS) return false;
        change(now, nextEnd(state.yaw), state.pitch, true, state.range, true, false); return true;
    }
    public void obstructed(long now) {
        Pose p = state.pose(Math.max(state.started, now - 1));
        state = new State(state.revision + 1, now, 0, p.yaw, p.pitch, p.yaw, p.pitch, state.scan, state.range, state.active, true);
    }
    public boolean command(Command command, long now, float maxPitch) {
        if (!state.active) return false;
        Pose p = state.pose(now);
        boolean scan = state.scan; int range = state.range;
        float yaw = state.yaw, pitch = state.pitch;
        switch (command) {
            case LEFT -> { yaw = Math.max(-MAX_YAW, state.scan ? p.yaw - 5 : yaw - 5); scan = false; }
            case RIGHT -> { yaw = Math.min(MAX_YAW, state.scan ? p.yaw + 5 : yaw + 5); scan = false; }
            case UP -> { pitch = Math.max(MIN_PITCH, state.scan ? p.pitch - 3 : pitch - 3); if (scan) yaw = p.yaw; scan = false; }
            case DOWN -> { pitch = Math.min(maxPitch, state.scan ? p.pitch + 3 : pitch + 3); if (scan) yaw = p.yaw; scan = false; }
            case HOME -> { yaw = 0; pitch = Math.min(HOME_PITCH, maxPitch); scan = false; }
            case AUTO -> { scan = !scan; yaw = scan ? nextEnd(p.yaw) : p.yaw; pitch = p.pitch; }
            case RANGE -> { range = range == 15 ? 25 : range == 25 ? 30 : 15; yaw = scan ? p.yaw <= 0 ? range : -range : yaw; }
        }
        change(now, yaw, Math.min(maxPitch, pitch), scan, range, true, false); return true;
    }
    private float nextEnd(float yaw) { return yaw <= 0 ? state.range : -state.range; }
    private void change(long now, float yaw, float pitch, boolean scan, int range, boolean active, boolean blocked) {
        Pose from = state.pose(now);
        int duration = active ? (int) Math.ceil(Math.max(Math.abs(yaw - from.yaw) * 3, Math.abs(pitch - from.pitch) * 4)) : 0;
        state = new State(state.revision + 1, now, duration, from.yaw, from.pitch, yaw, pitch, scan, range, active, blocked);
    }
}
