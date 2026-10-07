package me.cortex.voxy.commonImpl.importers;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import me.cortex.voxy.common.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

//Upgrades the block state and biome palettes of chunk sections saved by an older Minecraft version to the current format
// with the game's data fixers, like the game does when it loads such chunks (e.g. 26.3 renamed the block state keys
// Name/Properties to id/properties, without upgrading every block of an older chunk decodes as air)
// Only the palette entries are upgraded since they are all the importer reads from a section, each distinct entry is
// upgraded once per data version
final class PaletteUpgrader {
    static final int CURRENT_DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
    private static final int LOGGED_FAILURES = 5;

    private record Key(int dataVersion, Tag entry) {}

    private final ConcurrentHashMap<Key, Tag> blockStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Tag> biomes = new ConcurrentHashMap<>();
    private final AtomicInteger failures = new AtomicInteger();

    static boolean needsUpgrade(int dataVersion) {
        return dataVersion > 0 && dataVersion < CURRENT_DATA_VERSION;
    }

    //Replaces the palette entries of the section with their upgraded version
    void upgradeSection(CompoundTag section, int dataVersion) {
        section.getCompound("block_states").flatMap(states -> states.getList("palette"))
                .ifPresent(palette -> this.upgradePalette(palette, dataVersion, References.BLOCK_STATE, this.blockStates));
        section.getCompound("biomes").flatMap(biomes -> biomes.getList("palette"))
                .ifPresent(palette -> this.upgradePalette(palette, dataVersion, References.BIOME, this.biomes));
    }

    private void upgradePalette(ListTag palette, int dataVersion, DSL.TypeReference type, ConcurrentHashMap<Key, Tag> cache) {
        for (int i = 0; i < palette.size(); i++) {
            var entry = palette.get(i);
            var key = new Key(dataVersion, entry);
            var upgraded = cache.get(key);
            if (upgraded == null) {
                //Not computeIfAbsent, the first upgrades can be slow and should not block the other entries
                upgraded = this.upgrade(type, entry, dataVersion);
                var existing = cache.putIfAbsent(key, upgraded);
                if (existing != null) {
                    upgraded = existing;
                }
            }
            if (upgraded != entry) {
                palette.set(i, upgraded);
            }
        }
    }

    private Tag upgrade(DSL.TypeReference type, Tag entry, int dataVersion) {
        try {
            //Upgrade a copy, the entry is used as a cache key so it must never change
            return DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, entry.copy()), dataVersion, CURRENT_DATA_VERSION).getValue();
        } catch (Exception e) {
            if (this.failures.incrementAndGet() <= LOGGED_FAILURES) {
                Logger.warn("Could not upgrade the palette entry " + entry + " from data version " + dataVersion + ", importing it as it is: " + e);
            }
            return entry;
        }
    }
}
