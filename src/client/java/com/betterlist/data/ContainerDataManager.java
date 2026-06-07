package com.betterlist.data;

import com.betterlist.network.BmlClientNetworking;
import com.betterlist.party.PartyManager;
import com.betterlist.util.BmlServerId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores the contents of marked ("tracked") chests so they can be added to the "stored"
 * column on the material list.
 *
 * Data model (after the rework):
 *   Map<containerId, Map<itemName, count>>
 *
 *   A chest is identified ONLY by its physical location (dimension + position, see
 *   {@code AbstractContainerScreenMixin#getContainerId}). Data is no longer keyed by
 *   placement.
 *
 * Why:
 *   Chests used to be nested under a "joined active-placement label" (e.g. "A, B (+2
 *   more)"). That label depended on placement order and count and was computed in two
 *   diverging places (mixin write vs GUI read), so "stored" could show something
 *   different on every list opening — even solo and without opening a chest. A chest has
 *   one physical location shared by everyone in a party, so a global per-server key is
 *   natural and stable.
 *
 * File: {@code config/betterlist_data/<serverId>_containers_v2.json}.
 * The old format is migrated once on startup (flattened) and the original is kept as
 * {@code .bak}.
 */
@Environment(EnvType.CLIENT)
public class ContainerDataManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Containers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Constant placed in the "placement" field of sync packets — chests are global. */
    public static final String GLOBAL_PLACEMENT = "__global__";

    // A valid containerId is "<dimension>;<BlockPos>" — e.g. "minecraft:overworld;-220, 73, -212"
    // (or the bracketed "[10, 64, -20]" form). Anything else (e.g. a stale "PLAYER EQ" key
    // from an old build or a foreign packet) is rejected so it can't pollute the list/totals.
    private static final java.util.regex.Pattern CONTAINER_ID_PATTERN =
            java.util.regex.Pattern.compile("^.+;\\[?-?\\d+, ?-?\\d+, ?-?\\d+]?$");

    public static boolean isValidContainerId(String id) {
        return id != null && CONTAINER_ID_PATTERN.matcher(id).matches();
    }

    // Last clicked block — set by MultiPlayerGameModeMixin, used to derive the
    // containerId of the opened chest.
    public static net.minecraft.core.BlockPos lastInteractedBlockPos = null;

    // Map<containerId, Map<itemName, count>>
    private static Map<String, Map<String, Integer>> containers = new HashMap<>();

    // Write debounce: mutations set dirty=true; the actual disk write happens in flush(),
    // called periodically from ClientTickEvents and on disconnect. This keeps a burst of
    // clicks/scans from triggering a burst of synchronous writes on the main thread.
    private static volatile boolean dirty = false;

    private static void markDirty() {
        dirty = true;
    }

    /** Writes to disk only if there were changes since the last flush. */
    public static void flush() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    private static File dataDir() {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "betterlist_data");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File getSaveFile() {
        return new File(dataDir(), BmlServerId.current() + "_containers_v2.json");
    }

    private static File getLegacyFile() {
        return new File(dataDir(), BmlServerId.current() + "_containers.json");
    }

    public static void load() {
        containers.clear();
        File saveFile = getSaveFile();

        if (saveFile.exists()) {
            try (FileReader reader = new FileReader(saveFile)) {
                Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
                Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    containers = loaded;
                    pruneInvalidContainers();
                }
            } catch (Exception e) {
                LOGGER.error("[BML] Failed to read {} — keeping a backup and starting empty: {}",
                        saveFile.getName(), e.getMessage());
                backup(saveFile);
            }
            return;
        }

        // No new file — try to migrate the old (per-placement nested) format.
        migrateLegacy();
    }

    /** Drops any container whose id isn't a valid "dimension;pos" (e.g. legacy "PLAYER EQ"). */
    private static void pruneInvalidContainers() {
        int before = containers.size();
        containers.keySet().removeIf(id -> !isValidContainerId(id));
        int removed = before - containers.size();
        if (removed > 0) {
            LOGGER.info("[BML] Dropped {} invalid container key(s) from saved data.", removed);
            save();
        }
    }

    /**
     * Flattens the old {@code placement -> containerId -> items} format to
     * {@code containerId -> items}, summing contents if the same chest appeared under
     * multiple placements.
     */
    private static void migrateLegacy() {
        File legacy = getLegacyFile();
        if (!legacy.exists()) return;

        try (FileReader reader = new FileReader(legacy)) {
            Type type = new TypeToken<Map<String, Map<String, Map<String, Integer>>>>() {}.getType();
            Map<String, Map<String, Map<String, Integer>>> old = GSON.fromJson(reader, type);
            if (old != null) {
                for (Map<String, Map<String, Integer>> perPlacement : old.values()) {
                    if (perPlacement == null) continue;
                    for (Map.Entry<String, Map<String, Integer>> ce : perPlacement.entrySet()) {
                        Map<String, Integer> target = containers.computeIfAbsent(ce.getKey(), k -> new HashMap<>());
                        if (ce.getValue() != null) {
                            ce.getValue().forEach((item, count) ->
                                    target.merge(item, count, Integer::sum));
                        }
                    }
                }
                LOGGER.info("[BML] Migrated {} container(s) from legacy format.", containers.size());
                save();
            }
        } catch (Exception e) {
            LOGGER.error("[BML] Legacy container migration failed: {}", e.getMessage());
        } finally {
            // Regardless of outcome — move the old file aside so we don't re-migrate it.
            backup(legacy);
        }
    }

    private static void backup(File file) {
        try {
            File bak = new File(file.getParentFile(), file.getName() + ".bak");
            if (bak.exists()) bak.delete();
            file.renameTo(bak);
        } catch (Exception ignored) {}
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(getSaveFile())) {
            GSON.toJson(containers, writer);
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to save containers: {}", e.getMessage());
        }
    }

    public static void clear() {
        containers.clear();
    }

    public static boolean isContainerMarked(String containerId) {
        return containerId != null && containers.containsKey(containerId);
    }

    public static void setContainerMarked(String containerId, boolean marked) {
        if (containerId == null) return;
        if (marked) {
            containers.putIfAbsent(containerId, new HashMap<>());
        } else {
            containers.remove(containerId);
        }
        markDirty();
        if (PartyManager.isInParty()) {
            BmlClientNetworking.sendContainerMarkedSync(GLOBAL_PLACEMENT, containerId, marked);
        }
    }

    /**
     * Mark/unmark from a party member (no re-broadcast). The party is a single shared
     * "brain": a teammate marking/unmarking a chest propagates to everyone, exactly like
     * builds do. We only reject ids that aren't a valid "dimension;pos" so foreign/legacy
     * junk (e.g. "PLAYER EQ") can't enter the shared state.
     */
    public static void setContainerMarkedSilent(String containerId, boolean marked) {
        if (!isValidContainerId(containerId)) return;
        if (marked) {
            containers.putIfAbsent(containerId, new HashMap<>());
        } else {
            containers.remove(containerId);
        }
        markDirty();
    }

    /**
     * Updates a chest's contents (after a local scan) — only if it is marked.
     *
     * This intentionally accepts an empty scan: emptying a real tracked chest must drop its
     * "stored" count to 0. The data-loss bug where the WRONG screen (player inventory, a
     * crafting table, …) was scanned into a chest's id is prevented upstream by the
     * trackable-container guard in {@code AbstractContainerScreenMixin#onRemoved}, so only a
     * genuine scan of this chest reaches here.
     */
    public static void updateContainerItems(String containerId, Map<String, Integer> items) {
        if (containerId == null) return;
        if (isContainerMarked(containerId)) {
            containers.put(containerId, new HashMap<>(items));
            markDirty();
            if (PartyManager.isInParty()) {
                BmlClientNetworking.sendContainerSync(GLOBAL_PLACEMENT, containerId, items);
            }
        }
    }

    /**
     * Updates a chest's contents from data received from a party member (without
     * re-sending). We do not overwrite existing data with an EMPTY version — this
     * prevents losing "stored" when someone pushes full state before they have scanned
     * that chest themselves.
     */
    public static void updateContainerItemsSilent(String containerId, Map<String, Integer> items) {
        if (!isValidContainerId(containerId)) return;
        if ((items == null || items.isEmpty()) && isContainerMarked(containerId)
                && !containers.get(containerId).isEmpty()) {
            return;
        }
        containers.put(containerId, items == null ? new HashMap<>() : new HashMap<>(items));
        markDirty();
    }

    /** Sum of the contents of ALL marked chests on the server. */
    public static Map<String, Integer> getTotalItems() {
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Integer> contents : containers.values()) {
            if (contents == null) continue;
            for (Map.Entry<String, Integer> entry : contents.entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return totals;
    }

    public static void clearAll() {
        if (!containers.isEmpty()) {
            containers.clear();
            markDirty();
        }
    }

    public static java.util.Set<String> getMarkedContainers() {
        return new java.util.HashSet<>(containers.keySet());
    }

    /** Contents of a single marked chest (or an empty map). */
    public static Map<String, Integer> getContainerContents(String containerId) {
        if (containerId == null) return new HashMap<>();
        Map<String, Integer> c = containers.get(containerId);
        return c == null ? new HashMap<>() : new HashMap<>(c);
    }

    /** Full chest snapshot: { containerId -> { item -> count } }. For SYNC_FULL_STATE. */
    public static Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        containers.forEach((id, items) -> copy.put(id, new HashMap<>(items)));
        return copy;
    }
}
