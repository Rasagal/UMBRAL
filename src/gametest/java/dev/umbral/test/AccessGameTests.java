// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.block.*;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class AccessGameTests {
    private static final String STRUCTURE = "umbral-test:network";
    private static final BlockPos CORE = new BlockPos(1, 1, 1), DOOR = new BlockPos(5, 1, 3), READER = new BlockPos(7, 1, 3);
    private record Installation(ServerPlayer owner, ServerPlayer guest, DeviceBlockEntity core, DeviceBlockEntity door, DeviceBlockEntity reader) { }
    private DeviceBlockEntity place(GameTestHelper test, BlockPos pos, Block block, UUID owner) {
        test.setBlock(pos, block);
        var device = test.getBlockEntity(pos, DeviceBlockEntity.class); device.initializeOwner(owner, "Acceso norte");
        if (block == UmbralContent.DOOR) test.setBlock(pos.above(), block.defaultBlockState().setValue(SecurityDoorBlock.HALF, DoubleBlockHalf.UPPER));
        return device;
    }
    private Installation installation(GameTestHelper test) {
        return installation(test, GameType.SURVIVAL);
    }
    private Installation installation(GameTestHelper test, GameType mode) {
        var owner = (ServerPlayer) test.makeMockServerPlayer(mode);
        var guest = (ServerPlayer) test.makeMockServerPlayer(GameType.SURVIVAL);
        var core = place(test, CORE, UmbralContent.CORE, owner.getUUID());
        var door = place(test, DOOR, UmbralContent.DOOR, owner.getUUID());
        var reader = place(test, READER, UmbralContent.READER, owner.getUUID());
        door.bind(owner.getUUID(), core); reader.bind(owner.getUUID(), core); reader.targetDoor(owner.getUUID(), door);
        owner.setPos(Vec3.atCenterOf(reader.getBlockPos()).add(0, 0, -2)); guest.setPos(owner.position());
        return new Installation(owner, guest, core, door, reader);
    }
    private ItemStack issue(GameTestHelper test, Installation i) {
        i.owner.getInventory().setItem(0, new ItemStack(UmbralContent.ACCESS_CARD, 2));
        test.assertTrue(AccessService.issue(i.owner, i.reader) == AccessService.Result.ISSUED, "Owner with blank card must issue");
        for (int n = 0; n < i.owner.getInventory().getContainerSize(); n++) {
            ItemStack item = i.owner.getInventory().getItem(n);
            if (item.has(UmbralContent.CARD_ID)) return item.copy();
        }
        throw new AssertionError("Issuing must produce a physical card");
    }
    private AccessProtocol.Request request(Installation i, AccessProtocol.Operation op, UUID card) {
        return new AccessProtocol.Request(i.reader.getBlockPos(), i.reader.identity(), i.reader.link(), 73, op, card);
    }
    @GameTest(structure = STRUCTURE)
    public void physicalBearerPassAndRevocation(GameTestHelper test) {
        var i = installation(test); ItemStack card = issue(test, i);
        test.assertTrue(i.owner.getInventory().getItem(0).getCount() == 1, "Issuing must consume exactly one blank");
        test.assertTrue(card.getMaxStackSize() == 1, "Issued cards must not stack");
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.GRANTED, "A visitor carrying the pass must enter");
        test.assertTrue(i.door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Card opens the lower half");
        test.assertTrue(test.getBlockState(DOOR.above()).getValue(SecurityDoorBlock.OPEN), "Upper half must follow");
        test.assertTrue(AccessService.revoke(i.guest, i.reader, card.get(UmbralContent.CARD_ID)) == AccessService.Result.INVALID, "Visitors cannot revoke");
        test.assertTrue(AccessService.revoke(i.owner, i.reader, card.get(UmbralContent.CARD_ID)) == AccessService.Result.REVOKED, "Owner revokes immediately");
        test.assertTrue(AccessService.present(i.guest, i.reader, card.copy()) == AccessService.Result.DENIED, "All copies of a revoked ID must fail");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void blankForgedAndOtherNetworkCardsFail(GameTestHelper test) {
        var i = installation(test);
        ItemStack card = new ItemStack(UmbralContent.ACCESS_CARD);
        test.assertTrue(AccessService.present(i.owner, i.reader, card) == AccessService.Result.DENIED, "Owner cannot use an unissued card");
        card.set(UmbralContent.CARD_ID, UUID.randomUUID());
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.DENIED, "Random token is not a grant");
        var other = place(test, new BlockPos(12, 1, 1), UmbralContent.CORE, i.owner.getUUID());
        var grant = AccessRegistry.get(test.getLevel()).issue(i.owner.getUUID(), other.link(), "Otra instalación");
        card.set(UmbralContent.CARD_ID, grant.id());
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.DENIED, "An otherwise valid pass cannot cross installations");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void lostNetworkFailsClosedButExitAndOwnerStillWork(GameTestHelper test) {
        var i = installation(test); var card = issue(test, i);
        test.setBlock(CORE, Blocks.AIR);
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.OFFLINE, "No admission without network");
        test.assertTrue(AccessService.mayOpenManually(i.owner, i.door), "Owner override works offline");
        i.guest.setPos(Vec3.atCenterOf(i.door.getBlockPos()).add(0, 0, -1));
        test.assertFalse(AccessService.mayOpenManually(i.guest, i.door), "Exterior stranger cannot hand-open");
        i.guest.setPos(Vec3.atCenterOf(i.door.getBlockPos()).add(0, 0, 1));
        test.assertTrue(AccessService.mayOpenManually(i.guest, i.door), "Interior exit works offline");
        test.assertTrue(AccessService.revoke(i.owner, i.reader, card.get(UmbralContent.CARD_ID)) == AccessService.Result.REVOKED, "Owner can revoke during an outage");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void manualExitUsesPlayerSideForEveryFacing(GameTestHelper test) {
        var i = installation(test);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            test.setBlock(DOOR, i.door.getBlockState().setValue(DeviceBlock.FACING, facing));
            Vec3 center = Vec3.atCenterOf(i.door.getBlockPos());
            i.guest.setPos(center.add(facing.getStepX(), 0, facing.getStepZ()));
            test.assertFalse(AccessService.mayOpenManually(i.guest, i.door), "Facing side is exterior: " + facing);
            i.guest.setPos(center.add(-facing.getStepX(), 0, -facing.getStepZ()));
            test.assertTrue(AccessService.mayOpenManually(i.guest, i.door), "Opposite side is interior: " + facing);
            var closed = SecurityDoorBlock.passageShape(i.door.getBlockState(), 0);
            SecurityDoorBlock.open(test.getLevel(), i.door);
            var opened = SecurityDoorBlock.passageShape(i.door.getBlockState(), 1);
            test.assertTrue(closed.toAabbs().stream().anyMatch(b -> b.contains(.5, .5, .5)), "Closed door blocks center");
            test.assertFalse(opened.toAabbs().stream().anyMatch(b -> b.contains(.5, .5, .5)), "Open door clears center");
            test.setBlock(DOOR, i.door.getBlockState().setValue(SecurityDoorBlock.OPEN, false));
        }
        i.guest.setPos(Vec3.atCenterOf(i.door.getBlockPos()).add(0, 0, 10));
        test.assertFalse(AccessService.mayOpenManually(i.guest, i.door), "Remote exit requests fail");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void autoCloseWaitsForClearThreshold(GameTestHelper test) {
        var i = installation(test); SecurityDoorBlock.open(test.getLevel(), i.door);
        long deadline = i.door.closeAt();
        SecurityDoorBlock.autoClose(test.getLevel(), i.door);
        test.assertTrue(i.door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Door remains open during its hold interval");
        test.assertTrue(deadline == test.getLevel().getGameTime() + 80 + dev.umbral.access.DoorMotion.TRAVEL_TICKS, "Hold interval starts after the opening travel");
        i.door.doorMotion().accept(new dev.umbral.access.DoorMotion.State(50, test.getLevel().getGameTime(), 0, 1, 1), true);
        var occupant = test.spawn(EntityTypes.ARMOR_STAND, DOOR);
        occupant.setPos(Vec3.atBottomCenterOf(i.door.getBlockPos()));
        i.door.setCloseAt(test.getLevel().getGameTime()); SecurityDoorBlock.autoClose(test.getLevel(), i.door);
        test.assertTrue(i.door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Occupied threshold prevents closing");
        occupant.discard(); SecurityDoorBlock.autoClose(test.getLevel(), i.door);
        test.assertFalse(i.door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Clear threshold closes");
        test.assertFalse(test.getBlockState(DOOR.above()).getValue(SecurityDoorBlock.OPEN), "Both halves close together");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void replacedDoorAndReboundReaderRequireExplicitBinding(GameTestHelper test) {
        var i = installation(test); var card = issue(test, i);
        test.setBlock(DOOR, Blocks.AIR);
        var replacement = place(test, DOOR, UmbralContent.DOOR, i.owner.getUUID()); replacement.bind(i.owner.getUUID(), i.core);
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.DOOR_UNAVAILABLE, "Replacement cannot inherit target identity");
        test.assertTrue(i.reader.targetDoor(i.owner.getUUID(), replacement), "Owner can explicitly select replacement");
        test.assertTrue(AccessService.present(i.guest, i.reader, card) == AccessService.Result.GRANTED, "Explicit replacement binding restores access");
        var other = place(test, new BlockPos(12, 1, 1), UmbralContent.CORE, i.owner.getUUID());
        i.reader.bind(i.owner.getUUID(), other);
        test.assertTrue(i.reader.doorTarget() == null, "Changing network clears physical door binding");
        test.assertFalse(i.reader.targetDoor(i.owner.getUUID(), replacement), "Cross-network binding rejected");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void readerBindingRequiresOwnerAndShortRange(GameTestHelper test) {
        var i = installation(test);
        test.assertFalse(i.reader.targetDoor(i.guest.getUUID(), i.door), "Guest cannot change target");
        var distant = place(test, new BlockPos(13, 1, 3), UmbralContent.DOOR, i.owner.getUUID()); distant.bind(i.owner.getUUID(), i.core);
        test.assertFalse(i.reader.targetDoor(i.owner.getUUID(), distant), "Reader cannot command door beyond four blocks");
        test.assertFalse(i.reader.targetDoor(i.owner.getUUID(), i.core), "Target must be a door");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void bothHalvesProtectedAndCannotOpenFromRedstone(GameTestHelper test) {
        var i = installation(test);
        test.setBlock(DOOR.west(), UmbralContent.CABLE); test.setBlock(DOOR.above().west(), UmbralContent.CABLE);
        test.setBlock(DOOR, i.door.getBlockState().setValue(DeviceBlock.ONLINE, true));
        test.assertTrue(test.getBlockState(DOOR.west()).getValue(CopperCableBlock.CONNECTIONS.get(Direction.EAST)), "Lower half exposes the cable port");
        test.assertFalse(test.getBlockState(DOOR.above().west()).getValue(CopperCableBlock.CONNECTIONS.get(Direction.EAST)), "Upper half must not show a nonexistent cable port");
        var foreign = test.makeMockPlayer(GameType.SURVIVAL);
        for (BlockPos pos : new BlockPos[]{i.door.getBlockPos(), i.door.getBlockPos().above()}) {
            test.assertFalse(PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(test.getLevel(), foreign, pos,
                    test.getLevel().getBlockState(pos), test.getLevel().getBlockEntity(pos)), "Both halves must enforce ownership");
        }
        test.setBlock(DOOR.east(), Blocks.REDSTONE_BLOCK);
        test.assertFalse(i.door.getBlockState().getValue(SecurityDoorBlock.OPEN), "Power cannot bypass card access");
        test.setBlock(DOOR.below(), Blocks.AIR);
        test.assertTrue(test.getBlockState(DOOR).is(UmbralContent.DOOR), "Removing support cannot bypass ownership");
        int prior = test.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(i.door.getBlockPos()).inflate(2)).stream()
                .filter(e -> e.getItem().is(UmbralContent.DOOR.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
        test.assertTrue(prior == 0, "Setup must not drop a door; got " + prior);
        test.assertTrue(Block.getDrops(test.getBlockState(DOOR.above()), test.getLevel(), i.door.getBlockPos().above(), null).isEmpty(), "Upper half loot must be empty");
        test.getLevel().destroyBlock(i.door.getBlockPos().above(), true);
        test.assertTrue(test.getBlockState(DOOR).isAir(), "Removing upper must remove lower");
        int drops = test.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(i.door.getBlockPos()).inflate(2)).stream()
                .filter(e -> e.getItem().is(UmbralContent.DOOR.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
        test.assertTrue(drops == 1, "Breaking upper must drop exactly one door; got " + drops
                + ", block_drops=" + test.getLevel().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.BLOCK_DROPS));
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void managementRejectsForeignDistantAndStaleRequests(GameTestHelper test) {
        var i = installation(test); issue(test, i);
        var request = request(i, AccessProtocol.Operation.QUERY, null);
        var response = AccessProtocol.handle(i.guest, request);
        test.assertTrue(response.cards().isEmpty() && response.state() == AccessService.Result.INVALID, "Foreign callers learn no pass identifiers");
        i.owner.setPos(i.owner.position().add(20, 0, 0));
        test.assertTrue(AccessProtocol.handle(i.owner, request).cards().isEmpty(), "Remote requests fail");
        i.owner.setPos(i.guest.position());
        var issue = request(i, AccessProtocol.Operation.ISSUE, null);
        test.setBlock(READER, Blocks.AIR);
        var replacement = place(test, READER, UmbralContent.READER, i.owner.getUUID()); replacement.bind(i.owner.getUUID(), i.core);
        test.assertTrue(AccessProtocol.handle(i.owner, issue).result() == AccessService.Result.INVALID, "Old screen cannot issue on replacement reader");
        test.assertTrue(i.owner.getInventory().getItem(0).getCount() == 1, "Rejected mutation consumes nothing");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void registryAndPhysicalReferencesSurviveSave(GameTestHelper test) {
        var i = installation(test); var card = issue(test, i);
        var registry = AccessRegistry.get(test.getLevel());
        var savedRegistry = AccessRegistry.CODEC.encodeStart(NbtOps.INSTANCE, registry).getOrThrow();
        var restoredRegistry = AccessRegistry.CODEC.parse(NbtOps.INSTANCE, savedRegistry).getOrThrow();
        test.assertTrue(restoredRegistry.accepts(card.get(UmbralContent.CARD_ID), i.owner.getUUID(), i.core.link()), "Grants survive storage");
        for (var device : new DeviceBlockEntity[]{i.door, i.reader}) {
            var saved = device.saveWithoutMetadata(test.getLevel().registryAccess());
            var restored = new DeviceBlockEntity(device.getBlockPos(), device.getBlockState()); restored.setLevel(test.getLevel());
            restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), saved));
            test.assertTrue(device.identity().equals(restored.identity()), "Device identity survives reload");
            test.assertTrue(java.util.Objects.equals(device.doorTarget(), restored.doorTarget()), "Door reference survives reload");
        }
        registry.revoke(card.get(UmbralContent.CARD_ID), i.owner.getUUID(), i.core.link());
        var afterRevoke = AccessRegistry.CODEC.parse(NbtOps.INSTANCE, AccessRegistry.CODEC.encodeStart(NbtOps.INSTANCE, registry).getOrThrow()).getOrThrow();
        test.assertFalse(afterRevoke.accepts(card.get(UmbralContent.CARD_ID), i.owner.getUUID(), i.core.link()), "Revocation must persist");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void issuanceNeedsBlankAndHonorsCapacityAndNames(GameTestHelper test) {
        var i = installation(test);
        test.assertTrue(AccessService.issue(i.owner, i.reader) == AccessService.Result.NO_BLANK, "No free credentials");
        var blank = new ItemStack(UmbralContent.ACCESS_CARD); blank.set(DataComponents.CUSTOM_NAME, Component.literal("Visita taller"));
        i.owner.getInventory().setItem(0, blank);
        test.assertTrue(AccessService.issue(i.owner, i.reader) == AccessService.Result.ISSUED, "Named blank can be issued");
        test.assertTrue(i.owner.getInventory().getItem(0).getHoverName().getString().equals("Visita taller"), "Custom label survives issuance");
        var registry = AccessRegistry.get(test.getLevel());
        for (int n = 1; n < AccessRegistry.MAX_PASSES; n++) registry.issue(i.owner.getUUID(), i.core.link(), "");
        i.owner.getInventory().setItem(1, new ItemStack(UmbralContent.ACCESS_CARD));
        test.assertTrue(AccessService.issue(i.owner, i.reader) == AccessService.Result.LIMIT, "Per-network limit enforced");
        test.assertTrue(i.owner.getInventory().getItem(1).getCount() == 1, "Limit failure cannot consume blank");
        test.succeed();
    }
    @GameTest(structure = STRUCTURE)
    public void fullCreativeInventoryStillReceivesPhysicalPass(GameTestHelper test) {
        var i = installation(test, GameType.CREATIVE);
        test.assertTrue(i.owner.hasInfiniteMaterials(), "Fixture must really have creative inventory semantics");
        for (int n = 0; n < 36; n++) i.owner.getInventory().setItem(n, new ItemStack(Items.STONE, 64));
        i.owner.getInventory().setItem(0, new ItemStack(UmbralContent.ACCESS_CARD, 2));
        test.assertTrue(AccessService.issue(i.owner, i.reader) == AccessService.Result.DROPPED, "Full inventory reports ground delivery");
        var drops = test.getLevel().getEntitiesOfClass(ItemEntity.class, i.owner.getBoundingBox().inflate(3));
        test.assertTrue(drops.stream().anyMatch(e -> e.getItem().has(UmbralContent.CARD_ID)), "Creative overflow cannot silently discard the pass");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void doorRequiresItsOwnSignalAndSpectatorsCannotUsePasses(GameTestHelper test) {
        var i = installation(test); var card = issue(test, i);
        test.setBlock(READER, Blocks.AIR); test.setBlock(DOOR, Blocks.AIR);
        var reader = place(test, new BlockPos(16, 1, 1), UmbralContent.READER, i.owner.getUUID());
        var door = place(test, new BlockPos(20, 1, 1), UmbralContent.DOOR, i.owner.getUUID());
        reader.bind(i.owner.getUUID(), i.core); door.bind(i.owner.getUUID(), i.core); reader.targetDoor(i.owner.getUUID(), door);
        i.guest.setPos(Vec3.atCenterOf(reader.getBlockPos()));
        test.assertTrue(AccessService.present(i.guest, reader, card) == AccessService.Result.DOOR_OFFLINE,
                "A connected reader cannot supply connectivity to an out-of-range door");
        var spectator = (ServerPlayer) test.makeMockServerPlayer(GameType.SPECTATOR);
        spectator.setPos(i.guest.position());
        test.assertTrue(AccessService.present(spectator, reader, card) == AccessService.Result.DENIED, "Spectators cannot use physical credentials");
        test.assertFalse(AccessService.mayOpenManually(spectator, door), "Spectators cannot open doors manually");
        test.succeed();
    }

    @GameTest(structure = STRUCTURE)
    public void staleNetworkAndUnloadedRequestsCannotMutateOrLoadWorld(GameTestHelper test) {
        var i = installation(test); issue(test, i);
        var old = request(i, AccessProtocol.Operation.ISSUE, null);
        var other = place(test, new BlockPos(12, 1, 1), UmbralContent.CORE, i.owner.getUUID()); i.reader.bind(i.owner.getUUID(), other);
        test.assertTrue(AccessProtocol.handle(i.owner, old).result() == AccessService.Result.INVALID, "Stale screen cannot issue on a rebound reader");
        BlockPos remote = new BlockPos(25_000_000, 100, 25_000_000);
        test.assertFalse(test.getLevel().isLoaded(remote), "Fixture position must start unloaded");
        var probe = new AccessProtocol.Request(remote, UUID.randomUUID(), i.reader.link(), 9, AccessProtocol.Operation.QUERY, null);
        test.assertTrue(AccessProtocol.handle(i.owner, probe).cards().isEmpty(), "Unloaded reader discloses nothing");
        test.assertFalse(test.getLevel().isLoaded(remote), "Access request cannot force a chunk load");
        test.assertTrue(i.owner.getInventory().getItem(0).getCount() == 1, "Rejected requests consume no blank cards");
        test.succeed();
    }
}
