package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.ActiveSectionTracker;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record SectionData(int cx, int cy, int cz, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight){}
    //All the sections of a chunk are ingested by a single job, one job per section meant one service submission (and
    // worker wake up) per section on the thread loading the chunk, which with tall worlds is hundreds per chunk
    private record IngestBatch(WorldEngine world, SectionData[] sections){}
    private final ConcurrentLinkedDeque<IngestBatch> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        long start = System.nanoTime();
        Exception firstError = null;
        int failed = 0;
        try {
            for (var section : task.sections) {
                try {
                    ingestSection(task.world, section);
                } catch (Exception e) {
                    if (firstError == null) {
                        firstError = e;
                    }
                    failed++;
                }
            }
        } finally {
            //Release the ref we had acquired for the world
            task.world.releaseRef();
            Stats.WORKER_NANOS.add(System.nanoTime() - start);
            Stats.SECTIONS.add(task.sections.length);
        }
        if (firstError != null) {
            Logger.error("Failed to ingest " + failed + " of the " + task.sections.length + " sections of a chunk", firstError);
        }
        Stats.maybeLog();
    }

    private static void ingestSection(WorldEngine world, SectionData task) {
        var section = task.section;
        var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

        if (section.hasOnlyAir() && task.blockLight == null && task.skyLight == null) {//If the chunk section has lighting data, propagate it
            WorldUpdater.insertUpdate(world, vs.zero());
        } else {
            VoxelizedSection csec = WorldConversionFactory.convert(
                    vs,
                    world.getMapper(),
                    section.getStates(),
                    section.getBiomes(),
                    getLightingSupplier(task)
            );
            WorldVoxilizedSectionMipper.mipSection(csec, world.getMapper());
            WorldUpdater.insertUpdate(world, csec);
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(SectionData task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        long start = System.nanoTime();
        try {
            return this.enqueueIngest0(engine, chunk);
        } finally {
            Stats.recordEnqueue(System.nanoTime() - start);
        }
    }

    private boolean enqueueIngest0(WorldEngine engine, LevelChunk chunk) {
        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        var sections = chunk.getSections();
        var chunkPos = chunk.getPos();
        int cx = chunkPos.x();
        int cz = chunkPos.z();
        int minSectionY = chunk.getMinSectionY();

        boolean gotLighting = false;
        boolean allEmpty = true;
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || !shouldIngestSection(section, cx, minSectionY + i, cz)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunkPos, minSectionY + i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
            break;//allEmpty only matters when there is no lighting at all
        }

        if (!gotLighting) {
            if (allEmpty) {
                //Special case all empty chunk columns, we need to clear it out
                List<SectionData> batch = new ArrayList<>(sections.length);
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    if (section == null || !shouldIngestSection(section, cx, minSectionY + i, cz)) continue;
                    batch.add(new SectionData(cx, minSectionY + i, cz, section, null, null));
                }
                this.submit(engine, batch);
            }
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);

        List<SectionData> batch = new ArrayList<>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || !shouldIngestSection(section, cx, minSectionY + i, cz)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunkPos, minSectionY + i);

            //The light is copied here since the light engine is only safe to read on this thread
            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            }

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            batch.add(new SectionData(cx, minSectionY + i, cz, section, bl, sl));//TODO: fixme, this is technically not safe todo on the chunk load ingest, we need to copy the section data so it cant be modified while being read
        }
        this.submit(engine, batch);
        Stats.CHUNKS.increment();
        return true;
    }

    private void submit(WorldEngine engine, List<SectionData> sections) {
        if (sections.isEmpty()) {
            return;
        }
        engine.acquireRef();//This is not great but dont really have a better solution as all the others have there own problem
        this.ingestQueue.add(new IngestBatch(engine, sections.toArray(new SectionData[0])));
        try {
            this.service.execute();
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            engine.releaseRef();//we must manually release
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        while (!this.ingestQueue.isEmpty()) {
            //We need to manually release all our world locks
            this.ingestQueue.pop().world.releaseRef();
        }

    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        engine.acquireRef();
        this.ingestQueue.add(new IngestBatch(engine, new SectionData[]{new SectionData(x, y, z, section, bl, sl)}));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            engine.releaseRef();//we must manually release
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }

    //Called when a chunk leaving the render distance was not ingested again since nothing changed since it was ingested
    public static void noteSkippedReingest() {
        Stats.SKIPPED_REINGESTS.increment();
        Stats.maybeLog();
    }

    //Periodic statistics of the chunk ingestion, to see where the time goes while moving around
    private static final class Stats {
        private static final long INTERVAL_NANOS = 30_000_000_000L;
        private static final LongAdder CHUNKS = new LongAdder();
        private static final LongAdder SECTIONS = new LongAdder();
        private static final LongAdder SKIPPED_REINGESTS = new LongAdder();
        private static final LongAdder ENQUEUE_NANOS = new LongAdder();
        private static final AtomicLong MAX_ENQUEUE_NANOS = new AtomicLong();
        private static final LongAdder WORKER_NANOS = new LongAdder();
        private static final AtomicLong NEXT_LOG = new AtomicLong(System.nanoTime() + INTERVAL_NANOS);
        private static long lastLog = System.nanoTime();
        private static long lastLoads = ActiveSectionTracker.getTotalLoadCount();
        private static long lastLoadNanos = ActiveSectionTracker.getTotalLoadNanos();
        private static long lastSaves = SectionSavingService.getTotalSaveCount();
        private static final Map<String, long[]> lastGc = new HashMap<>();
        static {
            gcSummary();//Prime the collector counts so the first interval only reports its own collections
        }

        static void recordEnqueue(long nanos) {
            ENQUEUE_NANOS.add(nanos);
            MAX_ENQUEUE_NANOS.accumulateAndGet(nanos, Math::max);
        }

        static void maybeLog() {
            long now = System.nanoTime();
            long next = NEXT_LOG.get();
            if (now < next || !NEXT_LOG.compareAndSet(next, now + INTERVAL_NANOS)) {
                return;
            }
            synchronized (Stats.class) {
                long chunks = CHUNKS.sumThenReset();
                long sections = SECTIONS.sumThenReset();
                long skipped = SKIPPED_REINGESTS.sumThenReset();
                long enqueueNanos = ENQUEUE_NANOS.sumThenReset();
                long maxEnqueueNanos = MAX_ENQUEUE_NANOS.getAndSet(0);
                long workerNanos = WORKER_NANOS.sumThenReset();
                long loads = ActiveSectionTracker.getTotalLoadCount();
                long loadNanos = ActiveSectionTracker.getTotalLoadNanos();
                long saves = SectionSavingService.getTotalSaveCount();
                double seconds = (now - lastLog)/1e9;
                long deltaLoads = loads - lastLoads;
                long deltaLoadNanos = loadNanos - lastLoadNanos;
                long deltaSaves = saves - lastSaves;
                lastLog = now;
                lastLoads = loads;
                lastLoadNanos = loadNanos;
                lastSaves = saves;
                String gc = gcSummary();
                if (chunks == 0 && sections == 0 && skipped == 0) {
                    return;//Nothing happened, dont spam the log
                }
                Logger.info(String.format(Locale.ROOT,
                        "Voxy ingest stats (last %.0fs): %d chunks ingested (%d sections in total), %d unchanged chunks not ingested again when unloaded"
                        + " | chunk loading thread: %.1fms total, %.2fms max per chunk | workers: %.2fms per section"
                        + " | storage: %d section loads (%.3fms each), %d saves | GC: %s",
                        seconds, chunks, sections, skipped,
                        enqueueNanos/1e6, maxEnqueueNanos/1e6, workerNanos/1e6/Math.max(1, sections),
                        deltaLoads, deltaLoadNanos/1e6/Math.max(1, deltaLoads), deltaSaves, gc));
            }
        }

        //Collections since the last call per collector, so freezes can be compared with the GC activity
        private static String gcSummary() {
            List<String> parts = new ArrayList<>();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = bean.getCollectionCount();
                long time = bean.getCollectionTime();
                long[] last = lastGc.computeIfAbsent(bean.getName(), k -> new long[]{count, time});
                long deltaCount = count - last[0];
                long deltaTime = time - last[1];
                last[0] = count;
                last[1] = time;
                if (deltaCount > 0) {
                    parts.add(bean.getName() + " " + deltaCount + "x/" + deltaTime + "ms");
                }
            }
            return parts.isEmpty() ? "none" : String.join(", ", parts);
        }
    }
}
