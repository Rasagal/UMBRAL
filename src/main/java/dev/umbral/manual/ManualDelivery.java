// SPDX-License-Identifier: MPL-2.0

package dev.umbral.manual;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.Umbral;
import dev.umbral.UmbralContent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.util.datafix.DataFixTypes;
import java.nio.file.*;
import java.util.*;

/** One entitlement per player UUID and world/server, independent of death and dimension. */
public final class ManualDelivery {
    public static final class Ledger extends SavedData {
        private final Set<UUID> delivered=new HashSet<>(),pending=new HashSet<>();
        public static final Codec<Ledger> CODEC=RecordCodecBuilder.create(i->i.group(UUIDUtil.CODEC.listOf().fieldOf("delivered").forGetter(d->List.copyOf(d.delivered)),UUIDUtil.CODEC.listOf().optionalFieldOf("pending",List.of()).forGetter(d->List.copyOf(d.pending))).apply(i,Ledger::new));
        public static final SavedDataType<Ledger> TYPE=new SavedDataType<>(UmbralContent.id("manual_delivery"),Ledger::new,CODEC,DataFixTypes.LEVEL);
        public Ledger(){}
        private Ledger(List<UUID> delivered,List<UUID> pending){this.delivered.addAll(delivered);this.pending.addAll(pending);this.pending.removeAll(this.delivered);}
        public boolean received(UUID id){return delivered.contains(id);}
        public boolean waiting(UUID id){return pending.contains(id);}
        public void queue(UUID id){if(!delivered.contains(id)&&pending.add(id))setDirty();}
        private void finish(UUID id){pending.remove(id);delivered.add(id);setDirty();}
    }
    private static boolean giveOnFirstJoin=true;
    public static Ledger data(ServerLevel level){return level.getServer().overworld().getDataStorage().computeIfAbsent(Ledger.TYPE);}
    public static void joined(ServerPlayer p){if(!giveOnFirstJoin)return;data((ServerLevel)p.level()).queue(p.getUUID());tryDeliver(p);}
    public static boolean tryDeliver(ServerPlayer p){
        var ledger=data((ServerLevel)p.level());if(!giveOnFirstJoin||ledger.received(p.getUUID())||!ledger.waiting(p.getUUID())||!p.isAlive()||p.isSpectator())return false;
        for(int n=0;n<p.getInventory().getContainerSize();n++)if(p.getInventory().getItem(n).is(UmbralContent.FIELD_MANUAL)){ledger.finish(p.getUUID());return false;}
        // A full inventory keeps its entitlement pending. Nothing is dropped or overwritten.
        int slot=p.getInventory().getFreeSlot();if(slot<0)return false;
        p.getInventory().setItem(slot,new ItemStack(UmbralContent.FIELD_MANUAL));ledger.finish(p.getUUID());p.getInventory().setChanged();p.containerMenu.broadcastChanges();
        if(p.connection!=null)p.sendOverlayMessage(Component.translatable("manual.umbral.received"));return true;
    }
    private static void loadConfig(){
        Path path=FabricLoader.getInstance().getConfigDir().resolve("umbral-manual.json");
        try{if(!Files.exists(path)){Files.createDirectories(path.getParent());Files.writeString(path,"{\n  \"giveOnFirstJoin\": true\n}\n");}
            var json=com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();giveOnFirstJoin=!json.has("giveOnFirstJoin")||json.get("giveOnFirstJoin").getAsBoolean();
        }catch(Exception e){giveOnFirstJoin=true;Umbral.LOGGER.warn("UMBRAL: no se pudo leer umbral-manual.json; se usará la entrega inicial predeterminada");}
    }
    public static void initialize(){net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server->loadConfig());ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->joined(handler.getPlayer()));ServerTickEvents.END_SERVER_TICK.register(server->{if(!giveOnFirstJoin||server.overworld().getGameTime()%20!=0)return;for(var p:server.getPlayerList().getPlayers())if(data((ServerLevel)p.level()).waiting(p.getUUID()))tryDeliver(p);});}
    private ManualDelivery(){}
}
