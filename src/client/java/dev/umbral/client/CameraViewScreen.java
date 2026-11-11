// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.umbral.camera.CameraAccess;
import dev.umbral.camera.CameraProtocol;
import dev.umbral.camera.CameraMotion;
import dev.umbral.camera.CameraGeometry;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.client.mixin.CameraAccessor;
import dev.umbral.network.NetworkService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.HashMap;
import java.util.Map;

import static dev.umbral.client.ConsoleStyle.*;

/** The live lens follows the same servo transform as the physical head. */
public final class CameraViewScreen extends Screen {
    private final CameraDirectoryScreen parent;
    private CameraProtocol.Feed feed;
    private Marker lens;
    private Entity previousEntity;
    private CameraType previousPerspective;
    private ClientLevel level;
    private boolean hudWasHidden;
    private boolean attached;
    private int ticks, lastUpdate;
    private boolean showDetails = true;
    private boolean detailsKeyHeld;
    private CameraHudStyle.Control detailsControl,helpControl;
    private int controlsLeft;
    private final Map<Integer, CameraMotion.Command> held = new HashMap<>();
    private final java.util.List<CameraHudStyle.Control> servo = new java.util.ArrayList<>();
    private CameraHudStyle.Control autoControl;
    private int servoLeft, servoWidth, lastSteer = -100;

    CameraViewScreen(CameraDirectoryScreen parent, CameraProtocol.Feed feed) {
        super(Component.translatable("screen.umbral.surveillance"));
        this.parent = parent; this.feed = feed;
    }

    public static boolean videoAvailable(Minecraft client, BlockPos pos) {
        return client.level != null && client.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }
    public boolean matches(CameraProtocol.Feed update) { return feed.session().equals(update.session()); }
    public BlockPos cameraPosition() { return feed.camera(); }

    @Override public void added() {
        if (minecraft.level == null || minecraft.player == null) return;
        level = minecraft.level;
        previousEntity = minecraft.getCameraEntity(); previousPerspective = minecraft.options.getCameraType();
        hudWasHidden = minecraft.gui.hud.isHidden();
        if (!hudWasHidden) minecraft.gui.hud.toggle();
        lens = new Marker(EntityTypes.MARKER, level);
        positionLens();
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(lens);
        resetEyeHeight(lens);
        attached = true;
    }
    private CameraMotion.State motion() {
        if (level != null && level.getBlockEntity(feed.camera()) instanceof DeviceBlockEntity device
                && device.identity().equals(feed.identity()) && device.cameraMotion().state().revision() >= feed.motion().revision()) return device.cameraMotion().state();
        return feed.motion();
    }
    private void positionLens() { positionLens(0); }
    private void positionLens(float partialTick) {
        var pose = motion().pose(level.getGameTime() + partialTick);
        var position = CameraGeometry.lens(feed.camera(), feed.direction(), pose);
        lens.absSnapTo(position.x, position.y, position.z, feed.direction().toYRot() + pose.yaw(), pose.pitch());
    }
    public static void beforeCameraFrame(float partialTicks) {
        var client = Minecraft.getInstance();
        if (client.gui.screen() instanceof CameraViewScreen screen && screen.attached
                && client.level == screen.level && client.getCameraEntity() == screen.lens) screen.positionLens(partialTicks);
    }
    private void resetEyeHeight(Entity entity) {
        var accessor = (CameraAccessor) minecraft.gameRenderer.mainCamera();
        float height = entity == null ? 0 : entity.getEyeHeight();
        accessor.umbral$setEyeHeight(height); accessor.umbral$setOldEyeHeight(height);
    }
    @Override protected void init() {
        clearWidgets();
        servo.clear(); held.clear();
        Component exit = Component.translatable("hud.umbral.exit");
        Component details = Component.translatable("hud.umbral.data");
        int exitWidth = CameraHudStyle.controlWidth(font, exit, "Esc");
        int detailsWidth = CameraHudStyle.controlWidth(font, details, "F1");
        int exitX = width - exitWidth - 14;
        controlsLeft = exitX - detailsWidth - 6;
        detailsControl = addRenderableWidget(new CameraHudStyle.Control(controlsLeft, 14, detailsWidth,
                details, "F1", detailsDescription(), button -> toggleDetails()));
        helpControl=addRenderableWidget(new CameraHudStyle.Control(controlsLeft-27,14,22,Component.empty(),"?",
                Component.translatable("manual.umbral.camera_help"),b->{onClose();minecraft.gui.setScreen(new ManualScreen(parent,dev.umbral.manual.ManualCatalog.Entry.VIGIA));}));
        helpControl.visible=showDetails;
        detailsControl.selected(showDetails);
        addRenderableWidget(new CameraHudStyle.Control(exitX, 14, exitWidth, exit, "Esc",
                Component.translatable("screen.umbral.leave_camera"), button -> onClose()));
        Component home = Component.translatable("motion.umbral.home"), auto = Component.translatable("motion.umbral.auto");
        Component range = Component.literal("±" + motion().range() + "°");
        int homeW = CameraHudStyle.controlWidth(font, home, "R"), autoW = CameraHudStyle.controlWidth(font, auto, "Esp");
        int rangeW = CameraHudStyle.controlWidth(font, range, "G");
        servoWidth = 88 + homeW + autoW + rangeW + 24; servoLeft = (width - servoWidth) / 2;
        int x = servoLeft;
        CameraMotion.Command[] directions = {CameraMotion.Command.LEFT, CameraMotion.Command.UP, CameraMotion.Command.DOWN, CameraMotion.Command.RIGHT};
        String[] arrows = {"<", "^", "v", ">"};
        for (int i = 0; i < directions.length; i++) {
            var command = directions[i];
            addServo(x, 22, Component.empty(), arrows[i], "motion.umbral." + command.name().toLowerCase(java.util.Locale.ROOT), command); x += 26;
        }
        addServo(x, homeW, home, "R", "motion.umbral.home_hint", CameraMotion.Command.HOME); x += homeW + 4;
        autoControl = addServo(x, autoW, auto, "Esp", "motion.umbral.auto_hint", CameraMotion.Command.AUTO); x += autoW + 4;
        addServo(x, rangeW, range, "G", "motion.umbral.range_hint", CameraMotion.Command.RANGE);
        updateServo();
    }
    private CameraHudStyle.Control addServo(int x, int w, Component label, String key, String hint, CameraMotion.Command command) {
        var control = addRenderableWidget(new CameraHudStyle.Control(x, 47, w, label, key, Component.translatable(hint), b -> steer(command)));
        servo.add(control); return control;
    }
    private void updateServo() {
        for (var control : servo) { control.visible = showDetails; control.active = ticks - lastUpdate <= 60 && motion().active(); }
        if (autoControl != null) autoControl.selected(motion().scan());
    }
    private void steer(CameraMotion.Command command) {
        if (ticks - lastSteer < 5 || ticks - lastUpdate > 60) return;
        lastSteer = ticks;
        ClientPlayNetworking.send(new CameraProtocol.Steer(feed.session(), command));
    }

