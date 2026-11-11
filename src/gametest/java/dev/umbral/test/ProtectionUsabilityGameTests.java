// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.protection.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class ProtectionUsabilityGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private ServerPlayer player(GameTestHelper t,int count){var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);p.setPos(Vec3.atBottomCenterOf(t.absolutePos(new BlockPos(5,1,4))));p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UmbralContent.CONFIGURATOR));if(count>0)p.getInventory().setItem(1,new ItemStack(UmbralContent.PROTECTION_MATRIX,count));return p;}
    private ProtectionShape shape(GameTestHelper t,int x0,int x1){var a=t.absolutePos(new BlockPos(x0,0,2));return ProtectionShape.rectangle(a.getX(),a.getY(),a.getZ(),a.getX()+x1-x0,a.getY()+15,a.getZ()+3);}
    private ProtectionData.Region first(GameTestHelper t,ServerPlayer p){return ProtectionData.get(t.getLevel()).owned(p.getUUID()).getFirst();}
    @GameTest(structure=STRUCTURE)
    public void oneConfirmationInstallsOnlyTheRealMinimumAndProtectsFutureBlocks(GameTestHelper t){
        var p=player(t,4);p.setGameMode(GameType.CREATIVE);var stock=p.getInventory().getItem(1);stock.set(DataComponents.CUSTOM_NAME,Component.literal("Lote de casa"));var area=shape(t,3,6);
        t.assertTrue(ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,area,"Casa",true,1).equals("applied"),"One server transaction installs and applies");
        var data=ProtectionData.get(t.getLevel());t.assertTrue(data.matrices(p.getUUID())==1&&stock.getCount()==3&&stock.getHoverName().getString().equals("Lote de casa"),"Consumes exactly one real matrix, retaining remaining components even in creative");
        var guest=player(t,0);var pos=t.absolutePos(new BlockPos(4,4,3));t.setBlock(new BlockPos(4,4,3),Blocks.OAK_PLANKS);t.assertFalse(ProtectionPolicy.permits(t.getLevel(),pos,guest.getUUID(),ProtectionData.BUILD),"Later construction inside the saved volume needs no second marking");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anInvalidOrForeignEditConsumesNoMatricesAndCreatesNoCapacity(GameTestHelper t){
        var owner=player(t,2);var guest=player(t,4);var area=shape(t,3,6);ProtectionService.apply(owner,ProtectionData.NONE,0,ProtectionData.NONE,area,"Casa",true,1);
        t.assertTrue(ProtectionService.apply(guest,ProtectionData.NONE,0,ProtectionData.NONE,area,"Invadida",true,4096).equals("foreign_overlap"),"Foreign overlap rejected before payment");
        var data=ProtectionData.get(t.getLevel());t.assertTrue(data.matrices(guest.getUUID())==0&&data.owned(guest.getUUID()).isEmpty()&&guest.getInventory().getItem(1).getCount()==4,"No item, capacity or land side effects");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void aChangedQuoteCannotSpendMoreThanThePlayerConfirmed(GameTestHelper t){
        var p=player(t,4);var area=shape(t,3,6);
        t.assertTrue(ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,area,"Casa",true,0).equals("quote_changed"),"A previous zero-material quote cannot authorize a new charge");
        t.assertTrue(ProtectionData.get(t.getLevel()).matrices(p.getUUID())==0&&p.getInventory().getItem(1).getCount()==4,"Quote rejection preserves all materials");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void missingPhysicalMatricesCannotBeBypassedByTheGuidedPacket(GameTestHelper t){
        var p=player(t,0);var r=new ProtectionProtocol.Request(ProtectionProtocol.Access.tool(p),21,ProtectionProtocol.Action.APPLY_WITH_MATRICES,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",ProtectionData.NONE,4096,0,16);
        t.assertTrue(ProtectionProtocol.handle(p,r).result().equals("need_matrices")&&ProtectionData.get(t.getLevel()).owned(p.getUUID()).isEmpty(),"Client quote cannot create materials or an active area");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void aForeignDeviceIsValidatedBeforeAnyMatrixTransfer(GameTestHelper t){
        var p=player(t,3);var other=player(t,0);var pos=new BlockPos(4,1,3);t.setBlock(pos,UmbralContent.CAMERA);t.getBlockEntity(pos,DeviceBlockEntity.class).initializeOwner(other.getUUID(),"Ajena");
        t.assertTrue(ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1).equals("foreign_device"),"A physically invalid claim fails before installation");
        t.assertTrue(ProtectionData.get(t.getLevel()).matrices(p.getUUID())==0&&p.getInventory().getItem(1).getCount()==3,"No reserve is purchased by a rejected claim");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void appendingOnePieceReusesCapacityAndRetainsAreaIdentityAndPermissions(GameTestHelper t){
        var p=player(t,4);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var data=ProtectionData.get(t.getLevel());var r=first(t,p);var guest=player(t,0);
        r=new ProtectionData.Region(r.id(),r.owner(),r.dimension(),r.label(),r.parent(),r.shape(),List.of(new ProtectionData.Member(guest.getUUID(),"Visita",ProtectionData.USE)),ProtectionData.FIRE,r.revision()+1);data.put(r);
        var enlarged=r.shape().add(shape(t,7,9));t.assertTrue(ProtectionService.apply(p,r.id(),r.revision(),ProtectionData.NONE,enlarged,"Casa",true,0).equals("applied"),"Only new section is selected and no new matrix is needed");var saved=data.region(r.id());
        t.assertTrue(saved.shape().contains(r.shape())&&saved.members().equals(r.members())&&saved.flags()==ProtectionData.FIRE&&data.matrices(p.getUUID())==1&&p.getInventory().getItem(1).getCount()==3,"Original shape, identity, permissions and materials retained");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void alreadyProtectedBuildingNeverProducesAnExpansionNotice(GameTestHelper t){
        var p=player(t,2);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var inside=t.absolutePos(new BlockPos(4,1,3));t.setBlock(new BlockPos(4,1,3),Blocks.BRICKS);ProtectionSuggestions.placed(p,inside);
        t.assertTrue(ProtectionSuggestions.hints(p).isEmpty(),"Building inside an active volume is automatically protected and produces no notice");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void outsideConstructionIsGroupedPrivatelyAndNoticeDisappearsAfterOneExpansion(GameTestHelper t){
        var p=player(t,2);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var r=first(t,p);var pos=t.absolutePos(new BlockPos(8,1,3));t.setBlock(new BlockPos(8,1,3),Blocks.BRICKS);for(int n=0;n<12;n++)ProtectionSuggestions.placed(p,pos);
        var hints=ProtectionSuggestions.hints(p);t.assertTrue(hints.size()==1&&hints.getFirst().blocks()==1&&ProtectionSuggestions.hints(player(t,0)).isEmpty(),"One factual notice per area, deduplicated and owner-private");
        t.assertTrue(ProtectionSuggestions.suggest(p,r.id()).equals("proposal"),"NEXO prepares a draft");var draft=ProtectionSelections.draft(p);t.assertTrue(ProtectionService.apply(p,r.id(),r.revision(),ProtectionData.NONE,draft.base(),r.label(),true,0).equals("applied"),"One explicit expansion applies the proposal");
        t.assertTrue(ProtectionSuggestions.hints(p).isEmpty()&&ProtectionSuggestions.suggest(p,r.id()).equals("no_suggestion"),"No repeated delimitation or notice after the addition is protected");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void temporaryBlocksAndDismissedWorksDoNotLeavePersistentNotices(GameTestHelper t){
        var p=player(t,2);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var r=first(t,p);var pos=t.absolutePos(new BlockPos(8,1,3));t.setBlock(new BlockPos(8,1,3),Blocks.BRICKS);ProtectionSuggestions.placed(p,pos);t.setBlock(new BlockPos(8,1,3),Blocks.AIR);
        t.assertTrue(ProtectionSuggestions.hints(p).isEmpty(),"Removed construction is not reported as needing protection");t.setBlock(new BlockPos(8,1,3),Blocks.BRICKS);ProtectionSuggestions.placed(p,pos);
        t.assertTrue(ProtectionSuggestions.ignore(p,r.id()).equals("suggestion_ignored")&&ProtectionSuggestions.hints(p).isEmpty(),"Dismissing a notice removes that pending construction only");t.assertTrue(ProtectionData.get(t.getLevel()).region(r.id()).shape().equals(r.shape()),"Ignoring cannot modify active protection");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void guidedExpansionUsesTheSavedShapeEvenIfTheClientSendsAnEmptyBaseOrCutFlag(GameTestHelper t){
        var p=player(t,2);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var r=first(t,p);
        var request=new ProtectionProtocol.Request(ProtectionProtocol.Access.tool(p),19,ProtectionProtocol.Action.BEGIN_AREA,r.id(),r.revision(),ProtectionData.NONE,new ProtectionShape(),r.label(),ProtectionData.NONE,1,r.shape().bounds().y0(),r.shape().bounds().y1());
        var answer=ProtectionProtocol.handle(p,request);var draft=answer.selection();
        t.assertTrue(answer.result().equals("marking_area")&&draft.base().equals(r.shape())&&!draft.cut()&&draft.mode()==ProtectionSelections.Mode.FOOTPRINT,"Guided marking is an addition to the authoritative saved area, never a second full outline or hidden cut");t.succeed();
    }

    @GameTest(structure=STRUCTURE)
    public void cancellingGuidedMarkingDiscardsOnlyTheDraftAndPreservesSavedLand(GameTestHelper t){
        var p=player(t,2);ProtectionService.apply(p,ProtectionData.NONE,0,ProtectionData.NONE,shape(t,3,6),"Casa",true,1);var r=first(t,p);
        ProtectionSelections.begin(p,new ProtectionSelections.Draft(r.id(),r.revision(),ProtectionData.NONE,r.label(),r.shape(),false,ProtectionSelections.Mode.FOOTPRINT,List.of(t.absolutePos(new BlockPos(7,0,3))),r.shape().bounds().y0(),r.shape().bounds().y1()));
        ProtectionSelections.cancel(p);t.assertTrue(!ProtectionSelections.active(p.getUUID())&&ProtectionSelections.draft(p).base().empty()&&first(t,p).shape().equals(r.shape())&&p.getInventory().getItem(1).getCount()==1,"Cancel requires no second marking and changes neither live protection nor resources");t.succeed();
    }

}
