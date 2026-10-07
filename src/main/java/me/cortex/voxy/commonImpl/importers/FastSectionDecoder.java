package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

//Fast path for converting a chunk section read from a region file into the level 0 data of a VoxelizedSection
// The output is identical to decoding the section with the vanilla PalettedContainer codecs followed by
// WorldConversionFactory.convert, but every distinct palette entry is only resolved once (instead of going through the
// DataFixerUpper codecs for every section of every chunk) and the packed storage is read directly
// Anything not in the expected format makes decode return false, the caller must then fall back to the vanilla path
// which keeps the exact previous behaviour for those (rare) sections
final class FastSectionDecoder {
    private static final int UNRESOLVED = -1;
    //Bigger palettes use the "global" storage configuration, those are left to the vanilla path
    private static final int MAX_BLOCK_PALETTE_SIZE = 256;
    private static final int MAX_BIOME_PALETTE_SIZE = 8;

    //Index into the 4x4x4 biome cells for every block index of the 16x16x16 section, same as WorldConversionFactory
    private static final byte[] BIOME_INDEX = new byte[16*16*16];
    static {
        for (int i = 0; i < BIOME_INDEX.length; i++) {
            BIOME_INDEX[i] = (byte) Integer.compress(i, 0b1100_1100_1100);
        }
    }

    private static final class Scratch {
        private final int[] blockPalette = new int[MAX_BLOCK_PALETTE_SIZE];
        private final int[] biomePalette = new int[MAX_BIOME_PALETTE_SIZE];
        private final int[] biomes = new int[4*4*4];
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final Mapper mapper;
    private final Registry<Biome> biomeRegistry;
    private final Holder<Biome> defaultBiome;
    private final ConcurrentHashMap<Tag, Integer> blockStateIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> biomeIds = new ConcurrentHashMap<>();
    private volatile int defaultBiomeId = UNRESOLVED;

    FastSectionDecoder(Mapper mapper, Registry<Biome> biomeRegistry, Holder<Biome> defaultBiome) {
        this.mapper = mapper;
        this.biomeRegistry = biomeRegistry;
        this.defaultBiome = defaultBiome;
    }

    //Writes the level 0 data and non air count of the section into the voxelized section
    // returns false (leaving the voxelized section in an undefined state) if the vanilla path must be used instead
    boolean decode(CompoundTag section, VoxelizedSection into) {
        var blockStates = section.getCompound("block_states").orElse(null);
        if (blockStates == null) {
            return false;
        }

        //Like the vanilla path, a missing or empty light array means no light, any size other than 2048 is malformed
        byte[] blockLight = section.getByteArray("BlockLight").orElse(null);
        byte[] skyLight = section.getByteArray("SkyLight").orElse(null);
        if (blockLight != null && blockLight.length == 0) blockLight = null;
        if (skyLight != null && skyLight.length == 0) skyLight = null;
        if ((blockLight != null && blockLight.length != 2048) || (skyLight != null && skyLight.length != 2048)) {
            return false;
        }

        var scratch = SCRATCH.get();

        var palette = blockStates.getList("palette").orElse(null);
        if (palette == null) {
            return false;
        }
        int paletteSize = palette.size();
        if (paletteSize == 0 || paletteSize > MAX_BLOCK_PALETTE_SIZE) {
            return false;
        }
        int[] blockPalette = scratch.blockPalette;
        for (int i = 0; i < paletteSize; i++) {
            int id = this.getBlockStateId(palette.get(i));
            if (id == UNRESOLVED) {
                return false;
            }
            blockPalette[i] = id;
        }

        long[] data = null;
        int bits = 0;
        if (paletteSize != 1) {
            //Same storage size as the vanilla serializer, 4 bits minimum (linear palette) then the exact bit count
            bits = Math.max(4, ceilLog2(paletteSize));
            data = getLongArray(blockStates, "data");
            if (data == null || data.length != packedLength(16*16*16, bits)) {
                return false;
            }
        }

        int[] biomes = scratch.biomes;
        var biomeData = section.getCompound("biomes").orElse(null);
        if (biomeData == null) {
            Arrays.fill(biomes, this.getDefaultBiomeId());
        } else if (!this.decodeBiomes(biomeData, scratch.biomePalette, biomes)) {
            return false;
        }

        long[] out = into.section;
        int nonAirCount = 0;
        if (data == null) {
            int blockId = blockPalette[0];
            for (int i = 0; i < 16*16*16; i++) {
                out[i] = Mapper.composeMappingId(getLight(blockLight, skyLight, i), blockId, biomes[BIOME_INDEX[i]]);
            }
            nonAirCount = blockId != 0 ? 16*16*16 : 0;
        } else {
            //Entries never span two longs, same layout as SimpleBitStorage
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            int maxIndex = paletteSize - 1;
            int i = 0;
            for (long word : data) {
                for (int j = 0; j < perLong && i < 16*16*16; j++, i++) {
                    //Out of range indices are clamped to the last palette entry, like WorldConversionFactory does
                    int blockId = blockPalette[Math.min((int) (word & mask), maxIndex)];
                    word >>>= bits;
                    nonAirCount += blockId != 0 ? 1 : 0;
                    out[i] = Mapper.composeMappingId(getLight(blockLight, skyLight, i), blockId, biomes[BIOME_INDEX[i]]);
                }
            }
        }
        into.lvl0NonAirCount = nonAirCount;
        return true;
    }

