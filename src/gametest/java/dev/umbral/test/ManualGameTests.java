// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.manual.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import java.util.*;

public final class ManualGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private ServerPlayer player(GameTestHelper t){return (ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);}
    private int books(ServerPlayer p){int count=0;for(int n=0;n<p.getInventory().getContainerSize();n++){var stack=p.getInventory().getItem(n);if(stack.is(UmbralContent.FIELD_MANUAL))count+=stack.getCount();}return count;}
    @GameTest(structure=STRUCTURE)
    public void firstEntryGivesOneAndNeverDuplicatesAfterLossOrReconnect(GameTestHelper t){
        var p=player(t);ManualDelivery.joined(p);t.assertTrue(books(p)==1&&ManualDelivery.data(t.getLevel()).received(p.getUUID()),"First join grants and records one manual");
        ManualDelivery.joined(p);t.assertTrue(books(p)==1,"Repeated join grants nothing");p.getInventory().clearContent();ManualDelivery.joined(p);t.assertTrue(books(p)==0&&!ManualDelivery.data(t.getLevel()).waiting(p.getUUID()),"Losing a received book never renews the gift");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void fullInventoryWaitsAndNeverOverwritesOrDropsItems(GameTestHelper t){
        var p=player(t);for(int n=0;n<36;n++){var stack=new ItemStack(Items.IRON_INGOT,64);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Reserva "+n));p.getInventory().setItem(n,stack);}
        ManualDelivery.joined(p);t.assertTrue(books(p)==0&&ManualDelivery.data(t.getLevel()).waiting(p.getUUID()),"Full inventory leaves pending entitlement");for(int n=0;n<36;n++)t.assertTrue(p.getInventory().getItem(n).getCount()==64&&p.getInventory().getItem(n).getHoverName().getString().equals("Reserva "+n),"All real stacks and components preserved");
        p.getInventory().setItem(12,ItemStack.EMPTY);t.assertTrue(ManualDelivery.tryDeliver(p)&&p.getInventory().getItem(12).is(UmbralContent.FIELD_MANUAL),"A free main inventory slot completes the pending gift");t.assertFalse(ManualDelivery.tryDeliver(p),"Delivery is idempotent");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anExistingManualSatisfiesTheGiftWithoutAddingAnother(GameTestHelper t){
        var p=player(t);var stack=new ItemStack(UmbralContent.FIELD_MANUAL);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Mi guía"));p.getInventory().setItem(5,stack);ManualDelivery.joined(p);
        t.assertTrue(books(p)==1&&p.getInventory().getItem(5)==stack&&ManualDelivery.data(t.getLevel()).received(p.getUUID()),"Existing manual and name retained, gift recorded once");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void ineligiblePlayersWaitWithoutLosingTheirEntitlement(GameTestHelper t){
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SPECTATOR);ManualDelivery.joined(p);t.assertTrue(books(p)==0&&ManualDelivery.data(t.getLevel()).waiting(p.getUUID())&&!ManualDelivery.tryDeliver(p),"Spectator inventory remains untouched and entitlement pending");
        var dead=player(t);dead.setHealth(0);ManualDelivery.joined(dead);t.assertTrue(books(dead)==0&&ManualDelivery.data(t.getLevel()).waiting(dead.getUUID()),"Dead player keeps pending gift");dead.setHealth(20);t.assertTrue(ManualDelivery.tryDeliver(dead)&&books(dead)==1,"Becoming alive delivers exactly one");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void deliveredAndPendingPlayersSurviveSavedDataRoundTrip(GameTestHelper t){
        var p=player(t);ManualDelivery.joined(p);var pending=UUID.randomUUID();var ledger=ManualDelivery.data(t.getLevel());ledger.queue(pending);
        var restored=ManualDelivery.Ledger.CODEC.parse(NbtOps.INSTANCE,ManualDelivery.Ledger.CODEC.encodeStart(NbtOps.INSTANCE,ledger).getOrThrow()).getOrThrow();restored.queue(p.getUUID());
        t.assertTrue(restored.received(p.getUUID())&&!restored.waiting(p.getUUID())&&restored.waiting(pending),"World save retains UUID delivery, pending entitlement and deduplication");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void anotherPlayerReceivesAnIndependentCopy(GameTestHelper t){
        var a=player(t);var b=player(t);ManualDelivery.joined(a);ManualDelivery.joined(b);t.assertTrue(books(a)==1&&books(b)==1&&ManualDelivery.data(t.getLevel()).received(a.getUUID())&&ManualDelivery.data(t.getLevel()).received(b.getUUID()),"Entitlement belongs to player UUID, not server session");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void allChaptersResolveTheActualServerRecipeWithoutGrantingItems(GameTestHelper t){
        var p=player(t);p.getInventory().setItem(2,new ItemStack(Items.DIAMOND,7));var manager=t.getLevel().getServer().getRecipeManager();
        for(var e:ManualCatalog.Entry.values()){var response=ManualProtocol.recipe(p,new ManualProtocol.Request(e,93));var expected=manager.byKey(ResourceKey.create(Registries.RECIPE,UmbralContent.id(e.recipe))).orElseThrow().value().display().getFirst();t.assertTrue(response.entry()==e&&response.nonce()==93&&response.display().orElseThrow().equals(expected),"Manual display equals active recipe: "+e);}
        t.assertTrue(p.getInventory().getItem(2).getCount()==7&&books(p)==0,"Public help lookup cannot craft, charge or grant anything");t.succeed();
    }
    @GameTest(structure=STRUCTURE)
    public void replacementManualReallyCraftsFromBookAndCopperInAnyOrder(GameTestHelper t){
        var manager=t.getLevel().getServer().getRecipeManager();for(var pair:List.of(List.of(new ItemStack(Items.BOOK),new ItemStack(Items.COPPER_INGOT)),List.of(new ItemStack(Items.COPPER_INGOT),new ItemStack(Items.BOOK)))){
            var input=CraftingInput.of(2,1,pair);var recipe=manager.getRecipeFor(RecipeType.CRAFTING,input,t.getLevel()).orElseThrow();var result=recipe.value().assemble(input);t.assertTrue(result.is(UmbralContent.FIELD_MANUAL)&&result.getCount()==1,"Active shapeless recovery recipe produces one actual manual");}
        var invalid=CraftingInput.of(2,1,List.of(new ItemStack(Items.BOOK),new ItemStack(Items.IRON_INGOT)));t.assertFalse(manager.getRecipeFor(RecipeType.CRAFTING,invalid,t.getLevel()).map(r->r.value().assemble(invalid).is(UmbralContent.FIELD_MANUAL)).orElse(false),"Incorrect metal does not craft a manual");t.succeed();
    }
}
