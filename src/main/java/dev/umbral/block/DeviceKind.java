// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

public enum DeviceKind {
    FILTER("inventory_scanner"), CORE("network_core"), RELAY("sector_node"), TERMINAL("terminal"), CAMERA("security_camera"),
    DOOR("security_door"), READER("card_reader"), TURRET("baluarte_turret"), CENTELLA("centella_turret"), SENSOR("presence_sensor"), PULSE_MINE("pulse_mine"), NEXO("nexo_terminal"), DOCK("mota_dock"), FENCE("electric_fence");

    public boolean turret() { return this == TURRET || this == CENTELLA; }

    public final String id;

    DeviceKind(String id) { this.id = id; }
}
