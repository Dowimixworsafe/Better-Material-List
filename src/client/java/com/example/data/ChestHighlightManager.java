package com.example.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trzyma zbiór skrzyń (po containerId) zaznaczonych do podświetlenia w świecie.
 * Render robi {@code ExampleModClient} przez WorldRenderEvents + malilib RenderUtils.
 *
 * containerId ma postać "{dimension};{x, y, z}" (z BlockPos.toShortString()), np.
 * "minecraft:overworld;10, 64, -20".
 */
@Environment(EnvType.CLIENT)
public final class ChestHighlightManager {

    private static final Pattern NUM = Pattern.compile("-?\\d+");

    // Zbiór containerId aktualnie podświetlanych. Stan tylko w pamięci (reset na disconnect).
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
     * Globalny przełącznik podświetleń (keybind): jeśli cokolwiek jest podświetlone —
     * gasi wszystko; w przeciwnym razie podświetla wszystkie aktualnie śledzone skrzynie.
     * Zwraca nowy stan (true = coś świeci).
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

    /** Wymiar zakodowany w containerId (np. "minecraft:overworld"), lub null. */
    public static String dimensionOf(String containerId) {
        if (containerId == null) return null;
        int sep = containerId.indexOf(';');
        return sep > 0 ? containerId.substring(0, sep) : null;
    }

    /** Parsuje BlockPos z części po ';'. Odporny na "x, y, z" oraz "[x, y, z]". */
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
