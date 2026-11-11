// SPDX-License-Identifier: MPL-2.0

package dev.umbral;

import dev.umbral.block.CopperCableBlock;
import dev.umbral.block.DeviceBlock;
import dev.umbral.block.DeviceBlockEntity;
import dev.umbral.block.DeviceKind;
import dev.umbral.block.SecurityDoorBlock;
import dev.umbral.block.CardReaderBlock;
import dev.umbral.block.CameraBlock;
import dev.umbral.block.BaluarteBlock;
import dev.umbral.block.TerminalBlock;
import dev.umbral.turret.TurretBolt;
import dev.umbral.turret.NuggetBolt;
import dev.umbral.logistics.*;
import dev.umbral.block.SafetyBlock;
import dev.umbral.block.NexoDeviceBlock;
import dev.umbral.item.MotaItem;
import dev.umbral.block.CentellaBlock;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import dev.umbral.access.DoorTarget;
import dev.umbral.item.AccessCardItem;
import dev.umbral.item.ConfiguratorItem;
import dev.umbral.network.NetworkLink;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class UmbralContent {
    public static final DataComponentType<StockSeal> STOCK_SEAL = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,id("stock_seal"), DataComponentType.<StockSeal>builder().persistent(StockSeal.CODEC).build());
    public static final DataComponentType<DoorTarget> SELECTED_STOCK = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,id("selected_stock"), DataComponentType.<DoorTarget>builder().persistent(DoorTarget.CODEC).networkSynchronized(DoorTarget.STREAM_CODEC).build());
    public static final DataComponentType<java.util.UUID> CARD_ID = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, id("card_id"),
            DataComponentType.<java.util.UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DataComponentType<DoorTarget> SELECTED_DOOR = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, id("selected_door"),
            DataComponentType.<DoorTarget>builder().persistent(DoorTarget.CODEC).networkSynchronized(DoorTarget.STREAM_CODEC).build());
    public static final DataComponentType<NetworkLink> SELECTED_NETWORK = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, id("selected_network"),
            DataComponentType.<NetworkLink>builder().persistent(NetworkLink.CODEC).networkSynchronized(NetworkLink.STREAM_CODEC).build());

    public static final DeviceBlock FENCE=registerDevice(DeviceKind.FENCE);
    public static final DeviceBlock FILTER=registerDevice(DeviceKind.FILTER);
    public static final DeviceBlock CORE = registerDevice(DeviceKind.CORE);
    public static final DeviceBlock NODE = registerDevice(DeviceKind.RELAY);
    public static final DeviceBlock TERMINAL = registerDevice(DeviceKind.TERMINAL);
    public static final DeviceBlock CAMERA = registerDevice(DeviceKind.CAMERA);
    public static final DeviceBlock DOOR = registerDevice(DeviceKind.DOOR);
    public static final DeviceBlock READER = registerDevice(DeviceKind.READER);
    public static final DeviceBlock TURRET = registerDevice(DeviceKind.TURRET);
    public static final DeviceBlock CENTELLA = registerDevice(DeviceKind.CENTELLA);
    public static final DeviceBlock SENSOR=registerDevice(DeviceKind.SENSOR);
    public static final DeviceBlock PULSE_MINE=registerDevice(DeviceKind.PULSE_MINE);
    public static final DeviceBlock NEXO=registerDevice(DeviceKind.NEXO);
    public static final DeviceBlock DOCK=registerDevice(DeviceKind.DOCK);
    public static final EntityType<MotaEntity> MOTA_ENTITY=Registry.register(BuiltInRegistries.ENTITY_TYPE,id("mota"),
            EntityType.Builder.<MotaEntity>of(MotaEntity::new,MobCategory.MISC).sized(1.08f,.66f).clientTrackingRange(6).updateInterval(1).build(ResourceKey.create(Registries.ENTITY_TYPE,id("mota"))));
    public static final EntityType<NuggetBolt> NUGGET_BOLT = Registry.register(BuiltInRegistries.ENTITY_TYPE,id("centella_bolt"),
            EntityType.Builder.<NuggetBolt>of(NuggetBolt::new,MobCategory.MISC).sized(.25f,.25f).clientTrackingRange(4).updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,id("centella_bolt"))));
    public static final EntityType<TurretBolt> TURRET_BOLT = Registry.register(BuiltInRegistries.ENTITY_TYPE,id("baluarte_bolt"),
            EntityType.Builder.<TurretBolt>of(TurretBolt::new,MobCategory.MISC).sized(.5f,.5f).clientTrackingRange(4).updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,id("baluarte_bolt"))));
    public static final CopperCableBlock CABLE = registerCable();
    public static final Item CONFIGURATOR = Registry.register(BuiltInRegistries.ITEM, id("configurator"),
            new ConfiguratorItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("configurator"))).stacksTo(1)));
    public static final Item FIELD_MANUAL=Registry.register(BuiltInRegistries.ITEM,id("field_manual"),new dev.umbral.item.FieldManualItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id("field_manual"))).stacksTo(1)));
    public static final Item PROTECTION_MATRIX=Registry.register(BuiltInRegistries.ITEM,id("protection_matrix"),new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id("protection_matrix"))).stacksTo(16)));
    public static final Item MOTA=Registry.register(BuiltInRegistries.ITEM,id("mota"),new MotaItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id("mota"))).stacksTo(1)));
    public static final Item ACCESS_CARD = Registry.register(BuiltInRegistries.ITEM, id("access_card"),
            new AccessCardItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("access_card"))).stacksTo(16)));
    public static final BlockEntityType<DeviceBlockEntity> DEVICE_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, id("device"),
            FabricBlockEntityTypeBuilder.create(DeviceBlockEntity::new, CORE, NODE, TERMINAL, CAMERA, DOOR, READER, TURRET, CENTELLA, SENSOR, PULSE_MINE, NEXO, DOCK, FILTER, FENCE).build());

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(Umbral.MOD_ID, path); }

    private static DeviceBlock registerDevice(DeviceKind kind) {
        var key = ResourceKey.create(Registries.BLOCK, id(kind.id));
        var properties = BlockBehaviour.Properties.of().setId(key).mapColor(MapColor.METAL)
                .strength(kind == DeviceKind.DOOR ? 5f : 3.5f, kind == DeviceKind.DOOR ? 1200f : 6f)
                .sound(SoundType.COPPER).noOcclusion().pushReaction(PushReaction.IMMOVEABLE);
        if (kind == DeviceKind.CAMERA || kind == DeviceKind.DOOR || kind == DeviceKind.FILTER || kind == DeviceKind.FENCE) properties.dynamicShape();
        DeviceBlock block = switch (kind) {
            case FENCE -> new dev.umbral.block.ElectricFenceBlock(properties);
            case FILTER -> new dev.umbral.block.FilterBlock(properties);
            case TERMINAL -> new TerminalBlock(properties);
            case CAMERA -> new CameraBlock(properties);
            case DOOR -> new SecurityDoorBlock(properties);
            case READER -> new CardReaderBlock(properties);
            case TURRET -> new BaluarteBlock(properties);
            case CENTELLA -> new CentellaBlock(properties);
            case SENSOR, PULSE_MINE -> new SafetyBlock(kind,properties);
            case NEXO, DOCK -> new NexoDeviceBlock(kind,properties);
            default -> new DeviceBlock(kind, properties);
        };
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        registerBlockItem(kind.id, block);
        return block;
    }

    private static CopperCableBlock registerCable() {
        var key = ResourceKey.create(Registries.BLOCK, id("copper_cable"));
        var block = new CopperCableBlock(BlockBehaviour.Properties.of().setId(key).mapColor(MapColor.COLOR_ORANGE)
                .strength(0.8f).sound(SoundType.COPPER).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        registerBlockItem("copper_cable", block);
        return block;
    }

    private static void registerBlockItem(String path, Block block) {
        var key = ResourceKey.create(Registries.ITEM, id(path));
        Registry.register(BuiltInRegistries.ITEM, key, block instanceof dev.umbral.block.FilterBlock?new dev.umbral.item.FilterBlockItem(block,new Item.Properties().setId(key).useBlockDescriptionPrefix()):new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    public static void initialize() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("equipment"), FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.umbral.equipment"))
                .icon(() -> new ItemStack(CORE))
                .displayItems((parameters, output) -> {
                    output.accept(FIELD_MANUAL);output.accept(CORE); output.accept(NODE); output.accept(CABLE);
                    output.accept(TERMINAL); output.accept(CAMERA); output.accept(CONFIGURATOR);
                    output.accept(DOOR); output.accept(READER); output.accept(FILTER);output.accept(FENCE); output.accept(ACCESS_CARD);
                    output.accept(TURRET); output.accept(CENTELLA);output.accept(SENSOR);output.accept(PULSE_MINE);output.accept(NEXO);output.accept(DOCK);output.accept(MOTA);output.accept(PROTECTION_MATRIX);
                }).build());
    }

    private UmbralContent() { }
}
