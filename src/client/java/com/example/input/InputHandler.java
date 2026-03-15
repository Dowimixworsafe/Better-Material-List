package com.example.input;

import com.example.data.MaterialCacheManager;
import com.example.data.ContainerDataManager;
import com.example.gui.GuiBetterMaterialList;
import com.example.config.ModConfig;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class InputHandler implements IKeybindProvider, IHotkeyCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterMaterialList");
    private static final InputHandler INSTANCE = new InputHandler();

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    public void registerKeyCallbacks() {
        // Rejestrujemy nasłuchiwanie na wszystkie nasze klawisze
        ModConfig.OPEN_GUI.getKeybind().setCallback(this);
        ModConfig.RELOAD_LIST.getKeybind().setCallback(this);
        ModConfig.OPEN_CONFIG.getKeybind().setCallback(this);
    }

    public boolean onKeyAction(KeyAction action, IKeybind key) {

        // --- 1. Otwieranie Menu Konfiguracji ---
        if (key == ModConfig.OPEN_CONFIG.getKeybind()) {
            GuiBase.openGui(new com.example.gui.GuiConfigs());
            return true;
        }

        // --- 2. Przeładowanie (Reload) TYLKO wewnątrz otwartego GUI ---
        if (key == ModConfig.RELOAD_LIST.getKeybind()) {
            // Jeśli okno BML NIE jest otwarte, ignorujemy całkowicie ten klawisz.
            // Dzięki temu odświeżanie nie działa w tle podczas zwykłej gry.
            if (!(Minecraft.getInstance().screen instanceof GuiBetterMaterialList)) {
                return false;
            }

            GuiBetterMaterialList currentGui = (GuiBetterMaterialList) Minecraft.getInstance().screen;

            // Jeśli gracz kliknął pasek wyszukiwania i np. chce wpisać "dirt",
            // przepuszczamy literę "r" do paska i NIE odświeżamy listy.
            if (currentGui.isSearchFocused()) {
                return false;
            }

            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            List<SchematicPlacement> placements = manager.getAllSchematicsPlacements();

            if (placements != null && !placements.isEmpty()) {
                for (SchematicPlacement p : placements) {
                    if (p.isEnabled() && p.getMaterialList() != null) {
                        p.getMaterialList().setCompletionListener(currentGui);
                        p.getMaterialList().reCreateMaterialList();
                    }
                }
            }
            return true;
        }

        // --- 3. Otwieranie Głównego GUI (To co miałeś) ---
        if (key == ModConfig.OPEN_GUI.getKeybind()) {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            List<SchematicPlacement> placements = manager.getAllSchematicsPlacements();

            if (placements == null || placements.isEmpty()) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§c[BML] No schematic placements active!"), false);
                }
                return true;
            }

            // Try to collect fresh materials
            List<MaterialListEntry> freshEntries = collectMaterialsFromPlacements(placements);
            String cacheKey = MaterialCacheManager.getCacheKey(placements);

            boolean isCached = false;
            List<MaterialListEntry> entriesToShow;
            String placementLabel = buildPlacementLabel(placements);

            if (!freshEntries.isEmpty()) {
                // Fresh data available — use it and update the cache
                entriesToShow = freshEntries;
                MaterialCacheManager.saveCache(cacheKey, freshEntries);
            } else {
                // No fresh data (chunks unloaded, or never generated) — try loading from cache
                List<MaterialListEntry> cached = MaterialCacheManager.loadCache(cacheKey);
                if (cached != null && !cached.isEmpty()) {
                    entriesToShow = cached;
                    isCached = true;
                } else {
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                Component.literal(
                                        "§c[BML] No material data available. Open Litematica's native Material List to calculate it first."),
                                false);
                    }
                    return true;
                }
            }

            // Always update available quantities based on the player's CURRENT inventory
            // before showing!
            if (entriesToShow != null && Minecraft.getInstance().player != null) {
                fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(entriesToShow,
                        Minecraft.getInstance().player);
                addCachedContainerItems(entriesToShow, placementLabel);
            }

            GuiBase.openGui(new GuiBetterMaterialList(placementLabel, entriesToShow, isCached, placements));
            return true;
        }

        // Jeśli wciśnięty klawisz nie należy do nas
        return false;
    }

    public static void addCachedContainerItems(List<MaterialListEntry> entries, String placementLabel) {
        Map<String, Integer> containerItems = ContainerDataManager.getTotalItemsForPlacement(placementLabel);
        if (!containerItems.isEmpty() && entries != null) {
            for (MaterialListEntry entry : entries) {
                Identifier id = BuiltInRegistries.ITEM.getKey(entry.getStack().getItem());
                String itemKey = id.toString();
                Integer containerCount = containerItems.get(itemKey);
                if (containerCount != null && containerCount > 0) {
                    entry.setCountAvailable(entry.getCountAvailable() + containerCount);
                }
            }
        }
    }

    /**
     * Collects materials using Litematica's already-computed data.
     * First tries DataManager.getMaterialList() (global),
     * then falls back to per-placement material lists.
     */
    public static List<MaterialListEntry> collectMaterialsFromPlacements(List<SchematicPlacement> placements) {
        // Strategy 1: Try getting the global material list from DataManager
        MaterialListBase globalList = DataManager.getMaterialList();
        if (globalList != null) {
            List<MaterialListEntry> globalEntries = globalList.getMaterialsAll();
            LOGGER.info("[BML] Got global material list with {} entries",
                    globalEntries != null ? globalEntries.size() : 0);
            if (globalEntries != null && !globalEntries.isEmpty()) {
                return new ArrayList<>(globalEntries);
            }
        }

        // Strategy 2: Try getting per-placement material lists
        LOGGER.info("[BML] Global list empty/null, trying per-placement lists...");
        Map<String, int[]> merged = new HashMap<>();
        Map<String, ItemStack> stacks = new HashMap<>();

        for (SchematicPlacement placement : placements) {
            if (!placement.isEnabled()) {
                continue;
            }
            try {
                MaterialListBase mlb = placement.getMaterialList();
                if (mlb == null) {
                    LOGGER.info("[BML] Placement '{}' has no material list", placement.getName());
                    continue;
                }
                List<MaterialListEntry> entries = mlb.getMaterialsAll();
                LOGGER.info("[BML] Placement '{}' material list: {} entries",
                        placement.getName(), entries != null ? entries.size() : 0);

                if (entries != null) {
                    for (MaterialListEntry entry : entries) {
                        Identifier id = BuiltInRegistries.ITEM.getKey(entry.getStack().getItem());
                        String key = id.toString();

                        stacks.putIfAbsent(key, entry.getStack().copy());

                        int[] counts = merged.computeIfAbsent(key, k -> new int[] { 0, 0, 0, 0 });
                        counts[0] += entry.getCountTotal();
                        counts[1] += entry.getCountMissing();
                        counts[2] += entry.getCountMismatched();
                        counts[3] += entry.getCountAvailable();
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[BML] Error reading placement '{}': {}", placement.getName(), e.getMessage(), e);
            }
        }

        List<MaterialListEntry> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : merged.entrySet()) {
            ItemStack stack = stacks.get(e.getKey());
            int[] counts = e.getValue();
            if (stack != null && counts[0] > 0) {
                result.add(new MaterialListEntry(stack.copy(), counts[0], counts[1], counts[2], counts[3]));
            }
        }

        LOGGER.info("[BML] Per-placement collection returned {} entries", result.size());
        return result;
    }

    /**
     * Builds a human-readable label from placement names.
     */
    private static String buildPlacementLabel(List<SchematicPlacement> placements) {
        if (placements.size() == 1) {
            return placements.get(0).getName();
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (SchematicPlacement p : placements) {
            if (!p.isEnabled())
                continue;
            if (count > 0)
                sb.append(", ");
            sb.append(p.getName());
            count++;
            if (count >= 3) {
                int remaining = placements.size() - 3;
                if (remaining > 0) {
                    sb.append(" (+").append(remaining).append(" more)");
                }
                break;
            }
        }
        return sb.toString();
    }

    public void addKeysToMap(IKeybindManager manager) {
        Iterator var2 = ModConfig.HOTKEYS.iterator();
        while (var2.hasNext()) {
            IHotkey hotkey = (IHotkey) var2.next();
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory("bettermateriallist", "BetterMaterialList", ModConfig.HOTKEYS);
    }
}