// SPDX-License-Identifier: MPL-2.0

package dev.umbral.test;

import dev.umbral.UmbralContent;
import dev.umbral.access.*;
import dev.umbral.access.AccessSetupProtocol.*;
import dev.umbral.block.*;
import dev.umbral.filter.*;
import dev.umbral.installation.InstallationDirectory;
import dev.umbral.network.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Server-authoritative setup using a real placed scanner kit and physical endpoint references. */
public final class AccessSetupGameTests {
    private static final String STRUCTURE=FilterGameTests.STRUCTURE;

    private FilterGameTests.Setup unbound(GameTestHelper t){
        var i=new FilterGameTests().setup(t);
        t.assertTrue(i.reader().unbind(i.owner().getUUID()),"Reader is available for first setup");
        t.assertTrue(i.door().unbind(i.owner().getUUID()),"Door is available for first setup");
        t.assertTrue(i.filter().unbind(i.owner().getUUID()),"Complete scanner is available for first setup");
        NetworkService.invalidate(t.getLevel());
        return i;
    }

    private Request request(FilterGameTests.Setup i,Operation op){
        return request(i.core(),op,i.door().asDoorTarget(),i.reader().asDoorTarget(),i.filter().asDoorTarget());
    }

    private Request request(DeviceBlockEntity root,Operation op,DoorTarget door,DoorTarget reader,DoorTarget filter){
        return new Request(root.getBlockPos(),root.identity(),root.link(),83,op,door,reader,filter);
    }

    private DeviceBlockEntity put(GameTestHelper t,BlockPos pos,DeviceBlock block,ServerPlayer owner){
        return new FilterGameTests().put(t,pos,block,owner);
    }

    private DeviceBlockEntity door(GameTestHelper t,BlockPos pos,ServerPlayer owner){
        t.setBlock(pos.below(),Blocks.STONE);
        var d=put(t,pos,UmbralContent.DOOR,owner);
        t.setBlock(pos.above(),UmbralContent.DOOR.defaultBlockState().setValue(SecurityDoorBlock.HALF,DoubleBlockHalf.UPPER));
        return d;
    }

    private record Saved(List<CompoundTag> data,List<BlockState> blocks,List<InstallationDirectory.Entry> directory,List<AccessRegistry.Grant> passes,ItemStack hand){}

    private List<DeviceBlockEntity> endpoints(FilterGameTests.Setup i,DeviceBlockEntity... extra){
        var devices=new ArrayList<>(List.of(i.door(),i.reader(),i.filter()));
        devices.addAll(Arrays.asList(extra));
        return devices;
    }

    private Saved remember(GameTestHelper t,FilterGameTests.Setup i,DeviceBlockEntity... extra){
        var devices=endpoints(i,extra);
        return new Saved(devices.stream().map(d->d.saveWithoutMetadata(t.getLevel().registryAccess())).toList(),devices.stream().map(DeviceBlockEntity::getBlockState).toList(),InstallationDirectory.get(t.getLevel()).entries(i.owner().getUUID(),i.core().link()),AccessRegistry.get(t.getLevel()).list(i.owner().getUUID(),i.core().link()),i.owner().getMainHandItem().copy());
    }

    private void unchanged(GameTestHelper t,FilterGameTests.Setup i,Saved before,DeviceBlockEntity... extra){
        var after=remember(t,i,extra);
        t.assertTrue(after.data().equals(before.data()),"Rejected or read-only setup preserves all endpoint settings and references");
        t.assertTrue(after.blocks().equals(before.blocks()),"Rejected or read-only setup does not open or replace blocks");
        t.assertTrue(after.directory().equals(before.directory()),"Rejected or read-only setup never partially registers endpoints");
        t.assertTrue(after.passes().equals(before.passes()),"Setup never creates access passes");
        t.assertTrue(after.hand().getItem()==before.hand().getItem()&&after.hand().getCount()==before.hand().getCount(),"Setup consumes no kit or held item");
    }

