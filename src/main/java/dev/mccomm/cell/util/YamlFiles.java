package dev.mccomm.cell.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Saving data files so a crash or full disk mid-write never leaves a half-written file behind. */
public final class YamlFiles {
    private YamlFiles() {
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
