// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The two leaves share one server timeline, including when a closing door reverses. */
public final class DoorMotion {
    public static final int TRAVEL_TICKS = 12;
    public record State(long revision, long started, int duration, float from, float target) {
        public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("revision").forGetter(State::revision), Codec.LONG.fieldOf("started").forGetter(State::started),
                Codec.intRange(0, TRAVEL_TICKS).fieldOf("duration").forGetter(State::duration),
                Codec.floatRange(0, 1).fieldOf("from").forGetter(State::from), Codec.floatRange(0, 1).fieldOf("target").forGetter(State::target)
        ).apply(i, State::new));
        public float position(double time) {
            double t = duration == 0 ? 1 : Math.max(0, Math.min(1, (time - started) / duration));
            float smooth = (float) (t * t * (3 - 2 * t));
            return from + (target - from) * smooth;
        }
        public boolean moving(double time) { return duration > 0 && time < started + duration; }
        public int remaining(long time) { return (int) Math.max(0, started + duration - time); }
    }
    public record Saved(long revision, float position, boolean opening) {
        public static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("revision").forGetter(Saved::revision), Codec.floatRange(0, 1).fieldOf("position").forGetter(Saved::position),
                Codec.BOOL.fieldOf("opening").forGetter(Saved::opening)
        ).apply(i, Saved::new));
    }
    private State state = new State(0, 0, 0, 0, 0);
    private boolean resume, savedGoal;
    public State state() { return state; }
    public void snap(boolean open) { float p = open ? 1 : 0; state = new State(0, 0, 0, p, p); resume = false; }
    public boolean request(boolean open, long now) {
        float target = open ? 1 : 0;
        if (!resume && state.target == target) return false;
        float from = state.position(now);
        state = new State(state.revision + 1, now, (int) Math.ceil(TRAVEL_TICKS * Math.abs(target - from)), from, target);
        resume = false; return true;
    }
    public Saved saved(long now) { return new Saved(state.revision, state.position(now), resume ? savedGoal : state.target == 1); }
    public void load(Saved saved) {
        state = new State(saved.revision, 0, 0, saved.position, saved.position);
        savedGoal = saved.opening; resume = true;
    }
    public void accept(State update, boolean replaced) { if (replaced || update.revision >= state.revision) { state = update; resume = false; } }
    public boolean needsResume() { return resume; }
    public boolean savedGoal() { return savedGoal; }
}
