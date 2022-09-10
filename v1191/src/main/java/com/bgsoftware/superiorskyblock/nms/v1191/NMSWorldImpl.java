package com.bgsoftware.superiorskyblock.nms.v1191;

import com.bgsoftware.common.reflection.ReflectField;
import com.bgsoftware.common.reflection.ReflectMethod;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.Materials;
import com.bgsoftware.superiorskyblock.core.SchematicBlock;
import com.bgsoftware.superiorskyblock.core.Singleton;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.key.KeyImpl;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.listener.SignsListener;
import com.bgsoftware.superiorskyblock.nms.ICachedBlock;
import com.bgsoftware.superiorskyblock.nms.NMSWorld;
import com.bgsoftware.superiorskyblock.nms.algorithms.NMSCachedBlock;
import com.bgsoftware.superiorskyblock.nms.v1191.generator.IslandsGeneratorImpl;
import com.bgsoftware.superiorskyblock.nms.v1191.spawners.BaseSpawnerNotifier;
import com.bgsoftware.superiorskyblock.nms.v1191.world.PropertiesMapper;
import com.bgsoftware.superiorskyblock.tag.CompoundTag;
import com.destroystokyo.paper.antixray.ChunkPacketBlockController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.craftbukkit.v1_19_R1.CraftChunk;
import org.bukkit.craftbukkit.v1_19_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_19_R1.block.CraftBlock;
import org.bukkit.craftbukkit.v1_19_R1.block.CraftSign;
import org.bukkit.craftbukkit.v1_19_R1.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v1_19_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.generator.ChunkGenerator;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.IntFunction;

public class NMSWorldImpl implements NMSWorld {

    private static final ReflectMethod<Object> LINES_SIGN_CHANGE_EVENT = new ReflectMethod<>(SignChangeEvent.class, "lines");
    private static final ReflectField<Object> CHUNK_PACKET_BLOCK_CONTROLLER = new ReflectField<>(Level.class,
            Object.class, "chunkPacketBlockController")
            .removeFinal();
    private static final ReflectField<BaseSpawner> BAES_SPAWNER = new ReflectField<BaseSpawner>(
            SpawnerBlockEntity.class, BaseSpawner.class, Modifier.PRIVATE | Modifier.FINAL, 1)
            .removeFinal();

    private final SuperiorSkyblockPlugin plugin;
    private final Singleton<SignsListener> signsListener;

    public NMSWorldImpl(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.signsListener = plugin.getListener(SignsListener.class);
    }

    @Override
    public Key getBlockKey(ChunkSnapshot chunkSnapshot, int x, int y, int z) {
        BlockState blockState = ((CraftBlockData) chunkSnapshot.getBlockData(x, y, z)).getState();
        Material type = chunkSnapshot.getBlockType(x, y, z);
        short data = (short) (Block.getId(blockState) >> 12 & 15);

        Location location = new Location(
                Bukkit.getWorld(chunkSnapshot.getWorldName()),
                (chunkSnapshot.getX() << 4) + x,
                y,
                (chunkSnapshot.getZ() << 4) + z
        );

        return KeyImpl.of(KeyImpl.of(type, data), location);
    }

