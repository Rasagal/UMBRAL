// SPDX-License-Identifier: MPL-2.0

package dev.umbral.protection;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionShapeTest {
    private static Set<String> cells(ProtectionShape s){var out=new HashSet<String>();for(var b:s.boxes())for(int x=b.x0();x<b.x1();x++)for(int y=b.y0();y<b.y1();y++)for(int z=b.z0();z<b.z1();z++)out.add(x+","+y+","+z);return out;}
    @Test void randomUnionsAndCutsMatchAnIndependentBlockSet(){
        for(int seed=0;seed<40;seed++){
            var rng=new Random(seed);var shape=new ProtectionShape();var truth=new HashSet<String>();
            for(int step=0;step<10;step++){
                int x=rng.nextInt(6)-3,y=rng.nextInt(4)-2,z=rng.nextInt(6)-3;
                var part=new ProtectionShape(List.of(new ProtectionShape.Box(x,y,z,x+rng.nextInt(3)+1,y+rng.nextInt(3)+1,z+rng.nextInt(3)+1)));
                if(rng.nextBoolean()){shape=shape.add(part);truth.addAll(cells(part));}else{shape=shape.subtract(part);truth.removeAll(cells(part));}
                assertEquals(truth,cells(shape));assertEquals(truth.size(),shape.volume());
            }
        }
    }
    @Test void irregularContourHasItsCourtyardOutsideAndItsHeightExact(){
        var s=ProtectionShape.polygon(List.of(new ProtectionShape.Point(0,0),new ProtectionShape.Point(6,0),new ProtectionShape.Point(6,2),new ProtectionShape.Point(2,2),new ProtectionShape.Point(2,6),new ProtectionShape.Point(0,6)),-2,5);
        assertEquals(20*7,s.volume());assertTrue(s.contains(1,4,5));assertFalse(s.contains(4,0,4));assertFalse(s.contains(1,5,1));assertTrue(s.contains(5,-2,1));
    }
    @Test void invertedAndSelfCrossingContoursAreRejected(){assertThrows(IllegalArgumentException.class,()->ProtectionShape.polygon(List.of(new ProtectionShape.Point(0,0),new ProtectionShape.Point(4,4),new ProtectionShape.Point(0,4),new ProtectionShape.Point(4,0)),0,8));}
    @Test void overlapsAreNeverChargedTwiceAndCutRestoresExactCapacity(){
        var a=ProtectionShape.rectangle(0,0,0,9,15,9);var b=ProtectionShape.rectangle(5,0,0,14,15,9);assertEquals(600,a.add(b).cost());assertEquals(400,a.add(b).subtract(b).add(a).cost());assertEquals(0,a.subtract(a).cost());
    }
    @Test void horizontalLandCannotBeDiscountedWithPaperThinClaims(){
        var thin=ProtectionShape.rectangle(0,0,0,31,0,31);var house=ProtectionShape.rectangle(0,0,0,31,15,31);var tower=ProtectionShape.rectangle(0,0,0,31,31,31);
        assertEquals(4096,thin.cost());assertEquals(thin.cost(),house.cost());assertEquals(5120,tower.cost());
    }
    @Test void roomsMustBeSubsetsIncludingCourtyardAndVerticalGaps(){var building=ProtectionShape.rectangle(0,0,0,5,8,5).subtract(ProtectionShape.rectangle(2,0,2,3,8,3));assertTrue(building.contains(ProtectionShape.rectangle(0,0,0,1,8,5)));assertFalse(building.contains(ProtectionShape.rectangle(0,0,0,5,8,5)));}
    @Test void coordinateMagnitudeDoesNotAffectCapacityOrCausePerBlockAllocation(){var large=ProtectionShape.rectangle(-20000000,-64,20000000,-19999745,319,20000255);assertEquals(65536L*27,large.cost());assertEquals(1,large.boxes().size());}
    @Test void progressiveMatricesStayPositiveAndDoNotResetAtThresholds(){assertEquals(0,ProtectionEconomy.capacity(0));assertEquals(16384,ProtectionEconomy.capacity(8));assertEquals(17408,ProtectionEconomy.capacity(9));assertEquals(40960,ProtectionEconomy.capacity(32));assertEquals(41472,ProtectionEconomy.capacity(33));assertTrue(ProtectionEconomy.capacity(4096)>ProtectionEconomy.capacity(4095));}
}
