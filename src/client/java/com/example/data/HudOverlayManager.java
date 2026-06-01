package com.example.data;

import com.example.input.InputHandler;
import com.example.party.FocusManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Data for the top-right HUD: items the LOCAL player flagged as their "target"
 * (right-click on the list / focus) and which are still missing. Up to 8 rows; an item
 * drops off once its shortfall reaches zero.
 *
 * The snapshot is recomputed periodically (on a client tick), not every frame — render
 * only reads the precomputed rows.
 */
@Environment(EnvType.CLIENT)
public final class HudOverlayManager {

    public static final int MAX_ROWS = 8;

    /** One HUD row: icon + have / need. */
    public record Row(ItemStack stack, int have, int need) {}

    private static volatile boolean enabled = false;
    private static volatile List<Row> rows = new ArrayList<>();

    private HudOverlayManager() {}

    public static boolean isEnabled() { return enabled; }

    /** Toggles the HUD; recomputes immediately on enable so it appears without delay. */
    public static boolean toggle() {
        enabled = !enabled;
        if (enabled) recompute();
        else rows = new ArrayList<>();
        return enabled;
    }

    /** Sets the HUD state without save side effects (used when loading persisted state). */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (enabled) recompute();
        else rows = new ArrayList<>();
    }

    public static void disable() {
        enabled = false;
        rows = new ArrayList<>();
    }

    public static List<Row> getRows() { return rows; }

    /**
     * Recomputes the rows: takes the current material list, adds tracked-chest contents
     * and inventory, filters to the local player's targeted items that are still missing,
     * sorts by descending shortfall, and caps at MAX_ROWS.
     */
    public static void recompute() {
        if (!enabled) return;

        Set<String> myTargets = FocusManager.getMyTargets();
        if (myTargets.isEmpty()) {
            rows = new ArrayList<>();
            return;
        }

        List<SchematicPlacement> placements =
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        // Use the same cache-fallback path as the main GUI, so the HUD survives a relog
        // (Litematica's live list is empty until chunks finish loading).
        List<MaterialListEntry> entries = InputHandler.collectEntriesWithCacheFallback(placements);
        Minecraft mc = Minecraft.getInstance();
        if (entries == null || entries.isEmpty() || mc.player == null) {
            rows = new ArrayList<>();
            return;
        }

        MaterialListUtils.updateAvailableCounts(entries, mc.player);
        InputHandler.addCachedContainerItems(entries);

        List<Row> out = new ArrayList<>();
        for (MaterialListEntry e : entries) {
            String id = BuiltInRegistries.ITEM.getKey(e.getStack().getItem()).toString();
            if (!myTargets.contains(id)) continue;

            int have = e.getCountAvailable();
            int need = Math.max(0, e.getCountMissing() - have);
            if (need <= 0) continue; // fulfilled — drops off the HUD

            out.add(new Row(e.getStack().copy(), have, need));
        }

        out.sort((a, b) -> Integer.compare(b.need(), a.need()));
        if (out.size() > MAX_ROWS) out = new ArrayList<>(out.subList(0, MAX_ROWS));
        rows = out;
    }
}
