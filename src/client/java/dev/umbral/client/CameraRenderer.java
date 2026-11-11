// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Renders the shared editable optical head; its mount stays fixed while the servo moves. */
public final class CameraRenderer implements BlockEntityRenderer<DeviceBlockEntity, CameraRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        final ItemStackRenderState head = new ItemStackRenderState();
        boolean camera; float yaw, pitch;
    }
    private final ItemModelResolver models;
    private ItemStack on, off;
    public CameraRenderer(BlockEntityRendererProvider.Context context) { models = context.itemModelResolver(); }
    private static ItemStack head(boolean online) {
        ItemStack item = new ItemStack(UmbralContent.CAMERA);
        item.set(DataComponents.ITEM_MODEL, UmbralContent.id("security_camera_head_" + (online ? "on" : "off")));
        return item;
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(DeviceBlockEntity device, State state, float partialTicks, Vec3 cameraPosition,
                                             ModelFeatureRenderer.CrumblingOverlay progress) {
        BlockEntityRenderer.super.extractRenderState(device, state, partialTicks, cameraPosition, progress);
        state.camera = device.kind() == DeviceKind.CAMERA && device.getLevel() != null;
        if (!state.camera) return;
        // Item components are bound by the data pack sync, after the initial renderer/resource reload.
        if (on == null) { on = head(true); off = head(false); }
        var motion = device.cameraMotion().state();
        var pose = motion.pose(device.getLevel().getGameTime() + partialTicks);
        state.yaw = 180 - device.getBlockState().getValue(DeviceBlock.FACING).toYRot() - pose.yaw(); state.pitch = pose.pitch();
        models.updateForTopItem(state.head, motion.active() ? on : off, ItemDisplayContext.NONE, device.getLevel(), null, 0);
    }
    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.camera) return;
        poses.pushPose();
        poses.translate(.5, .625, .5);
        poses.rotateDegrees(Axis.YP, state.yaw); poses.rotateDegrees(Axis.XP, -state.pitch);
        // ItemDisplayContext.NONE centers the baked model at (.5,.5,.5); the servo pivot is 2px higher.
        poses.translate(0, -.125, 0);
        state.head.submit(poses, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poses.popPose();
    }
}