    private void rejected(GameTestHelper t,FilterGameTests.Setup i,Request r,String reason,DeviceBlockEntity... extra){
        var before=remember(t,i,extra);
        var result=AccessSetupProtocol.handle(i.owner(),r);
        t.assertTrue(result.valid()&&!result.ready()&&result.result().equals(reason),"Expected actionable rejection "+reason+", got "+result.result());
        unchanged(t,i,before,extra);
    }

    private void assertLinked(GameTestHelper t,FilterGameTests.Setup i){
        for(var d:List.of(i.door(),i.reader(),i.filter()))t.assertTrue(i.core().link().equals(d.link()),"Every selected endpoint joins the actual core");
        t.assertTrue(i.door().asDoorTarget().equals(i.reader().doorTarget()),"Reader targets the physical selected door");
        t.assertTrue(i.door().asDoorTarget().equals(i.filter().filter().door()),"Scanner targets the same physical door");
        t.assertFalse(i.filter().filter().armed(),"Setup does not arm inspection");
        t.assertTrue(i.filter().filter().mode()==FilterState.Mode.INFORM&&i.door().filterGuard()==null,"Setup does not silently impose an access policy");
        t.assertTrue(AccessRegistry.get(t.getLevel()).list(i.owner().getUUID(),i.core().link()).isEmpty(),"Pass creation remains explicit");
        t.assertFalse(i.door().getBlockState().getValue(SecurityDoorBlock.OPEN),"Setup keeps the actual door closed");
    }

