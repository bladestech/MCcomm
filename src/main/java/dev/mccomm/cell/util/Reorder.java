package dev.mccomm.cell.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Moves one entry of an insertion-ordered map earlier or later, which is how plan lists are ordered. */
public final class Reorder {
    private Reorder() {
    }

    /** Zero-based position of {@code id}, or -1. */
    public static <V> int indexOf(Map<String, V> map, String id) {
        int i = 0;
        for (String k : map.keySet()) {
            if (k.equals(id)) return i;
            i++;
        }
        return -1;
    }

    /**
     * Moves {@code id} by {@code delta} places (negative = earlier), stopping at either end.
     *
     * @return true if the order changed
     */
    public static <V> boolean move(Map<String, V> map, String id, int delta) {
        List<Map.Entry<String, V>> list = new ArrayList<>(map.entrySet());
        int from = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getKey().equals(id)) from = i;
        if (from < 0) return false;
        int to = Math.clamp((long) from + delta, 0, list.size() - 1);
        if (to == from) return false;
        List<Map.Entry<String, V>> copy = new ArrayList<>();
        for (Map.Entry<String, V> e : list) copy.add(Map.entry(e.getKey(), e.getValue()));
        Map.Entry<String, V> moved = copy.remove(from);
        copy.add(to, moved);
        map.clear();
        for (Map.Entry<String, V> e : copy) map.put(e.getKey(), e.getValue());
        return true;
    }
}
