// SPDX-License-Identifier: MPL-2.0

package dev.umbral.installation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.network.NetworkLink;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import java.util.*;

/** Discovery only: every reading and command must validate the loaded physical identity again. */
public final class InstallationDirectory extends SavedData {
    public static final int PER_NETWORK=128, TOTAL=4096;
    public record Entry(BlockPos position,UUID identity,UUID owner,NetworkLink link,DeviceKind kind,String label) {
        public Entry { position=position.immutable();label=label.substring(0,Math.min(label.length(),48)); }
        public static final Codec<Entry> CODEC=RecordCodecBuilder.create(i->i.group(
                BlockPos.CODEC.fieldOf("position").forGetter(Entry::position),UUIDUtil.CODEC.fieldOf("identity").forGetter(Entry::identity),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Entry::owner),NetworkLink.CODEC.fieldOf("link").forGetter(Entry::link),
                Codec.STRING.xmap(DeviceKind::valueOf,DeviceKind::name).fieldOf("kind").forGetter(Entry::kind),Codec.STRING.fieldOf("label").forGetter(Entry::label)
        ).apply(i,Entry::new));
    }
    public static final Codec<InstallationDirectory> CODEC=Entry.CODEC.listOf().xmap(InstallationDirectory::new,d->List.copyOf(d.entries.values()));
    public static final SavedDataType<InstallationDirectory> TYPE=new SavedDataType<>(UmbralContent.id("installation_directory"),InstallationDirectory::new,CODEC,DataFixTypes.LEVEL);
    private final Map<BlockPos,Entry> entries=new HashMap<>();
    private static final Comparator<Entry> ORDER=Comparator.comparing(Entry::label).thenComparingLong(e->e.position.asLong());
    private static final class Bucket {
        final NavigableSet<Entry> ordered=new TreeSet<>(ORDER);
        final EnumMap<DeviceKind,NavigableSet<Entry>> kinds=new EnumMap<>(DeviceKind.class);
        final EnumMap<DeviceKind,List<Entry>> typedViews=new EnumMap<>(DeviceKind.class);
        List<Entry> view;
        long revision;
        void changed(long revision){this.revision=revision;view=null;typedViews.clear();}
    }
    private static final class Network {
        final Map<UUID,Bucket> owners=new HashMap<>();
        int size;
    }
    private final Map<NetworkLink,Network> networks=new HashMap<>();
    private long revision;
    public InstallationDirectory(){}
    private InstallationDirectory(List<Entry> saved){saved.stream().limit(TOTAL).forEach(this::put);}
    public static InstallationDirectory get(ServerLevel level){return level.getDataStorage().computeIfAbsent(TYPE);}
    public boolean hasRoom(NetworkLink link,BlockPos pos){
        var old=entries.get(pos);var network=networks.get(link);
        int count=network==null?0:network.size;
        return (old!=null||entries.size()<TOTAL)&&count-(old!=null&&old.link.equals(link)?1:0)<PER_NETWORK;
    }
    /** Reserve no data: check a complete, small setup before binding its first device. */
    public boolean hasRoomFor(NetworkLink link,Collection<BlockPos> positions){
        var unique=new HashSet<>(positions);
        int global=entries.size(),count=networks.containsKey(link)?networks.get(link).size:0;
        for(var position:unique){var old=entries.get(position);if(old==null)global++;if(old==null||!old.link.equals(link))count++;}
        return global<=TOTAL&&count<=PER_NETWORK;
    }
    public void remember(DeviceBlockEntity d){
        if(d.kind()==DeviceKind.FENCE&&!dev.umbral.fence.FenceService.head(d)){remove(d.getBlockPos());return;}
        if(d.kind()==DeviceKind.FILTER&&!FilterBlock.base(d.getBlockState())){remove(d.getBlockPos());return;}
        if(d.owner()==null||d.link()==null){remove(d.getBlockPos());return;}
        var old=entries.get(d.getBlockPos());
        if(old!=null&&old.identity.equals(d.identity())&&old.owner.equals(d.owner())&&old.link.equals(d.link())&&old.kind==d.kind()&&old.label.equals(d.label()))return;
        if(!hasRoom(d.link(),d.getBlockPos()))return;
        var e=new Entry(d.getBlockPos(),d.identity(),d.owner(),d.link(),d.kind(),d.label());
        put(e);setDirty();
    }
    private void put(Entry entry){
        var old=entries.put(entry.position,entry);if(old!=null)unindex(old);
        var network=networks.computeIfAbsent(entry.link,k->new Network());network.size++;
        var bucket=network.owners.computeIfAbsent(entry.owner,k->new Bucket());bucket.ordered.add(entry);
        bucket.kinds.computeIfAbsent(entry.kind,k->new TreeSet<>(ORDER)).add(entry);bucket.changed(++revision);
    }
    private void unindex(Entry entry){
        var network=networks.get(entry.link);var bucket=network.owners.get(entry.owner);
        bucket.ordered.remove(entry);var kind=bucket.kinds.get(entry.kind);kind.remove(entry);if(kind.isEmpty())bucket.kinds.remove(entry.kind);
        bucket.changed(++revision);if(bucket.ordered.isEmpty())network.owners.remove(entry.owner);if(--network.size==0)networks.remove(entry.link);
    }
    private Bucket bucket(UUID owner,NetworkLink link){var network=networks.get(link);return network==null?null:network.owners.get(owner);}
    public void remove(BlockPos pos){var old=entries.remove(pos);if(old!=null){unindex(old);setDirty();}}
    public List<Entry> entries(UUID owner,NetworkLink link){
        var bucket=bucket(owner,link);if(bucket==null)return List.of();
        if(bucket.view==null)bucket.view=bucket.ordered.stream().limit(PER_NETWORK).toList();return bucket.view;
    }
    /** Typed discovery uses the same bounded, ordered installation view as the full list. */
    public List<Entry> entries(UUID owner,NetworkLink link,DeviceKind kind){
        var bucket=bucket(owner,link);if(bucket==null)return List.of();
        return bucket.typedViews.computeIfAbsent(kind,k->bucket.ordered.size()>PER_NETWORK?entries(owner,link).stream().filter(e->e.kind==k).toList():List.copyOf(bucket.kinds.getOrDefault(k,Collections.emptyNavigableSet())));
    }
    public long revision(UUID owner,NetworkLink link){var bucket=bucket(owner,link);return bucket==null?0:bucket.revision;}
    public static DeviceBlockEntity loaded(ServerLevel level,Entry e){
        if(!level.isLoaded(e.position)||!(level.getBlockEntity(e.position) instanceof DeviceBlockEntity d)||!d.identity().equals(e.identity)||!d.ownedBy(e.owner)||!Objects.equals(d.link(),e.link))return null;return d;
    }
}
