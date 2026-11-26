package dev.mccomm.cell.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saving data files so a crash or full disk mid-write never leaves a half-written file behind, and so the
 * periodic autosave doesn't stall the server: the YAML is built on the main thread (a snapshot), then turned
 * into text and written on one background thread, in order.
 */
public final class YamlFiles {
    private static volatile ExecutorService writer;

    private YamlFiles() {
    }

    /** Starts the background writer (on plugin enable). */
    public static synchronized void start() {
        if (writer != null) return;
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MCcomm-save");
            t.setDaemon(true);
            return t;
        });
    }

    /** Finishes every pending write, then switches back to saving on the calling thread (on plugin disable). */
    public static synchronized void stop() {
        ExecutorService w = writer;
        writer = null;
        if (w == null) return;
        w.shutdown();
        try {
            if (!w.awaitTermination(30, TimeUnit.SECONDS)) w.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Saves {@code y} to {@code file}: in the background while the writer runs, else right away. The YAML must not be
     * touched afterwards (each save builds a new one), which is what makes handing it to another thread safe.
     */
    public static void save(YamlConfiguration y, File file, Logger log) {
        ExecutorService w = writer;
        if (w != null) {
            try {
                w.execute(() -> write(y, file, log));
                return;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down: save here instead
            }
        }
        write(y, file, log);
    }

    private static void write(YamlConfiguration y, File file, Logger log) {
        try {
            saveAtomic(y, file);
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "Couldn't save " + file.getName(), e);
        }
    }

    /** Writes to {@code <name>.tmp} first, then moves it over the real file in one step. */
    public static void saveAtomic(YamlConfiguration y, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Couldn't create " + parent);
        File tmp = new File(parent, file.getName() + ".tmp");
        y.save(tmp);
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