    @Override
    public void listenSpawner(CreatureSpawner creatureSpawner, IntFunction<Integer> delayChangeCallback) {
        Location location = creatureSpawner.getLocation();
        org.bukkit.World world = location.getWorld();

        if (world == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (!(blockEntity instanceof SpawnerBlockEntity spawnerBlockEntity))
            return;

        BaseSpawner baseSpawner = spawnerBlockEntity.getSpawner();

        if (!(baseSpawner instanceof BaseSpawnerNotifier)) {
            BaseSpawnerNotifier baseSpawnerNotifier = new BaseSpawnerNotifier(baseSpawner, delayChangeCallback);
            BAES_SPAWNER.set(spawnerBlockEntity, baseSpawnerNotifier);
            baseSpawnerNotifier.updateDelay();
        }
    }

    @Override
    public void setWorldBorder(SuperiorPlayer superiorPlayer, Island island) {
        try {
            if (!plugin.getSettings().isWorldBorders())
                return;

            boolean disabled = !superiorPlayer.hasWorldBorderEnabled();

            Player player = superiorPlayer.asPlayer();
            org.bukkit.World world = superiorPlayer.getWorld();

            if (world == null || player == null)
                return;

            ServerLevel serverLevel = ((CraftWorld) world).getHandle();

            WorldBorder worldBorder;

            if (disabled || island == null || (!plugin.getSettings().getSpawn().isWorldBorder() && island.isSpawn())) {
                worldBorder = serverLevel.getWorldBorder();
            } else {
                worldBorder = new WorldBorder();
                worldBorder.world = serverLevel;

                org.bukkit.World.Environment environment = world.getEnvironment();
                Location center = island.getCenter(environment);

                worldBorder.setWarningBlocks(0);
                worldBorder.setSize((island.getIslandSize() * 2) + 1);
                worldBorder.setCenter(center.getX(), center.getZ());

                double worldBorderSize = worldBorder.getSize();
                switch (superiorPlayer.getBorderColor()) {
                    case GREEN -> worldBorder.lerpSizeBetween(worldBorderSize - 0.1D, worldBorderSize, Long.MAX_VALUE);
                    case RED -> worldBorder.lerpSizeBetween(worldBorderSize, worldBorderSize - 1.0D, Long.MAX_VALUE);
                }
            }

            ClientboundInitializeBorderPacket initializeBorderPacket = new ClientboundInitializeBorderPacket(worldBorder);
            ((CraftPlayer) player).getHandle().connection.send(initializeBorderPacket);
        } catch (NullPointerException ignored) {
        }
    }

    @Override
    public Object getBlockData(org.bukkit.block.Block block) {
        return block.getBlockData();
    }

    @Override
    public void setBlocks(org.bukkit.Chunk bukkitChunk, List<SchematicBlock> blockDataList) {
        LevelChunk levelChunk = ((CraftChunk) bukkitChunk).getHandle();
        blockDataList.forEach(blockData -> {
            NMSUtils.setBlock(levelChunk, new BlockPos(blockData.getX(), blockData.getY(), blockData.getZ()),
                    blockData.getCombinedId(), blockData.getStatesTag(), blockData.getTileEntityData());
        });
        setBiome(levelChunk, IslandUtils.getDefaultWorldBiome(bukkitChunk.getWorld().getEnvironment()));
    }

    @Override
    public void setBlock(Location location, int combinedId) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        NMSUtils.setBlock(serverLevel.getChunkAt(blockPos), blockPos, combinedId, null, null);

        ClientboundBlockUpdatePacket blockUpdatePacket = new ClientboundBlockUpdatePacket(serverLevel, blockPos);
        NMSUtils.sendPacketToRelevantPlayers(serverLevel, blockPos.getX() >> 4, blockPos.getZ() >> 4, blockUpdatePacket);
    }

    @Override
    public ICachedBlock cacheBlock(org.bukkit.block.Block block) {
        return new NMSCachedBlock(block);
    }

    @Override
    public CompoundTag readBlockStates(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return null;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getX(), location.getY(), location.getZ());
        BlockState blockState = serverLevel.getBlockState(blockPos);

        if (blockState.getValues().isEmpty())
            return null;

        CompoundTag compoundTag = new CompoundTag();

        blockState.getValues().forEach((property, value) -> {
            String name = property.getName();

            if (property instanceof BooleanProperty) {
                compoundTag.setByte(name, (Boolean) value ? (byte) 1 : 0);
            } else if (property instanceof IntegerProperty integerProperty) {
                compoundTag.setIntArray(name, new int[]{(Integer) value, integerProperty.min, integerProperty.max});
            } else if (property instanceof EnumProperty<?>) {
                compoundTag.setString(PropertiesMapper.getPropertyName(property), ((Enum<?>) value).name());
            }
        });

