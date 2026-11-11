// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.protection.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import java.util.*;

/** Selection expiry must change the meaning of this click without swallowing it. */
public final class ConfiguratorUsabilityGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(3,1,3),NODE=CORE.east(2);
    private record Setup(ServerPlayer player,DeviceBlockEntity core,DeviceBlockEntity node){}
    @SuppressWarnings("removal") private static ServerPlayer connected(GameTestHelper t){return t.makeMockServerPlayerInLevel();}
    private static void remove(ServerPlayer p){ProtectionSelections.clear(p.getUUID());p.level().getServer().getPlayerList().remove(p);}
    private Setup setup(GameTestHelper t){
        var p=connected(t);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));
        t.setBlock(CORE,UmbralContent.CORE);t.setBlock(NODE,UmbralContent.NODE);
        var core=t.getBlockEntity(CORE,DeviceBlockEntity.class);var node=t.getBlockEntity(NODE,DeviceBlockEntity.class);
        core.initializeOwner(p.getUUID(),"Casa");node.initializeOwner(p.getUUID(),"");p.setPos(Vec3.atCenterOf(core.getBlockPos()).add(0,0,-2));return new Setup(p,core,node);
    }
    private static void click(ServerPlayer p,BlockPos at){
        UmbralContent.CONFIGURATOR.useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at),Direction.UP,at,false)));
    }
    private static void begin(ServerPlayer p){
        ProtectionSelections.begin(p,new ProtectionSelections.Draft(ProtectionData.NONE,0,ProtectionData.NONE,"Borrador",new ProtectionShape(),false,ProtectionSelections.Mode.FOOTPRINT,List.of(),p.getBlockY(),p.getBlockY()+16));
    }
    /** Move only this player's fixture deadline/dimension; never advance the shared test-world clock. */
    @SuppressWarnings("unchecked") private static void invalidate(ServerPlayer p,boolean dimension){
        try{
            var field=ProtectionSelections.class.getDeclaredField("SESSIONS");field.setAccessible(true);var sessions=(Map<UUID,Object>)field.get(null);Object old=sessions.get(p.getUUID());
            var type=old.getClass();var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
            var draft=type.getDeclaredMethod("draft");draft.setAccessible(true);var dim=type.getDeclaredMethod("dimension");dim.setAccessible(true);var expiry=type.getDeclaredMethod("expires");expiry.setAccessible(true);
            sessions.put(p.getUUID(),constructor.newInstance(draft.invoke(old),dimension?Identifier.parse("umbral-test:old_dimension"):dim.invoke(old),dimension?expiry.invoke(old):p.level().getServer().overworld().getGameTime()-1));
        }catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
    @GameTest(structure=STRUCTURE) public void expiredAreaSelectionDoesNotEatTheCoreSelectionClick(GameTestHelper t){
        var s=setup(t);begin(s.player);invalidate(s.player,false);click(s.player,s.core.getBlockPos());
        t.assertTrue(s.core.link().equals(s.player.getMainHandItem().get(UmbralContent.SELECTED_NETWORK)),"The same click selects the core after selection expiry");
        t.assertFalse(ProtectionSelections.active(s.player.getUUID()),"Expired marking is discarded");click(s.player,s.node.getBlockPos());
        t.assertTrue(s.core.link().equals(s.node.link()),"The following equipment bind works normally");remove(s.player);t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void changingDimensionDoesNotEatTheNextCoreClick(GameTestHelper t){
        var s=setup(t);begin(s.player);invalidate(s.player,true);click(s.player,s.core.getBlockPos());
        t.assertTrue(s.core.link().equals(s.player.getMainHandItem().get(UmbralContent.SELECTED_NETWORK))&&!ProtectionSelections.active(s.player.getUUID()),"Old-dimension marking cannot intercept a new core selection");remove(s.player);t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void liveAreaSelectionStillMarksInsteadOfBindingAnApparatus(GameTestHelper t){
        var s=setup(t);begin(s.player);click(s.player,s.core.getBlockPos());
        t.assertTrue(ProtectionSelections.draft(s.player).points().equals(List.of(s.core.getBlockPos())),"An active selection keeps its point-marking behavior");
        t.assertFalse(s.player.getMainHandItem().has(UmbralContent.SELECTED_NETWORK),"Marking is not also a network bind");remove(s.player);t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void deadPlayerClearsDraftWithoutSelectingOrChangingOwnership(GameTestHelper t){
        var s=setup(t);begin(s.player);s.player.setHealth(0);click(s.player,s.core.getBlockPos());
        t.assertFalse(ProtectionSelections.active(s.player.getUUID()),"Death invalidates marking before click dispatch");
        t.assertFalse(s.player.getMainHandItem().has(UmbralContent.SELECTED_NETWORK),"Dead players cannot select a core");t.assertTrue(s.core.ownedBy(s.player.getUUID()),"No device ownership changes");remove(s.player);t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void expiredMarkingNeverGrantsForeignDeviceControl(GameTestHelper t){
        var s=setup(t);var stranger=connected(t);stranger.setPos(s.player.position());stranger.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));
        begin(stranger);invalidate(stranger,false);click(stranger,s.core.getBlockPos());
        t.assertFalse(stranger.getMainHandItem().has(UmbralContent.SELECTED_NETWORK),"Expiry does not bypass ownership");t.assertTrue(s.core.ownedBy(s.player.getUUID()),"Foreign core retains its owner");remove(stranger);remove(s.player);t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void allToolSelectionsSurviveTheRealItemPersistenceCodec(GameTestHelper t){
        var s=setup(t);click(s.player,s.core.getBlockPos());var stack=s.player.getMainHandItem();
        var door=new dev.umbral.access.DoorTarget(t.getLevel().dimension().identifier(),s.core.getBlockPos().east(),UUID.randomUUID());
        var stock=new dev.umbral.access.DoorTarget(t.getLevel().dimension().identifier(),s.core.getBlockPos().south(),UUID.randomUUID());
        stack.set(UmbralContent.SELECTED_DOOR,door);stack.set(UmbralContent.SELECTED_STOCK,stock);
        var ops=t.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var restored=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,stack).getOrThrow()).getOrThrow();
        t.assertTrue(restored.is(UmbralContent.CONFIGURATOR)&&s.core.link().equals(restored.get(UmbralContent.SELECTED_NETWORK))
                &&door.equals(restored.get(UmbralContent.SELECTED_DOOR))&&stock.equals(restored.get(UmbralContent.SELECTED_STOCK)),"Real inventory persistence keeps network, door and stock identity");remove(s.player);t.succeed();
    }
}
