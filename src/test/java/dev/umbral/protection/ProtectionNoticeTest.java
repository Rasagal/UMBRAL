// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProtectionNoticeTest {
    @Test void aBuildingSessionIsGroupedAfterTwoSecondsOfQuiet(){
        var notice=new ProtectionNotice();assertFalse(notice.ready(20,0));assertFalse(notice.ready(40,10));assertTrue(notice.ready(50,10));
    }
    @Test void idleConstructionNeverRepeatsEvenAfterTheCooldown(){
        var notice=new ProtectionNotice();notice.sent(40,0);assertFalse(notice.ready(640,0));assertFalse(notice.ready(6040,0));
    }
    @Test void newConstructionWaitsForBothQuietAndTheRateLimit(){
        var notice=new ProtectionNotice();notice.sent(40,0);assertFalse(notice.ready(100,60));assertFalse(notice.ready(640,620));assertTrue(notice.ready(660,620));notice.sent(660,620);assertFalse(notice.ready(1260,620));assertTrue(notice.ready(1260,1220));
    }
}
