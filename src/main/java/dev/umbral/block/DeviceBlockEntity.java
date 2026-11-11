// SPDX-License-Identifier: MPL-2.0

package dev.umbral.block;

import dev.umbral.UmbralContent;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.installation.InstallationDirectory;
import dev.umbral.safety.*;
import dev.umbral.logistics.*;
import dev.umbral.camera.CameraMotion;
import dev.umbral.camera.CameraGeometry;
import dev.umbral.access.DoorTarget;
import dev.umbral.access.DoorMotion;
import dev.umbral.turret.TurretState;
import dev.umbral.turret.TurretMotion;
import dev.umbral.turret.TurretService;
import dev.umbral.network.NetworkLink;
import dev.umbral.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

public final class DeviceBlockEntity extends BlockEntity {
    private UUID owner;
    private NetworkLink link;
    private String label = "";
    private UUID identity = UUID.randomUUID();
    private final dev.umbral.fence.FenceState fence=new dev.umbral.fence.FenceState();
    public dev.umbral.fence.FenceState fence(){return fence;}
    private final dev.umbral.airlock.AirlockState airlock=new dev.umbral.airlock.AirlockState();
    private dev.umbral.airlock.AirlockLink airlockLink;
    public dev.umbral.airlock.AirlockState airlock(){return airlock;}
    public dev.umbral.airlock.AirlockLink airlockLink(){return airlockLink;}
    public void airlockLink(dev.umbral.airlock.AirlockLink value){airlockLink=value;setChanged();}
    private DoorTarget doorTarget,filterGuard;
    private final dev.umbral.filter.FilterState filter=new dev.umbral.filter.FilterState();
    public dev.umbral.filter.FilterState filter(){return filter;}
    public DoorTarget filterGuard(){return filterGuard;}
    public void filterGuard(DoorTarget value){filterGuard=value;setChanged();}
    private long closeAt;
    private final CameraMotion cameraMotion = new CameraMotion();
    private long motionSent = Long.MIN_VALUE;
    private final DoorMotion doorMotion = new DoorMotion();
    private long doorSent = Long.MIN_VALUE;
    private long pendingHold = -1;
    private final TurretState turret;
    private long turretSent=Long.MIN_VALUE;
    private final SafetyState safety=new SafetyState();
    private final DockState dock=new DockState();
    public SafetyState safety(){return safety;}
    public DockState dock(){return dock;}

    public DeviceBlockEntity(BlockPos pos, BlockState state) {
        super(UmbralContent.DEVICE_ENTITY, pos, state);
        turret = new TurretState(state.getBlock() instanceof DeviceBlock d && d.kind()==DeviceKind.CENTELLA);
        if (state.getBlock() instanceof SecurityDoorBlock) doorMotion.snap(state.getValue(SecurityDoorBlock.OPEN));
    }

