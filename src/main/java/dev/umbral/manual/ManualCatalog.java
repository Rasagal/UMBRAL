// SPDX-License-Identifier: MPL-2.0

package dev.umbral.manual;

import java.text.Normalizer;
import java.util.*;

/** One public table of contents for the portable manual, recipes and contextual help. */
public final class ManualCatalog {
    public enum Group { GOALS, EQUIPMENT, REFERENCE }
    public enum Figure { START, CLAIM, EXPAND, NETWORK, CAMERA, ACCESS, INSPECTION, PERIMETER, AIRLOCK, DEFENSE, LOGISTICS, DEVICE, DIAGNOSTICS }
    public enum Entry {
        START(Group.GOALS,Figure.START,"field_manual"),
        FIRST_BASE(Group.GOALS,Figure.CLAIM,"protection_matrix"),
        EXPANSION(Group.GOALS,Figure.EXPAND,"protection_matrix"),
        INSTALLATION(Group.GOALS,Figure.NETWORK,"network_core"),
        SURVEILLANCE(Group.GOALS,Figure.CAMERA,"security_camera"),
        ENTRANCE(Group.GOALS,Figure.ACCESS,"card_reader"),
        INSPECTION(Group.GOALS,Figure.INSPECTION,"inventory_scanner"),
        PERIMETER(Group.GOALS,Figure.PERIMETER,"electric_fence"),
        AIRLOCK(Group.GOALS,Figure.AIRLOCK,"inventory_scanner"),
        DEFENDING(Group.GOALS,Figure.DEFENSE,"baluarte_turret"),
        REPLENISHING(Group.GOALS,Figure.LOGISTICS,"mota"),
        CORE(Group.EQUIPMENT,Figure.NETWORK,"network_core"),
        CABLE(Group.EQUIPMENT,Figure.NETWORK,"copper_cable"),
        NODE(Group.EQUIPMENT,Figure.NETWORK,"sector_node"),
        TERMINAL(Group.EQUIPMENT,Figure.DEVICE,"terminal"),
        VIGIA(Group.EQUIPMENT,Figure.CAMERA,"security_camera"),
        CONFIGURATOR(Group.EQUIPMENT,Figure.DEVICE,"configurator"),
        DOOR(Group.EQUIPMENT,Figure.ACCESS,"security_door"),
        READER(Group.EQUIPMENT,Figure.ACCESS,"card_reader"),
        FILTER(Group.EQUIPMENT,Figure.INSPECTION,"inventory_scanner"),
        CARD(Group.EQUIPMENT,Figure.ACCESS,"access_card"),
        BALUARTE(Group.EQUIPMENT,Figure.DEFENSE,"baluarte_turret"),
        CENTELLA(Group.EQUIPMENT,Figure.DEFENSE,"centella_turret"),
        FENCE(Group.EQUIPMENT,Figure.PERIMETER,"electric_fence"),
        SENSOR(Group.EQUIPMENT,Figure.DEVICE,"presence_sensor"),
        MINE(Group.EQUIPMENT,Figure.DEVICE,"pulse_mine"),
        NEXO(Group.EQUIPMENT,Figure.DEVICE,"nexo_terminal"),
        DOCK(Group.EQUIPMENT,Figure.LOGISTICS,"mota_dock"),
        MOTA(Group.EQUIPMENT,Figure.LOGISTICS,"mota"),
        MATRIX(Group.EQUIPMENT,Figure.CLAIM,"protection_matrix"),
        BOOK(Group.EQUIPMENT,Figure.DEVICE,"field_manual"),
        ROOMS(Group.REFERENCE,Figure.CLAIM,"protection_matrix"),
        DIAGNOSTICS(Group.REFERENCE,Figure.DIAGNOSTICS,"configurator"),
        RECOVERY(Group.REFERENCE,Figure.DEVICE,"field_manual");

        public final Group group;public final Figure figure;public final String recipe;
        Entry(Group group,Figure figure,String recipe){this.group=group;this.figure=figure;this.recipe=recipe;}
        public String id(){return name().toLowerCase(Locale.ROOT);}
        public String key(String part){return "manual.umbral."+id()+"."+part;}
    }
    public static Entry device(String id){return Arrays.stream(Entry.values()).filter(e->e.group==Group.EQUIPMENT&&e.recipe.equals(id)).findFirst().orElse(Entry.START);}
    public static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).strip();}
    public static boolean matches(String query,String text){String haystack=normalize(text);return Arrays.stream(normalize(query).split("\\s+")).allMatch(haystack::contains);}
    public static List<Entry> related(Entry entry){return switch(entry.figure){
        case START->List.of(Entry.FIRST_BASE,Entry.INSTALLATION,Entry.REPLENISHING);
        case CLAIM,EXPAND->List.of(Entry.FIRST_BASE,Entry.EXPANSION,Entry.MATRIX,Entry.ROOMS);
        case NETWORK->List.of(Entry.CORE,Entry.CABLE,Entry.NODE,Entry.TERMINAL);
        case CAMERA->List.of(Entry.VIGIA,Entry.INSTALLATION,Entry.TERMINAL);
        case ACCESS->List.of(Entry.DOOR,Entry.READER,Entry.CARD);
        case INSPECTION->List.of(Entry.FILTER,Entry.READER,Entry.CARD,Entry.NEXO);
        case PERIMETER->List.of(Entry.FENCE,Entry.NEXO,Entry.CARD);
        case AIRLOCK->List.of(Entry.FILTER,Entry.DOOR,Entry.READER,Entry.NEXO);
        case DEFENSE->List.of(Entry.BALUARTE,Entry.CENTELLA,Entry.REPLENISHING);
        case LOGISTICS->List.of(Entry.MOTA,Entry.DOCK,Entry.NEXO);
        case DIAGNOSTICS->List.of(Entry.INSTALLATION,Entry.VIGIA,Entry.REPLENISHING);
        default->List.of(Entry.START,Entry.DIAGNOSTICS,Entry.RECOVERY);
    };}
    private ManualCatalog(){}
}
