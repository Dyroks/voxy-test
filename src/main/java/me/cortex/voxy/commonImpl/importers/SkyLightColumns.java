package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.world.other.Mapper;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

//Sky light of the sections of a chunk that have no stored sky light, the sections must be processed from the top down
// The game does not store the sky light of sections it can derive: such a section gets, for each column, the light at
// the bottom of the closest stored section above it, or 15 if there is none (the importer used to read them as 0, so as
// darkness). Chunks without any stored light (the game computes it when loading them) get an estimate instead
final class SkyLightColumns {
    //Sky light reaching the top of the next section for each column, indexed (z<<4)|x
    private final byte[] columns = new byte[16*16];

    //Called before the top section of a chunk
    void reset() {
        Arrays.fill(this.columns, (byte) 15);
    }

    //Remembers the bottom layer of a section with stored sky light (same nibble layout as DataLayer)
    void takeBottomLayer(byte[] skyLight) {
        for (int i = 0; i < 16*16; i++) {
            this.columns[i] = (byte) ((skyLight[i >> 1] >> ((i & 1) << 2)) & 0xF);
        }
    }

    //Section without stored sky light in a chunk that has light, the light comes straight down like the game does
    void applyFromAbove(long[] section) {
        for (int i = 0; i < 16*16*16; i++) {
            section[i] = withSkyLight(section[i], this.columns[i & 0xFF]);
        }
    }

    //Section of a chunk without any stored light, the light goes down each column and is dimmed by every block it
    // crosses (fully by opaque blocks), there is no sideways spreading so overhangs and caves are darker than in game
    void applyEstimated(long[] section, IntUnaryOperator lightDampening) {
        for (int y = 15; y >= 0; y--) {
            for (int column = 0; column < 16*16; column++) {
                int i = (y << 8) | column;
                long id = section[i];
                int light = this.columns[column];
                if (light != 0 && !Mapper.isAir(id)) {
                    light = Math.max(0, light - lightDampening.applyAsInt(Mapper.getBlockId(id)));
                }
                this.columns[column] = (byte) light;
                section[i] = withSkyLight(id, light);
            }
        }
    }

    //The sky light is the low nibble of the light byte at the top of the id
    private static long withSkyLight(long id, int skyLight) {
        return (id & ~(0xFL << 56)) | ((long) skyLight << 56);
    }
}
