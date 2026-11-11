// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorMotionTest {
    @Test void openingAndClosingAreContinuousBoundedAndFinish() {
        var motion = new DoorMotion(); motion.request(true, 100); var open = motion.state();
        assertEquals(0, open.position(99)); assertEquals(1, open.position(112));
        float previous = 0;
        for (double time = 100; time <= 112; time += .25) {
            float p = open.position(time); assertTrue(p >= previous && p <= 1); previous = p;
        }
        motion.request(false, 112); assertEquals(1, motion.state().position(112)); assertEquals(0, motion.state().position(124));
    }
    @Test void reversingKeepsTheActualPositionAndShortensTheReturnTravel() {
        var motion = new DoorMotion(); motion.snap(true); motion.request(false, 100);
        float position = motion.state().position(106); assertEquals(.5f, position);
        motion.request(true, 106);
        assertEquals(position, motion.state().position(106)); assertEquals(6, motion.state().duration());
        assertEquals(1, motion.state().position(112));
    }
    @Test void savedTravelWaitsUntilTheServerResumesIt() {
        var motion = new DoorMotion(); motion.request(true, 50);
        var saved = motion.saved(56); var restored = new DoorMotion(); restored.load(saved);
        assertEquals(.5f, restored.state().position(10000)); assertTrue(restored.needsResume()); assertTrue(restored.savedGoal());
        restored.request(restored.savedGoal(), 10000);
        assertEquals(.5f, restored.state().position(10000)); assertEquals(1, restored.state().position(10006));
    }
    @Test void publicUpdatesRejectOldTimelinesButAllowReplacementIdentities() {
        var motion = new DoorMotion();
        motion.accept(new DoorMotion.State(10, 100, 12, 0, 1), false);
        motion.accept(new DoorMotion.State(9, 100, 0, 0, 0), false);
        assertEquals(10, motion.state().revision());
        motion.accept(new DoorMotion.State(0, 100, 0, 0, 0), true);
        assertEquals(0, motion.state().revision());
    }
}
