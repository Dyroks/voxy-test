package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

//Remembers which region files of an import source were fully imported into a voxy world (and which version of them,
// using the file modification time and size), so that an interrupted import can be resumed and a repeated import only
// processes the regions that changed since
public final class ImportProgress {
    //Older progress was recorded by imports that read the chunks saved before 26.3 as air (v1) or the chunks without
    // stored light as darkness (v2), it is discarded so they get imported again
    private static final String HEADER = "#voxy-import-progress-v3";
    //Regions are only recorded a while after they finished importing, so their sections had the time to be saved
    private static final long COMMIT_DELAY_NANOS = 60_000_000_000L;

    private record CompletedRegion(String name, long lastModified, long size, long completedAt) {}

    private final HashMap<String, long[]> completed = new HashMap<>();
    private final ConcurrentLinkedQueue<CompletedRegion> pending = new ConcurrentLinkedQueue<>();
    private BufferedWriter writer;

    private ImportProgress() {}

    //Opens the progress file, the previous progress is only kept if it was recorded for the same source and the same
    // storage (identified by storageIdentity, so a recreated storage starts from scratch)
    public static ImportProgress open(Path file, String source, String storageIdentity) throws IOException {
        var progress = new ImportProgress();
        String[] header = {HEADER, "#source=" + source, "#storage=" + storageIdentity};

        boolean valid = false;
        if (Files.isRegularFile(file)) {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            valid = lines.size() >= header.length;
            for (int i = 0; valid && i < header.length; i++) {
                valid = lines.get(i).equals(header[i]);
            }
            if (valid) {
                for (int i = header.length; i < lines.size(); i++) {
                    var parts = lines.get(i).split(" ");
                    if (parts.length != 3) {
                        continue;
                    }
                    try {
                        progress.completed.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                    } catch (NumberFormatException e) {
                        //Ignore broken lines (e.g. the game crashed while writing)
                    }
                }
            }
        }

        Files.createDirectories(file.getParent());
        if (valid) {
            boolean endsWithNewline;
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                var last = ByteBuffer.allocate(1);
                endsWithNewline = channel.size() == 0 || (channel.position(channel.size() - 1).read(last) == 1 && last.get(0) == '\n');
            }
            progress.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            if (!endsWithNewline) {
                progress.writer.newLine();
            }
        } else {
            progress.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            for (var line : header) {
                progress.writer.write(line);
                progress.writer.newLine();
            }
        }
        progress.writer.flush();
        return progress;
    }

    public static String fileNameFor(String source) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8) + ".txt";
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    //Deletes all the progress files in the directory, returns how many were removed
    public static int reset(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (var files = Files.list(directory)) {
            for (var file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().endsWith(".txt")) {
                    Files.delete(file);
                    removed++;
                }
            }
        }
        return removed;
    }

    public int getCompletedCount() {
        return this.completed.size();
    }

    public boolean isCompleted(String region, long lastModified, long size) {
        var entry = this.completed.get(region);
        return entry != null && entry[0] == lastModified && entry[1] == size;
    }

    //Called (from any thread) once every chunk of the region was imported
    void markCompleted(String region, long lastModified, long size) {
        this.pending.add(new CompletedRegion(region, lastModified, size, System.nanoTime()));
    }

    //Records the completed regions, all of them or only those that completed long enough ago
    // flushStorage is run first so that the data of those regions is durable before they are recorded
    void commit(boolean all, Runnable flushStorage) {
        if (this.writer == null) {
            return;
        }
        long now = System.nanoTime();
        List<CompletedRegion> ready = new ArrayList<>();
        CompletedRegion entry;
        while ((entry = this.pending.peek()) != null && (all || now - entry.completedAt() >= COMMIT_DELAY_NANOS)) {
            ready.add(this.pending.poll());
        }
        if (ready.isEmpty()) {
            return;
        }
        try {
            flushStorage.run();
            for (var region : ready) {
                this.writer.write(region.name() + " " + region.lastModified() + " " + region.size());
                this.writer.newLine();
                this.completed.put(region.name(), new long[]{region.lastModified(), region.size()});
            }
            this.writer.flush();
        } catch (Exception e) {
            Logger.error("Failed to record the voxy import progress, these regions will be imported again next time", e);
        }
    }

    void close() {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.close();
        } catch (IOException e) {
            Logger.error("Failed to close the voxy import progress file", e);
        }
        this.writer = null;
    }
}
