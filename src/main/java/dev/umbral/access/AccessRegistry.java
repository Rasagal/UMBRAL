// SPDX-License-Identifier: MPL-2.0

package dev.umbral.access;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umbral.UmbralContent;
import dev.umbral.network.NetworkLink;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.*;

/** Server-owned bearer grants. A card carries only an opaque identifier, never authority fields. */
public final class AccessRegistry extends SavedData {
    public static final int MAX_PASSES = 64;
    public static final int MAX_TOTAL = 4096;
    public record Grant(UUID id, UUID owner, NetworkLink link, String label, long serial) {
        public Grant { label = label.substring(0, Math.min(32, label.length())); }
        public static final Codec<Grant> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Grant::id), UUIDUtil.CODEC.fieldOf("owner").forGetter(Grant::owner),
                NetworkLink.CODEC.fieldOf("link").forGetter(Grant::link), Codec.STRING.fieldOf("label").forGetter(Grant::label),
                Codec.LONG.fieldOf("serial").forGetter(Grant::serial)
        ).apply(i, Grant::new));
    }
    public static final Codec<AccessRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 1).fieldOf("schema").forGetter(r -> 1),
            Grant.CODEC.listOf().fieldOf("grants").forGetter(r -> List.copyOf(r.grants.values())),
            Codec.LONG.fieldOf("serial").forGetter(r -> r.serial)
    ).apply(i, (schema, grants, serial) -> new AccessRegistry(grants, serial)));
    public static final SavedDataType<AccessRegistry> TYPE = new SavedDataType<>(
            UmbralContent.id("access_registry"), AccessRegistry::new, CODEC, DataFixTypes.LEVEL);
    private final Map<UUID, Grant> grants = new HashMap<>();
    private long serial;
    public AccessRegistry() { }
    private AccessRegistry(List<Grant> saved, long serial) {
        for (Grant grant : saved) grants.put(grant.id, grant);
        this.serial = serial;
    }
    public static AccessRegistry get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }
    public List<Grant> list(UUID owner, NetworkLink link) {
        return grants.values().stream().filter(g -> g.owner.equals(owner) && g.link.equals(link))
                .sorted(Comparator.comparingLong(Grant::serial)).limit(MAX_PASSES).toList();
    }
    public boolean hasRoom(UUID owner, NetworkLink link) {
        return link != null && grants.size() < MAX_TOTAL && list(owner, link).size() < MAX_PASSES;
    }
    public Grant issue(UUID owner, NetworkLink link, String label) {
        if (owner == null || !hasRoom(owner, link)) return null;
        Grant grant = new Grant(UUID.randomUUID(), owner, link, label, ++serial);
        grants.put(grant.id, grant); setDirty(); return grant;
    }
    public boolean accepts(UUID id, UUID owner, NetworkLink link) {
        Grant grant = grants.get(id);
        return grant != null && grant.owner.equals(owner) && grant.link.equals(link);
    }
    public boolean revoke(UUID id, UUID owner, NetworkLink link) {
        if (!accepts(id, owner, link)) return false;
        grants.remove(id); setDirty(); return true;
    }
}