    private boolean decodeBiomes(CompoundTag biomeData, int[] biomePalette, int[] biomes) {
        var palette = biomeData.getList("palette").orElse(null);
        if (palette == null) {
            return false;
        }
        int paletteSize = palette.size();
        if (paletteSize == 0 || paletteSize > MAX_BIOME_PALETTE_SIZE) {
            return false;
        }
        for (int i = 0; i < paletteSize; i++) {
            if (!(palette.get(i) instanceof StringTag name)) {
                return false;
            }
            int id = this.getBiomeId(name.value());
            if (id == UNRESOLVED) {
                return false;
            }
            biomePalette[i] = id;
        }

        if (paletteSize == 1) {
            Arrays.fill(biomes, biomePalette[0]);
            return true;
        }

        int bits = ceilLog2(paletteSize);
        long[] data = getLongArray(biomeData, "data");
        if (data == null || data.length != packedLength(4*4*4, bits)) {
            return false;
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < 4*4*4; i++) {
            int index = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (index >= paletteSize) {
                return false;//The vanilla path fails on these, let it handle (and report) it
            }
            biomes[i] = biomePalette[index];
        }
        return true;
    }

    private static long[] getLongArray(CompoundTag tag, String key) {
        return tag.get(key) instanceof LongArrayTag array ? array.getAsLongArray() : null;
    }

    private static byte getLight(byte[] blockLight, byte[] skyLight, int index) {
        //Same nibble layout as DataLayer
        int shift = (index & 1) << 2;
        int block = blockLight == null ? 0 : (blockLight[index >> 1] >> shift) & 0xF;
        int sky = skyLight == null ? 0 : (skyLight[index >> 1] >> shift) & 0xF;
        return (byte) (sky | (block << 4));
    }

    private int getBlockStateId(Tag entry) {
        Integer id = this.blockStateIds.get(entry);
        if (id == null) {
            id = this.resolveBlockState(entry);
            this.blockStateIds.putIfAbsent(entry, id);
        }
        return id;
    }

    private int resolveBlockState(Tag entry) {
        if (!(entry instanceof CompoundTag)) {
            return UNRESOLVED;
        }
        //Same codec as the vanilla palette, partial results are kept like the vanilla list codec does
        var result = BlockState.CODEC.parse(NbtOps.INSTANCE, entry);
        if (!result.hasResultOrPartial()) {
            return UNRESOLVED;
        }
        return this.mapper.getIdForBlockState(result.getPartialOrThrow());
    }

    private int getBiomeId(String name) {
        Integer id = this.biomeIds.get(name);
        if (id == null) {
            id = this.resolveBiome(name);
            this.biomeIds.putIfAbsent(name, id);
        }
        return id;
    }

    private int resolveBiome(String name) {
        Identifier identifier;
        try {
            identifier = Identifier.parse(name);
        } catch (Exception e) {
            return UNRESOLVED;
        }
        var biome = this.biomeRegistry.get(identifier);
        if (biome.isEmpty()) {
            return UNRESOLVED;
        }
        return this.mapper.getIdForBiome(biome.get());
    }

    private int getDefaultBiomeId() {
        int id = this.defaultBiomeId;
        if (id == UNRESOLVED) {
            this.defaultBiomeId = id = this.mapper.getIdForBiome(this.defaultBiome);
        }
        return id;
    }

    private static int ceilLog2(int value) {
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static int packedLength(int entries, int bits) {
        int perLong = 64 / bits;
        return (entries + perLong - 1) / perLong;
    }
}
