// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.google.gson.JsonArray;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.umbral.UmbralContent;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.*;

/** Translate and clip each leaf inside the frame; texture pixels move with the metal panels. */
public final class DoorRenderer implements BlockEntityRenderer<DeviceBlockEntity, DoorRenderer.State> {
    private static final Set<String> MATERIALS = Set.of("graphite", "ivory", "copper", "amethyst", "shadow", "status",
            "door_ivory", "door_steel", "door_graphite", "door_copper", "door_gasket", "door_ivory_light", "door_ivory_shade", "door_glass");
    public static final class State extends BlockEntityRenderState {
        float progress, rotation; int upperLight; boolean online;
        Map<String, List<Box>> boxes = Map.of();
    }
    private record Box(float x0, float y0, float z0, float x1, float y1, float z1, int side, float uvY) { }
    private Map<String, List<Box>> geometry;
    private Map<String, List<Box>> geometry() {
        if (geometry != null) return geometry;
        Map<String, List<Box>> boxes = new LinkedHashMap<>();
        try (var reader = Minecraft.getInstance().getResourceManager().getResource(UmbralContent.id("geometry/security_door.json")).orElseThrow().openAsReader()) {
            var json = GsonHelper.parse(reader);
            if (GsonHelper.getAsInt(json, "schema") != 1) throw new IllegalArgumentException("Unsupported door geometry");
            for (var value : json.getAsJsonArray("boxes")) {
                var part = value.getAsJsonObject(); JsonArray a = part.getAsJsonArray("from"), b = part.getAsJsonArray("to");
                String texture = GsonHelper.getAsString(part, "texture");
                if (!MATERIALS.contains(texture)) throw new IllegalArgumentException("Unknown door material");
                boxes.computeIfAbsent(texture, ignored -> new ArrayList<>()).add(new Box(
                        a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), b.get(0).getAsFloat(), b.get(1).getAsFloat(), b.get(2).getAsFloat(),
                        GsonHelper.getAsInt(part, "side"), GsonHelper.getAsFloat(part, "uv_y")));
            }
        } catch (IOException error) { throw new IllegalStateException("Cannot read UMBRAL door geometry", error); }
        boxes.replaceAll((key, value) -> List.copyOf(value)); geometry = Collections.unmodifiableMap(boxes); return geometry;
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(DeviceBlockEntity door, State state, float partialTicks, Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(door, state, partialTicks, cameraPosition, breakProgress);
        state.progress = door.doorMotion().state().position(door.getLevel().getGameTime() + partialTicks);
        state.rotation = 180 - door.getBlockState().getValue(DeviceBlock.FACING).toYRot();
        state.online = door.getBlockState().getValue(DeviceBlock.ONLINE);
        state.upperLight = LightCoordsUtil.getLightCoords(door.getLevel(), door.getBlockPos().above());
        state.boxes = geometry();
    }
    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.progress >= 1) return;
        poses.pushPose(); poses.translate(.5, 0, .5); poses.rotateDegrees(Axis.YP, state.rotation); poses.translate(-.5, 0, -.5);
        state.boxes.forEach((material, boxes) -> {
            String texture = material.equals("status") ? state.online ? "teal" : "amber" : material;
            Identifier path = UmbralContent.id("textures/block/" + texture + ".png");
            var renderType = material.equals("door_glass") ? RenderTypes.entityTranslucentCull(path) : RenderTypes.entitySolid(path);
            collector.submitCustomGeometry(poses, renderType, (pose, buffer) -> {
                for (Box box : boxes) drawBox(pose, buffer, box, state.progress, box.y0 >= 16 ? state.upperLight : state.lightCoords);
            });
        });
        poses.popPose();
    }
    private static void drawBox(PoseStack.Pose pose, VertexConsumer buffer, Box b, float progress, int light) {
        float shift = b.side * 6.5f * progress, x0 = Math.max(2, b.x0 + shift), x1 = Math.min(14, b.x1 + shift);
        if (x0 >= x1) return;
        float y0 = b.y0, y1 = b.y1, z0 = b.z0, z1 = b.z1;
        vertex(pose, buffer, x0,y0,z0, 16-(x0-shift),16-(y0-b.uvY),0,0,-1,light);
        vertex(pose, buffer, x0,y1,z0, 16-(x0-shift),16-(y1-b.uvY),0,0,-1,light);
        vertex(pose, buffer, x1,y1,z0, 16-(x1-shift),16-(y1-b.uvY),0,0,-1,light);
        vertex(pose, buffer, x1,y0,z0, 16-(x1-shift),16-(y0-b.uvY),0,0,-1,light);
        vertex(pose, buffer, x1,y0,z1, x1-shift,16-(y0-b.uvY),0,0,1,light);
        vertex(pose, buffer, x1,y1,z1, x1-shift,16-(y1-b.uvY),0,0,1,light);
        vertex(pose, buffer, x0,y1,z1, x0-shift,16-(y1-b.uvY),0,0,1,light);
        vertex(pose, buffer, x0,y0,z1, x0-shift,16-(y0-b.uvY),0,0,1,light);
        vertex(pose, buffer, x0,y0,z1, z1,16-(y0-b.uvY),-1,0,0,light);
        vertex(pose, buffer, x0,y1,z1, z1,16-(y1-b.uvY),-1,0,0,light);
        vertex(pose, buffer, x0,y1,z0, z0,16-(y1-b.uvY),-1,0,0,light);
        vertex(pose, buffer, x0,y0,z0, z0,16-(y0-b.uvY),-1,0,0,light);
        vertex(pose, buffer, x1,y0,z0, 16-z0,16-(y0-b.uvY),1,0,0,light);
        vertex(pose, buffer, x1,y1,z0, 16-z0,16-(y1-b.uvY),1,0,0,light);
        vertex(pose, buffer, x1,y1,z1, 16-z1,16-(y1-b.uvY),1,0,0,light);
        vertex(pose, buffer, x1,y0,z1, 16-z1,16-(y0-b.uvY),1,0,0,light);
        vertex(pose, buffer, x0,y1,z0, x0-shift,z0,0,1,0,light);
        vertex(pose, buffer, x0,y1,z1, x0-shift,z1,0,1,0,light);
        vertex(pose, buffer, x1,y1,z1, x1-shift,z1,0,1,0,light);
        vertex(pose, buffer, x1,y1,z0, x1-shift,z0,0,1,0,light);
        vertex(pose, buffer, x0,y0,z1, x0-shift,16-z1,0,-1,0,light);
        vertex(pose, buffer, x0,y0,z0, x0-shift,16-z0,0,-1,0,light);
        vertex(pose, buffer, x1,y0,z0, x1-shift,16-z0,0,-1,0,light);
        vertex(pose, buffer, x1,y0,z1, x1-shift,16-z1,0,-1,0,light);
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float u, float v, float nx, float ny, float nz, int light) {
        buffer.addVertex(pose, x/16, y/16, z/16).setColor(-1).setUv(u/16, v/16).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,nx,ny,nz);
    }
}
