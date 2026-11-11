// SPDX-License-Identifier: MPL-2.0

package dev.umbral.filter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InspectionTest {
    final UUID visitor=UUID.randomUUID();
    Inspection.Result inspect(Map<String,Long> items,FilterRule...rules){return Inspection.evaluate(visitor,items,List.of(rules),(tag,item)->tag.equals("minecraft:swords")&&item.endsWith("_sword"));}
    @Test void cleanAndEmptyRulesAreAccepted(){assertTrue(inspect(Map.of("minecraft:stone",64L),new FilterRule("minecraft:tnt",1)).accepted());assertTrue(inspect(Map.of("minecraft:tnt",64L)).accepted());}
    @Test void exactThresholdUsesTotalCount(){assertTrue(inspect(Map.of("minecraft:tnt",3L),new FilterRule("minecraft:tnt",4)).accepted());assertFalse(inspect(Map.of("minecraft:tnt",4L),new FilterRule("minecraft:tnt",4)).accepted());}
    @Test void tagsAggregateDistinctItems(){var r=inspect(Map.of("minecraft:iron_sword",1L,"minecraft:diamond_sword",2L),new FilterRule("#minecraft:swords",3));assertFalse(r.accepted());assertEquals(2,r.matches().size());}
    @Test void overlappingRulesDoNotDoubleCount(){var r=inspect(Map.of("minecraft:iron_sword",3L),new FilterRule("minecraft:iron_sword",1),new FilterRule("#minecraft:swords",1));assertEquals(List.of(new Inspection.Match("minecraft:iron_sword",3)),r.matches());}
    @Test void exceptionsApplyToOnlyOneRule(){var e=new FilterRule.Exemption(visitor,"Alex");var r=inspect(Map.of("minecraft:tnt",2L,"minecraft:lava_bucket",1L),new FilterRule("minecraft:tnt",1,List.of(e)),new FilterRule("minecraft:lava_bucket",1));assertEquals(List.of(new Inspection.Match("minecraft:lava_bucket",1)),r.matches());}
    @Test void exceptionsUseUUIDNotName(){var rule=new FilterRule("minecraft:tnt",1,List.of(new FilterRule.Exemption(UUID.randomUUID(),"SameName")));assertFalse(inspect(Map.of("minecraft:tnt",1L),rule).accepted());}
    @Test void matchesAreDeterministicAndImmutable(){var r=inspect(Map.of("minecraft:iron_sword",1L,"minecraft:diamond_sword",1L),new FilterRule("#minecraft:swords",1));assertEquals("minecraft:diamond_sword",r.matches().getFirst().item());assertThrows(UnsupportedOperationException.class,()->r.matches().clear());}
    @Test void selectorsAndThresholdsAreValidated(){for(String s:List.of("tnt","Minecraft:tnt","minecraft:tnt;op","#","minecraft:"))assertFalse(FilterRule.validSelector(s));assertTrue(FilterRule.validSelector("#minecraft:swords"));assertThrows(IllegalArgumentException.class,()->new FilterRule("minecraft:tnt",0));assertThrows(IllegalArgumentException.class,()->new FilterRule("minecraft:tnt",4097));}
    @Test void duplicateExceptionsAreRejected(){var e=new FilterRule.Exemption(visitor,"Alex");assertThrows(IllegalArgumentException.class,()->new FilterRule("minecraft:tnt",1,List.of(e,e)));}
    @Test void resultLimitDoesNotChangeDenial(){var items=new HashMap<String,Long>();for(int n=0;n<40;n++)items.put("minecraft:test_"+n,1L);var result=Inspection.evaluate(visitor,items,List.of(new FilterRule("#minecraft:all",1)),(t,i)->true);assertFalse(result.accepted());assertEquals(16,result.matches().size());}
}
