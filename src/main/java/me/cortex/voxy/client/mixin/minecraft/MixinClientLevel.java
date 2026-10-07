package me.cortex.voxy.client.mixin.minecraft;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import me.cortex.voxy.client.IVoxyIngestTracker;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements IVoxyIngestTracker {

    @Unique
    private int bottomSectionY;

    //Loaded chunks that were ingested and not modified since
    @Unique
    private LongSet voxy$unchangedIngestedChunks = LongSets.synchronize(new LongOpenHashSet());

    @Override
    public void voxy$markChunkIngested(int x, int z) {
        this.voxy$unchangedIngestedChunks.add(ChunkPos.pack(x, z));
    }

    @Override
    public void voxy$markChunkModified(int x, int z) {
        this.voxy$unchangedIngestedChunks.remove(ChunkPos.pack(x, z));
    }

    @Override
    public boolean voxy$consumeChunkUnchanged(int x, int z) {
        return this.voxy$unchangedIngestedChunks.remove(ChunkPos.pack(x, z));
    }

    @Shadow public abstract ClientChunkCache getChunkSource();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getBottom(
            final ClientPacketListener connection,
            final ClientLevel.ClientLevelData levelData,
            final ResourceKey<Level> dimension,
            final Holder<DimensionType> dimensionType,
            final int serverChunkRadius,
            final int serverSimulationDistance,
            final LevelExtractor levelExtractor,
            final boolean isDebug,
            final long biomeZoomSeed,
            final int seaLevel,
            CallbackInfo cir) {
        this.bottomSectionY = ((Level)(Object)this).getMinY()>>4;
    }

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxy$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
        if (old == updated) return;
        //Any change means the chunk must be ingested again when it is unloaded
        this.voxy$markChunkModified(pos.getX()>>4, pos.getZ()>>4);

        //TODO: is this _really_ needed, we should have enough processing power to not need todo it if its only a
        // block removal
        if (!updated.isAir()) return;
        if (VoxyCommon.getInstance()==null) return;
        if (!VoxyConfig.CONFIG.ingestEnabled) return;//Only ingest if setting enabled

        var self = (Level)(Object)this;
        var wi = WorldIdentifier.of(self);
        if (wi == null) {
            return;
        }

        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        if (x == 0 || x==15 || y==0 || y==15 || z==0||z==15) {//Update if there is a statechange on the boarder
            var csp = SectionPos.of(pos);
            //Is not using voxy$cheekyGetChunk as dont think is need
            var chunk = self.getChunk(pos.getX()>>4, pos.getZ()>>4, ChunkStatus.FULL, false);
            if (chunk != null) {
                var section = chunk.getSection(csp.y() - this.bottomSectionY);
                var lp = self.getLightEngine();

                var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                VoxelIngestService.rawIngest(wi, section, csp.x(), csp.y(), csp.z(), blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
            }
        }
    }
}
