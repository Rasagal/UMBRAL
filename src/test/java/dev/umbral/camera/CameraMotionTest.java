// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraMotionTest {
    @Test void servoEasesContinuouslyAndHonorsItsSpeedLimit() {
        var motion = new CameraMotion(); motion.powered(true, 100);
        motion.command(CameraMotion.Command.RIGHT, 100, 20);
        var s = motion.state();
        assertEquals(0, s.pose(99).yaw()); assertEquals(5, s.pose(100 + s.duration()).yaw());
        float prior = 0;
        for (double t = 100; t < 100 + s.duration() + .1; t += .25) {
            float yaw = s.pose(t).yaw(); assertTrue(yaw >= prior - .00001); assertTrue(yaw - prior <= .126); prior = yaw;
        }
    }
    @Test void scanPausesAtTheEndAndThenReverses() {
        var motion = new CameraMotion(); motion.initialize(0); motion.powered(true, 0);
        var first = motion.state(); assertTrue(first.scan()); assertEquals(25, first.yaw());
        long end = first.started() + first.duration();
        assertFalse(motion.advance(end + 39)); assertEquals(25, motion.state().pose(end + 39).yaw());
        assertTrue(motion.advance(end + 40)); assertEquals(-25, motion.state().yaw());
        assertEquals(25, motion.state().pose(end + 40).yaw());
    }
    @Test void offlineAndSavedCamerasHoldTheirPoseWithoutCatchingUp() {
        var motion = new CameraMotion(); motion.initialize(0); motion.powered(true, 0);
        var atCut = motion.state().pose(25); motion.powered(false, 25);
        assertEquals(atCut, motion.state().pose(10_000)); assertTrue(motion.state().scan());
        var restored = new CameraMotion(); restored.restore(motion.saved(10_000));
        assertEquals(atCut, restored.state().pose(20_000)); assertFalse(restored.state().active());
        restored.powered(true, 20_000); assertEquals(atCut, restored.state().pose(20_000));
    }
    @Test void manualCommandsRespectStopsAndCeilingTilt() {
        var motion = new CameraMotion(); motion.initialize(0); motion.powered(true, 0);
        long now = 0;
        for (int n = 0; n < 40; n++) { now += 200; motion.command(CameraMotion.Command.RIGHT, now, 8); motion.command(CameraMotion.Command.DOWN, now, 8); }
        assertFalse(motion.state().scan()); assertEquals(30, motion.state().yaw()); assertEquals(8, motion.state().pitch());
        for (int n = 0; n < 40; n++) { now += 200; motion.command(CameraMotion.Command.LEFT, now, 8); motion.command(CameraMotion.Command.UP, now, 8); }
        assertEquals(-30, motion.state().yaw()); assertEquals(-8, motion.state().pitch());
    }
}
