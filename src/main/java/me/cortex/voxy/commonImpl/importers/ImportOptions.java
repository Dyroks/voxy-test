package me.cortex.voxy.commonImpl.importers;

//Options of a region file based world import
// hasCenter/centerX/centerZ: block position the regions are imported around, closest regions first
// radius: if > 0 (and there is a center), only the chunks whose center is within this many blocks are imported
// progress: if not null, regions that were already imported (and not modified since) are skipped and the regions
//  that get fully imported are recorded
public record ImportOptions(boolean hasCenter, double centerX, double centerZ, int radius, ImportProgress progress) {
    public static final ImportOptions NONE = new ImportOptions(false, 0, 0, 0, null);

    public boolean hasRadius() {
        return this.hasCenter && this.radius > 0;
    }

    public double distanceSquaredToRegion(int regionX, int regionZ) {
        double dx = regionX*512.0 + 256 - this.centerX;
        double dz = regionZ*512.0 + 256 - this.centerZ;
        return dx*dx + dz*dz;
    }

    //If any part of the region is within the radius
    public boolean overlapsRegion(int regionX, int regionZ) {
        if (!this.hasRadius()) {
            return true;
        }
        double dx = Math.max(0, Math.max(regionX*512.0 - this.centerX, this.centerX - (regionX*512.0 + 512)));
        double dz = Math.max(0, Math.max(regionZ*512.0 - this.centerZ, this.centerZ - (regionZ*512.0 + 512)));
        return dx*dx + dz*dz <= (double) this.radius*this.radius;
    }

    //If the center of the chunk is within the radius
    public boolean includesChunk(int chunkX, int chunkZ) {
        if (!this.hasRadius()) {
            return true;
        }
        double dx = chunkX*16.0 + 8 - this.centerX;
        double dz = chunkZ*16.0 + 8 - this.centerZ;
        return dx*dx + dz*dz <= (double) this.radius*this.radius;
    }
}
