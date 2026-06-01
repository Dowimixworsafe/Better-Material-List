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
 * Dane dla HUD-a w prawym górnym rogu: itemy, które LOKALNY gracz oznaczył jako swój
 * "target" (PPM na liście / focus) i których wciąż brakuje. Lista max 8 pozycji; item
 * znika gdy braki spadną do zera.
 *
 * Snapshot jest przeliczany okresowo (z ticka klienta), nie co klatkę — render tylko
 * czyta gotowe wiersze.
 */
@Environment(EnvType.CLIENT)
public final class HudOverlayManager {

    public static final int MAX_ROWS = 8;

    /** Jeden wiersz HUD: ikona + ile mam / ile potrzeba. */
    public record Row(ItemStack stack, int have, int need) {}

    private static volatile boolean enabled = false;
    private static volatile List<Row> rows = new ArrayList<>();

    private HudOverlayManager() {}

    public static boolean isEnabled() { return enabled; }

    /** Przełącza HUD; przy włączeniu od razu przelicza, żeby pojawił się bez opóźnienia. */
    public static boolean toggle() {
        enabled = !enabled;
        if (enabled) recompute();
        else rows = new ArrayList<>();
        return enabled;
    }

    /** Ustawia stan HUD bez efektów ubocznych zapisu (używane przy wczytywaniu stanu). */
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
     * Przelicza wiersze: bierze aktualną listę materiałów, dolicza zawartość śledzonych
     * skrzyń i ekwipunek, filtruje do itemów targetowanych przez lokalnego gracza, którym
     * wciąż brakuje, sortuje malejąco wg braków i obcina do MAX_ROWS.
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
        List<MaterialListEntry> entries = InputHandler.collectMaterialsFromPlacements(placements);
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
            if (need <= 0) continue; // uzupełnione — znika z HUD

            out.add(new Row(e.getStack().copy(), have, need));
        }

        out.sort((a, b) -> Integer.compare(b.need(), a.need()));
        if (out.size() > MAX_ROWS) out = new ArrayList<>(out.subList(0, MAX_ROWS));
        rows = out;
    }
}