    @GameTest(structure=STRUCTURE) public void queryAndReviewAreReadOnly(GameTestHelper t){
        var i=unbound(t);var before=remember(t,i);
        var query=AccessSetupProtocol.handle(i.owner(),request(i,Operation.QUERY));
        t.assertTrue(query.valid()&&query.ready()&&query.result().equals("ready"),"Complete unbound access is discoverable");
        t.assertTrue(query.choices().size()==3&&query.choices().stream().filter(c->c.kind()==DeviceKind.FILTER).count()==1,"Seven scanner parts appear as one selectable device");
        unchanged(t,i,before);
        var review=AccessSetupProtocol.handle(i.owner(),request(i,Operation.REVIEW));
        t.assertTrue(review.valid()&&review.ready()&&review.result().equals("review"),"Review confirms a valid plan");
        unchanged(t,i,before);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void applyBindsAndAssociatesWithoutArmingOrIssuingPasses(GameTestHelper t){
        var i=unbound(t);var rules=i.filter().filter().rules();int kits=i.owner().getMainHandItem().getCount();
        var result=AccessSetupProtocol.handle(i.owner(),request(i,Operation.APPLY));
        t.assertTrue(result.valid()&&result.result().equals("saved"),"Nearby first setup saves online");
        assertLinked(t,i);
        t.assertTrue(i.filter().filter().rules().equals(rules)&&i.owner().getMainHandItem().getCount()==kits,"Existing inspection rules and physical kit remain intact");
        t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void scannerIsOptionalAndRemainsUntouchedWhenOmitted(GameTestHelper t){
        var i=unbound(t);var filter=i.filter().saveWithoutMetadata(t.getLevel().registryAccess());
        var result=AccessSetupProtocol.handle(i.owner(),request(i.core(),Operation.APPLY,i.door().asDoorTarget(),i.reader().asDoorTarget(),null));
        t.assertTrue(result.result().equals("saved")&&i.core().link().equals(i.door().link())&&i.core().link().equals(i.reader().link()),"Door and reader form an access without inspection");
        t.assertTrue(i.reader().doorTarget().equals(i.door().asDoorTarget()),"Reader association is saved");
        t.assertTrue(filter.equals(i.filter().saveWithoutMetadata(t.getLevel().registryAccess())),"Unselected scanner is not bound or configured");
        t.assertTrue(AccessRegistry.get(t.getLevel()).list(i.owner().getUUID(),i.core().link()).isEmpty(),"No implicit access passes");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void repeatedApplyIsIdempotent(GameTestHelper t){
        var i=unbound(t);var r=request(i,Operation.APPLY);
        t.assertTrue(AccessSetupProtocol.handle(i.owner(),r).result().equals("saved"),"First save succeeds");
        var before=remember(t,i);
        t.assertTrue(AccessSetupProtocol.handle(i.owner(),r).result().equals("saved"),"Repeated save safely acknowledges existing setup");
        unchanged(t,i,before);assertLinked(t,i);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void onlineTerminalControlsTheCoresSetup(GameTestHelper t){
        var i=unbound(t);var terminal=put(t,new BlockPos(2,1,3),UmbralContent.TERMINAL,i.owner());
        t.assertTrue(terminal.bind(i.owner().getUUID(),i.core()),"Terminal joins the installation");
        i.owner().setPos(Vec3.atCenterOf(terminal.getBlockPos()));
        var result=AccessSetupProtocol.handle(i.owner(),request(terminal,Operation.APPLY,i.door().asDoorTarget(),i.reader().asDoorTarget(),i.filter().asDoorTarget()));
        t.assertTrue(result.result().equals("saved"),"Online owned terminal is a valid setup root");
        assertLinked(t,i);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void foreignConsoleUseRevealsNoChoicesOrAuthority(GameTestHelper t){
        var i=unbound(t);var before=remember(t,i);
        var result=AccessSetupProtocol.handle(i.visitor(),request(i,Operation.APPLY));
        t.assertFalse(result.valid(),"Nearby non-owner cannot operate the root");
        t.assertTrue(result.choices().isEmpty(),"A forged request leaks no private discovery");
        unchanged(t,i,before);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void foreignEndpointIsNeitherDiscoveredNorSelectable(GameTestHelper t){
        var i=unbound(t);var foreign=put(t,new BlockPos(7,1,5),UmbralContent.READER,i.visitor());
        var query=AccessSetupProtocol.handle(i.owner(),request(i,Operation.QUERY));
        t.assertTrue(query.choices().stream().noneMatch(c->c.reference().equals(foreign.asDoorTarget())),"Foreign endpoint is excluded even within reach");
        rejected(t,i,request(i.core(),Operation.APPLY,i.door().asDoorTarget(),foreign.asDoorTarget(),i.filter().asDoorTarget()),"changed",foreign);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void nearerDoorsCannotStarveDiscoveryOfAFartherValidReader(GameTestHelper t){
        var i=unbound(t);t.setBlock(FilterGameTests.READER,Blocks.AIR);
        int nearerDoors=0;
        for(int x=0;x<=12&&nearerDoors<96;x++)for(int z=0;z<8&&nearerDoors<96;z++){
            var pos=new BlockPos(x,4,z);
            if(!t.getBlockState(pos.below()).isAir())continue;
            var nearby=door(t,pos,i.owner());
            t.assertTrue(nearby.bind(i.owner().getUUID(),i.core()),"A large valid installation contains nearer owned doors");nearerDoors++;
        }
        t.assertTrue(nearerDoors==96,"Fixture exceeds the old shared discovery budget");
        var target=door(t,new BlockPos(13,1,1),i.owner());
        t.assertTrue(target.bind(i.owner().getUUID(),i.core()),"Farther access door belongs to the same installation");
        var reader=put(t,new BlockPos(15,1,1),UmbralContent.READER,i.owner());
        var result=AccessSetupProtocol.handle(i.owner(),request(i.core(),Operation.QUERY,target.asDoorTarget(),reader.asDoorTarget(),null));
        t.assertTrue(result.valid()&&result.ready(),"Farther reader is within setup range and two blocks from its door");
        t.assertTrue(result.choices().stream().filter(c->c.kind()==DeviceKind.DOOR).count()==98,"All nearer doors remain discoverable within their own budget");
        t.assertTrue(result.choices().stream().anyMatch(c->c.reference().equals(reader.asDoorTarget())&&c.reason().equals("ready")),"Door population cannot consume the reader discovery budget");
        t.assertTrue(reader.link()==null&&reader.doorTarget()==null,"Discovery does not bind or configure the discovered reader");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void replacedDoorCannotInheritReviewedIdentity(GameTestHelper t){
        var i=unbound(t);var r=request(i,Operation.APPLY);
        t.setBlock(FilterGameTests.DOOR,Blocks.AIR);var replacement=door(t,FilterGameTests.DOOR,i.owner());
        t.assertFalse(replacement.identity().equals(r.door().identity()),"Replacement has a new physical identity");
        rejected(t,i,r,"changed",replacement);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void replacedReaderCannotInheritReviewedIdentity(GameTestHelper t){
        var i=unbound(t);var r=request(i,Operation.APPLY);
        t.setBlock(FilterGameTests.READER,Blocks.AIR);var replacement=put(t,FilterGameTests.READER,UmbralContent.READER,i.owner());
        rejected(t,i,r,"changed",replacement);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void staleRootAndForgedNetworkAreRejectedWithoutDiscovery(GameTestHelper t){
        var i=unbound(t);var r=request(i,Operation.APPLY);var before=remember(t,i);
        var forged=new Request(r.root(),r.identity(),new NetworkLink(r.link().dimension(),r.link().core(),UUID.randomUUID()),r.nonce(),r.operation(),r.door(),r.reader(),r.filter());
        var denied=AccessSetupProtocol.handle(i.owner(),forged);
        t.assertTrue(!denied.valid()&&denied.choices().isEmpty(),"Network identity is checked against the physical root");unchanged(t,i,before);
        t.setBlock(FilterGameTests.CORE,Blocks.AIR);put(t,FilterGameTests.CORE,UmbralContent.CORE,i.owner());before=remember(t,i);
        denied=AccessSetupProtocol.handle(i.owner(),r);
        t.assertTrue(!denied.valid()&&denied.choices().isEmpty(),"Same-position replacement is not the reviewed root");unchanged(t,i,before);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void crossDimensionEndpointReferenceIsRejected(GameTestHelper t){
        var i=unbound(t);var ref=i.door().asDoorTarget();var wrongDimension=new DoorTarget(Level.NETHER.identifier(),ref.position(),ref.identity());
        rejected(t,i,request(i.core(),Operation.APPLY,wrongDimension,i.reader().asDoorTarget(),i.filter().asDoorTarget()),"changed");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void rootControlStopsBeyondEightBlocks(GameTestHelper t){
        var i=unbound(t);var r=request(i,Operation.APPLY);var before=remember(t,i);
        i.owner().setPos(Vec3.atCenterOf(i.core().getBlockPos()).add(8,0,0));
        var boundary=AccessSetupProtocol.handle(i.owner(),request(i,Operation.REVIEW));
        t.assertTrue(boundary.valid()&&boundary.result().equals("review"),"Exactly eight blocks from the console is allowed");
        i.owner().setPos(Vec3.atCenterOf(i.core().getBlockPos()).add(8.01,0,0));
        var denied=AccessSetupProtocol.handle(i.owner(),r);
        t.assertTrue(!denied.valid()&&denied.choices().isEmpty(),"Leaving console range invalidates a previously reviewed request");
        unchanged(t,i,before);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void endpointAtSixteenBlockBoundaryCanBeSaved(GameTestHelper t){
        var i=unbound(t);var d=door(t,new BlockPos(17,1,1),i.owner());var reader=put(t,new BlockPos(16,1,1),UmbralContent.READER,i.owner());
        var r=request(i.core(),Operation.APPLY,d.asDoorTarget(),reader.asDoorTarget(),null);
        var result=AccessSetupProtocol.handle(i.owner(),r);
        t.assertTrue(result.result().startsWith("saved"),"Exactly sixteen blocks is within the setup sphere");
        t.assertTrue(result.choices().stream().anyMatch(c->c.reference().equals(d.asDoorTarget())),"Boundary endpoint is discoverable");
        t.assertTrue(reader.doorTarget().equals(d.asDoorTarget())&&i.core().link().equals(d.link()),"Boundary endpoint is physically bound");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void endpointBeyondSixteenBlocksIsHiddenAndRejected(GameTestHelper t){
        var i=unbound(t);var d=door(t,new BlockPos(18,1,1),i.owner());var reader=put(t,new BlockPos(17,1,1),UmbralContent.READER,i.owner());
        var result=AccessSetupProtocol.handle(i.owner(),request(i,Operation.QUERY));
        t.assertTrue(result.choices().stream().noneMatch(c->c.reference().equals(d.asDoorTarget())),"Out-of-range endpoint is not a choice");
        rejected(t,i,request(i.core(),Operation.APPLY,d.asDoorTarget(),reader.asDoorTarget(),null),"changed",d,reader);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void readerBeyondFourBlocksCannotPartiallyBind(GameTestHelper t){
        var i=unbound(t);var reader=put(t,new BlockPos(10,1,6),UmbralContent.READER,i.owner());
        rejected(t,i,request(i.core(),Operation.APPLY,i.door().asDoorTarget(),reader.asDoorTarget(),i.filter().asDoorTarget()),"reader_far",reader);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void endpointInAnotherNetworkIsHiddenAndPreserved(GameTestHelper t){
        var i=unbound(t);var otherCore=put(t,new BlockPos(9,1,1),UmbralContent.CORE,i.owner());
        t.assertTrue(i.reader().bind(i.owner().getUUID(),otherCore),"Reader belongs to another real installation");
        var query=AccessSetupProtocol.handle(i.owner(),request(i,Operation.QUERY));
        t.assertTrue(query.choices().stream().noneMatch(c->c.reference().equals(i.reader().asDoorTarget())),"Another installation is excluded from nearby discovery");
        rejected(t,i,request(i,Operation.APPLY),"changed",otherCore);
        t.assertTrue(i.reader().link().equals(otherCore.link()),"Rejected setup never steals a device from its network");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void misalignedScannerCannotPartiallyBind(GameTestHelper t){
        var i=unbound(t);var d=door(t,new BlockPos(7,1,6),i.owner());
        rejected(t,i,request(i.core(),Operation.APPLY,d.asDoorTarget(),i.reader().asDoorTarget(),i.filter().asDoorTarget()),"filter_position",d);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void scannerAndDoorMustFaceTheSameWay(GameTestHelper t){
        var i=unbound(t);
        t.setBlock(FilterGameTests.DOOR,i.door().getBlockState().setValue(DeviceBlock.FACING,Direction.EAST));
        t.setBlock(FilterGameTests.DOOR.above(),t.getBlockState(FilterGameTests.DOOR.above()).setValue(DeviceBlock.FACING,Direction.EAST));
        rejected(t,i,request(i,Operation.APPLY),"filter_facing");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void incompleteRealScannerCannotPartiallyBind(GameTestHelper t){
        var i=unbound(t);var missing=FilterBlock.partPos(i.filter().getBlockPos(),Direction.NORTH,FilterBlock.Part.LINTEL);
        t.getLevel().setBlock(missing,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        t.assertTrue(t.getLevel().getBlockEntity(i.filter().getBlockPos())==i.filter()&&!FilterBlock.complete(i.filter()),"Controller survives a missing physical arch part");
        rejected(t,i,request(i,Operation.APPLY),"filter_incomplete");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void armedScannerRequiresExplicitDisarmBeforeSetup(GameTestHelper t){
        var i=unbound(t);t.assertTrue(i.filter().bind(i.owner().getUUID(),i.core()),"Scanner already belongs to the network");
        t.assertTrue(FilterService.arm(i.filter(),true).equals("armed"),"Real complete online scanner arms");
        rejected(t,i,request(i,Operation.APPLY),"filter_active");
        t.assertTrue(i.filter().filter().armed(),"Wizard cannot disarm an existing policy as a side effect");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void activeAirlockDoorsAreNeverReassigned(GameTestHelper t){
        var helper=new AirlockGameTests();var airlock=helper.setup(t);helper.enable(t,airlock);var i=airlock.base();
        rejected(t,i,request(i,Operation.APPLY),"door_busy",airlock.outer());
        t.assertTrue(i.filter().airlock().enabled()&&i.door().airlockLink()!=null&&airlock.outer().airlockLink()!=null,"Both interlocks and the live controller remain intact");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void activeAirlockScannerCannotBeBorrowedForAnotherDoor(GameTestHelper t){
        var helper=new AirlockGameTests();var airlock=helper.setup(t);helper.enable(t,airlock);var i=airlock.base();
        var d=door(t,new BlockPos(7,1,6),i.owner());var reader=put(t,new BlockPos(8,1,6),UmbralContent.READER,i.owner());
        rejected(t,i,request(i.core(),Operation.APPLY,d.asDoorTarget(),reader.asDoorTarget(),i.filter().asDoorTarget()),"filter_active",airlock.outer(),d,reader);t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void existingReaderAssociationRequiresExplicitReassignment(GameTestHelper t){
        var i=unbound(t);var other=door(t,new BlockPos(7,1,6),i.owner());
        t.assertTrue(other.bind(i.owner().getUUID(),i.core())&&i.reader().bind(i.owner().getUUID(),i.core())&&i.reader().targetDoor(i.owner().getUUID(),other),"Existing reader association is real and valid");
        rejected(t,i,request(i,Operation.APPLY),"reader_busy",other);
        t.assertTrue(i.reader().doorTarget().equals(other.asDoorTarget()),"Existing reader target is retained");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void existingScannerAssociationRequiresExplicitReassignment(GameTestHelper t){
        var i=unbound(t);var other=door(t,new BlockPos(5,1,7),i.owner());
        t.assertTrue(other.bind(i.owner().getUUID(),i.core())&&i.filter().bind(i.owner().getUUID(),i.core()),"Existing scanner and door share an installation");
        t.assertTrue(FilterService.link(i.owner(),i.filter(),other.asDoorTarget()).equals("door_linked"),"Other aligned physical door is already linked");
        rejected(t,i,request(i,Operation.APPLY),"filter_busy",other);
        t.assertTrue(i.filter().filter().door().equals(other.asDoorTarget()),"Existing scanner target is retained");t.succeed();
    }

    private void fillDirectory(GameTestHelper t,FilterGameTests.Setup i,int size){
        var directory=InstallationDirectory.get(t.getLevel());int next=0;
        while(directory.entries(i.owner().getUUID(),i.core().link()).size()<size){
            var node=put(t,new BlockPos(10+next%32,1+next/32,2),UmbralContent.NODE,i.owner());
            t.assertTrue(node.bind(i.owner().getUUID(),i.core()),"Capacity fixture binds an owned actual device");next++;
            t.assertTrue(next<=128,"Capacity fixture stays inside the test structure");
        }
    }

    @GameTest(structure=STRUCTURE) public void directoryRejectsWholeSetupBeforeFirstBinding(GameTestHelper t){
        var i=unbound(t);fillDirectory(t,i,InstallationDirectory.PER_NETWORK-1);
        rejected(t,i,request(i.core(),Operation.APPLY,i.door().asDoorTarget(),i.reader().asDoorTarget(),null),"directory_full");
        t.assertTrue(i.door().link()==null&&i.reader().link()==null,"One remaining slot cannot partially register a two-device access");t.succeed();
    }

    @GameTest(structure=STRUCTURE) public void exactDirectoryCapacitySavesAndRepeatedApplyStaysSafe(GameTestHelper t){
        var i=unbound(t);fillDirectory(t,i,InstallationDirectory.PER_NETWORK-2);
        var r=request(i.core(),Operation.APPLY,i.door().asDoorTarget(),i.reader().asDoorTarget(),null);
        t.assertTrue(AccessSetupProtocol.handle(i.owner(),r).result().equals("saved"),"Exactly two remaining slots accept both selected endpoints");
        t.assertTrue(InstallationDirectory.get(t.getLevel()).entries(i.owner().getUUID(),i.core().link()).size()==InstallationDirectory.PER_NETWORK,"Capacity is reached without duplicate entries");
        var before=remember(t,i);
        t.assertTrue(AccessSetupProtocol.handle(i.owner(),r).result().equals("saved"),"A full directory still accepts an unchanged existing setup");
        unchanged(t,i,before);t.succeed();
    }
}
