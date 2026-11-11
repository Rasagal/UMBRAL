// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.access.AccessService;
import dev.umbral.filter.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.GameType;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Allocation reductions must preserve traversal budgets, components and opening-time authority. */
public final class FilterPerformanceGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final List<FilterRule> TNT=List.of(new FilterRule("minecraft:tnt",1));
    private static ServerPlayer player(GameTestHelper t){return (ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);}
    private static ItemStack box(ItemStack child){var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(child)));return box;}
    private static ItemStack nested(int levels){ItemStack next=new ItemStack(Items.TNT);for(int n=0;n<levels;n++)next=box(next);return next;}
    private static void count(GameTestHelper t,Inspection.Result result,long expected,String reason){t.assertTrue(result.outcome()==Inspection.Outcome.MATCH&&result.matches().equals(List.of(new Inspection.Match("minecraft:tnt",expected))),reason);}

    @GameTest(structure=STRUCTURE) public void aNestedComponentSwapCannotReuseAnApprovalInTheSameTick(GameTestHelper t){
        var fixture=new FilterGameTests();var i=fixture.setup(t);fixture.access(t,i);var card=fixture.pass(t,i);var carried=new ItemStack(Items.SHULKER_BOX);i.visitor().getInventory().setItem(0,carried);
        long now=t.getLevel().getGameTime();t.assertTrue(AccessService.present(i.visitor(),i.reader(),card)==AccessService.Result.GRANTED,"Real clear inventory and pass receive approval");
        carried.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.TNT,3))));
        t.assertTrue(t.getLevel().getGameTime()==now&&AccessService.present(i.visitor(),i.reader(),card)==AccessService.Result.SCAN_BLOCKED,"A component change on the same carried stack is reinspected at the exact next card request");
        count(t,FilterService.inspect(i.filter(),i.visitor(),false),3,"The authoritative result contains the newly hidden items");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void bothComponentsAndStackedParentsAreCountedWithoutMutation(GameTestHelper t){
        var p=player(t);var inner=new ItemStack(Items.STICK,4);inner.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.TNT,5))));
        var outer=new ItemStack(Items.APPLE,3);outer.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(inner)));outer.set(DataComponents.BUNDLE_CONTENTS,new BundleContents(List.of(new ItemStackTemplate(Items.TNT,2))));var before=outer.copy();p.getInventory().setItem(0,outer);
        count(t,FilterInventory.inspect(p,TNT),66,"Both components on an arbitrary item retain recursive parent multiplication");
        t.assertTrue(ItemStack.isSameItemSameComponents(before,outer)&&outer.getCount()==3,"Read-only traversal changes neither counts nor nested component data");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void depthEightIsAllowedAndDepthNineFailsEvenWithoutApplicableRules(GameTestHelper t){
        var p=player(t);p.getInventory().setItem(0,nested(8));count(t,FilterInventory.inspect(p,TNT),1,"The depth-eight leaf remains legal");
        p.getInventory().setItem(0,nested(9));t.assertTrue(FilterInventory.inspect(p,List.of()).outcome()==Inspection.Outcome.TOO_COMPLEX,"No-rule scans still enforce the depth budget");
        var exemption=new FilterRule("minecraft:tnt",1,List.of(new FilterRule.Exemption(p.getUUID(),"Visitor")));
        t.assertTrue(FilterInventory.inspect(p,List.of(exemption)).outcome()==Inspection.Outcome.TOO_COMPLEX,"Per-rule exemption cannot skip complexity validation");t.succeed();
    }
    private static ItemStack tree(int lastLeaves){
        var branches=new ArrayList<ItemStack>();for(int n=0;n<16;n++){var branch=new ItemStack(Items.STICK);branch.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(Collections.nCopies(n==15?lastLeaves:255,new ItemStack(Items.TNT))));branches.add(branch);}
        var root=new ItemStack(Items.APPLE);root.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(branches));return root;
    }
    @GameTest(structure=STRUCTURE) public void exactVisitedStackBoundaryIsUnchanged(GameTestHelper t){
        var p=player(t);p.getInventory().setItem(0,tree(254));count(t,FilterInventory.inspect(p,TNT),4079,"4096 visited nonempty stacks are legal");
        p.getInventory().setItem(0,tree(255));t.assertTrue(FilterInventory.inspect(p,TNT).outcome()==Inspection.Outcome.TOO_COMPLEX,"The 4097th visited stack still fails closed");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void pendingEmptyRootsStillCountAgainstTheWideQueueBudget(GameTestHelper t){
        var p=player(t);var root=new ItemStack(Items.STICK);p.getInventory().setItem(0,root);
        root.set(DataComponents.BUNDLE_CONTENTS,new BundleContents(Collections.nCopies(4056,new ItemStackTemplate(Items.TNT))));count(t,FilterInventory.inspect(p,TNT),4056,"4056 children plus forty pending root slots fit the existing queue bound");
        root.set(DataComponents.BUNDLE_CONTENTS,new BundleContents(Collections.nCopies(4057,new ItemStackTemplate(Items.TNT))));t.assertTrue(FilterInventory.inspect(p,TNT).outcome()==Inspection.Outcome.TOO_COMPLEX,"Queue overflow remains fail-closed even before the visited-stack limit");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void customChildComponentsRetainVanillaStrictValidation(GameTestHelper t){
        var p=player(t);var invalid=new ItemStack(Items.TNT);invalid.set(DataComponents.MAX_DAMAGE,100);invalid.set(DataComponents.MAX_STACK_SIZE,64);
        var oversized=new ItemStack(Items.TNT,65);var named=new ItemStack(Items.TNT,3);named.set(DataComponents.CUSTOM_NAME,Component.literal("Evidence"));
        var root=new ItemStack(Items.STICK);root.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(invalid,oversized,named)));p.getInventory().setItem(0,root);
        count(t,FilterInventory.inspect(p,TNT),3,"Invalid damage/stack combinations and over-limit child stacks retain vanilla copy validation; valid custom data is counted");
        t.assertTrue(named.get(DataComponents.CUSTOM_NAME).getString().equals("Evidence")&&oversized.getCount()==65,"Inspection never repairs or edits source component data");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void tagThresholdsCollectEveryItemAndEvaluateEachCandidateOnce(GameTestHelper t){
        var calls=new AtomicInteger();var counts=Map.of("minecraft:iron_sword",1L,"minecraft:golden_sword",1L,"minecraft:tnt",0L);var rule=new FilterRule("#test:swords",2);
        var r=Inspection.evaluate(UUID.randomUUID(),counts,List.of(rule),(tag,item)->{calls.incrementAndGet();return tag.equals("test:swords")&&item.endsWith("_sword");});
        t.assertTrue(r.matches().equals(List.of(new Inspection.Match("minecraft:golden_sword",1),new Inspection.Match("minecraft:iron_sword",1))),"A combined threshold reports all matching item identities in sorted order");
        t.assertTrue(calls.get()==2,"Each positive candidate is tagged once, without a second matching pass");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void largeCountsOverlapsExceptionsAndOutputLimitsRemainStable(GameTestHelper t){
        var p=player(t);ItemStack next=new ItemStack(Items.TNT,64);for(int n=0;n<4;n++){var parent=new ItemStack(Items.STICK,64);parent.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(next)));next=parent;}p.getInventory().setItem(0,next);
        count(t,FilterInventory.inspect(p,TNT),Inspection.MAX_COUNT,"Recursive multiplication saturates at the original count bound");
        var counts=new HashMap<String,Long>();for(int n=24;n>=0;n--)counts.put("test:item"+String.format(Locale.ROOT,"%02d",n),Inspection.MAX_COUNT+100);
        var exempt=new FilterRule("#test:all",1,List.of(new FilterRule.Exemption(p.getUUID(),"Visitor")));var exact=new FilterRule("test:item24",1);
        var direct=Inspection.evaluate(p.getUUID(),counts,List.of(exempt,exact),(tag,item)->true);t.assertTrue(direct.matches().equals(List.of(new Inspection.Match("test:item24",Inspection.MAX_COUNT))),"Tag exceptions do not bypass an independent exact rule");
        var all=Inspection.evaluate(p.getUUID(),counts,List.of(new FilterRule("#test:all",1),exact),(tag,item)->true);
        t.assertTrue(all.matches().size()==16&&all.matches().getFirst().item().equals("test:item00")&&all.matches().getLast().item().equals("test:item15")&&all.matches().stream().allMatch(m->m.count()==Inspection.MAX_COUNT),"Overlapping rules remain unique, sorted, capped at sixteen and count-saturated");
        boolean immutable=false;try{all.matches().clear();}catch(UnsupportedOperationException expected){immutable=true;}t.assertTrue(immutable,"A shared or newly allocated result never exposes mutable matches");t.succeed();
    }
}
