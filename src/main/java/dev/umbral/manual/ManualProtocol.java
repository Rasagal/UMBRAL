// SPDX-License-Identifier: MPL-2.0

package dev.umbral.manual;

import dev.umbral.UmbralContent;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import java.util.*;

/** Public, read-only recipe lookup. Ingredient layouts come from the server's active recipe manager. */
public final class ManualProtocol {
    public record Open(ManualCatalog.Entry entry) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(UmbralContent.id("manual_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=new StreamCodec<>(){public Open decode(RegistryFriendlyByteBuf b){return new Open(b.readEnum(ManualCatalog.Entry.class));}public void encode(RegistryFriendlyByteBuf b,Open v){b.writeEnum(v.entry);}};
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(ManualCatalog.Entry entry,long nonce) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(UmbralContent.id("manual_recipe_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>(){public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readEnum(ManualCatalog.Entry.class),b.readLong());}public void encode(RegistryFriendlyByteBuf b,Request v){b.writeEnum(v.entry);b.writeLong(v.nonce);}};
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Recipe(ManualCatalog.Entry entry,long nonce,Optional<RecipeDisplay> display) implements CustomPacketPayload {
        public static final Type<Recipe> TYPE=new Type<>(UmbralContent.id("manual_recipe"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Recipe> CODEC=new StreamCodec<>(){
            public Recipe decode(RegistryFriendlyByteBuf b){var e=b.readEnum(ManualCatalog.Entry.class);long n=b.readLong();return new Recipe(e,n,b.readBoolean()?Optional.of(RecipeDisplay.STREAM_CODEC.decode(b)):Optional.empty());}
            public void encode(RegistryFriendlyByteBuf b,Recipe v){b.writeEnum(v.entry);b.writeLong(v.nonce);b.writeBoolean(v.display.isPresent());v.display.ifPresent(d->RecipeDisplay.STREAM_CODEC.encode(b,d));}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final Map<UUID,Long> LAST=new HashMap<>();
    public static Recipe recipe(ServerPlayer p,Request r){
        if(!p.isAlive())return new Recipe(r.entry,r.nonce,Optional.empty());
        var holder=p.level().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,UmbralContent.id(r.entry.recipe)));
        var display=holder.flatMap(h->h.value().display().stream().filter(d->d.isEnabled(p.level().enabledFeatures())).findFirst());
        return new Recipe(r.entry,r.nonce,display);
    }
    public static void open(ServerPlayer p,ManualCatalog.Entry entry){if(p.isAlive())ServerPlayNetworking.send(p,new Open(entry));}
    public static void initialize(){
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE,Open.CODEC);PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Recipe.TYPE,Recipe.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(r,c)->{long now=c.server().overworld().getGameTime();var last=LAST.get(c.player().getUUID());if(last!=null&&now>=last&&now-last<10)return;LAST.put(c.player().getUUID(),now);ServerPlayNetworking.send(c.player(),recipe(c.player(),r));});
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->LAST.remove(h.getPlayer().getUUID()));
    }
    public static void clear(){LAST.clear();}
    private ManualProtocol(){}
}
