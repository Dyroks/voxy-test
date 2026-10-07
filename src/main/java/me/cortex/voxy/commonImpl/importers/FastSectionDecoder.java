package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

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

    //Reasons a section is left to the vanilla path, counted and logged to diagnose unexpected formats
    private static final String[] FALLBACK_REASONS = {
            "missing block_states",
            "malformed light array",
            "missing block palette",
            "block palette size",
            "unresolved block palette entry",
            "missing or unexpected block data",
            "missing biome palette",
            "biome palette size",
            "unresolved biome palette entry",
            "missing or unexpected biome data",
            "biome index outside of the palette"
    };
    private static final int MISSING_BLOCK_STATES = 0;
    private static final int MALFORMED_LIGHT = 1;
    private static final int MISSING_BLOCK_PALETTE = 2;
    private static final int BLOCK_PALETTE_SIZE = 3;
    private static final int UNRESOLVED_BLOCK = 4;
    private static final int BLOCK_DATA = 5;
    private static final int MISSING_BIOME_PALETTE = 6;
    private static final int BIOME_PALETTE_SIZE = 7;
    private static final int UNRESOLVED_BIOME = 8;
    private static final int BIOME_DATA = 9;
    private static final int BIOME_INDEX_RANGE = 10;
    private static final int LOGGED_FALLBACKS_PER_REASON = 3;
    private static final int LOGGED_PARTIAL_BLOCK_STATES = 10;

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
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Holder<Biome> defaultBiome;
    private final ConcurrentHashMap<Tag, Integer> blockStateIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Tag, Integer> biomeIds = new ConcurrentHashMap<>();
    private final AtomicLongArray fallbacks = new AtomicLongArray(FALLBACK_REASONS.length);
    //Distinct block palette entries the vanilla codec could only partially decode (e.g. unknown blocks), both decoding
    // paths import them as the partial result (usually air), a lot of them means the chunks are in a format this
    // version does not understand
    private final AtomicLong partialBlockStates = new AtomicLong();
    private volatile int defaultBiomeId = UNRESOLVED;

    FastSectionDecoder(Mapper mapper, Codec<PalettedContainer<BlockState>> blockStateCodec, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec, Holder<Biome> defaultBiome) {
        this.mapper = mapper;
        this.blockStateCodec = blockStateCodec;
        this.biomeCodec = biomeCodec;
        this.defaultBiome = defaultBiome;
    }

    //Writes the level 0 data and non air count of the section into the voxelized section
    // returns false (leaving the voxelized section in an undefined state) if the vanilla path must be used instead
    boolean decode(CompoundTag section, VoxelizedSection into) {
        var blockStates = section.getCompound("block_states").orElse(null);
        if (blockStates == null) {
            return this.fallback(MISSING_BLOCK_STATES, section::toString);
        }

        //Like the vanilla path, a missing or empty light array means no light, any size other than 2048 is malformed
        byte[] blockLight = section.getByteArray("BlockLight").orElse(null);
        byte[] skyLight = section.getByteArray("SkyLight").orElse(null);
        if (blockLight != null && blockLight.length == 0) blockLight = null;
        if (skyLight != null && skyLight.length == 0) skyLight = null;
        if ((blockLight != null && blockLight.length != 2048) || (skyLight != null && skyLight.length != 2048)) {
            int blockLightBytes = blockLight == null ? 0 : blockLight.length;
            int skyLightBytes = skyLight == null ? 0 : skyLight.length;
            return this.fallback(MALFORMED_LIGHT, () -> "block light " + blockLightBytes + " bytes, sky light " + skyLightBytes + " bytes");
        }

        var scratch = SCRATCH.get();

        var palette = blockStates.getList("palette").orElse(null);
        if (palette == null) {
            return this.fallback(MISSING_BLOCK_PALETTE, blockStates::toString);
        }
        int paletteSize = palette.size();
        if (paletteSize == 0 || paletteSize > MAX_BLOCK_PALETTE_SIZE) {
            return this.fallback(BLOCK_PALETTE_SIZE, () -> paletteSize + " entries");
        }
        int[] blockPalette = scratch.blockPalette;
        for (int i = 0; i < paletteSize; i++) {
            var entry = palette.get(i);
            int id = this.getBlockStateId(entry);
            if (id == UNRESOLVED) {
                return this.fallback(UNRESOLVED_BLOCK, () -> describe(entry));
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
                int expectedBits = bits;
                return this.fallback(BLOCK_DATA, () -> paletteSize + " palette entries so " + packedLength(16*16*16, expectedBits) + " longs of " + expectedBits + " bits expected, got " + describe(blockStates.get("data")));
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
            return this.fallback(MISSING_BIOME_PALETTE, biomeData::toString);
        }
        int paletteSize = palette.size();
        if (paletteSize == 0 || paletteSize > MAX_BIOME_PALETTE_SIZE) {
            return this.fallback(BIOME_PALETTE_SIZE, () -> paletteSize + " entries");
        }
        for (int i = 0; i < paletteSize; i++) {
            var entry = palette.get(i);
            int id = this.getBiomeId(entry);
            if (id == UNRESOLVED) {
                return this.fallback(UNRESOLVED_BIOME, () -> describe(entry));
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
            return this.fallback(BIOME_DATA, () -> paletteSize + " palette entries so " + packedLength(4*4*4, bits) + " longs of " + bits + " bits expected, got " + describe(biomeData.get("data")));
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < 4*4*4; i++) {
            int index = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (index >= paletteSize) {
                return this.fallback(BIOME_INDEX_RANGE, () -> "index " + index + " with " + paletteSize + " palette entries");//The vanilla path fails on these, let it handle (and report) it
            }
            biomes[i] = biomePalette[index];
        }
        return true;
    }

    //The detail is only built for the few fallbacks that get logged
    private boolean fallback(int reason, Supplier<String> detail) {
        if (this.fallbacks.incrementAndGet(reason) <= LOGGED_FALLBACKS_PER_REASON) {
            String text = String.valueOf(detail.get());
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            Logger.warn("Fast import decoding not possible (" + FALLBACK_REASONS[reason] + "), using the vanilla decoder for this section: " + text);
        }
        return false;
    }

    //Summary of why sections were left to the vanilla path, null if none were
    String getFallbackSummary() {
        var parts = new ArrayList<String>();
        for (int i = 0; i < FALLBACK_REASONS.length; i++) {
            long count = this.fallbacks.get(i);
            if (count != 0) {
                parts.add(FALLBACK_REASONS[i] + ": " + count);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String describe(Tag tag) {
        if (tag == null) {
            return "nothing";
        }
        return tag.getClass().getSimpleName() + " " + tag;
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

    //Wraps a single palette entry in a container so it can be decoded by the vanilla container codec, this way the
    // entry is interpreted exactly like the vanilla path does, whatever its representation
    private static CompoundTag singleEntryContainer(Tag entry) {
        var palette = new ListTag();
        palette.add(entry.copy());
        var container = new CompoundTag();
        container.put("palette", palette);
        return container;
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
        BlockState state;
        String error;
        try {
            //Partial results are kept, like the vanilla path which uses the partial container
            var result = this.blockStateCodec.parse(NbtOps.INSTANCE, singleEntryContainer(entry));
            if (!result.hasResultOrPartial()) {
                return UNRESOLVED;
            }
            state = result.getPartialOrThrow().get(0, 0, 0);
            error = result.error().map(e -> e.message()).orElse(null);
        } catch (Exception e) {
            return UNRESOLVED;//Let the vanilla path deal with (and report) it
        }
        if (error != null && this.partialBlockStates.incrementAndGet() <= LOGGED_PARTIAL_BLOCK_STATES) {
            Logger.warn("Import could only partially decode the block state " + describe(entry) + ", it is imported as " + state + ": " + error);
        }
        return this.mapper.getIdForBlockState(state);
    }

    long getPartialBlockStateCount() {
        return this.partialBlockStates.get();
    }

    private int getBiomeId(Tag entry) {
        Integer id = this.biomeIds.get(entry);
        if (id == null) {
            id = this.resolveBiome(entry);
            this.biomeIds.putIfAbsent(entry, id);
        }
        return id;
    }

    private int resolveBiome(Tag entry) {
        Holder<Biome> biome;
        try {
            //Only complete results, like the vanilla path which falls back to the default biome on any error
            var result = this.biomeCodec.parse(NbtOps.INSTANCE, singleEntryContainer(entry)).result();
            if (result.isEmpty()) {
                return UNRESOLVED;
            }
            biome = result.get().get(0, 0, 0);
        } catch (Exception e) {
            return UNRESOLVED;//Let the vanilla path deal with (and report) it
        }
        return this.mapper.getIdForBiome(biome);
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
