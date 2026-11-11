// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.UUID;

public final class NetworkGameTests {
    private static final String STRUCTURE = "umbral-test:network";
    private static final BlockPos CORE = new BlockPos(1, 1, 1);

    private DeviceBlockEntity place(GameTestHelper test, BlockPos pos, Block block, UUID owner) {
        test.setBlock(pos, block);
        DeviceBlockEntity device = test.getBlockEntity(pos, DeviceBlockEntity.class);
        device.initializeOwner(owner, "Banco de pruebas");
        return device;
    }

    private NetworkService.Status status(GameTestHelper test, DeviceBlockEntity device) {
        return NetworkService.inspect(test.getLevel(), device).status();
    }

    @GameTest(structure = STRUCTURE)
    public void localCoverageAndPairing(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var terminal = place(test, new BlockPos(17, 1, 1), UmbralContent.TERMINAL, owner);
        test.assertTrue(status(test, terminal) == NetworkService.Status.UNBOUND, "Coverage must not pair a terminal automatically");
        test.assertTrue(terminal.bind(owner, core), "Owner should be able to pair");
        test.assertTrue(status(test, terminal) == NetworkService.Status.WIRELESS, "Terminal at radius 16 must connect");
        var outside = place(test, new BlockPos(18, 1, 1), UmbralContent.TERMINAL, owner);
        outside.bind(owner, core);
        test.assertTrue(status(test, outside) == NetworkService.Status.NO_SIGNAL, "Terminal at radius 17 must stay offline");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void relayBackboneDisconnectAndReconnect(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var node = place(test, new BlockPos(25, 1, 1), UmbralContent.NODE, owner);
        var terminal = place(test, new BlockPos(41, 1, 1), UmbralContent.TERMINAL, owner);
        node.bind(owner, core);
        terminal.bind(owner, core);
        for (int x = 2; x < 25; x++) test.setBlock(x, 1, 1, UmbralContent.CABLE);
        test.assertTrue(status(test, node) == NetworkService.Status.WIRED, "Node must join the cable backbone");
        test.assertTrue(status(test, terminal) == NetworkService.Status.WIRELESS, "Wired node must extend radio coverage");
        test.setBlock(12, 1, 1, Blocks.AIR);
        test.assertTrue(status(test, terminal) == NetworkService.Status.NO_SIGNAL, "Cutting cable must invalidate cached coverage");
        test.setBlock(12, 1, 1, UmbralContent.CABLE);
        test.assertTrue(status(test, terminal) == NetworkService.Status.WIRELESS, "Restoring cable must restore coverage");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void nearbyRelayCannotRepeatWithoutCable(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var node = place(test, new BlockPos(9, 1, 1), UmbralContent.NODE, owner);
        var terminal = place(test, new BlockPos(25, 1, 1), UmbralContent.TERMINAL, owner);
        node.bind(owner, core);
        terminal.bind(owner, core);
        test.assertTrue(status(test, node) == NetworkService.Status.NO_SIGNAL, "Relays require cable even inside radio coverage");
        test.assertTrue(status(test, terminal) == NetworkService.Status.NO_SIGNAL, "An offline relay must not extend coverage");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void anotherPlayerCannotPairOrBreakOwnedDevices(GameTestHelper test) {
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var terminal = place(test, new BlockPos(3, 1, 1), UmbralContent.TERMINAL, owner);
        test.assertFalse(terminal.bind(player.getUUID(), core), "Foreign player must not pair an owned terminal");
        var foreignTerminal = place(test, new BlockPos(5, 1, 1), UmbralContent.TERMINAL, player.getUUID());
        test.assertFalse(foreignTerminal.bind(player.getUUID(), core), "Owning a terminal does not grant access to another core");
        boolean allowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(test.getLevel(), player,
                core.getBlockPos(), core.getBlockState(), core);
        test.assertFalse(allowed, "Breaking another player's device must be denied");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void removedCoreIdentityCannotBeReusedByReplacement(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var terminal = place(test, new BlockPos(3, 1, 1), UmbralContent.TERMINAL, owner);
        terminal.bind(owner, core);
        var oldIdentity = core.link();
        test.setBlock(CORE, Blocks.AIR);
        var replacement = place(test, CORE, UmbralContent.CORE, owner);
        test.assertFalse(oldIdentity.equals(replacement.link()), "Replacement core must have a fresh identity");
        test.assertTrue(status(test, terminal) == NetworkService.Status.CORE_MISSING, "Old pairing must not connect to a replacement core");
        test.assertTrue(terminal.bind(owner, replacement), "Owner may explicitly pair to the replacement");
        test.assertTrue(status(test, terminal) == NetworkService.Status.WIRELESS, "Explicit new pairing must work");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void ownershipAndPairingSurviveSerialization(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var terminal = place(test, new BlockPos(3, 1, 1), UmbralContent.TERMINAL, owner);
        terminal.bind(owner, core);
        var saved = terminal.saveWithoutMetadata(test.getLevel().registryAccess());
        var restored = new DeviceBlockEntity(terminal.getBlockPos(), terminal.getBlockState());
        restored.setLevel(test.getLevel());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
        test.assertTrue(owner.equals(restored.owner()), "Owner UUID must persist");
        test.assertTrue(terminal.link().equals(restored.link()), "Core identity and dimension must persist");
        test.assertTrue(terminal.label().equals(restored.label()), "Custom label must persist");
        test.assertTrue(status(test, restored) == NetworkService.Status.WIRELESS, "Restored terminal must resolve its original network");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void unknownSavedSchemaFailsClosedAndPreservesOwnership(GameTestHelper test) {
        UUID owner = UUID.randomUUID();
        var core = place(test, CORE, UmbralContent.CORE, owner);
        var saved = core.saveWithoutMetadata(test.getLevel().registryAccess());
        saved.putInt("umbral_schema", 2);
        var restored = new DeviceBlockEntity(core.getBlockPos(), core.getBlockState());
        restored.setLevel(test.getLevel());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
        test.assertTrue(owner.equals(restored.owner()), "Unknown schema must not make an owned core claimable");
        test.assertTrue(restored.link() == null, "Unknown network schema must not activate a network");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void telemetryRequiresOwnerAndInteractionRange(GameTestHelper test) {
        var owner = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        var foreign = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        var core = place(test, CORE, UmbralContent.CORE, owner.getUUID());
        var center = net.minecraft.world.phys.Vec3.atCenterOf(core.getBlockPos());
        owner.setPos(center);
        foreign.setPos(center);
        test.assertTrue(NetworkProtocol.mayInspect(owner, core), "Nearby owner must be allowed to inspect");
        test.assertFalse(NetworkProtocol.mayInspect(foreign, core), "Foreign requests must not disclose network telemetry");
        owner.setPos(center.add(9, 0, 0));
        test.assertFalse(NetworkProtocol.mayInspect(owner, core), "Even an owner must be in interaction range");
        test.succeed();
    }
}
