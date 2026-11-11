// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.installation.*;
import dev.umbral.installation.NexoProtocol.*;
import dev.umbral.network.NetworkService;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Every new NEXO command authenticates the current physical installation again. */
public final class NexoUsabilityGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static final BlockPos CORE=new BlockPos(2,1,2),TARGET=new BlockPos(4,1,2);
    private record Setup(ServerPlayer owner,DeviceBlockEntity core,DeviceBlockEntity target){}
    private DeviceBlockEntity put(GameTestHelper t,BlockPos at,DeviceBlock block,ServerPlayer p,String label){
        t.setBlock(at,block);var d=t.getBlockEntity(at,DeviceBlockEntity.class);d.initializeOwner(p.getUUID(),label);return d;
    }
    private Setup setup(GameTestHelper t,DeviceBlock block){
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);
        var core=put(t,CORE,UmbralContent.CORE,p,"Casa");var target=put(t,TARGET,block,p,"Patio");
        target.bind(p.getUUID(),core);p.setPos(Vec3.atCenterOf(core.getBlockPos()));return new Setup(p,core,target);
    }
    private Request command(Setup s,Operation operation,String text){return new Request(s.core.getBlockPos(),s.core.identity(),s.core.link(),73,operation,s.target.getBlockPos(),s.target.identity(),text);}
    @GameTest(structure=STRUCTURE) public void renameUpdatesLiveRowsCameraDirectoryAndPersistentName(GameTestHelper t){
        var s=setup(t,UmbralContent.CAMERA);var identity=s.target.identity();var result=NexoProtocol.handle(s.owner,command(s,Operation.RENAME,"  Ca\u0301mara   jardín  "));
        t.assertTrue(result.valid()&&result.result().equals("renamed")&&result.nonce()==73,"The authenticated command returns its matching nonce");
        t.assertTrue(s.target.label().equals("Cámara jardín")&&s.target.identity().equals(identity)&&s.target.link().equals(s.core.link()),"Rename preserves physical identity and network");
        t.assertTrue(result.rows().stream().anyMatch(r->r.identity().equals(identity)&&r.label().equals("Cámara jardín")),"The next NEXO reading contains the new name");
        t.assertTrue(InstallationDirectory.get(t.getLevel()).entries(s.owner.getUUID(),s.core.link()).stream().anyMatch(e->e.identity().equals(identity)&&e.label().equals("Cámara jardín")),"Discovery is updated immediately");
        t.assertTrue(CameraDirectory.get(t.getLevel()).cameras(s.owner.getUUID(),s.core.link()).stream().anyMatch(e->e.position().equals(s.target.getBlockPos())&&e.label().equals("Cámara jardín")),"Surveillance discovery uses the same name");
        var copy=new DeviceBlockEntity(s.target.getBlockPos(),s.target.getBlockState());copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,t.getLevel().registryAccess(),s.target.saveWithoutMetadata(t.getLevel().registryAccess())));
        t.assertTrue(copy.label().equals("Cámara jardín")&&copy.identity().equals(identity),"The real persistence codec retains the renamed equipment");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void renameRejectsInvalidNamesWithoutMutatingDirectory(GameTestHelper t){
        var s=setup(t,UmbralContent.CAMERA);
        for(String invalid:new String[]{""," ","a".repeat(49),"§aPatio","Patio\nNorte","Patio\u202eNorte"})
            t.assertTrue(NexoProtocol.handle(s.owner,command(s,Operation.RENAME,invalid)).result().equals("bad_name"),"Invalid names are rejected on the server");
        t.assertTrue(s.target.label().equals("Patio")&&InstallationDirectory.get(t.getLevel()).entries(s.owner.getUUID(),s.core.link()).stream().anyMatch(e->e.identity().equals(s.target.identity())&&e.label().equals("Patio")),"Rejected names leave both block and directory unchanged");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void renameRejectsVisitorsDistanceAndReplacedIdentities(GameTestHelper t){
        var s=setup(t,UmbralContent.CAMERA);var request=command(s,Operation.RENAME,"Jardín");
        var guest=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);guest.setPos(s.owner.position());
        t.assertFalse(NexoProtocol.handle(guest,request).valid(),"A visitor cannot rename another player's installation");
        s.owner.setPos(s.owner.position().add(20,0,0));t.assertFalse(NexoProtocol.handle(s.owner,request).valid(),"A distant stale console cannot rename equipment");
        s.owner.setPos(Vec3.atCenterOf(s.core.getBlockPos()));t.setBlock(TARGET,Blocks.AIR);var replacement=put(t,TARGET,UmbralContent.CAMERA,s.owner,"Nuevo");replacement.bind(s.owner.getUUID(),s.core);
        t.assertTrue(NexoProtocol.handle(s.owner,request).result().equals("invalid")&&replacement.label().equals("Nuevo"),"The removed physical device's identity grants no control over its replacement");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void renameRejectsAnotherNetworkAndChangedConsoleIdentity(GameTestHelper t){
        var s=setup(t,UmbralContent.CAMERA);var request=command(s,Operation.RENAME,"Jardín");var other=put(t,new BlockPos(6,1,2),UmbralContent.CORE,s.owner,"Otra red");s.target.bind(s.owner.getUUID(),other);
        t.assertTrue(NexoProtocol.handle(s.owner,request).result().equals("invalid")&&s.target.label().equals("Patio"),"Sharing an owner does not bypass network boundaries");
        s.target.bind(s.owner.getUUID(),s.core);var staleRoot=new Request(s.core.getBlockPos(),UUID.randomUUID(),s.core.link(),73,Operation.RENAME,s.target.getBlockPos(),s.target.identity(),"Jardín");
        t.assertFalse(NexoProtocol.handle(s.owner,staleRoot).valid(),"The console's current physical identity is required");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void offlineRemoteRenameIsBlockedButLocalUnpairedRenameIsPossible(GameTestHelper t){
        var s=setup(t,UmbralContent.NODE);NetworkService.invalidate(t.getLevel());
        var row=NexoProtocol.snapshot(s.owner,s.core,1,false,"ready").rows().stream().filter(r->r.identity().equals(s.target.identity())).findFirst().orElseThrow();
        t.assertTrue(row.reading()==Reading.OFFLINE&&row.connection().equals("no_signal"),"The row exposes the actual missing cable reason for a relay");
        t.assertTrue(NexoProtocol.handle(s.owner,command(s,Operation.RENAME,"Nodo norte")).result().equals("offline")&&s.target.label().equals("Patio"),"Remote rename does not bypass missing signal");
        t.assertTrue(s.target.unbind(s.owner.getUUID()),"Owner explicitly unpairs the local fixture");s.owner.setPos(Vec3.atCenterOf(s.target.getBlockPos()));
        var local=new Request(s.target.getBlockPos(),s.target.identity(),null,74,Operation.RENAME,s.target.getBlockPos(),s.target.identity(),"Nodo norte");
        t.assertTrue(NexoProtocol.handle(s.owner,local).result().equals("renamed")&&s.target.link()==null,"Naming the local unpaired apparatus never pairs it silently");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void remoteAmmunitionChoiceIsBoundedAndNeverLoadsOrArms(GameTestHelper t){
        var s=setup(t,UmbralContent.CENTELLA);var result=NexoProtocol.handle(s.owner,command(s,Operation.CYCLE_AMMO,"ignored"));
        t.assertTrue(result.result().equals("ammo_preference")&&s.target.turret().preferenceId().equals("copper"),"NEXO cycles the server-owned preference enum");
        t.assertTrue(s.target.turret().count()==0&&!s.target.turret().armed()&&result.rows().stream().anyMatch(r->r.identity().equals(s.target.identity())&&r.preference().equals("copper")),"Snapshot shows the choice without creating ammunition or arming");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void snapshotAndRenamePacketsRetainNamesConnectionAndPreference(GameTestHelper t){
        var s=setup(t,UmbralContent.CENTELLA);NexoProtocol.handle(s.owner,command(s,Operation.CYCLE_AMMO,""));
        var request=command(s,Operation.RENAME,"CENTELLA · jardín");var snapshot=NexoProtocol.snapshot(s.owner,s.core,73,false,"ready");
        var requestBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),t.getLevel().registryAccess());var snapshotBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),t.getLevel().registryAccess());
        try{
            Request.CODEC.encode(requestBuffer,request);t.assertTrue(Request.CODEC.decode(requestBuffer).equals(request),"The bounded rename text travels with the command identity and nonce");
            Snapshot.CODEC.encode(snapshotBuffer,snapshot);var copy=Snapshot.CODEC.decode(snapshotBuffer);
            t.assertTrue(copy.equals(snapshot)&&copy.rows().stream().anyMatch(r->r.identity().equals(s.target.identity())&&r.connection().equals("wireless")&&r.preference().equals("copper")),"The packet preserves actual signal and ammunition preference alongside old telemetry");
        }finally{requestBuffer.release();snapshotBuffer.release();}
        t.succeed();
    }
}
