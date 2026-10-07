package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class WorldImporter implements IDataImporter {
    private static final long STATS_INTERVAL_NANOS = 30_000_000_000L;
    //Decodes every section with both the fast and the vanilla path and reports any difference (for testing)
    private static final boolean VERIFY_FAST_DECODE = VoxyCommon.isVerificationFlagOn("verifyImportDecode");

    //Region file chunk compression types
    private static final byte COMPRESSION_ZLIB = 2;
    private static final byte COMPRESSION_NONE = 3;

    //Result of importing a single chunk, every chunk must end in exactly one of these so that the import can complete
    private static final int CHUNK_IMPORTED = 0;
    private static final int CHUNK_SKIPPED = 1;//Not a real/full chunk, removed from the total
    private static final int CHUNK_FAILED = 2;//Unreadable chunk, removed from the total and counted as failed

    private final WorldEngine world;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final FastSectionDecoder fastDecoder;
    private final IntSupplier pendingSaves;
    private final AtomicInteger estimatedTotalChunks = new AtomicInteger();//Slowly converges to the true value
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger chunksProcessed = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();

    //Cumulative time (in nanoseconds, summed over all threads) spent in each stage of the import
    private final LongAdder decompressNanos = new LongAdder();
    private final LongAdder nbtNanos = new LongAdder();
    private final LongAdder decodeNanos = new LongAdder();
    private final LongAdder insertNanos = new LongAdder();
    private final LongAdder fastSections = new LongAdder();
    private final LongAdder vanillaSections = new LongAdder();
    private final AtomicInteger verifyMismatches = new AtomicInteger();
    private long statsStartTime;
    private long lastStatsTime;
    private long lastStatsProcessed;
    private long lastStatsSaves;
    private long lastStatsSaveNanos;

    private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
    private final Service service;

    private volatile boolean isRunning;

    public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
        this(worldEngine, mcWorld, sm, runChecker, ()->0);
    }

    public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker, IntSupplier pendingSaves) {
        this.world = worldEngine;
        this.pendingSaves = pendingSaves;
        this.service = sm.createService(()->new Pair<>(()->this.jobQueue.poll().run(), ()->{}), 3, "World importer", runChecker);

        var biomeRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        this.defaultBiomeProvider = new PalettedContainerRO<>() {
            @Override
            public Holder<Biome> get(int x, int y, int z) {
                return defaultBiome;
            }

            @Override
            public void getAll(Consumer<Holder<Biome>> action) {
                action.accept(defaultBiome);
            }

            @Override
            public void write(FriendlyByteBuf buf) {

            }

            @Override
            public int getSerializedSize() {
                return 0;
            }

            @Override
            public int bitsPerEntry() {
                return 0;
            }

            @Override
            public boolean maybeHas(Predicate<Holder<Biome>> predicate) {
                return predicate.test(defaultBiome);
            }

            @Override
            public void forEachInPalette(Consumer<Holder<Biome>> consumer) {
                consumer.accept(defaultBiome);
            }

            @Override
            public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {
                counter.accept(defaultBiome, 1);
            }

            @Override
            public PalettedContainer<Holder<Biome>> copy() {
                return null;
            }

            @Override
            public PalettedContainer<Holder<Biome>> recreate() {
                return null;
            }

            @Override
            public PackedData<Holder<Biome>> pack(Strategy<Holder<Biome>> provider) {
                return null;
            }
        };

        var factory = PalettedContainerFactory.create(mcWorld.registryAccess());
        this.biomeCodec = factory.biomeContainerCodec();
        this.blockStateCodec = factory.blockStatesContainerCodec();
        this.fastDecoder = new FastSectionDecoder(this.world.getMapper(), biomeRegistry, defaultBiome);
    }


    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning) {
            throw new IllegalStateException();
        }
        if (this.worker == null) {//Can happen if no files
            completionCallback.onCompletion(0);
            return;
        }
        this.isRunning = true;
        this.world.acquireRef();
        this.updateCallback = updateCallback;
        this.completionCallback = completionCallback;
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.world;
    }

    private final AtomicBoolean isShutdown = new AtomicBoolean();
    public void shutdown() {
        if (this.isShutdown.getAndSet(true)) {
            return;
        }
        this.isRunning = false;
        if (this.worker != null) {
            try {
                this.worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (this.service.isLive()) {
            this.world.releaseRef();
            this.service.shutdown();
        }
        //Free all the remaining entries by running the lambda
        while (!this.jobQueue.isEmpty()) {
            this.jobQueue.poll().run();
        }
    }

    private interface IImporterMethod <T> {
        void importRegion(T file) throws Exception;
    }

    private volatile Thread worker;
    private IUpdateCallback updateCallback;
    private ICompletionCallback completionCallback;
    public void importRegionDirectoryAsync(File directory) {
        var files = directory.listFiles((dir, name) -> {
            var sections = name.split("\\.");
            if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                Logger.error("Unknown file: " + name);
                return false;
            }
            return true;
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
        try {
            innerDirectory = innerDirectory.replace("\\\\", "\\").replace("\\", "/");
            var file = ZipFile.builder().setFile(zip).get();
            ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
            for (var e = file.getEntries(); e.hasMoreElements();) {
                var entry = e.nextElement();
                if (entry.isDirectory()||!entry.getName().startsWith(innerDirectory)) {
                    continue;
                }
                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");
                if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                    Logger.error("Unknown file: " + name);
                    continue;
                }
                regions.add(entry);
            }
            this.importRegionsAsync(regions.toArray(ZipArchiveEntry[]::new), (entry)->{
                if (entry.getSize() == 0) {
                    return;
                }
                var buf = new MemoryBuffer(entry.getSize());
                try (var channel = Channels.newChannel(file.getInputStream(entry))) {
                    if (channel.read(buf.asByteBuffer()) != buf.size) {
                        buf.free();
                        throw new IllegalStateException("Could not read full zip entry");
                    }
                }

                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");

                try {
                    this.importRegion(buf, Integer.parseInt(sections[1]), Integer.parseInt(sections[2]));
                } catch (NumberFormatException e) {
                    Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
                }
                buf.free();
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private <T> void importRegionsAsync(T[] regionFiles, IImporterMethod<T> importer) {
        this.totalChunks.set(0);
        this.estimatedTotalChunks.set(0);
        this.chunksProcessed.set(0);
        this.worker = new Thread(() -> {
            this.statsStartTime = this.lastStatsTime = System.nanoTime();
            this.lastStatsSaves = SectionSavingService.getTotalSaveCount();
            this.lastStatsSaveNanos = SectionSavingService.getTotalSaveNanos();
            this.estimatedTotalChunks.addAndGet(regionFiles.length*1024);
            for (var file : regionFiles) {
                this.estimatedTotalChunks.addAndGet(-1024);
                try {
                    importer.importRegion(file);
                } catch (Exception e) {
                    //A broken region must not kill the importer, the chunks already queued are still processed
                    Logger.error("Failed to import region " + file + ", skipping it", e);
                }
                while ((this.totalChunks.get()-this.chunksProcessed.get() > 10_000) && this.isRunning) {
                    this.logStatistics(false);
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                this.logStatistics(false);
                if (!this.isRunning) {
                    this.service.blockTillEmpty();
                    this.completionCallback.onCompletion(this.totalChunks.get());
                    this.worker = null;
                    return;
                }
            }
            this.service.blockTillEmpty();
            while (this.chunksProcessed.get() != this.totalChunks.get() && this.isRunning) {
                Thread.yield();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            this.logStatistics(true);
            if (!this.isShutdown.getAndSet(true)) {
                this.worker = null;
                this.service.shutdown();
                this.world.releaseRef();
            }
            this.completionCallback.onCompletion(this.totalChunks.get());
        });
        this.worker.setName("World importer");
    }

    public boolean isBusy() {
        return this.isRunning || this.worker != null;
    }

    public boolean isRunning() {
        return this.isRunning || (this.worker != null && this.worker.isAlive());
    }

    private void importRegionFile(File file) throws IOException {
        var name = file.getName();
        var sections = name.split("\\.");
        if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
            Logger.error("Unknown file: " + name);
            throw new IllegalStateException();
        }
        int rx = 0;
        int rz = 0;
        try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
        } catch (NumberFormatException e) {
            Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
            return;
        }
        try (var fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0) {
                return;
            }
            var fileData = new MemoryBuffer(fileStream.size());
            if (fileStream.read(fileData.asByteBuffer(), 0) < 8192) {
                fileData.free();
                Logger.warn("Header of region file invalid");
                return;
            }
            this.importRegion(fileData, rx, rz);
            fileData.free();
        }
    }


    private void importRegion(MemoryBuffer regionFile, int x, int z) {
        //Find and load all saved chunks
        if (regionFile.size < 8192) {//File not big enough
            Logger.warn("Header of region file invalid");
            return;
        }
        for (int idx = 0; idx < 1024; idx++) {
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(regionFile.address+idx*4));//Assumes little endian
            if (sectorMeta == 0) {
                //Empty chunk
                continue;
            }
            int sectorStart = sectorMeta>>>8;
            int sectorCount = sectorMeta&((1<<8)-1);

            if (sectorCount == 0) {
                continue;
            }

            //TODO: create memory copy for each section
            if (regionFile.size < ((sectorCount-1) + sectorStart) * 4096L) {
                Logger.warn("Cannot access chunk sector as it goes out of bounds. start bytes: " + (sectorStart*4096) + " sector count: " + sectorCount + " fileSize: " + regionFile.size);
                continue;
            }

            {
                long base = regionFile.address + sectorStart * 4096L;
                int chunkLen = sectorCount * 4096;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte b = MemoryUtil.memGetByte(base + 4L);
                if (m == 0) {
                    Logger.error("Chunk is allocated, but stream is missing");
                } else {
                    int n = m - 1;
                    if (regionFile.size < (n + sectorStart*4096L)) {
                        Logger.warn("Chunk stream to small");
                    } else if ((b & 128) != 0) {
                        if (n != 0) {
                            Logger.error("Chunk has both internal and external streams");
                        }
                        Logger.error("Chunk has external stream which is not supported");
                    } else if (n > chunkLen-5) {
                        Logger.error("Chunk stream is truncated: expected "+n+" but read " + (chunkLen-5));
                    } else if (n < 0) {
                        Logger.error("Declared size of chunk is negative");
                    } else {
                        var data = new MemoryBuffer(n).cpyFrom(base + 5);
                        this.jobQueue.add(()-> {
                            if (!this.isRunning) {
                                data.free();
                                return;
                            }
                            try {
                                this.importChunk(b, data, x, z);
                            } finally {
                                data.free();
                            }
                        });
                        this.totalChunks.incrementAndGet();
                        this.estimatedTotalChunks.incrementAndGet();
                        this.service.execute();
                    }
                }
            }
        }
    }

    private static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;
            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (this.offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, this.available());
                if (len == 0) {
                    return -1;
                }
                UnsafeUtil.memcpy(data.address+this.offset, len, b, off); this.offset+=len;
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size-this.offset);
            }
        };
    }

    private static final int INITIAL_INFLATE_BUFFER_SIZE = 256*1024;
    private static final int MAX_RETAINED_INFLATE_BUFFER_SIZE = 8*1024*1024;
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
    private static final ThreadLocal<byte[][]> INFLATE_BUFFER = ThreadLocal.withInitial(()->new byte[][]{new byte[INITIAL_INFLATE_BUFFER_SIZE]});

    //Inflates a whole zlib chunk stream at once (reading straight from native memory) into a reused per thread buffer
    // the returned stream is only valid until the next call on the same thread
    private static DataInputStream inflate(MemoryBuffer stream) throws IOException {
        var inflater = INFLATER.get();
        var bufferHolder = INFLATE_BUFFER.get();
        byte[] buffer = bufferHolder[0];
        int length = 0;
        int stalls = 0;
        inflater.reset();
        try {
            inflater.setInput(MemoryUtil.memByteBuffer(stream.address, (int) stream.size));
            while (!inflater.finished()) {
                if (length == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length*2);
                }
                int inflated = inflater.inflate(buffer, length, buffer.length - length);
                if (inflated == 0 && !inflater.finished()) {
                    if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 16) {
                        throw new EOFException("Truncated or invalid zlib chunk stream");
                    }
                }
                length += inflated;
            }
        } catch (DataFormatException e) {
            throw new IOException("Invalid zlib chunk stream", e);
        } finally {
            inflater.reset();//Drop the reference to the native input buffer
        }
        if (buffer.length <= MAX_RETAINED_INFLATE_BUFFER_SIZE) {
            bufferHolder[0] = buffer;
        }
        return new DataInputStream(new FastByteArrayInputStream(buffer, 0, length));
    }

    private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        if (flags == COMPRESSION_ZLIB) {//The default, decompressed directly
            return inflate(stream);
        }
        if (flags == COMPRESSION_NONE) {
            byte[] raw = new byte[(int) stream.size];
            UnsafeUtil.memcpy(stream.address, raw.length, raw);
            return new DataInputStream(new FastByteArrayInputStream(raw));
        }
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    //Imports a single chunk and accounts for it, this must never throw as an unaccounted chunk would stall the import forever
    private void importChunk(byte compression, MemoryBuffer data, int regionX, int regionZ) {
        int result;
        try {
            result = this.importChunk0(compression, data, regionX, regionZ);
        } catch (Exception e) {
            Logger.error("Failed to import a chunk of region (" + regionX + ", " + regionZ + "), skipping it", e);
            result = CHUNK_FAILED;
        }

        if (result == CHUNK_IMPORTED) {
            this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
        } else {
            if (result == CHUNK_FAILED) {
                this.failedChunks.incrementAndGet();
            }
            this.estimatedTotalChunks.decrementAndGet();
            this.totalChunks.decrementAndGet();
        }
    }

    private int importChunk0(byte compression, MemoryBuffer data, int regionX, int regionZ) throws IOException {
        long start = System.nanoTime();
        var decompressedData = this.decompress(compression, data);
        long decompressed = System.nanoTime();
        this.decompressNanos.add(decompressed - start);
        if (decompressedData == null) {
            Logger.error("Error decompressing chunk data");
            return CHUNK_FAILED;
        }

        CompoundTag nbt;
        try (decompressedData) {
            nbt = NbtIo.read(decompressedData);
        }
        this.nbtNanos.add(System.nanoTime() - decompressed);
        return this.importChunkNBT(nbt, regionX, regionZ);
    }

    private int importChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
        if (!chunk.contains("Status")) {
            //Its not real so decrement the chunk
            return CHUNK_SKIPPED;
        }

        //Dont process non full chunk sections
        var status = ChunkStatus.byName(chunk.getStringOr("Status", null));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {//We also import empty since they are from data upgrade
            return CHUNK_SKIPPED;
        }

        try {
            int x = chunk.getIntOr("xPos", Integer.MIN_VALUE);
            int z = chunk.getIntOr("zPos", Integer.MIN_VALUE);
            if (x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, expected: (" + regionX + ", " + regionZ+"), got: " + "(" + (x>>5) + ", " + (z>>5)+"), importing anyway");
            }

            for (var sectionE : chunk.getList("sections").orElseThrow()) {
                var section = (CompoundTag) sectionE;
                int y = section.getIntOr("Y", Integer.MIN_VALUE);
                this.importSectionNBT(x, y, z, section);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }
        return CHUNK_IMPORTED;
    }

    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundTag section) {
        if (section.getCompound("block_states").isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        VoxelizedSection csec = SECTION_CACHE.get().setPosition(x, y, z);
        if (this.fastDecoder.decode(section, csec)) {
            this.fastSections.increment();
            if (VERIFY_FAST_DECODE) {
                this.verifyFastDecode(section, csec);
            }
        } else {
            //Unusual section, use the vanilla codecs which keep the previous behaviour exactly
            this.vanillaSections.increment();
            csec = this.legacyDecodeSection(section, csec);
        }
        long decoded = System.nanoTime();
        this.decodeNanos.add(decoded - start);
        if (csec == null) {
            return;
        }

        WorldVoxilizedSectionMipper.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
        this.insertNanos.add(System.nanoTime() - decoded);
    }

    //Decodes the section with the vanilla codecs, returns null if the section could not be decoded and should be skipped
    private VoxelizedSection legacyDecodeSection(CompoundTag section, VoxelizedSection into) {
        byte[] blockLightData = section.getByteArray("BlockLight").orElse(EMPTY);
        byte[] skyLightData = section.getByteArray("SkyLight").orElse(EMPTY);

        DataLayer blockLight;
        if (blockLightData.length != 0) {
            blockLight = new DataLayer(blockLightData);
        } else {
            blockLight = null;
        }

        DataLayer skyLight;
        if (skyLightData.length != 0) {
            skyLight = new DataLayer(skyLightData);
        } else {
            skyLight = null;
        }

        var blockStatesRes = blockStateCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states").get());
        if (!blockStatesRes.hasResultOrPartial()) {
            //TODO: if its only partial, it means should try to upgrade the nbt format with datafixerupper probably
            return null;
        }
        var blockStates = blockStatesRes.getPartialOrThrow();
        var biomes = this.defaultBiomeProvider;
        var optBiomes = section.getCompound("biomes");
        if (optBiomes.isPresent()) {
            biomes = this.biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get()).result().orElse(this.defaultBiomeProvider);
        }
        return WorldConversionFactory.convert(
                into,
                this.world.getMapper(),
                blockStates,
                biomes,
                (bx, by, bz) -> {
                    int block = 0;
                    int sky = 0;
                    if (blockLight != null) {
                        block = blockLight.get(bx, by, bz);
                    }
                    if (skyLight != null) {
                        sky = skyLight.get(bx, by, bz);
                    }
                    return (byte) (sky|(block<<4));
                }
        );
    }

    private static final ThreadLocal<VoxelizedSection> VERIFY_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void verifyFastDecode(CompoundTag section, VoxelizedSection fast) {
        VoxelizedSection expected;
        try {
            expected = this.legacyDecodeSection(section, VERIFY_CACHE.get().setPosition(fast.x, fast.y, fast.z));
        } catch (Exception e) {
            expected = null;
        }
        String problem = null;
        if (expected == null) {
            problem = "the vanilla path could not decode the section";
        } else if (expected.lvl0NonAirCount != fast.lvl0NonAirCount) {
            problem = "non air count " + fast.lvl0NonAirCount + " expected " + expected.lvl0NonAirCount;
        } else {
            for (int i = 0; i < 16*16*16; i++) {
                if (expected.section[i] != fast.section[i]) {
                    problem = "index " + i + " is " + Long.toHexString(fast.section[i]) + " expected " + Long.toHexString(expected.section[i]);
                    break;
                }
            }
        }
        if (problem != null) {
            int count = this.verifyMismatches.incrementAndGet();
            if (count <= 20 || count%1000 == 0) {
                String blockStates = section.getCompound("block_states").map(Object::toString).orElse("none");
                if (blockStates.length() > 2000) {
                    blockStates = blockStates.substring(0, 2000) + "...";
                }
                Logger.error("Fast import decode mismatch #" + count + " at section " + fast.x + ", " + fast.y + ", " + fast.z + ": " + problem + ", block states: " + blockStates);
            }
        }
    }

    private void logStatistics(boolean force) {
        long now = System.nanoTime();
        if ((!force) && now - this.lastStatsTime < STATS_INTERVAL_NANOS) {
            return;
        }
        long processed = this.chunksProcessed.get();
        long saves = SectionSavingService.getTotalSaveCount();
        long saveNanos = SectionSavingService.getTotalSaveNanos();
        double intervalSeconds = Math.max(0.001, (now - this.lastStatsTime)/1e9);
        double totalSeconds = Math.max(0.001, (now - this.statsStartTime)/1e9);
        long chunks = Math.max(1, processed);
        long deltaSaves = saves - this.lastStatsSaves;

        var sb = new StringBuilder("Voxy import stats: ");
        sb.append(processed).append(" chunks imported (").append((long) ((processed - this.lastStatsProcessed)/intervalSeconds)).append("/s now, ")
                .append((long) (processed/totalSeconds)).append("/s average), ").append(this.failedChunks.get()).append(" failed");
        sb.append(" | cpu ms per chunk: decompress ").append(perChunkMs(this.decompressNanos.sum(), chunks))
                .append(", nbt ").append(perChunkMs(this.nbtNanos.sum(), chunks))
                .append(", decode ").append(perChunkMs(this.decodeNanos.sum(), chunks))
                .append(", mip+insert ").append(perChunkMs(this.insertNanos.sum(), chunks));
        long fast = this.fastSections.sum();
        long vanilla = this.vanillaSections.sum();
        sb.append(" | sections: ").append(fast).append(" fast, ").append(vanilla).append(" vanilla fallback");
        if (VERIFY_FAST_DECODE) {
            sb.append(", ").append(this.verifyMismatches.get()).append(" verification mismatches");
        }
        sb.append(" | saves: ").append((long) (deltaSaves/intervalSeconds)).append("/s, ")
                .append(deltaSaves==0?"-":perChunkMs(saveNanos - this.lastStatsSaveNanos, deltaSaves)).append("ms each, ")
                .append(this.pendingSaves.getAsInt()).append(" queued");
        var storageStats = new ArrayList<String>();
        try {
            this.world.storage.addStatistics(storageStats);
        } catch (Exception e) {
            storageStats.add("storage stats unavailable: " + e.getMessage());
        }
        for (var stat : storageStats) {
            sb.append(" | ").append(stat);
        }
        Logger.info(sb.toString());

        this.lastStatsTime = now;
        this.lastStatsProcessed = processed;
        this.lastStatsSaves = saves;
        this.lastStatsSaveNanos = saveNanos;
    }

    private static String perChunkMs(long nanos, long count) {
        return String.format(Locale.ROOT, "%.3f", nanos/1e6/Math.max(1, count));
    }

    @Override
    public String getCompletionDetails() {
        int failed = this.failedChunks.get();
        return failed == 0 ? null : failed + " chunks could not be read and were skipped (see log)";
    }
}