    private Component detailsDescription() {
        return Component.translatable(showDetails ? "hud.umbral.hide_data" : "hud.umbral.show_data");
    }

    private void toggleDetails() {
        showDetails = !showDetails;
        detailsControl.describe(detailsDescription());
        helpControl.visible=showDetails;
        detailsControl.selected(showDetails);
        updateServo();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_F1) {
            if (!detailsKeyHeld) toggleDetails();
            detailsKeyHeld = true;
            return true;
        }
        CameraMotion.Command command = switch (event.key()) {
            case InputConstants.KEY_LEFT -> CameraMotion.Command.LEFT;
            case InputConstants.KEY_RIGHT -> CameraMotion.Command.RIGHT;
            case InputConstants.KEY_UP -> CameraMotion.Command.UP;
            case InputConstants.KEY_DOWN -> CameraMotion.Command.DOWN;
            case InputConstants.KEY_R -> CameraMotion.Command.HOME;
            case InputConstants.KEY_SPACE -> CameraMotion.Command.AUTO;
            case InputConstants.KEY_G -> CameraMotion.Command.RANGE;
            default -> null;
        };
        if (command != null) {
            if (!held.containsKey(event.key())) { held.put(event.key(), command); steer(command); }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean keyReleased(KeyEvent event) {
        if (event.key() == InputConstants.KEY_F1) { detailsKeyHeld = false; return true; }
        if (held.remove(event.key()) != null) return true;
        return super.keyReleased(event);
    }
    public void accept(CameraProtocol.Feed update) {
        if (!matches(update)) return;
        feed = update; lastUpdate = ticks;
        if (update.state() != CameraAccess.State.LIVE) { leave(Component.translatable(update.state().key())); return; }
        if (level != null && level.getBlockEntity(feed.camera()) instanceof DeviceBlockEntity device) device.acceptMotion(feed.identity(), feed.motion());
        if (lens != null) positionLens();
        if (!servo.isEmpty() && !servo.getLast().label().getString().equals("±" + motion().range() + "°")) init();
        updateServo();
    }
    @Override public void tick() {
        ticks++;
        if (!attached || minecraft.player == null || minecraft.level != level || !minecraft.player.isAlive()) {
            minecraft.gui.setScreen(null); return;
        }
        if (minecraft.player.hurtTime > 0 || minecraft.player.isPassenger() || minecraft.player.isSleeping()
                || minecraft.player.isSpectator() || feed.terminal().distToCenterSqr(minecraft.player.position()) > 64) {
            leave(Component.translatable("camera.umbral.interrupted")); return;
        }
        if (!videoAvailable(minecraft, feed.camera())) { leave(Component.translatable("camera.umbral.no_video")); return; }
        if (ticks - lastUpdate > 60) { leave(Component.translatable("camera.umbral.expired")); return; }
        if (minecraft.getCameraEntity() != lens) { leave(Component.translatable("camera.umbral.interrupted")); return; }
        updateServo();
        if (ticks - lastSteer >= 10) held.values().stream().filter(c -> c == CameraMotion.Command.LEFT || c == CameraMotion.Command.RIGHT
                || c == CameraMotion.Command.UP || c == CameraMotion.Command.DOWN).findFirst().ifPresent(this::steer);
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        if (ticks % 20 == 0) ClientPlayNetworking.send(new CameraProtocol.Control(feed.session(), false));
    }
    private void leave(Component reason) { parent.showNotice(reason); minecraft.gui.setScreen(parent); }
    @Override public void onClose() { leave(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float delta) { }

    @Override public void removed() {
        if (attached) {
            attached = false;
            if (minecraft.getCameraEntity() == lens) {
                Entity restore = previousEntity != null && !previousEntity.isRemoved() && previousEntity.level() == minecraft.level
                        ? previousEntity : minecraft.player;
                minecraft.setCameraEntity(restore); resetEyeHeight(restore);
            }
            minecraft.options.setCameraType(previousPerspective);
            if (minecraft.gui.hud.isHidden() != hudWasHidden) minecraft.gui.hud.toggle();
        }
        if (minecraft.getConnection() != null && ClientPlayNetworking.canSend(CameraProtocol.Control.TYPE)) {
            ClientPlayNetworking.send(new CameraProtocol.Control(feed.session(), true));
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        CameraHudStyle.rail(g, 8, 8, width - 16, 32);
        mark(g, 17, 17, 15, CameraHudStyle.TEXT);
        g.fill(38, 13, 72, 25, LCD);
        g.text(font, "VIGÍA", 41, 15, INK, false);
        g.fill(80, 17, 84, 21, RED);
        g.fill(80, 17, 81, 18, 0xFFF09A79);
        text(g, font, Component.translatable("camera.umbral.live"), 90, 15, controlsLeft - 125, CameraHudStyle.SECONDARY);
        Component name = CameraDirectoryScreen.cameraName(feed.label(), feed.camera());
        text(g, font, name, 38, 28, controlsLeft - 73, CameraHudStyle.TEXT);
        if (mouseX >= 38 && mouseX < controlsLeft - 8 && mouseY >= 25 && mouseY < 39 && font.width(name) > controlsLeft - 73)
            g.setTooltipForNextFrame(font, name, mouseX, mouseY);

        if (showDetails) {
            CameraHudStyle.rail(g, servoLeft - 4, 43, servoWidth + 8, 28);
            CameraHudStyle.viewport(g, width, height);
            int bottomY = height - 30;
            CameraHudStyle.rail(g, 8, bottomY, width - 16, 22);
            Component position = Component.translatable("ui.umbral.camera_position", NetworkService.coordinates(feed.camera()));
            int orientationX = width - 148;
            text(g, font, position, 18, bottomY + 7, orientationX - 30, CameraHudStyle.TEXT);
            var pose = motion().pose(level.getGameTime() + delta);
            Component orientation = Component.translatable("motion.umbral.angles", Math.round(pose.yaw()), Math.round(pose.pitch()));
            g.fill(orientationX - 8, bottomY + 6, orientationX - 7, bottomY + 16, CameraHudStyle.EDGE);
            text(g, font, orientation, orientationX, bottomY + 7, 132, CameraHudStyle.TEXT);
            Component fixed = Component.translatable(motion().blocked() ? "motion.umbral.blocked" : motion().scan() ? "motion.umbral.scanning" : "motion.umbral.manual");
            int fixedX = orientationX - 24 - font.width(fixed);
            if (fixedX > 30 + font.width(position))
                g.text(font, fixed, fixedX, bottomY + 7, CameraHudStyle.SECONDARY, false);
            if (mouseX >= 18 && mouseX < orientationX - 12 && mouseY >= bottomY && mouseY < bottomY + 22 && font.width(position) > orientationX - 30)
                g.setTooltipForNextFrame(font, position, mouseX, mouseY);

            // This useful reminder appears briefly on entry, then gives the space back to the image.
            if (ticks < 100) {
                Component reminder = Component.translatable("camera.umbral.body_stays");
                int reminderWidth = Math.min(width - 40, font.width(reminder) + 20);
                int reminderX = (width - reminderWidth) / 2;
                cutPanel(g, reminderX, height - 57, reminderWidth, 18, 0xE61B251E);
                g.fill(reminderX + 3, height - 53, reminderX + 4, height - 43, ORANGE);
                text(g, font, reminder, reminderX + 10, height - 52, reminderWidth - 20, CameraHudStyle.SECONDARY);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
}