    public DeviceKind kind() { return ((DeviceBlock) getBlockState().getBlock()).kind(); }
    public UUID owner() { return owner; }
    public NetworkLink link() { return link; }
    public String label() { return label; }
    public boolean rename(UUID actor,String name) {
        if(!(level instanceof ServerLevel server)||!ownedBy(actor))return false;
        String clean=dev.umbral.installation.DeviceLabels.clean(name);
        if(clean.isEmpty())return false;
        label=clean;setChanged();updateDirectory();
        server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        return true;
    }
    public UUID identity() { return identity; }
    public CameraMotion cameraMotion() { return cameraMotion; }
    public DoorMotion doorMotion() { return doorMotion; }
    public TurretState turret() { return turret; }
    public void syncTurret() {
        if(kind().turret()&&level instanceof ServerLevel server&&turretSent!=turret.motion().revision()) {
            turretSent=turret.motion().revision();setChanged();
            server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
        }
    }
    public void resumeDoorHold(long now) {
        if (pendingHold >= 0) { closeAt = now + doorMotion.state().remaining(now) + pendingHold; pendingHold = -1; setChanged(); }
    }
    public void syncDoor() {
        if (kind() == DeviceKind.DOOR && level instanceof ServerLevel server && doorSent != doorMotion.state().revision()) {
            doorSent = doorMotion.state().revision(); setChanged();
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
    public void acceptMotion(UUID id, CameraMotion.State state) {
        if (level != null && level.isClientSide() && id.equals(identity) && state.revision() >= cameraMotion.state().revision()) cameraMotion.restore(state);
    }
    public void syncMotion() {
        if (kind() == DeviceKind.CAMERA && level instanceof ServerLevel server && motionSent != cameraMotion.state().revision()) {
            motionSent = cameraMotion.state().revision();
            setChanged(); server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
    public DoorTarget doorTarget() { return doorTarget; }
    public long closeAt() { return closeAt; }
    public void setCloseAt(long tick) { closeAt = tick; setChanged(); }
    public DoorTarget asDoorTarget() {
        return new DoorTarget(level.dimension().identifier(), worldPosition, identity);
    }
    public boolean targetDoor(UUID actor, DeviceBlockEntity door) {
        if (kind() != DeviceKind.READER || door.kind() != DeviceKind.DOOR || !(level instanceof ServerLevel)
                || level != door.level || !ownedBy(actor) || !door.ownedBy(actor) || link == null || !link.equals(door.link)
                || worldPosition.distSqr(door.worldPosition) > 16) return false;
        doorTarget = door.asDoorTarget(); setChanged(); return true;
    }
    public boolean ownedBy(UUID player) { return player != null && player.equals(owner); }

    public void initializeOwner(UUID player, String name) {
        if (owner != null || !(level instanceof ServerLevel)) return;
        owner = player;
        if(kind()==DeviceKind.FILTER&&FilterBlock.base(getBlockState()))filter.assembly(identity);
        label = name.substring(0, Math.min(name.length(), 48));
        if (kind() == DeviceKind.CORE) {
            link = new NetworkLink(level.dimension().identifier(), worldPosition.immutable(), UUID.randomUUID());
        }
        if (kind() == DeviceKind.CAMERA) cameraMotion.initialize(level.getGameTime());
        setChanged();
        NetworkService.invalidate(level);updateDirectory();
    }

    public boolean bind(UUID actor, DeviceBlockEntity core) {
        if (kind() == DeviceKind.CORE || core.kind() != DeviceKind.CORE || core.link == null
                || !(level instanceof ServerLevel) || level != core.level
                || !ownedBy(actor) || !core.ownedBy(actor)) return false;
        if((kind()!=DeviceKind.FENCE||dev.umbral.fence.FenceService.head(this))&&!InstallationDirectory.get((ServerLevel)level).hasRoom(core.link,worldPosition))return false;
        if(kind()==DeviceKind.DOOR&&airlockLink!=null&&!java.util.Objects.equals(link,core.link()))return false;
        if(kind()==DeviceKind.FILTER&&filter.door()!=null&&!java.util.Objects.equals(link,core.link()))return false;
        if(kind()==DeviceKind.DOCK && MotaService.active((ServerLevel)level,this) && !core.link.equals(link))return false;
        if (kind() == DeviceKind.CAMERA && !CameraDirectory.get((ServerLevel) level).hasRoom(core.link, worldPosition)) return false;
        if (!core.link.equals(link)) {
            doorTarget = null;safety.arm(false);if(kind()==DeviceKind.DOCK)dock.stock(null);
            if(kind().turret()){turret.setArmed(false,level.getGameTime());turret.stop(level.getGameTime());}
        }
        link = core.link;
        updateDirectory();
        setChanged();
        NetworkService.invalidate(level);
        return true;
    }

    public boolean unbind(UUID actor) {
        if (kind() == DeviceKind.CORE || !(level instanceof ServerLevel) || !ownedBy(actor)) return false;
        if(kind()==DeviceKind.DOCK&&MotaService.active((ServerLevel)level,this))return false;
        if(kind()==DeviceKind.DOOR&&airlockLink!=null)return false;
        if(kind()==DeviceKind.FILTER&&filter.door()!=null)return false;
        link = null;safety.arm(false);if(kind()==DeviceKind.DOCK)dock.stock(null);
        if(kind().turret()){turret.setArmed(false,level.getGameTime());turret.stop(level.getGameTime());}
        doorTarget = null;
        updateDirectory();
        setChanged();
        NetworkService.invalidate(level);
        return true;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("umbral_schema", 1);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.storeNullable("network_link", NetworkLink.CODEC, link);
        output.putString("label", label);
        output.store("identity", UUIDUtil.CODEC, identity);
        output.storeNullable("door_target", DoorTarget.CODEC, doorTarget);
        output.putLong("close_at", closeAt);
        if(kind()==DeviceKind.FENCE)fence.save(output);
        if(kind()==DeviceKind.FILTER){filter.save(output);airlock.save(output);}
        if(kind()==DeviceKind.DOOR)output.storeNullable("airlock_link",dev.umbral.airlock.AirlockLink.CODEC,airlockLink);
        if(kind()==DeviceKind.DOOR)output.storeNullable("filter_guard",DoorTarget.CODEC,filterGuard);
        if (kind() == DeviceKind.CAMERA) output.store("camera_motion", CameraMotion.State.CODEC, cameraMotion.saved(level == null ? 0 : level.getGameTime()));
        if (kind() == DeviceKind.DOOR) {
            long now = level == null ? 0 : level.getGameTime();
            output.store("door_motion", DoorMotion.Saved.CODEC, doorMotion.saved(now));
            output.putLong("door_hold_remaining", pendingHold >= 0 ? pendingHold : Math.max(0, closeAt - now - doorMotion.state().remaining(now)));
        }
        if(kind()==DeviceKind.SENSOR||kind()==DeviceKind.PULSE_MINE)safety.save(output);
        if(kind()==DeviceKind.DOCK)dock.save(output);
        if(kind().turret())turret.save(output,level==null?0:level.getGameTime());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (level == null) input.read("door_animation", DoorMotion.State.CODEC).ifPresent(update -> doorMotion.accept(update, true));
        if(level==null)input.read("turret_motion",TurretMotion.CODEC).ifPresent(update->turret.accept(update,true));
        if (level != null && level.isClientSide()) {
            UUID received = input.read("identity", UUIDUtil.CODEC).orElse(identity);
            boolean replaced = !received.equals(identity); identity = received;
            input.read("camera_motion", CameraMotion.State.CODEC).ifPresent(update -> {
                if (replaced || update.revision() >= cameraMotion.state().revision()) cameraMotion.restore(update);
            });
            input.read("door_animation", DoorMotion.State.CODEC).ifPresent(update -> doorMotion.accept(update, replaced));
            input.read("turret_motion",TurretMotion.CODEC).ifPresent(update->turret.accept(update,replaced));
            if(kind()==DeviceKind.FILTER)filter.publicLoad(input);
            return;
        }
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        link = input.getIntOr("umbral_schema", 1) == 1
                ? input.read("network_link", NetworkLink.CODEC).orElse(null) : null;
        String savedLabel = input.getStringOr("label", "");
        label = savedLabel.substring(0, Math.min(savedLabel.length(), 48));
        identity = input.read("identity", UUIDUtil.CODEC).orElseGet(UUID::randomUUID);
        doorTarget = input.read("door_target", DoorTarget.CODEC).orElse(null);
        closeAt = input.getLongOr("close_at", 0);
        if(kind()==DeviceKind.FENCE)fence.load(input);
        if(kind()==DeviceKind.FILTER){filter.load(input);airlock.load(input);}
        airlockLink=input.read("airlock_link",dev.umbral.airlock.AirlockLink.CODEC).orElse(null);
        filterGuard=input.read("filter_guard",DoorTarget.CODEC).orElse(null);
        input.read("camera_motion", CameraMotion.State.CODEC).ifPresent(cameraMotion::restore);
        motionSent = Long.MIN_VALUE;
        if (kind() == DeviceKind.DOOR) {
            input.read("door_motion", DoorMotion.Saved.CODEC).ifPresent(doorMotion::load);
            pendingHold = input.getLongOr("door_hold_remaining", -1); doorSent = Long.MIN_VALUE;
        }
        if(kind()==DeviceKind.SENSOR||kind()==DeviceKind.PULSE_MINE)safety.load(input);
        if(kind()==DeviceKind.DOCK)dock.load(input);
        if(kind().turret()){turret.load(input);turretSent=Long.MIN_VALUE;}
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return kind() == DeviceKind.FILTER || kind() == DeviceKind.CAMERA || kind() == DeviceKind.DOOR || kind().turret() ? ClientboundBlockEntityDataPacket.create(this) : null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (kind() == DeviceKind.CAMERA) {
            tag.put("identity", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, identity).getOrThrow());
            tag.put("camera_motion", CameraMotion.State.CODEC.encodeStart(NbtOps.INSTANCE, cameraMotion.state()).getOrThrow());
        }
        if (kind() == DeviceKind.DOOR) {
            tag.put("identity", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, identity).getOrThrow());
            tag.put("door_animation", DoorMotion.State.CODEC.encodeStart(NbtOps.INSTANCE, doorMotion.state()).getOrThrow());
        }
        if(kind().turret()){
            tag.put("identity",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,identity).getOrThrow());
            tag.put("turret_motion",TurretMotion.CODEC.encodeStart(NbtOps.INSTANCE,turret.motion()).getOrThrow());
        }
        if(kind()==DeviceKind.FILTER){tag.put("identity",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,identity).getOrThrow());tag.putInt("filter_mode",filter.mode().ordinal());tag.putBoolean("filter_armed",filter.armed());tag.putBoolean("filter_complete",filter.complete());tag.putLong("filter_flash_at",filter.flashAt());tag.putInt("filter_flash",filter.flash().ordinal());}
        return tag;
    }

