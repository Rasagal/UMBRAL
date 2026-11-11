// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.network.NetworkLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

/** Per-dimension discovery metadata. Authorization always checks the actual devices again. */
public final class CameraDirectory extends SavedData {
    public static final int MAX_CAMERAS = 64;

    public record Entry(BlockPos position, UUID owner, NetworkLink link, String label) {
        public Entry { position = position.immutable(); label = label.substring(0, Math.min(48, label.length())); }
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("position").forGetter(Entry::position),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Entry::owner),
                NetworkLink.CODEC.fieldOf("link").forGetter(Entry::link),
                Codec.STRING.fieldOf("label").forGetter(Entry::label)
        ).apply(i, Entry::new));
    }

    public static final Codec<CameraDirectory> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 1).fieldOf("schema").forGetter(d -> 1),
            Entry.CODEC.listOf().fieldOf("cameras").forGetter(d -> List.copyOf(d.entries.values()))
    ).apply(i, (schema, entries) -> new CameraDirectory(entries)));
    public static final SavedDataType<CameraDirectory> TYPE = new SavedDataType<>(
            UmbralContent.id("camera_directory"), CameraDirectory::new, CODEC, DataFixTypes.LEVEL);
    private final Map<BlockPos, Entry> entries = new HashMap<>();
    private static final Comparator<Entry> ORDER=Comparator.comparing(Entry::label).thenComparingLong(e->e.position.asLong());
    private static final class Bucket {
        final NavigableSet<Entry> ordered=new TreeSet<>(ORDER);
        List<Entry> view;
    }
    private static final class Network {
        final Map<UUID,Bucket> owners=new HashMap<>();
        int size;
    }
    private final Map<NetworkLink,Network> networks=new HashMap<>();

    public CameraDirectory() { }
    private CameraDirectory(List<Entry> saved) { for (Entry entry : saved) put(entry); }
    public static CameraDirectory get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }

    public boolean hasRoom(NetworkLink link, BlockPos position) {
        var network=networks.get(link);var old=entries.get(position);
        return (network==null?0:network.size)-(old!=null&&old.link.equals(link)?1:0)<MAX_CAMERAS;
    }

    public void remember(DeviceBlockEntity camera) {
        if (camera.kind() != DeviceKind.CAMERA) return;
        BlockPos position = camera.getBlockPos();
        if (camera.owner() == null || camera.link() == null) { remove(position); return; }
        var old=entries.get(position);
        if(old!=null&&old.owner.equals(camera.owner())&&old.link.equals(camera.link())&&old.label.equals(camera.label()))return;
        Entry replacement = new Entry(position, camera.owner(), camera.link(), camera.label());
        put(replacement);setDirty();
    }

    private void put(Entry entry){
        var old=entries.put(entry.position,entry);if(old!=null)unindex(old);
        var network=networks.computeIfAbsent(entry.link,k->new Network());network.size++;
        var bucket=network.owners.computeIfAbsent(entry.owner,k->new Bucket());bucket.ordered.add(entry);bucket.view=null;
    }
    private void unindex(Entry entry){
        var network=networks.get(entry.link);var bucket=network.owners.get(entry.owner);bucket.ordered.remove(entry);bucket.view=null;
        if(bucket.ordered.isEmpty())network.owners.remove(entry.owner);if(--network.size==0)networks.remove(entry.link);
    }
    public void remove(BlockPos position) { var old=entries.remove(position);if(old!=null){unindex(old);setDirty();} }

    public boolean knows(UUID owner, NetworkLink link, BlockPos position) {
        Entry entry = entries.get(position);
        return entry != null && entry.owner.equals(owner) && entry.link.equals(link);
    }

    public List<Entry> cameras(UUID owner, NetworkLink link) {
        var network=networks.get(link);var bucket=network==null?null:network.owners.get(owner);if(bucket==null)return List.of();
        if(bucket.view==null)bucket.view=bucket.ordered.stream().limit(MAX_CAMERAS).toList();return bucket.view;
    }
}
