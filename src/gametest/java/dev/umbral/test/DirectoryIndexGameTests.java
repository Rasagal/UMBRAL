// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.block.*;
import dev.umbral.camera.CameraDirectory;
import dev.umbral.installation.InstallationDirectory;
import dev.umbral.network.NetworkLink;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.*;

/** Indexed discovery retains ownership boundaries, canonical order and exact admission caps. */
public final class DirectoryIndexGameTests {
    private static final String STRUCTURE="umbral-test:network";
    private static InstallationDirectory directory(List<InstallationDirectory.Entry> entries){return InstallationDirectory.CODEC.parse(NbtOps.INSTANCE,InstallationDirectory.Entry.CODEC.listOf().encodeStart(NbtOps.INSTANCE,entries).getOrThrow()).getOrThrow();}
    private static CameraDirectory cameras(List<CameraDirectory.Entry> entries){
        var tag=new net.minecraft.nbt.CompoundTag();tag.putInt("schema",1);tag.put("cameras",CameraDirectory.Entry.CODEC.listOf().encodeStart(NbtOps.INSTANCE,entries).getOrThrow());return CameraDirectory.CODEC.parse(NbtOps.INSTANCE,tag).getOrThrow();
    }
    private static NetworkLink link(GameTestHelper t,int x){return new NetworkLink(t.getLevel().dimension().identifier(),new BlockPos(x,1,1),UUID.randomUUID());}
    private static InstallationDirectory.Entry entry(int n,UUID owner,NetworkLink link,DeviceKind kind,String label){return new InstallationDirectory.Entry(new BlockPos(n,2,1),UUID.randomUUID(),owner,link,kind,label);}
    @GameTest(structure=STRUCTURE) public void savedDuplicatePositionsMoveBetweenOwnerNetworkAndTypeIndexes(GameTestHelper t){
        var a=UUID.randomUUID();var b=UUID.randomUUID();var first=link(t,1);var second=link(t,2);var old=entry(3,a,first,DeviceKind.DOCK,"Zulu");
        var replacement=new InstallationDirectory.Entry(old.position(),UUID.randomUUID(),b,second,DeviceKind.CAMERA,"Alfa");var other=entry(4,a,first,DeviceKind.DOCK,"Beta");var d=directory(List.of(old,other,replacement));
        t.assertTrue(d.entries(a,first).equals(List.of(other))&&d.entries(a,first,DeviceKind.DOCK).equals(List.of(other)),"Replacing one saved position removes its old owner, network and type indexes");
        t.assertTrue(d.entries(b,second,DeviceKind.CAMERA).equals(List.of(replacement))&&d.entries(a,second).isEmpty()&&d.entries(b,first).isEmpty(),"Indexed discovery never leaks across owners or networks");
        var roundtrip=InstallationDirectory.CODEC.parse(NbtOps.INSTANCE,InstallationDirectory.CODEC.encodeStart(NbtOps.INSTANCE,d).getOrThrow()).getOrThrow();
        t.assertTrue(roundtrip.entries(a,first).equals(List.of(other))&&roundtrip.entries(b,second).equals(List.of(replacement)),"Indexes are rebuilt from the unchanged persisted entry schema");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void installationCapacityCountsAllOwnersAndReclaimsExistingPositions(GameTestHelper t){
        var owner=UUID.randomUUID();var other=UUID.randomUUID();var network=link(t,1);var list=new ArrayList<InstallationDirectory.Entry>();
        for(int n=0;n<InstallationDirectory.PER_NETWORK;n++)list.add(entry(n,n%2==0?owner:other,network,DeviceKind.DOCK,"Equipo "+n));var d=directory(list);var existing=list.getFirst().position();var extra=new BlockPos(999,2,1);
        t.assertFalse(d.hasRoom(network,extra),"The cap counts all owners sharing a saved network");t.assertTrue(d.hasRoom(network,existing),"Refreshing the same position needs no additional slot");
        t.assertTrue(d.hasRoomFor(network,List.of(existing,existing))&&!d.hasRoomFor(network,List.of(existing,extra)),"Batch admission deduplicates positions and counts only new slots");
        d.remove(existing);t.assertTrue(d.hasRoom(network,extra)&&d.hasRoomFor(network,List.of(extra)),"Removing an indexed entry immediately frees exactly one slot");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void totalCapacityAndNetworkTransfersUseCurrentCounts(GameTestHelper t){
        var owner=UUID.randomUUID();var list=new ArrayList<InstallationDirectory.Entry>();var networks=new ArrayList<NetworkLink>();for(int n=0;n<32;n++)networks.add(link(t,n));
        for(int n=0;n<InstallationDirectory.TOTAL;n++)list.add(entry(n,owner,networks.get(n/128),DeviceKind.SENSOR,"Equipo"));var d=directory(list);var empty=link(t,99);var existing=list.getFirst().position();
        t.assertFalse(d.hasRoom(empty,new BlockPos(99999,2,1)),"The global cap still applies even to a new network");
        t.assertTrue(d.hasRoom(empty,existing)&&d.hasRoomFor(empty,List.of(existing)),"Moving an existing position does not consume a new global slot");d.remove(existing);
        t.assertTrue(d.hasRoom(empty,new BlockPos(99999,2,1)),"A removed position is reclaimed from the global count");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void renameInvalidatesBothOrderedViewsWithoutMutatingOldSnapshots(GameTestHelper t){
        var p=(ServerPlayer)t.makeMockServerPlayer(GameType.SURVIVAL);var corePos=new BlockPos(2,1,2);var cameraPos=new BlockPos(4,1,2);t.setBlock(corePos,UmbralContent.CORE);t.setBlock(cameraPos,UmbralContent.CAMERA);
        var core=t.getBlockEntity(corePos,DeviceBlockEntity.class);var camera=t.getBlockEntity(cameraPos,DeviceBlockEntity.class);core.initializeOwner(p.getUUID(),"Casa");camera.initializeOwner(p.getUUID(),"Zulu");camera.bind(p.getUUID(),core);
        var d=InstallationDirectory.get(t.getLevel());var c=CameraDirectory.get(t.getLevel());var old=d.entries(p.getUUID(),core.link());var oldCameras=c.cameras(p.getUUID(),core.link());long revision=d.revision(p.getUUID(),core.link());d.remember(camera);c.remember(camera);
        t.assertTrue(d.revision(p.getUUID(),core.link())==revision&&d.entries(p.getUUID(),core.link())==old&&c.cameras(p.getUUID(),core.link())==oldCameras,"Periodic unchanged refreshes retain their immutable views and revision");
        camera.rename(p.getUUID(),"Alfa");t.assertTrue(d.revision(p.getUUID(),core.link())!=revision&&d.entries(p.getUUID(),core.link()).getFirst().label().equals("Alfa"),"A real rename invalidates the cached canonical sort immediately");
        t.assertTrue(c.cameras(p.getUUID(),core.link()).getFirst().label().equals("Alfa")&&oldCameras.getFirst().label().equals("Zulu")&&old.stream().anyMatch(e->e.label().equals("Zulu")),"Surveillance and NEXO names agree while older snapshots remain immutable");t.succeed();
    }
    @GameTest(structure=STRUCTURE) public void cameraMigrationAndCapSurviveReloadAndRemoval(GameTestHelper t){
        var owner=UUID.randomUUID();var other=UUID.randomUUID();var network=link(t,1);var second=link(t,2);var list=new ArrayList<CameraDirectory.Entry>();
        for(int n=0;n<CameraDirectory.MAX_CAMERAS;n++)list.add(new CameraDirectory.Entry(new BlockPos(n,2,2),owner,network,"Cámara "+n));var existing=list.getFirst().position();list.add(new CameraDirectory.Entry(existing,other,second,"Nueva"));var d=cameras(list);
        t.assertTrue(d.cameras(owner,network).size()==63&&d.cameras(other,second).size()==1&&!d.knows(owner,network,existing),"A saved replacement removes the previous camera owner and network indexes");
        t.assertTrue(d.hasRoom(network,new BlockPos(1000,2,2))&&d.hasRoom(second,existing),"Migrating one camera reclaims its former network slot");
        var copy=CameraDirectory.CODEC.parse(NbtOps.INSTANCE,CameraDirectory.CODEC.encodeStart(NbtOps.INSTANCE,d).getOrThrow()).getOrThrow();copy.remove(existing);
        t.assertTrue(copy.cameras(other,second).isEmpty()&&copy.cameras(owner,network).size()==63,"Reload rebuilds independent camera buckets and removal reclaims the right bucket");t.succeed();
    }
}
