// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.protection.*;
import dev.umbral.logistics.MotaService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import java.util.*;

public final class ProtectionGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private record Setup(ServerPlayer owner,ServerPlayer guest,ProtectionData data,ProtectionData.Region region){}
    private Setup setup(GameTestHelper t){return setup(t,new BlockPos(4,0,2),new BlockPos(8,5,6));}
    private Setup setup(GameTestHelper t,BlockPos a,BlockPos b){
        for(int x=0;x<12;x++)for(int z=0;z<8;z++)t.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        var owner=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);
        owner.setPos(Vec3.atCenterOf(t.absolutePos(new BlockPos(5,1,4))));guest.setPos(owner.position());owner.getInventory().setItem(0,new ItemStack(UmbralContent.PROTECTION_MATRIX,4));
        for(int n=0;n<4;n++)t.assertTrue(ProtectionService.install(owner).equals("installed"),"Actual matrix install");
        a=t.absolutePos(a);b=t.absolutePos(b);var shape=ProtectionShape.rectangle(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());
        t.assertTrue(ProtectionService.apply(owner,ProtectionData.NONE,0,ProtectionData.NONE,shape,"Casa").equals("applied"),"Paid area activated");
        var data=ProtectionData.get(t.getLevel());var r=data.owned(owner.getUUID()).getLast();return new Setup(owner,guest,data,r);
    }
    private ProtectionShape box(GameTestHelper t,BlockPos a,BlockPos b){a=t.absolutePos(a);b=t.absolutePos(b);return ProtectionShape.rectangle(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());}
    private void grants(Setup s,ProtectionData.Region r,int mask){s.data.put(new ProtectionData.Region(r.id(),r.owner(),r.dimension(),r.label(),r.parent(),r.shape(),List.of(new ProtectionData.Member(s.guest.getUUID(),"Visita",mask)),r.flags(),r.revision()+1));}
    private InteractionResult use(ServerPlayer player,BlockPos pos,Direction face,ItemStack stack){player.setItemInHand(InteractionHand.MAIN_HAND,stack);return player.gameMode.useItemOn(player,player.level(),stack,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos).add(new Vec3(face.getStepX(),face.getStepY(),face.getStepZ()).scale(.5)),face,pos,false));}
    @GameTest(structure=STRUCTURE)
    public void matricesConsumeRealItemsPreserveComponentsAndSurviveCreativeMode(GameTestHelper t){
        var i=setup(t);t.assertTrue(i.owner.getInventory().getItem(0).isEmpty()&&i.data.matrices(i.owner.getUUID())==4&&i.data.capacity(i.owner.getUUID())==8192,"Four actual matrices installed");
        i.owner.setGameMode(GameType.CREATIVE);var stack=new ItemStack(UmbralContent.PROTECTION_MATRIX,2);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Lote"));i.owner.getInventory().setItem(0,stack);
        t.assertTrue(ProtectionService.install(i.owner).equals("installed")&&stack.getCount()==1,"Creative also transfers a real matrix");
        t.assertTrue(stack.getHoverName().getString().equals("Lote"),"Remaining item components intact");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void noMaterialMeansNoCapacityAndARequestCannotInventMatrices(GameTestHelper t){
        var i=setup(t);i.owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));
        var r=new ProtectionProtocol.Request(ProtectionProtocol.Access.tool(i.owner),5,ProtectionProtocol.Action.INSTALL,ProtectionData.NONE,0,ProtectionData.NONE,new ProtectionShape(),"",ProtectionData.NONE,4096,0,8);
        t.assertTrue(ProtectionProtocol.handle(i.owner,r).result().equals("need_matrix")&&i.data.matrices(i.owner.getUUID())==4,"Client quantity cannot create capacity");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void guestCannotBreakProtectedStoneButOwnerCan(GameTestHelper t){
        var i=setup(t);var pos=t.absolutePos(new BlockPos(5,1,4));t.setBlock(new BlockPos(5,1,4),Blocks.STONE);
        t.assertFalse(net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(t.getLevel(),i.guest,pos,t.getLevel().getBlockState(pos),null),"The live break event denies foreign breaking");t.assertTrue(t.getLevel().getBlockState(pos).is(Blocks.STONE),"Protected block remains");
        t.assertTrue(net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(t.getLevel(),i.owner,pos,t.getLevel().getBlockState(pos),null),"Owner break is authorized");t.getLevel().destroyBlock(pos,false);t.assertTrue(t.getLevel().getBlockState(pos).isAir(),"Owner can edit their base");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void deniedPlacementDoesNotConsumeBlocksAndBuilderCanPlace(GameTestHelper t){
        var i=setup(t);var target=t.absolutePos(new BlockPos(5,1,3));var stack=new ItemStack(Items.STONE,4);use(i.guest,target.below(),Direction.UP,stack);
        t.assertTrue(t.getLevel().getBlockState(target).isAir()&&stack.getCount()==4,"Denied placement preserves world and inventory");grants(i,i.region,ProtectionData.BUILD|ProtectionData.USE);use(i.guest,target.below(),Direction.UP,stack);
        t.assertTrue(t.getLevel().getBlockState(target).is(Blocks.STONE)&&stack.getCount()==3,"Authorized builder places one actual block");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void inventoryAndMechanismPermissionsAreSeparate(GameTestHelper t){
        var i=setup(t);var chest=t.absolutePos(new BlockPos(5,1,3));t.setBlock(new BlockPos(5,1,3),Blocks.CHEST);grants(i,i.region,ProtectionData.USE);
        var before=i.guest.containerMenu;use(i.guest,chest,Direction.NORTH,ItemStack.EMPTY);t.assertTrue(i.guest.containerMenu==before,"Mechanism grant does not open a chest");
        var button=new BlockPos(6,1,4);t.setBlock(button,Blocks.STONE_BUTTON.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE,AttachFace.FLOOR));use(i.guest,t.absolutePos(button),Direction.UP,ItemStack.EMPTY);
        t.assertTrue(t.getBlockState(button).getValue(ButtonBlock.POWERED),"Same grant can operate a button");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void unprotectedHalfCannotOpenProtectedDoubleChestInventory(GameTestHelper t){
        var i=setup(t,new BlockPos(5,1,4),new BlockPos(5,2,4));var a=new BlockPos(5,1,4);var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.LEFT);var b=a.relative(ChestBlock.getConnectedDirection(state));t.setBlock(a,state);t.setBlock(b,state.setValue(ChestBlock.TYPE,ChestType.RIGHT));
        var before=i.guest.containerMenu;use(i.guest,t.absolutePos(b),Direction.NORTH,ItemStack.EMPTY);t.assertTrue(i.guest.containerMenu==before,"Opening the outside half validates both physical inventories");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void multiBlockDoorCannotBePlacedAcrossForeignBoundary(GameTestHelper t){
        var i=setup(t,new BlockPos(5,2,4),new BlockPos(5,3,4));var item=new ItemStack(Items.OAK_DOOR,2);use(i.guest,t.absolutePos(new BlockPos(5,0,4)),Direction.UP,item);
        t.assertTrue(t.getBlockState(new BlockPos(5,2,4)).isAir()&&item.getCount()==2,"A door cannot overwrite protected upper space");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void actualExplosionPreservesClaimedWallAndStillDamagesOutside(GameTestHelper t){
        var i=setup(t,new BlockPos(5,1,4),new BlockPos(5,3,4));t.setBlock(new BlockPos(5,2,4),Blocks.OAK_PLANKS);t.setBlock(new BlockPos(5,2,2),Blocks.OAK_PLANKS);
        var center=Vec3.atCenterOf(t.absolutePos(new BlockPos(5,2,3)));t.getLevel().explode(null,center.x,center.y,center.z,4,Level.ExplosionInteraction.TNT);
        t.assertTrue(t.getBlockState(new BlockPos(5,2,4)).is(Blocks.OAK_PLANKS),"Explosion cannot destroy protected wood");t.assertTrue(t.getBlockState(new BlockPos(5,2,2)).isAir(),"Ordinary outside explosion behavior is preserved");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void fireAndEntityDestructionAreBlockedBySavedPolicy(GameTestHelper t){
        var i=setup(t);var p=t.absolutePos(new BlockPos(5,1,3));t.assertFalse(t.getLevel().setBlockAndUpdate(p,Blocks.FIRE.defaultBlockState()),"Fire cannot be created inside a fire-protected area");t.setBlock(new BlockPos(5,1,3),Blocks.DIRT);var mob=t.spawn(EntityTypes.HUSK,new BlockPos(3,1,3));mob.setNoAi(true);
        t.assertFalse(t.getLevel().destroyBlock(p,true,mob)&&t.getLevel().getBlockState(p).isAir(),"A creature cannot destroy the protected block");t.assertTrue(t.getLevel().getBlockState(p).is(Blocks.DIRT),"No destruction or drops");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void pistonCannotPushUnclaimedBlockIntoProtectedSpace(GameTestHelper t){
        var i=setup(t,new BlockPos(6,0,3),new BlockPos(8,4,5));t.setBlock(new BlockPos(4,1,4),Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));t.setBlock(new BlockPos(5,1,4),Blocks.STONE);t.setBlock(new BlockPos(4,0,4),Blocks.REDSTONE_BLOCK);
        t.runAfterDelay(6,()->{t.assertTrue(t.getBlockState(new BlockPos(5,1,4)).is(Blocks.STONE)&&t.getBlockState(new BlockPos(6,1,4)).isAir(),"Boundary blocks the actual piston push");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void aPistonEntirelyInsideOneOwnersAreaStillWorks(GameTestHelper t){
        var i=setup(t,new BlockPos(3,0,3),new BlockPos(8,4,5));t.setBlock(new BlockPos(4,1,4),Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));t.setBlock(new BlockPos(5,1,4),Blocks.STONE);t.setBlock(new BlockPos(4,0,4),Blocks.REDSTONE_BLOCK);
        t.runAfterDelay(6,()->{t.assertTrue(t.getBlockState(new BlockPos(6,1,4)).is(Blocks.STONE),"Internal automation is preserved");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void anOutsideHopperCannotExtractFromProtectedChest(GameTestHelper t){
        var i=setup(t,new BlockPos(5,2,4),new BlockPos(5,3,4));t.setBlock(new BlockPos(5,2,4),Blocks.CHEST);t.setBlock(new BlockPos(5,1,4),Blocks.HOPPER);var chest=t.getBlockEntity(new BlockPos(5,2,4),ChestBlockEntity.class);chest.setItem(0,new ItemStack(Items.IRON_INGOT,8));
        t.runAfterDelay(20,()->{t.assertTrue(chest.getItem(0).getCount()==8&&t.getBlockEntity(new BlockPos(5,1,4),HopperBlockEntity.class).isEmpty(),"A real hopper cannot siphon protected inventory");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void internalHoppersTransferNormally(GameTestHelper t){
        var i=setup(t);t.setBlock(new BlockPos(5,2,4),Blocks.CHEST);t.setBlock(new BlockPos(5,1,4),Blocks.HOPPER);var chest=t.getBlockEntity(new BlockPos(5,2,4),ChestBlockEntity.class);chest.setItem(0,new ItemStack(Items.IRON_INGOT,8));
        t.runAfterDelay(20,()->{int inside=t.getBlockEntity(new BlockPos(5,1,4),HopperBlockEntity.class).getItem(0).getCount();t.assertTrue(inside>0&&inside+chest.getItem(0).getCount()==8,"Internal transfers conserve actual items");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void waterCannotFlowAcrossTheBoundary(GameTestHelper t){
        var i=setup(t,new BlockPos(5,0,3),new BlockPos(8,5,5));for(int x=2;x<=9;x++){t.setBlock(new BlockPos(x,1,4),Blocks.STONE);for(int y=2;y<=4;y++){t.setBlock(new BlockPos(x,y,3),Blocks.STONE);t.setBlock(new BlockPos(x,y,5),Blocks.STONE);}}
        t.setBlock(new BlockPos(4,2,4),Blocks.WATER);t.runAfterDelay(35,()->{t.assertTrue(t.getLevel().getFluidState(t.absolutePos(new BlockPos(5,2,4))).isEmpty(),"Flowing water stops at the saved boundary");t.succeed();});
    }
    @GameTest(structure=STRUCTURE)
    public void irregularCutLeavesCourtyardUnprotectedAndReturnsCapacity(GameTestHelper t){
        var i=setup(t);long before=i.data.used(i.owner.getUUID());var removed=box(t,new BlockPos(6,0,4),new BlockPos(6,5,4));var shape=i.region.shape().subtract(removed);
        t.assertTrue(ProtectionService.apply(i.owner,i.region.id(),i.region.revision(),ProtectionData.NONE,shape,"Casa").equals("applied"),"Exact cut applied");
        t.assertTrue(i.data.used(i.owner.getUUID())==before-4&&ProtectionPolicy.permits(t.getLevel(),t.absolutePos(new BlockPos(6,1,4)),i.guest.getUUID(),ProtectionData.BUILD),"Courtyard is outside, capacity released");
        t.assertFalse(ProtectionPolicy.permits(t.getLevel(),t.absolutePos(new BlockPos(5,1,4)),i.guest.getUUID(),ProtectionData.BUILD),"Neighboring room remains protected");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void deletingAndReclaimingUsesExistingCapacityWithoutRefundingItems(GameTestHelper t){
        var i=setup(t);long cap=i.data.capacity(i.owner.getUUID());t.assertTrue(ProtectionService.delete(i.owner,i.region.id(),i.region.revision()).equals("deleted"),"Removed by owner");
        t.assertTrue(i.data.used(i.owner.getUUID())==0&&i.data.capacity(i.owner.getUUID())==cap&&i.owner.getInventory().getItem(0).isEmpty(),"Capacity retained, no duplicate item refund");
        t.assertTrue(ProtectionService.apply(i.owner,ProtectionData.NONE,0,ProtectionData.NONE,i.region.shape(),"Nueva").equals("applied")&&i.data.matrices(i.owner.getUUID())==4,"Reallocation needs no new payment");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void overlapAndStaleEditsCannotStealOrReplaceClaims(GameTestHelper t){
        var i=setup(t);i.guest.getInventory().setItem(0,new ItemStack(UmbralContent.PROTECTION_MATRIX,4));for(int n=0;n<4;n++)ProtectionService.install(i.guest);
        t.assertTrue(ProtectionService.apply(i.guest,ProtectionData.NONE,0,ProtectionData.NONE,i.region.shape(),"Ajena").equals("foreign_overlap"),"Paid foreign overlap denied");
        t.assertTrue(ProtectionService.apply(i.guest,i.region.id(),i.region.revision(),ProtectionData.NONE,i.region.shape(),"Robo").equals("changed"),"Knowing ID is not authority");
        var enlarged=i.region.shape().add(box(t,new BlockPos(3,0,2),new BlockPos(3,5,6)));ProtectionService.apply(i.owner,i.region.id(),i.region.revision(),ProtectionData.NONE,enlarged,"Grande");
        t.assertTrue(ProtectionService.apply(i.owner,i.region.id(),i.region.revision(),ProtectionData.NONE,i.region.shape(),"Antigua").equals("changed"),"Stale revision cannot roll back a later expansion");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void roomPermissionsOverrideTheParentWithoutBuyingAnotherArea(GameTestHelper t){
        var i=setup(t);grants(i,i.region,ProtectionData.USE|ProtectionData.INVENTORY);long used=i.data.used(i.owner.getUUID());var room=box(t,new BlockPos(5,1,3),new BlockPos(6,3,4));
        t.assertTrue(ProtectionService.apply(i.owner,ProtectionData.NONE,0,i.region.id(),room,"Almacén").equals("applied"),"Room registered inside its parent");var r=i.data.owned(i.owner.getUUID()).getLast();grants(i,r,0);
        t.assertFalse(ProtectionPolicy.permits(t.getLevel(),t.absolutePos(new BlockPos(5,1,3)),i.guest.getUUID(),ProtectionData.INVENTORY),"Room override excludes inherited inventory access");
        t.assertTrue(ProtectionPolicy.permits(t.getLevel(),t.absolutePos(new BlockPos(7,1,5)),i.guest.getUUID(),ProtectionData.INVENTORY)&&i.data.used(i.owner.getUUID())==used,"Parent permissions preserved elsewhere and room adds no land cost");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void parentCannotBeTrimmedOutFromUnderItsRoom(GameTestHelper t){
        var i=setup(t);var room=box(t,new BlockPos(5,1,3),new BlockPos(6,3,4));ProtectionService.apply(i.owner,ProtectionData.NONE,0,i.region.id(),room,"Sala");
        t.assertTrue(ProtectionService.apply(i.owner,i.region.id(),i.region.revision(),ProtectionData.NONE,i.region.shape().subtract(room),"Casa").equals("room_outside"),"A trim cannot orphan a room or silently remove its protection");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void ledgerPersistenceKeepsAreasRoomsMaterialsAndPermissions(GameTestHelper t){
        var i=setup(t);grants(i,i.region,ProtectionData.USE);var tag=ProtectionData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,i.data).getOrThrow();var copy=ProtectionData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,tag).getOrThrow();
        t.assertTrue(copy.capacity(i.owner.getUUID())==8192&&copy.used(i.owner.getUUID())==i.region.shape().cost(),"Investment and assigned capacity persist");
        var r=copy.at(t.getLevel().dimension().identifier(),t.absolutePos(new BlockPos(5,1,4)));t.assertTrue(r!=null&&copy.permits(r,i.guest.getUUID(),ProtectionData.USE)&&!copy.permits(r,i.guest.getUUID(),ProtectionData.BUILD),"Shape and effective permissions persist");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void terminalAndCoreRemovalCannotRemoveTerritorialProtection(GameTestHelper t){
        var i=setup(t);var core=new BlockPos(5,1,5);t.setBlock(core,UmbralContent.CORE);t.getBlockEntity(core,DeviceBlockEntity.class).initializeOwner(i.owner.getUUID(),"Núcleo");t.setBlock(core,Blocks.AIR);
        t.assertFalse(ProtectionPolicy.permits(t.getLevel(),t.absolutePos(new BlockPos(5,1,4)),i.guest.getUUID(),ProtectionData.BUILD),"Ownership ledger is independent of the terminal and power");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void enforcingAnUnloadedAreaDoesNotLoadItsChunk(GameTestHelper t){
        var i=setup(t);BlockPos edge=t.absolutePos(new BlockPos(1,100,1));int n=0;while(t.getLevel().isLoaded(edge)&&n++<2048)edge=edge.east();t.assertFalse(t.getLevel().isLoaded(edge),"Unloaded boundary found");
        var shape=ProtectionShape.rectangle(edge.getX(),90,edge.getZ(),edge.getX()+2,110,edge.getZ()+2);i.data.put(new ProtectionData.Region(UUID.randomUUID(),i.owner.getUUID(),t.getLevel().dimension().identifier(),"Remota",Optional.empty(),shape,List.of(),ProtectionData.ALL,1));
        t.assertFalse(ProtectionPolicy.permits(t.getLevel(),edge,i.guest.getUUID(),ProtectionData.BUILD),"Saved metadata enforces protection without terrain");t.assertFalse(t.getLevel().isLoaded(edge),"No chunk was forced");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void blockedActionsAreGroupedAndOnlyVisibleToTheOwner(GameTestHelper t){
        var i=setup(t);var pos=t.absolutePos(new BlockPos(5,1,4));for(int n=0;n<30;n++)ProtectionPolicy.player(i.guest,pos,ProtectionData.BUILD,"break");
        t.assertTrue(i.data.incidents(i.owner.getUUID()).size()==1&&i.data.incidents(i.guest.getUUID()).isEmpty(),"Repeated attempts group without leaking another owner's log");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void nexoExpansionIsOnlyAProposalAndRequiresTheNormalBudgetCheck(GameTestHelper t){
        var i=setup(t);var pos=t.absolutePos(new BlockPos(9,1,4));t.getLevel().setBlockAndUpdate(pos,Blocks.BRICKS.defaultBlockState());ProtectionSuggestions.placed(i.owner,pos);
        long used=i.data.used(i.owner.getUUID());t.assertTrue(ProtectionSuggestions.suggest(i.owner,i.region.id()).equals("proposal"),"NEXO detects recent nearby construction");
        var draft=ProtectionSelections.draft(i.owner);t.assertTrue(draft.base().contains(pos.getX(),pos.getY(),pos.getZ())&&draft.base().contains(i.region.shape()),"Proposal includes the extension and existing land");
        t.assertTrue(i.data.used(i.owner.getUUID())==used&&ProtectionPolicy.permits(t.getLevel(),pos,i.guest.getUUID(),ProtectionData.BUILD),"Unconfirmed proposals never acquire land");
        t.assertTrue(ProtectionService.apply(i.owner,draft.region(),draft.revision(),draft.parent(),draft.base(),draft.label()).equals("applied"),"Explicit confirmation uses the authoritative apply path");t.succeed();
    }
    private void roomShell(GameTestHelper t){for(int x=4;x<=8;x++)for(int z=2;z<=6;z++)for(int y=0;y<=4;y++)if(x==4||x==8||z==2||z==6||y==0||y==4)t.setBlock(new BlockPos(x,y,z),Blocks.STONE);}
    @GameTest(structure=STRUCTURE)
    public void roomDetectionFindsAnEnclosedInteriorWithoutApplyingIt(GameTestHelper t){
        var i=setup(t);roomShell(t);long used=i.data.used(i.owner.getUUID());
        t.assertTrue(ProtectionSuggestions.room(i.owner,i.region.id(),t.absolutePos(new BlockPos(6,1,4))).equals("detected"),"Enclosed walkable room detected");
        var draft=ProtectionSelections.draft(i.owner);t.assertTrue(draft.base().volume()==27&&draft.parent().equals(i.region.id())&&i.data.owned(i.owner.getUUID()).size()==1&&i.data.used(i.owner.getUUID())==used,"Only the 3×3×3 interior is proposed; ledger is untouched");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void openRoomDetectionStopsAtTheExistingBoundary(GameTestHelper t){
        var i=setup(t);roomShell(t);t.setBlock(new BlockPos(8,1,4),Blocks.AIR);
        t.assertTrue(ProtectionSuggestions.room(i.owner,i.region.id(),t.absolutePos(new BlockPos(6,1,4))).equals("open_room"),"An open doorway cannot flood into unowned terrain");t.assertTrue(i.data.owned(i.owner.getUUID()).size()==1,"No automatic claim was made");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void aForgedTerminalIdentityAndForeignRegionCannotAdministerProtection(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(5,1,4);t.setBlock(pos,UmbralContent.NEXO);var d=t.getBlockEntity(pos,DeviceBlockEntity.class);d.initializeOwner(i.owner.getUUID(),"NEXO");
        var real=new ProtectionProtocol.Access(false,t.getLevel().dimension().identifier(),t.absolutePos(pos),d.identity());var forged=new ProtectionProtocol.Access(false,real.dimension(),real.position(),UUID.randomUUID());
        t.assertTrue(ProtectionProtocol.authorized(i.owner,real)&&!ProtectionProtocol.authorized(i.owner,forged)&&!ProtectionProtocol.authorized(i.guest,real),"Physical identity and ownership are both checked even offline");
        t.assertTrue(ProtectionService.flags(i.guest,i.region.id(),i.region.revision(),0).equals("changed")&&ProtectionService.delete(i.guest,i.region.id(),i.region.revision()).equals("changed"),"Foreign IDs cannot change rules or delete land");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void capacityIsSharedAcrossDimensionsAndCannotBeResetWithAnotherCore(GameTestHelper t){
        var i=setup(t);var nether=t.getLevel().getServer().getLevel(Level.NETHER);t.assertTrue(nether!=null&&ProtectionData.get(nether)==i.data,"All dimensions use the same account ledger");
        var shape=ProtectionShape.rectangle(0,0,0,15,7,15);i.data.put(new ProtectionData.Region(UUID.randomUUID(),i.owner.getUUID(),Level.NETHER.identifier(),"Otra dimensión",Optional.empty(),shape,List.of(),ProtectionData.ALL,1));
        t.assertTrue(i.data.used(i.owner.getUUID())==i.region.shape().cost()+1024&&i.data.capacity(i.owner.getUUID())==8192,"Land in another dimension consumes existing capacity");t.succeed();
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void dispensersCannotInjectItemsAcrossAProtectedBoundary(GameTestHelper t){
        var i=setup(t,new BlockPos(5,1,4),new BlockPos(5,3,4));var pos=new BlockPos(4,1,4);t.setBlock(pos,Blocks.DROPPER.defaultBlockState().setValue(DropperBlock.FACING,Direction.EAST));t.setBlock(new BlockPos(5,1,4),Blocks.CHEST);
        var drop=t.getBlockEntity(pos,DropperBlockEntity.class);drop.setItem(0,new ItemStack(Items.IRON_INGOT,8));t.setBlock(pos.below(),Blocks.REDSTONE_BLOCK);
        t.runAfterDelay(12,()->{t.assertTrue(drop.getItem(0).getCount()==8&&t.getBlockEntity(new BlockPos(5,1,4),ChestBlockEntity.class).isEmpty(),"A real dropper preserves both inventories when crossing is denied");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void fallingBlocksFromOutsideBecomeOneItemRatherThanBuildingInsideTheClaim(GameTestHelper t){
        var i=setup(t,new BlockPos(5,0,4),new BlockPos(5,2,4));var pos=t.absolutePos(new BlockPos(5,5,4));t.getLevel().setBlockAndUpdate(pos,Blocks.SAND.defaultBlockState());net.minecraft.world.entity.item.FallingBlockEntity.fall(t.getLevel(),pos,Blocks.SAND.defaultBlockState());
        t.runAfterDelay(30,()->{t.assertTrue(t.getBlockState(new BlockPos(5,1,4)).isAir(),"External sand cannot build in the protected volume");int count=t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(t.absolutePos(new BlockPos(4,0,3))).inflate(3)).stream().filter(e->e.getItem().is(Items.SAND)).mapToInt(e->e.getItem().getCount()).sum();t.assertTrue(count==1,"The rejected falling block becomes exactly one recoverable item");t.succeed();});
    }
    @GameTest(structure=STRUCTURE,maxTicks=100)
    public void fallingBlocksWithinOneAreaKeepTheirVanillaBehavior(GameTestHelper t){
        var i=setup(t);var pos=t.absolutePos(new BlockPos(5,4,4));t.getLevel().setBlockAndUpdate(pos,Blocks.SAND.defaultBlockState());net.minecraft.world.entity.item.FallingBlockEntity.fall(t.getLevel(),pos,Blocks.SAND.defaultBlockState());
        t.runAfterDelay(30,()->{t.assertTrue(t.getBlockState(new BlockPos(5,1,4)).is(Blocks.SAND),"Internal falling sand still settles normally");t.succeed();});
    }
    private net.minecraft.world.entity.ai.goal.Goal endermanGoal(net.minecraft.world.entity.monster.Enderman enderman,String name){try{var c=Class.forName("net.minecraft.world.entity.monster.Enderman$"+name);var ctor=c.getDeclaredConstructor(net.minecraft.world.entity.monster.Enderman.class);ctor.setAccessible(true);return (net.minecraft.world.entity.ai.goal.Goal)ctor.newInstance(enderman);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    @GameTest(structure=STRUCTURE)
    public void endermanCannotDuplicateAProtectedBlockByPickingItUp(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(6,1,4);t.setBlock(pos,Blocks.DIRT);var mob=t.spawn(EntityTypes.ENDERMAN,new BlockPos(5,1,4));mob.setNoAi(true);var goal=endermanGoal(mob,"EndermanTakeBlockGoal");
        for(int n=0;n<1024;n++)goal.tick();t.assertTrue(t.getBlockState(pos).is(Blocks.DIRT)&&mob.getCarriedBlock()==null,"Denied removal never creates a carried copy");
        ProtectionService.flags(i.owner,i.region.id(),i.region.revision(),ProtectionData.ALL^ProtectionData.MOBS);for(int n=0;n<1024&&mob.getCarriedBlock()==null;n++)goal.tick();
        t.assertTrue(t.getBlockState(pos).isAir()&&mob.getCarriedBlock()!=null,"The actual vanilla goal can take the same block when the rule is disabled");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void endermanKeepsItsCarriedBlockWhenAProtectedPlacementIsDenied(GameTestHelper t){
        var i=setup(t);var mob=t.spawn(EntityTypes.ENDERMAN,new BlockPos(6,1,4));mob.setNoAi(true);mob.setCarriedBlock(Blocks.DIRT.defaultBlockState());var goal=endermanGoal(mob,"EndermanLeaveBlockGoal");
        for(int n=0;n<512;n++)goal.tick();t.assertTrue(mob.getCarriedBlock()!=null,"A denied placement never consumes the carried block");
        ProtectionService.flags(i.owner,i.region.id(),i.region.revision(),ProtectionData.ALL^ProtectionData.MOBS);for(int n=0;n<512&&mob.getCarriedBlock()!=null;n++)goal.tick();t.assertTrue(mob.getCarriedBlock()==null,"Vanilla placement resumes after the owner disables creature protection");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void authorizedCardStillOpensTheDoorWithoutGrantingLandPermissions(GameTestHelper t){
        var i=setup(t);var corePos=new BlockPos(4,1,3);var doorPos=new BlockPos(6,1,3);var readerPos=new BlockPos(7,1,3);
        t.setBlock(corePos,UmbralContent.CORE);var core=t.getBlockEntity(corePos,DeviceBlockEntity.class);core.initializeOwner(i.owner.getUUID(),"Núcleo");
        t.setBlock(doorPos,UmbralContent.DOOR);t.setBlock(doorPos.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));var door=t.getBlockEntity(doorPos,DeviceBlockEntity.class);door.initializeOwner(i.owner.getUUID(),"Puerta");door.bind(i.owner.getUUID(),core);
        t.setBlock(readerPos,UmbralContent.READER);var reader=t.getBlockEntity(readerPos,DeviceBlockEntity.class);reader.initializeOwner(i.owner.getUUID(),"Lector");reader.bind(i.owner.getUUID(),core);reader.targetDoor(i.owner.getUUID(),door);
        var grant=dev.umbral.access.AccessRegistry.get(t.getLevel()).issue(i.owner.getUUID(),core.link(),"Visita");var card=new ItemStack(UmbralContent.ACCESS_CARD);card.set(UmbralContent.CARD_ID,grant.id());
        var previous=ProtectionPolicy.enter(i.guest);try{t.assertTrue(dev.umbral.access.AccessService.present(i.guest,reader,card)==dev.umbral.access.AccessService.Result.GRANTED,"Card authorization survives claim enforcement");}finally{ProtectionPolicy.leave(previous);}
        t.assertTrue(t.getBlockState(doorPos).getValue(SecurityDoorBlock.OPEN)&&t.getBlockState(doorPos.above()).getValue(SecurityDoorBlock.OPEN),"Both real door halves opened");
        t.assertFalse(ProtectionPolicy.permits(t.getLevel(),t.absolutePos(doorPos),i.guest.getUUID(),ProtectionData.BUILD),"The card did not grant structural access");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void bucketCannotDrainAProtectedWaterloggedBlockOrLoseItsContentsOnDeniedPlacement(GameTestHelper t){
        var i=setup(t);grants(i,i.region,ProtectionData.USE);var pos=t.absolutePos(new BlockPos(5,1,3));t.getLevel().setBlockAndUpdate(pos,Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true));
        var bucket=(BucketItem)Items.WATER_BUCKET;t.assertFalse(bucket.emptyContents(i.guest,t.getLevel(),pos.above(),null),"A use-only guest cannot empty a bucket inside the claim");
        i.guest.setPos(Vec3.atBottomCenterOf(t.absolutePos(new BlockPos(5,1,1))));var delta=Vec3.atCenterOf(pos).add(0,-.25,0).subtract(i.guest.getEyePosition());i.guest.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));i.guest.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance())));var empty=new ItemStack(Items.BUCKET);i.guest.setItemInHand(InteractionHand.MAIN_HAND,empty);i.guest.gameMode.useItem(i.guest,t.getLevel(),empty,InteractionHand.MAIN_HAND);
        t.assertTrue(i.guest.getMainHandItem().is(Items.BUCKET),"Actual empty-bucket use cannot create a filled duplicate");
        t.assertTrue(t.getLevel().getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED)&&t.getLevel().getFluidState(pos.above()).isEmpty(),"Neither the existing source nor the destination was altered");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void revokedInventoryPermissionAlsoStopsMotaAtAnAlreadySealedStockChest(GameTestHelper t){
        var i=setup(t);grants(i,i.region,ProtectionData.INVENTORY);var corePos=new BlockPos(2,1,3);var dockPos=new BlockPos(3,1,3);var chestPos=t.absolutePos(new BlockPos(5,1,3));
        t.setBlock(corePos,UmbralContent.CORE);var core=t.getBlockEntity(corePos,DeviceBlockEntity.class);core.initializeOwner(i.guest.getUUID(),"Núcleo visita");t.setBlock(dockPos,UmbralContent.DOCK);var dock=t.getBlockEntity(dockPos,DeviceBlockEntity.class);dock.initializeOwner(i.guest.getUUID(),"MOTA visita");dock.bind(i.guest.getUUID(),core);t.setBlock(new BlockPos(5,1,3),Blocks.CHEST);
        var reference=MotaService.seal(i.guest,chestPos,core.link());t.assertTrue(reference!=null&&MotaService.stock(t.getLevel(),dock,reference)!=null,"Inventory grant permits sealing and reading real stock");
        grants(i,i.data.region(i.region.id()),0);t.assertTrue(MotaService.stock(t.getLevel(),dock,reference)==null,"Existing physical seals cannot bypass a revoked territorial grant");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void outsidersAndCreaturesCannotTrampleFarmlandButTheOwnerCan(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(5,1,3);t.setBlock(pos,Blocks.FARMLAND);var state=t.getBlockState(pos);var farm=(FarmlandBlock)state.getBlock();var abs=t.absolutePos(pos);
        farm.turnToBaseBlock(i.guest,state,t.getLevel(),abs);t.assertTrue(t.getBlockState(pos).is(Blocks.FARMLAND),"Visitor cannot trample protected farmland");var cow=t.spawn(EntityTypes.COW,new BlockPos(6,1,3));cow.setNoAi(true);farm.turnToBaseBlock(cow,state,t.getLevel(),abs);t.assertTrue(t.getBlockState(pos).is(Blocks.FARMLAND),"Creature rule preserves farmland");
        farm.turnToBaseBlock(i.owner,state,t.getLevel(),abs);t.assertTrue(t.getBlockState(pos).is(Blocks.DIRT),"Owner can intentionally change the same block");t.succeed();
    }
    private void crushEgg(TurtleEggBlock egg,Level level,BlockPos pos,net.minecraft.world.level.block.state.BlockState state,Entity entity){try{var method=TurtleEggBlock.class.getDeclaredMethod("destroyEgg",Level.class,net.minecraft.world.level.block.state.BlockState.class,BlockPos.class,Entity.class,int.class);method.setAccessible(true);method.invoke(egg,level,state,pos,entity,1);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    @GameTest(structure=STRUCTURE)
    public void outsidersCannotCrushProtectedEggsButVanillaOwnerBehaviorRemains(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(5,1,3);t.setBlock(pos,Blocks.TURTLE_EGG);var egg=(TurtleEggBlock)Blocks.TURTLE_EGG;
        crushEgg(egg,t.getLevel(),t.absolutePos(pos),t.getBlockState(pos),i.guest);t.assertTrue(t.getBlockState(pos).is(Blocks.TURTLE_EGG),"Visitor steps never crush an egg");
        crushEgg(egg,t.getLevel(),t.absolutePos(pos),t.getBlockState(pos),i.owner);t.assertTrue(t.getBlockState(pos).isAir(),"Owner steps follow the actual vanilla break path");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anOutsidersBurningArrowCannotPrimeProtectedTnt(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(5,1,3);t.setBlock(pos,Blocks.TNT);var abs=t.absolutePos(pos);var arrow=new net.minecraft.world.entity.projectile.arrow.Arrow(t.getLevel(),i.guest,new ItemStack(Items.ARROW),new ItemStack(Items.BOW));arrow.igniteForSeconds(5);var hit=new BlockHitResult(Vec3.atCenterOf(abs),Direction.NORTH,abs,false);
        var state=t.getBlockState(pos);state.onProjectileHit(t.getLevel(),state,hit,arrow);t.assertTrue(t.getBlockState(pos).is(Blocks.TNT),"Foreign projectile cannot replace the protected block with primed TNT");
        arrow.setOwner(i.owner);state.onProjectileHit(t.getLevel(),state,hit,arrow);t.assertTrue(t.getBlockState(pos).isAir(),"Owner projectile retains ordinary TNT behavior");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anExpiredSelectionCannotBypassProtectedInventoryChecks(GameTestHelper t){
        var i=setup(t);var chest=new BlockPos(5,1,3);t.setBlock(chest,Blocks.CHEST);i.guest.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));
        ProtectionSelections.begin(i.guest,new ProtectionSelections.Draft(ProtectionData.NONE,0,ProtectionData.NONE,"",new ProtectionShape(),false,ProtectionSelections.Mode.BOX,List.of(),0,8));i.guest.setHealth(0);
        var result=net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(i.guest,t.getLevel(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(t.absolutePos(chest)),Direction.NORTH,t.absolutePos(chest),false));
        t.assertTrue(result==InteractionResult.FAIL&&!ProtectionSelections.active(i.guest.getUUID()),"A discarded selection falls through to the normal claim permission check");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void aLandOwnerCanRemoveEquipmentLeftByAnAuthorizedBuilder(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(5,1,3);t.setBlock(pos,UmbralContent.CAMERA);var device=t.getBlockEntity(pos,DeviceBlockEntity.class);device.initializeOwner(i.guest.getUUID(),"Equipo del constructor");
        var event=net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker();var abs=t.absolutePos(pos);
        t.assertTrue(event.beforeBlockBreak(t.getLevel(),i.owner,abs,t.getBlockState(pos),device),"Land owner can reclaim space from a builder's equipment without taking its console ownership");
        t.assertFalse(event.beforeBlockBreak(t.getLevel(),i.guest,abs,t.getBlockState(pos),device),"Equipment ownership cannot bypass revoked land construction permission");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void memberChangesResolveRealNamesAndRejectUnknownProfilesOrOldRevisions(GameTestHelper t){
        var i=setup(t);t.assertTrue(ProtectionService.member(i.owner,i.region.id(),i.region.revision(),UUID.randomUUID(),"Inventado",7,false).equals("player_missing"),"Unknown unauthenticated profiles cannot be granted access by inventing a name");grants(i,i.region,ProtectionData.USE);var current=i.data.region(i.region.id());
        t.assertTrue(ProtectionService.member(i.owner,current.id(),current.revision(),i.guest.getUUID(),"Suplantado",ProtectionData.INVENTORY,false).equals("permissions"),"An existing authenticated grant can be edited while its player is offline");var updated=i.data.region(current.id());
        t.assertTrue(updated.members().getFirst().name().equals("Visita")&&updated.members().getFirst().permissions()==ProtectionData.INVENTORY,"Client-supplied name is ignored, independent inventory permission is saved");
        t.assertTrue(ProtectionService.member(i.owner,current.id(),current.revision(),i.guest.getUUID(),"",7,true).equals("changed"),"A stale screen cannot revoke a newer grant");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void outsideRedstoneCannotCreatePrimedCopiesOfProtectedTnt(GameTestHelper t){
        var i=setup(t,new BlockPos(5,1,4),new BlockPos(5,2,4));var target=new BlockPos(5,1,4);var power=new BlockPos(4,1,4);t.setBlock(target,Blocks.TNT);
        var previous=ProtectionPolicy.enter(i.guest);try{t.setBlock(power,Blocks.REDSTONE_BLOCK);}finally{ProtectionPolicy.leave(previous);}
        var bounds=new AABB(t.absolutePos(target)).inflate(3);t.assertTrue(t.getBlockState(target).is(Blocks.TNT)&&t.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class,bounds).isEmpty(),"Synchronous outside power cannot spawn TNT before a rejected block removal");
        t.setBlock(power,Blocks.AIR);t.setBlock(power,Blocks.REDSTONE_BLOCK);t.assertTrue(t.getBlockState(target).is(Blocks.TNT),"Unattributed redstone also respects explosion protection");
        ProtectionService.flags(i.owner,i.region.id(),i.region.revision(),ProtectionData.ALL^ProtectionData.EXPLOSIONS);t.setBlock(power,Blocks.AIR);t.setBlock(power,Blocks.REDSTONE_BLOCK);
        t.assertTrue(t.getBlockState(target).isAir()&&t.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class,bounds).size()==1,"Disabling the rule restores vanilla priming exactly once");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void theUmbralDoorAlsoChecksItsUpperHalfBeforeConsumingAnItem(GameTestHelper t){
        var i=setup(t,new BlockPos(5,2,4),new BlockPos(5,3,4));var item=new ItemStack(UmbralContent.DOOR,2);use(i.guest,t.absolutePos(new BlockPos(5,0,4)),Direction.UP,item);
        t.assertTrue(t.getBlockState(new BlockPos(5,1,4)).isAir()&&t.getBlockState(new BlockPos(5,2,4)).isAir()&&item.getCount()==2,"Custom two-part security door is rejected atomically at a foreign upper boundary");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void claimingOnlyTheUpperHalfCannotCaptureAnotherPlayersSecurityDoor(GameTestHelper t){
        var i=setup(t);var pos=new BlockPos(9,1,4);t.setBlock(pos,UmbralContent.DOOR);t.setBlock(pos.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));var door=t.getBlockEntity(pos,DeviceBlockEntity.class);door.initializeOwner(i.guest.getUUID(),"Ajena");
        var shape=i.region.shape().add(box(t,pos.above(),pos.above()));t.assertTrue(ProtectionService.apply(i.owner,i.region.id(),i.region.revision(),ProtectionData.NONE,shape,"Casa").equals("foreign_device"),"Both door halves refer to the actual lower identity when expanding land");t.succeed();
    }
}