        return compoundTag;
    }

    @Override
    public byte[] getLightLevels(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return new byte[0];

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        LevelLightEngine lightEngine = serverLevel.getLightEngine();
        return new byte[]{
                location.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL ? 0 :
                        (byte) lightEngine.getLayerListener(LightLayer.SKY).getLightValue(blockPos),
                (byte) lightEngine.getLayerListener(LightLayer.BLOCK).getLightValue(blockPos)
        };
    }

    @Override
    public CompoundTag readTileEntity(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return null;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (blockEntity == null)
            return null;

        net.minecraft.nbt.CompoundTag compoundTag = blockEntity.saveWithFullMetadata();

        compoundTag.remove("x");
        compoundTag.remove("y");
        compoundTag.remove("z");

        return CompoundTag.fromNBT(compoundTag);
    }

    @Override
    public boolean isWaterLogged(org.bukkit.block.Block block) {
        if (Materials.isWater(block.getType()))
            return true;

        BlockData blockData = block.getBlockData();

        return blockData instanceof Waterlogged && ((Waterlogged) blockData).isWaterlogged();
    }

    @Override
    public int getDefaultAmount(org.bukkit.block.Block bukkitBlock) {
        BlockState blockState = ((CraftBlock) bukkitBlock).getNMS();
        Block block = blockState.getBlock();
        return NMSUtils.isDoubleBlock(block, blockState) ? 2 : 1;
    }

    @Override
    public void placeSign(Island island, Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);

        if (!(blockEntity instanceof SignBlockEntity signBlockEntity))
            return;

        String[] lines = new String[4];
        System.arraycopy(CraftSign.revertComponents(signBlockEntity.messages), 0, lines, 0, lines.length);
        String[] strippedLines = new String[4];
        for (int i = 0; i < 4; i++)
            strippedLines[i] = Formatters.STRIP_COLOR_FORMATTER.format(lines[i]);

        Component[] newLines;

        if (signsListener.get().shouldReplaceSignLines(island.getOwner(), island, location, strippedLines, false))
            newLines = CraftSign.sanitizeLines(strippedLines);
        else
            newLines = CraftSign.sanitizeLines(lines);

        System.arraycopy(newLines, 0, signBlockEntity.messages, 0, 4);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setSignLines(SignChangeEvent signChangeEvent, String[] lines) {
        if (LINES_SIGN_CHANGE_EVENT.isValid()) {
            for (int i = 0; i < lines.length; i++)
                signChangeEvent.setLine(i, lines[i]);
        }
    }

    @Override
    public void playGeneratorSound(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        serverLevel.levelEvent(1501, blockPos, 0);
    }

    @Override
    public void playBreakAnimation(org.bukkit.block.Block block) {
        ServerLevel serverLevel = ((CraftWorld) block.getWorld()).getHandle();
        BlockPos blockPos = new BlockPos(block.getX(), block.getY(), block.getZ());
        serverLevel.levelEvent(null, 2001, blockPos, Block.getId(serverLevel.getBlockState(blockPos)));
    }

    @Override
    public void playPlaceSound(Location location) {
        org.bukkit.World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        BlockPos blockPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        SoundType soundType = serverLevel.getBlockState(blockPos).getSoundType();

        serverLevel.playSound(null, blockPos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
    }

    @Override
    public int getMinHeight(org.bukkit.World world) {
        return world.getMinHeight();
    }

    @Override
    public void removeAntiXray(org.bukkit.World bukkitWorld) {
        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();
        if (CHUNK_PACKET_BLOCK_CONTROLLER.isValid())
            CHUNK_PACKET_BLOCK_CONTROLLER.set(serverLevel, ChunkPacketBlockController.NO_OPERATION_INSTANCE);
    }

    @Override
    public ChunkGenerator createGenerator(SuperiorSkyblockPlugin plugin) {
        return new IslandsGeneratorImpl(plugin);
    }

    private void setBiome(LevelChunk levelChunk, org.bukkit.block.Biome bukkitBiome) {
        Holder<Biome> biome = CraftBlock.biomeToBiomeBase(levelChunk.biomeRegistry, bukkitBiome);

        LevelChunkSection[] chunkSections = levelChunk.getSections();
        for (int i = 0; i < chunkSections.length; ++i) {
            LevelChunkSection currentSection = chunkSections[i];
            if (currentSection != null) {
                PalettedContainer<BlockState> statesContainer = currentSection.getStates();
                PalettedContainer<Holder<Biome>> biomesContainer = new PalettedContainer<>(
                        levelChunk.biomeRegistry.asHolderIdMap(),
                        biome,
                        PalettedContainer.Strategy.SECTION_STATES
                );
                chunkSections[i] = new LevelChunkSection(currentSection.bottomBlockY() >> 4, statesContainer, biomesContainer);
            }
        }
    }

}