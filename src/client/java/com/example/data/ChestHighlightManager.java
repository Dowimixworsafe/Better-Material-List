package com.example.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the set of chests (by containerId) flagged for in-world highlighting.
 * Rendering is done by {@code ExampleModClient} via WorldRenderEvents + malilib RenderUtils.
 *
 * containerId has the form "{dimension};{x, y, z}" (from BlockPos.toShortString()), e.g.
 * "minecraft:overworld;10, 64, -20".
 */
@Environment(EnvType.CLIENT)
public final class ChestHighlightManager {

    private static final Pattern NUM = Pattern.compile("-?\\d+");

    // Set of currently highlighted containerIds. Memory-only (reset on disconnect).
    private static final Set<String> highlighted = new HashSet<>();

    private ChestHighlightManager() {}

    public static boolean isHighlighted(String containerId) {
        return containerId != null && highlighted.contains(containerId);
    }

    public static void toggle(String containerId) {
        if (containerId == null) return;
        if (!highlighted.remove(containerId)) highlighted.add(containerId);
    }

    public static void clear() {
        highlighted.clear();
    }

    /**
     * Global highlight toggle (keybind): if anything is highlighted, clears everything;
     * otherwise highlights all currently tracked chests. Returns the new state
     * (true = something is lit).
     */
    public static boolean toggleAll() {
        if (!highlighted.isEmpty()) {
            highlighted.clear();
            return false;
        }
        highlighted.addAll(ContainerDataManager.getMarkedContainers());
        return !highlighted.isEmpty();
    }

    public static Set<String> all() {
        return new HashSet<>(highlighted);
    }

    /** Dimension encoded in the containerId (e.g. "minecraft:overworld"), or null. */
    public static String dimensionOf(String containerId) {
        if (containerId == null) return null;
        int sep = containerId.indexOf(';');
        return sep > 0 ? containerId.substring(0, sep) : null;
    }

    /** Parses a BlockPos from the part after ';'. Tolerant of "x, y, z" and "[x, y, z]". */
    public static BlockPos posOf(String containerId) {
        if (containerId == null) return null;
        int sep = containerId.indexOf(';');
        if (sep < 0) return null;
        Matcher m = NUM.matcher(containerId.substring(sep + 1));
        try {
            if (!m.find()) return null;
            int x = Integer.parseInt(m.group());
            if (!m.find()) return null;
            int y = Integer.parseInt(m.group());
            if (!m.find()) return null;
            int z = Integer.parseInt(m.group());
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}
