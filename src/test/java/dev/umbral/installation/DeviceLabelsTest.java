// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceLabelsTest {
    @Test void namesKeepTheirAccentsAndNormalizeSpacing() {
        assertEquals("Cámara del jardín",DeviceLabels.clean("  Ca\u0301mara   del jardín  "));
        assertEquals("Patio 🛡",DeviceLabels.clean("Patio 🛡"));
    }
    @Test void namesRejectFormattingControlsAndInvisibleDirections() {
        for(String value:new String[]{"Patio\nNorte","Patio\tNorte","§aPatio","Patio\u202eNorte","Patio\u200bNorte","Patio\ud800"})
            assertEquals("",DeviceLabels.clean(value),value);
        assertEquals("",DeviceLabels.clean(null));
        assertEquals("",DeviceLabels.clean("   "));
    }
    @Test void namesRespectTheSameBoundAsThePacket() {
        assertEquals("a".repeat(48),DeviceLabels.clean("a".repeat(48)));
        assertEquals("",DeviceLabels.clean("a".repeat(49)));
    }
    @Test void searchAcceptsAccentsCaseAndCoordinatesWithoutChangingNames() {
        assertEquals("camara del jardin",DeviceLabels.searchKey(" CÁMARA   del JARDÍN "));
        assertEquals("-12, 64, 8",DeviceLabels.searchKey("-12, 64, 8"));
        assertEquals("",DeviceLabels.searchKey(null));
    }
}
