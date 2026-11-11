// SPDX-License-Identifier: MPL-2.0

package dev.umbral.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.ElectricFenceBlock;
import dev.umbral.block.FilterBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Existing worlds keep the same block-entity type while devices gain their own animated parts. */
public final class DeviceRenderer implements BlockEntityRenderer<DeviceBlockEntity, DeviceRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        DeviceKind kind;
        FilterRenderer.State filter;
        CameraRenderer.State camera;
        DoorRenderer.State door;
        TurretRenderer.State turret;
        final CableConnectorRenderer.State cables = new CableConnectorRenderer.State();
    }
    private final CameraRenderer camera;
    private final FilterRenderer filter=new FilterRenderer();
    private final DoorRenderer door = new DoorRenderer();
    private final TurretRenderer turret;
    private final CableConnectorRenderer cables = new CableConnectorRenderer();
    public DeviceRenderer(BlockEntityRendererProvider.Context context) { camera = new CameraRenderer(context);turret=new TurretRenderer(context); }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(DeviceBlockEntity device, State state, float partial, Vec3 position, ModelFeatureRenderer.CrumblingOverlay progress) {
        BlockEntityRenderer.super.extractRenderState(device, state, partial, position, progress); state.kind = device.kind();
        cables.extract(device,state.cables);
        if(state.kind==DeviceKind.FILTER){if(state.filter==null)state.filter=new FilterRenderer.State();filter.extract(device,state.filter,partial);}
        if (state.kind == DeviceKind.CAMERA) {if(state.camera==null)state.camera=new CameraRenderer.State();camera.extractRenderState(device, state.camera, partial, position, progress);}
        else if (state.kind == DeviceKind.DOOR) {if(state.door==null)state.door=new DoorRenderer.State();door.extractRenderState(device, state.door, partial, position, progress);}
        else if(state.kind.turret()){if(state.turret==null)state.turret=new TurretRenderer.State();turret.extractRenderState(device,state.turret,partial,position,progress);}
    }
    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState view) {
        cables.submit(state.cables,poses,collector,state.lightCoords);
        if(state.kind==DeviceKind.FILTER)filter.submit(state.filter,poses,collector);
        if (state.kind == DeviceKind.CAMERA) camera.submit(state.camera, poses, collector, view);
        else if (state.kind == DeviceKind.DOOR) door.submit(state.door, poses, collector, view);
        else if(state.kind.turret())turret.submit(state.turret,poses,collector,view);
    }
    /** Conservative sweep bounds, including cables, rather than only the controller's cell. */
    public static AABB renderBounds(BlockState state,BlockPos pos){
        var kind=((DeviceBlock)state.getBlock()).kind();
        if(kind==DeviceKind.FILTER){
            var other=pos.relative(state.getValue(DeviceBlock.FACING).getClockWise(),2);
            return new AABB(pos).minmax(new AABB(other)).expandTowards(0,2,0);
        }
        if(kind==DeviceKind.DOOR)return new AABB(pos).expandTowards(0,1,0);
        // The camera head rotates about (.5,.625,.5); all authored vertices are within .75.
        if(kind==DeviceKind.CAMERA)return new AABB(-.25,-.125,-.25,1.25,1.375,1.25).move(pos);
        // Includes both turrets at any yaw/pitch, the extended bowstring and full recoil.
        if(kind.turret())return new AABB(-.625,-.625,-.625,1.625,1.625,1.625).move(pos);
        return new AABB(pos);
    }
    public static boolean visible(Frustum frustum,BlockState state,BlockPos pos){return frustum.isVisible(renderBounds(state,pos));}
    @Override public boolean shouldRender(DeviceBlockEntity device,Vec3 cameraPosition){
        if(!BlockEntityRenderer.super.shouldRender(device,cameraPosition))return false;
        var state=device.getBlockState();
        if(state.getBlock() instanceof FilterBlock&&!FilterBlock.base(state))return false;
        if(state.getBlock() instanceof ElectricFenceBlock&&!state.getValue(ElectricFenceBlock.FEEDER))return false;
        return visible(Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum(),state,device.getBlockPos());
    }
    // Global registration keeps extended parts eligible even if their controller's section is culled.
    // shouldRender applies the complete sweep's frustum before constructing any render state.
    @Override public boolean shouldRenderOffScreen() { return true; }
    public int cachedCableMeshes(){return cables.cachedMeshes();}
    public long cableMeshBuilds(){return cables.meshBuilds();}
    public static int connectorQuads(State state){return state.cables.quads;}
    public static int connectorBatches(State state){return state.cables.batches.size();}
}
