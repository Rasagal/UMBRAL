// SPDX-License-Identifier: MPL-2.0

package dev.umbral.camera;

import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.network.NetworkProtocol;
import dev.umbral.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Shared checks for initial requests, directory entries and ongoing view sessions. */
public final class CameraAccess {
    public enum State {
        LIVE, ACCESS_DENIED, TERMINAL_OFFLINE, CAMERA_OFFLINE, UNLOADED, REMOVED,
        OTHER_NETWORK, OBSTRUCTED, INTERRUPTED, EXPIRED;
        public String key() { return "camera.umbral." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Check(State state, DeviceBlockEntity terminal, DeviceBlockEntity camera) {
        public boolean allowed() { return state == State.LIVE; }
    }

    public static State terminal(ServerPlayer player, BlockPos terminalPos) {
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(terminalPos)
                || !(level.getBlockEntity(terminalPos) instanceof DeviceBlockEntity terminal)
                || terminal.kind() != DeviceKind.TERMINAL || !NetworkProtocol.mayInspect(player, terminal)) return State.ACCESS_DENIED;
        if (!player.isAlive() || player.isSleeping() || player.isPassenger() || player.hurtTime > 0) return State.INTERRUPTED;
        return NetworkService.inspect(level, terminal).online() ? State.LIVE : State.TERMINAL_OFFLINE;
    }

    public static Check check(ServerPlayer player, BlockPos terminalPos, BlockPos cameraPos) {
        State terminalState = terminal(player, terminalPos);
        if (terminalState != State.LIVE) return new Check(terminalState, null, null);
        ServerLevel level = (ServerLevel) player.level();
        DeviceBlockEntity terminal = (DeviceBlockEntity) level.getBlockEntity(terminalPos);
        if (!CameraDirectory.get(level).knows(player.getUUID(), terminal.link(), cameraPos)) {
            return new Check(State.ACCESS_DENIED, terminal, null);
        }
        if (!level.isLoaded(cameraPos)) return new Check(State.UNLOADED, terminal, null);
        if (!(level.getBlockEntity(cameraPos) instanceof DeviceBlockEntity camera) || camera.kind() != DeviceKind.CAMERA) {
            return new Check(State.REMOVED, terminal, null);
        }
        if (!camera.ownedBy(player.getUUID())) return new Check(State.ACCESS_DENIED, terminal, null);
        if (!terminal.link().equals(camera.link())) return new Check(State.OTHER_NETWORK, terminal, null);
        if (!NetworkService.inspect(level, camera).online()) return new Check(State.CAMERA_OFFLINE, terminal, camera);
        return new Check(CameraGeometry.lensState(level, cameraPos, camera.getBlockState().getValue(DeviceBlock.FACING),
                camera.cameraMotion().state().pose(level.getGameTime())), terminal, camera);
    }

    private CameraAccess() { }
}
