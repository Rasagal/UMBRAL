// SPDX-License-Identifier: MPL-2.0

package dev.umbral.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.umbral.network.NetworkTopology.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkTopologyTest {
    private final UUID network = UUID.randomUUID();
    private final GridPoint core = new GridPoint(0, 0, 0);
    private final Map<GridPoint, Cell> blocks = new HashMap<>();

    @BeforeEach
    void placeCore() { blocks.put(core, new Cell(Kind.CORE, network)); }

    private Snapshot scan() { return NetworkTopology.scan(network, core, p -> blocks.getOrDefault(p, Cell.EMPTY), MAX_CONDUCTORS); }
    private GridPoint p(int x, int y, int z) { return new GridPoint(x, y, z); }

    @Test
    void coreProvidesSixteenBlockSphericalCoverage() {
        Snapshot result = scan();
        assertEquals(Link.WIRELESS, result.connectionTo(p(16, 0, 0), false));
        assertEquals(Link.WIRELESS, result.connectionTo(p(0, -16, 0), false));
        assertEquals(Link.NO_SIGNAL, result.connectionTo(p(17, 0, 0), false));
        assertEquals(Link.NO_SIGNAL, result.connectionTo(p(12, 12, 0), false));
    }

    @Test
    void relayInRadioRangeStillRequiresACable() {
        blocks.put(p(8, 0, 0), new Cell(Kind.RELAY, network));
        assertEquals(Link.NO_SIGNAL, scan().connectionTo(p(8, 0, 0), true));
        assertEquals(Link.NO_SIGNAL, scan().connectionTo(p(24, 0, 0), false));
    }

    @Test
    void cableConnectedRelayExtendsCoverageAndCuttingItRemovesCoverage() {
        for (int x = 1; x < 24; x++) blocks.put(p(x, 0, 0), Cell.CABLE);
        blocks.put(p(24, 0, 0), new Cell(Kind.RELAY, network));
        assertEquals(Link.WIRED, scan().connectionTo(p(24, 0, 0), true));
        assertEquals(Link.WIRELESS, scan().connectionTo(p(40, 0, 0), false));
        blocks.remove(p(12, 0, 0));
        assertEquals(Link.NO_SIGNAL, scan().connectionTo(p(40, 0, 0), false));
        blocks.put(p(12, 0, 0), Cell.CABLE);
        assertEquals(Link.WIRELESS, scan().connectionTo(p(40, 0, 0), false));
    }

    @Test
    void relayFromAnotherNetworkDoesNotProvideCoverageOrABridge() {
        blocks.put(p(1, 0, 0), new Cell(Kind.RELAY, UUID.randomUUID()));
        for (int x = 2; x < 24; x++) blocks.put(p(x, 0, 0), Cell.CABLE);
        blocks.put(p(24, 0, 0), new Cell(Kind.RELAY, network));
        assertEquals(1, scan().emitters().size());
        assertEquals(Link.NO_SIGNAL, scan().connectionTo(p(40, 0, 0), false));
    }

    @Test
    void unboundRelayDoesNotJoinByProximityOrCable() {
        blocks.put(p(1, 0, 0), new Cell(Kind.RELAY, null));
        assertEquals(Link.NO_SIGNAL, scan().connectionTo(p(1, 0, 0), true));
    }

    @Test
    void aWiredTerminalIsAnEndpointNotARepeater() {
        for (int x = 1; x < 24; x++) blocks.put(p(x, 0, 0), Cell.CABLE);
        blocks.put(p(24, 0, 0), new Cell(Kind.TERMINAL, network));
        blocks.put(p(25, 0, 0), Cell.CABLE);
        blocks.put(p(26, 0, 0), new Cell(Kind.RELAY, network));
        Snapshot result = scan();
        assertEquals(Link.WIRED, result.connectionTo(p(24, 0, 0), false));
        assertEquals(Link.NO_SIGNAL, result.connectionTo(p(26, 0, 0), true));
        assertEquals(Link.NO_SIGNAL, result.connectionTo(p(40, 0, 0), false));
    }

    @Test
    void replacingCoreAtSameCoordinatesDoesNotInheritItsIdentity() {
        blocks.put(core, new Cell(Kind.CORE, UUID.randomUUID()));
        assertEquals(Link.CORE_MISSING, scan().connectionTo(p(1, 0, 0), false));
    }

    @Test
    void aWiredCameraCannotForwardDataOrExtendRadio() {
        for (int x = 1; x < 24; x++) blocks.put(p(x, 0, 0), Cell.CABLE);
        blocks.put(p(24, 0, 0), new Cell(Kind.CAMERA, network));
        blocks.put(p(25, 0, 0), Cell.CABLE);
        blocks.put(p(26, 0, 0), new Cell(Kind.RELAY, network));
        Snapshot result = scan();
        assertEquals(Link.WIRED, result.connectionTo(p(24, 0, 0), false));
        assertEquals(Link.NO_SIGNAL, result.connectionTo(p(26, 0, 0), true));
        assertEquals(1, result.emitters().size());
    }

    @Test
    void accessDevicesAcceptWireWithoutBridgingOrRepeating() {
        for (Kind kind : new Kind[]{Kind.DOOR, Kind.READER, Kind.TURRET}) {
            for (int x = 1; x < 24; x++) blocks.put(p(x, 0, 0), Cell.CABLE);
            blocks.put(p(24, 0, 0), new Cell(kind, network));
            blocks.put(p(25, 0, 0), Cell.CABLE);
            blocks.put(p(26, 0, 0), new Cell(Kind.RELAY, network));
            Snapshot result = scan();
            assertEquals(Link.WIRED, result.connectionTo(p(24, 0, 0), false));
            assertEquals(Link.NO_SIGNAL, result.connectionTo(p(26, 0, 0), true));
            assertEquals(1, result.emitters().size());
        }
    }

    @Test
    void unloadedCoreIsNotMistakenForADestroyedCore() {
        blocks.put(core, Cell.UNLOADED);
        assertEquals(Link.UNAVAILABLE, scan().connectionTo(p(1, 0, 0), false));
    }

    @Test
    void unloadedBranchDoesNotDisableKnownLocalCoverage() {
        blocks.put(p(1, 0, 0), Cell.UNLOADED);
        Snapshot result = scan();
        assertTrue(result.incomplete());
        assertEquals(Link.WIRELESS, result.connectionTo(p(-16, 0, 0), false));
        assertEquals(Link.UNAVAILABLE, result.connectionTo(p(40, 0, 0), false));
    }

    @Test
    void loopsAndRedundantRoutesAreHandledWithoutDuplicateEmitters() {
        blocks.put(p(1, 0, 0), Cell.CABLE);
        blocks.put(p(0, 1, 0), Cell.CABLE);
        blocks.put(p(1, 1, 0), new Cell(Kind.RELAY, network));
        assertEquals(4, scan().conductors());
        assertEquals(2, scan().emitters().size());
        blocks.remove(p(1, 0, 0));
        assertEquals(Link.WIRED, scan().connectionTo(p(1, 1, 0), true));
    }

    @Test
    void scanLimitFailsClosedInsteadOfReturningPartialCoverage() {
        blocks.put(p(1, 0, 0), Cell.CABLE);
        Snapshot result = NetworkTopology.scan(network, core, p -> blocks.getOrDefault(p, Cell.EMPTY), 1);
        assertEquals(ScanState.LIMIT_REACHED, result.state());
        assertEquals(Link.LIMIT_REACHED, result.connectionTo(core, false));
        assertTrue(result.emitters().isEmpty());
    }

    @Test
    void negativeCoordinatesAndWorldBorderDistancesDoNotOverflow() {
        assertEquals(3_600_000_000_000_000L, p(-30_000_000, 0, 0).distanceSquared(p(30_000_000, 0, 0)));
        assertEquals(Link.WIRELESS, scan().connectionTo(p(-16, 0, 0), false));
    }
}
