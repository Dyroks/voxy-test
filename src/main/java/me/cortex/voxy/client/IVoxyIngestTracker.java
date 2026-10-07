package me.cortex.voxy.client;

//Implemented by the client level, remembers which loaded chunks were ingested and not modified since, so that they do not
// need to be ingested again when they leave the render distance
public interface IVoxyIngestTracker {
    void voxy$markChunkIngested(int x, int z);

    void voxy$markChunkModified(int x, int z);

    //Returns true if the chunk was ingested and not modified since, the chunk is forgotten either way
    boolean voxy$consumeChunkUnchanged(int x, int z);
}