    @Override public void preRemoveSideEffects(BlockPos pos,BlockState state) {
        if(state.getBlock() instanceof BaluarteBlock&&level instanceof ServerLevel) {
            var items=turret.unload(level.getGameTime());
            if(!items.isEmpty())net.minecraft.world.Containers.dropItemStack(level,pos.getX(),pos.getY(),pos.getZ(),items);
        }
        if(level instanceof ServerLevel server){
            dev.umbral.installation.NexoJournal.get(server).forget(identity);
            if(kind()==DeviceKind.PULSE_MINE){var charge=safety.unload();if(!charge.isEmpty())net.minecraft.world.Containers.dropItemStack(server,pos.getX(),pos.getY(),pos.getZ(),charge);}
            if(kind()==DeviceKind.DOCK)MotaService.removed(server,this);
        }
        super.preRemoveSideEffects(pos,state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DeviceBlockEntity entity) {
        if (!(level instanceof ServerLevel server)) return;
        if(entity.kind()==DeviceKind.FENCE){dev.umbral.fence.FenceService.tick(server,entity);return;}
        if(entity.kind()==DeviceKind.FILTER){if(!FilterBlock.base(state))return;dev.umbral.filter.FilterService.tick(server,entity);dev.umbral.airlock.AirlockService.tick(server,entity);}
        if (entity.kind() == DeviceKind.CAMERA) {
            long now = level.getGameTime();
            if (now % 5 == 0) entity.cameraMotion.powered(NetworkService.inspect(server, entity).online(), now);
            entity.cameraMotion.advance(now);
            var motion = entity.cameraMotion.state();
            if (motion.moving(now) && !CameraGeometry.clearToMove(server, pos, state, motion.pose(now))) entity.cameraMotion.obstructed(now);
            if (motion.active() && (motion.moving(now) && now % 20 == 0 || now == motion.started() + motion.duration())) entity.setChanged();
            entity.syncMotion();
        }
        if (entity.kind() == DeviceKind.DOOR) {
            SecurityDoorBlock.autoClose(server, entity);
            var door = entity.doorMotion.state();
            if (door.moving(level.getGameTime()) || level.getGameTime() == door.started() + door.duration()) entity.setChanged();
            entity.syncDoor();
        }
        if(entity.kind().turret()){
            TurretService.tick(server,entity);entity.syncTurret();
            if(entity.turret.cooldown()>0)entity.setChanged();
        }
        if(entity.kind()==DeviceKind.SENSOR||entity.kind()==DeviceKind.PULSE_MINE)SafetyService.tick(server,entity);
        if ((level.getGameTime() + pos.asLong()) % 20 != 0) return;
        entity.updateDirectory();
        dev.umbral.installation.NexoJournal.get(server).observe(server,entity);
        boolean online = NetworkService.inspect(server, entity).online();
        if (state.getValue(DeviceBlock.ONLINE) != online) {
            // Doors synchronize their upper half through normal neighbor updates.
            level.setBlock(pos, entity.getBlockState().setValue(DeviceBlock.ONLINE, online),
                    entity.kind() == DeviceKind.DOOR || entity.kind()==DeviceKind.FILTER ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS);
        }
    }

    private void updateDirectory() {
        if(level instanceof ServerLevel server)InstallationDirectory.get(server).remember(this);
        if (kind() == DeviceKind.CAMERA && level instanceof ServerLevel server) CameraDirectory.get(server).remember(this);
    }
}
