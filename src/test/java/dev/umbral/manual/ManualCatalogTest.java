// SPDX-License-Identifier: MPL-2.0

package dev.umbral.manual;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.umbral.manual.ManualCatalog.*;

final class ManualCatalogTest {
    @Test void searchAcceptsSpanishAccentsAndAnyWordOrder(){
        assertTrue(matches("CAMARA techo","VIGÍA · Cámara en techo"));assertTrue(matches("sin REDIBUJAR ampliación","Ampliación sin redibujar"));assertFalse(matches("cámara techo","Cámara en pared"));
    }
    @Test void everyCraftableDeviceHasExactlyOneEquipmentChapter(){
        var equipment=Arrays.stream(Entry.values()).filter(e->e.group==Group.EQUIPMENT).toList();assertEquals(20,equipment.size());assertEquals(equipment.size(),equipment.stream().map(e->e.recipe).distinct().count());
        for(var e:equipment)assertSame(e,device(e.recipe));assertSame(Entry.START,device("another_mod:unknown"));
    }
    @Test void publicRecipeLookupIsRestrictedToTheCraftableCatalogue(){
        var recipes=Arrays.stream(Entry.values()).filter(e->e.group==Group.EQUIPMENT).map(e->e.recipe).toList();for(var e:Entry.values())assertTrue(recipes.contains(e.recipe));
    }
    @Test void relatedChaptersAlwaysLeadToRealContent(){
        for(var e:Entry.values()){assertFalse(related(e).isEmpty());assertEquals(related(e).size(),new HashSet<>(related(e)).size());for(var next:related(e))assertNotNull(next);}
    }
}
