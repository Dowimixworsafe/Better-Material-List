package com.betterlist.input;

import com.betterlist.data.MaterialCacheManager;
import com.betterlist.data.ContainerDataManager;
import com.betterlist.gui.GuiBetterMaterialList;
import com.betterlist.config.ModConfig;
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
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterList");
    private static final InputHandler INSTANCE = new InputHandler();

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    public void registerKeyCallbacks() {
        // Register listeners for all our keybinds.
        ModConfig.OPEN_GUI.getKeybind().setCallback(this);
        ModConfig.RELOAD_LIST.getKeybind().setCallback(this);
        ModConfig.OPEN_CONFIG.getKeybind().setCallback(this);
        ModConfig.OPEN_PARTY.getKeybind().setCallback(this);
        ModConfig.OPEN_CHESTS.getKeybind().setCallback(this);
        ModConfig.TOGGLE_HIGHLIGHT.getKeybind().setCallback(this);
        ModConfig.TOGGLE_HUD.getKeybind().setCallback(this);
        ModConfig.HUD_SCROLL_FWD.getKeybind().setCallback(this);
        ModConfig.HUD_SCROLL_BACK.getKeybind().setCallback(this);
    }

    public boolean onKeyAction(KeyAction action, IKeybind key) {

        // --- 1. Otwieranie Menu Konfiguracji ---
        if (key == ModConfig.OPEN_CONFIG.getKeybind()) {
            GuiBase.openGui(new com.betterlist.gui.GuiConfigs());
            return true;
        }

        if (key == ModConfig.OPEN_PARTY.getKeybind()) {
            GuiBase.openGui(new com.betterlist.gui.GuiParty());
            return true;
        }

        if (key == ModConfig.OPEN_CHESTS.getKeybind()) {
            GuiBase.openGui(new com.betterlist.gui.GuiBmlChests(buildPlacementLabel(
                    DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())));
            return true;
        }

        if (key == ModConfig.TOGGLE_HIGHLIGHT.getKeybind()) {
            boolean on = com.betterlist.data.ChestHighlightManager.toggleAll();
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        on ? "§a" + com.betterlist.util.BmlLang.tr("bml.highlight.on")
                           : "§7" + com.betterlist.util.BmlLang.tr("bml.highlight.off")));
            }
            return true;
        }

        if (key == ModConfig.HUD_SCROLL_FWD.getKeybind()) {
            com.betterlist.data.HudOverlayManager.scrollForward();
            return true;
        }

        if (key == ModConfig.HUD_SCROLL_BACK.getKeybind()) {
            com.betterlist.data.HudOverlayManager.scrollBack();
            return true;
        }

        if (key == ModConfig.TOGGLE_HUD.getKeybind()) {
            boolean on = com.betterlist.data.HudOverlayManager.toggle();
            com.betterlist.party.FocusManager.save(); // persist the HUD flag across relogs
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        on ? "§a" + com.betterlist.util.BmlLang.tr("bml.hud.on")
                           : "§7" + com.betterlist.util.BmlLang.tr("bml.hud.off")));
            }
            return true;
        }

        // --- 2. Reload ONLY while the GUI is open ---
        if (key == ModConfig.RELOAD_LIST.getKeybind()) {
            // If the BML screen is NOT open, ignore this key entirely.
            // This keeps reload from firing in the background during normal play.
            if (!(Minecraft.getInstance().screen instanceof GuiBetterMaterialList)) {
                return false;
            }

            GuiBetterMaterialList currentGui = (GuiBetterMaterialList) Minecraft.getInstance().screen;

            // If the player clicked the search bar and wants to type e.g. "dirt",
            // pass the "r" through to the bar and do NOT reload the list.
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

        // --- 3. Open the main GUI ---
        if (key == ModConfig.OPEN_GUI.getKeybind()) {
            openMaterialList();
            return true;
        }

        // Key isn't ours.
        return false;
    }

    /**
     * Opens the main material-list GUI (fresh data or cache). Public because it's also
     * used by the "back" buttons in sub-screens (Config / Chests / Party).
     */
    public static void openMaterialList() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        List<SchematicPlacement> placements = manager.getAllSchematicsPlacements();

        List<MaterialListEntry> entriesToShow = collectEntriesWithCacheFallback(placements);
        String placementLabel = (placements != null && !placements.isEmpty())
                ? buildPlacementLabel(placements) : com.betterlist.util.BmlLang.tr("bml.list.no_schematic");
        boolean isCached = lastCollectWasCached;

        if (entriesToShow != null && !entriesToShow.isEmpty() && Minecraft.getInstance().player != null) {
            applyAvailableCounts(entriesToShow, Minecraft.getInstance().player);
        }

        GuiBase.openGui(new GuiBetterMaterialList(placementLabel, entriesToShow, isCached, placements));
    }

    // Set by collectEntriesWithCacheFallback: whether the last result came from cache.
    private static boolean lastCollectWasCached = false;

    // Last non-empty list; survives an in-place dimension swap when Litematica's live data is
    // briefly empty, so the GUI list and HUD don't blank out. Cleared on disconnect.
    private static List<MaterialListEntry> lastGoodEntries = null;

    /**
     * Material-list entries for the given placements, falling back to the on-disk cache and
     * then the in-memory last-good snapshot when Litematica's live data is empty (relog /
     * dimension swap). Fresh data is saved to the cache. Shared by the main GUI and the HUD.
     */
    public static List<MaterialListEntry> collectEntriesWithCacheFallback(List<SchematicPlacement> placements) {
        lastCollectWasCached = false;

        if (placements != null && !placements.isEmpty()) {
            List<MaterialListEntry> fresh = collectMaterialsFromPlacements(placements);
            String cacheKey = MaterialCacheManager.getCacheKey(placements);
            if (!fresh.isEmpty()) {
                MaterialCacheManager.saveCache(cacheKey, fresh);
                lastGoodEntries = copyEntries(fresh);
                return fresh;
            }
            List<MaterialListEntry> cached = MaterialCacheManager.loadCache(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                lastCollectWasCached = true;
                lastGoodEntries = copyEntries(cached);
                return cached;
            }
        }

        if (lastGoodEntries != null && !lastGoodEntries.isEmpty()) {
            lastCollectWasCached = true;
            return copyEntries(lastGoodEntries);
        }
        return new ArrayList<>();
    }

    // Deep copy so a stored snapshot can't be mutated by updateAvailableCounts.
    private static List<MaterialListEntry> copyEntries(List<MaterialListEntry> src) {
        List<MaterialListEntry> out = new ArrayList<>(src.size());
        for (MaterialListEntry e : src) {
            out.add(new MaterialListEntry(e.getStack().copy(),
                    e.getCountTotal(), e.getCountMissing(), e.getCountMismatched(), e.getCountAvailable()));
        }
        return out;
    }

    public static void clearLastGoodEntries() {
        lastGoodEntries = null;
    }

    public static void scheduleQuietRecount(List<SchematicPlacement> placements) {
        Minecraft mc = Minecraft.getInstance();
        if (placements == null || placements.isEmpty() || mc.player == null || mc.level == null) return;
        fi.dy.masa.litematica.scheduler.TaskScheduler scheduler =
                fi.dy.masa.litematica.scheduler.TaskScheduler.getInstanceClient();
        if (scheduler.hasTask(fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement.class)) return;
        boolean ignoreState =
                fi.dy.masa.litematica.config.Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue();
        for (SchematicPlacement p : placements) {
            if (!p.isEnabled()) continue;
            MaterialListBase mlb = p.getMaterialList();
            if (mlb == null) continue;
            net.minecraft.core.BlockPos origin = p.getOrigin();
            if (origin == null || !mc.level.hasChunkAt(origin)) continue;
            scheduler.scheduleTask(
                    new fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement(p, mlb, ignoreState), 20);
        }
    }

    public static void applyAvailableCounts(List<MaterialListEntry> entries, net.minecraft.world.entity.player.Player player) {
        if (entries == null) return;
        if (com.betterlist.config.ModConfig.COUNT_PLAYER_INVENTORY && player != null) {
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(entries, player);
        } else {
            for (MaterialListEntry entry : entries) entry.setCountAvailable(0);
        }
        addCachedContainerItems(entries);
    }

    public static void addCachedContainerItems(List<MaterialListEntry> entries) {
        Map<String, Integer> containerItems = ContainerDataManager.getTotalItems();
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
        manager.addHotkeysForCategory("betterlist", "Better List", ModConfig.HOTKEYS);
    }
}