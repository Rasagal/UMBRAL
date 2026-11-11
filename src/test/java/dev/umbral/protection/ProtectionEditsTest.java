// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProtectionEditsTest {
    @Test void twoGroundCornersIncludeFoundationWallsAndRoofInsteadOfOneLayer(){
        var area=ProtectionEdits.footprint(2,64,3,8,64,9,-64,319);var b=area.bounds();
        assertEquals(16,b.y1()-b.y0());assertEquals(62,b.y0());assertEquals(78,b.y1());
        assertTrue(area.contains(5,64,6));assertTrue(area.contains(5,76,6));assertFalse(area.contains(5,78,6));
    }
    @Test void cornersAtDifferentLevelsRetainTheHigherCornerAndMargin(){
        var b=ProtectionEdits.footprint(0,40,0,4,61,4,-64,319).bounds();assertEquals(38,b.y0());assertEquals(75,b.y1());
    }
    @Test void suggestedHeightClampsAtBothWorldBoundaries(){
        assertEquals(-64,ProtectionEdits.footprint(0,-64,0,2,-64,2,-64,319).bounds().y0());
        assertEquals(320,ProtectionEdits.footprint(0,318,0,2,319,2,-64,319).bounds().y1());
    }
    @Test void adjustingAnExtensionNeverStretchesOrRemovesSavedIrregularFloors(){
        var saved=ProtectionShape.rectangle(0,0,0,3,7,3).add(ProtectionShape.rectangle(1,12,1,2,19,2));
        var draft=saved.add(ProtectionShape.rectangle(4,0,1,6,7,3));
        var edited=ProtectionEdits.reheightAdded(saved,draft,2,12);
        assertTrue(edited.contains(saved));assertFalse(edited.contains(5,1,2));assertTrue(edited.contains(5,11,2));assertFalse(edited.contains(5,12,2));
        assertTrue(saved.subtract(edited).empty());assertEquals(saved.volume()+3*3*10,edited.volume());
    }
    @Test void aThinNewPartRequiresWarningEvenWhenTheSavedBuildingIsTall(){
        var saved=ProtectionShape.rectangle(0,0,0,3,15,3);
        assertTrue(ProtectionEdits.thinAddition(saved,saved.add(ProtectionShape.rectangle(4,0,0,5,0,3))));
        assertFalse(ProtectionEdits.thinAddition(saved,saved));
        assertFalse(ProtectionEdits.thinAddition(saved,saved.add(ProtectionShape.rectangle(4,0,0,5,15,3))));
    }
    @Test void inverseCapacityQuotesTheMinimumAtEveryEconomyTransition(){
        assertEquals(0,ProtectionEconomy.additionalMatrices(1,2048));assertEquals(1,ProtectionEconomy.additionalMatrices(1,2049));
        assertEquals(1,ProtectionEconomy.additionalMatrices(8,17408));assertEquals(2,ProtectionEconomy.additionalMatrices(8,17409));
        assertEquals(1,ProtectionEconomy.additionalMatrices(32,41472));assertEquals(2,ProtectionEconomy.additionalMatrices(32,41473));
        for(int installed:new int[]{0,1,7,8,31,32,64,4095})for(int next: new int[]{0,1,2,9}){
            int target=Math.min(4096,installed+next);long required=ProtectionEconomy.capacity(target);int quote=ProtectionEconomy.additionalMatrices(installed,required);
            assertTrue(quote>=0);assertTrue(ProtectionEconomy.capacity(installed+quote)>=required);
            if(quote>0)assertTrue(ProtectionEconomy.capacity(installed+quote-1)<required);
        }
        assertEquals(-1,ProtectionEconomy.additionalMatrices(4095,ProtectionEconomy.capacity(4096)+1));
    }
}
