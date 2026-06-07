package com.betterlist.data;

import com.betterlist.input.InputHandler;
import com.betterlist.party.FocusManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
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

    /** One HUD row: icon + have / required. {@code done} means have >= required. */
    public record Row(ItemStack stack, int have, int required, boolean done) {}

    private static volatile boolean enabled = false;
    private static volatile List<Row> rows = new ArrayList<>();      // visible window (≤ MAX_ROWS)
    private static volatile List<Row> allRows = new ArrayList<>();   // full sorted list
    private static volatile int scrollOffset = 0;                    // window start into allRows

    private HudOverlayManager() {}

    public static boolean isEnabled() { return enabled; }

    /** Toggles the HUD; recomputes immediately on enable so it appears without delay. */
    public static boolean toggle() {
        enabled = !enabled;
        if (enabled) recompute();
        else clearRows();
        return enabled;
    }

    /** Sets the HUD state without save side effects (used when loading persisted state). */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (enabled) recompute();
        else clearRows();
    }

    public static void disable() {
        enabled = false;
        clearRows();
    }

    public static List<Row> getRows() { return rows; }

    public static boolean hasMoreAbove() { return scrollOffset > 0; }
    public static boolean hasMoreBelow() { return scrollOffset + MAX_ROWS < allRows.size(); }

    /** Scrolls the window one row toward later targets (Ctrl+J). */
    public static void scrollForward() {
        if (enabled && hasMoreBelow()) { scrollOffset++; applyWindow(); }
    }

    /** Scrolls the window one row back toward earlier targets (Alt+J). */
    public static void scrollBack() {
        if (enabled && scrollOffset > 0) { scrollOffset--; applyWindow(); }
    }

    private static void clearRows() {
        rows = new ArrayList<>();
        allRows = new ArrayList<>();
        scrollOffset = 0;
    }

    /** Slices the visible window out of allRows, clamping the offset to valid bounds. */
    private static void applyWindow() {
        int max = Math.max(0, allRows.size() - MAX_ROWS);
        if (scrollOffset > max) scrollOffset = max;
        if (scrollOffset < 0) scrollOffset = 0;
        int end = Math.min(allRows.size(), scrollOffset + MAX_ROWS);
        rows = new ArrayList<>(allRows.subList(scrollOffset, end));
    }

    /**
     * Recomputes the rows: takes the current material list, adds tracked-chest contents
     * and inventory, filters to the local player's targeted items that are still missing,
     * sorts by descending shortfall, and caps at MAX_ROWS.
     */
    public static void recompute() {
        if (!enabled) return;

        Set<String> myTargets = FocusManager.getMyTargets();
        if (myTargets.isEmpty()) {
            clearRows();
            return;
        }

        List<SchematicPlacement> placements =
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        // Use the same cache-fallback path as the main GUI, so the HUD survives a relog
        // (Litematica's live list is empty until chunks finish loading).
        List<MaterialListEntry> entries = InputHandler.collectEntriesWithCacheFallback(placements);
        Minecraft mc = Minecraft.getInstance();
        if (entries == null || entries.isEmpty() || mc.player == null) {
            clearRows();
            return;
        }

        InputHandler.applyAvailableCounts(entries, mc.player);

        List<MaterialListEntry> targeted = new ArrayList<>();
        for (MaterialListEntry e : entries) {
            String id = BuiltInRegistries.ITEM.getKey(e.getStack().getItem()).toString();
            if (!myTargets.contains(id)) continue;
            if (e.getCountMissing() <= 0) continue;
            targeted.add(e);
        }

        targeted.sort((a, b) -> {
            boolean ad = a.getCountAvailable() >= a.getCountMissing();
            boolean bd = b.getCountAvailable() >= b.getCountMissing();
            if (ad != bd) return ad ? 1 : -1;
            int c = Integer.compare(b.getCountTotal(), a.getCountTotal());
            if (c != 0) return c;
            return BuiltInRegistries.ITEM.getKey(a.getStack().getItem()).toString()
                    .compareTo(BuiltInRegistries.ITEM.getKey(b.getStack().getItem()).toString());
        });

        List<Row> full = new ArrayList<>(targeted.size());
        for (MaterialListEntry e : targeted) {
            int have = e.getCountAvailable();
            int required = e.getCountMissing();
            full.add(new Row(e.getStack().copy(), have, required, have >= required));
        }
        allRows = full;
        applyWindow();
    }
}
