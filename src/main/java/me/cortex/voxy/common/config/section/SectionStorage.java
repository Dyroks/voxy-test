package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

import java.util.List;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    //Appends human readable runtime statistics of the underlying storage to the list, used for diagnostics
    public void addStatistics(List<String> out) {
    }
}
