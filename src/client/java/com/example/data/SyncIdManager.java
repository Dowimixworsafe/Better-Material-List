package com.example.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
 * Utrzymuje mapowanie: (Lokalna Nazwa Placementu Litematiki) -> (Identyfikator Party Sync).
 * Dzięki temu gracze mogą mieć własne nazwy schematów na kliencie, 
 * ale wszystkie postępy popłyną wspólnym kanałem np. 'zamek'.
 */
public class SyncIdManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-SyncId");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SAVE_FILE;

    // K: placementName, V: syncId
    private static final Map<String, String> placementToSyncId = new HashMap<>();

    static {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        if (!configDir.exists()) configDir.mkdirs();
        SAVE_FILE = new File(configDir, "bml_sync_ids.json");
    }

    public static void load() {
        if (!SAVE_FILE.exists()) return;
        try (FileReader reader = new FileReader(SAVE_FILE)) {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                placementToSyncId.putAll(loaded);
            }
            LOGGER.info("[BML] Loaded {} Sync ID mappings.", placementToSyncId.size());
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to load sync IDs: {}", e.getMessage());
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(SAVE_FILE)) {
            GSON.toJson(placementToSyncId, writer);
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to save sync IDs: {}", e.getMessage());
        }
    }

    /** Pobierz Identyfikator Party dla danego schematu. Domyślnie zwraca lokalną nazwę. */
    public static String getSyncId(String placementName) {
        return placementToSyncId.getOrDefault(placementName, placementName);
    }

    /** Odwrócone zapytanie: otrzymaliśmy pakiet dla `syncId`, dla jakiego lokalnego schematu to wpisać? */
    public static String getPlacementBySyncId(String syncId) {
        for (Map.Entry<String, String> entry : placementToSyncId.entrySet()) {
            if (entry.getValue().equals(syncId)) return entry.getKey();
        }
        return syncId; // Jeśli brak dedykowanego mapowania, zakładamy że syncId == placementName (domyślne działanie)
    }

    /** Ustaw i zapisz nowe powiązanie. */
    public static void setSyncId(String placementName, String syncId) {
        if (syncId == null || syncId.isBlank() || syncId.equals(placementName)) {
            placementToSyncId.remove(placementName);
        } else {
            placementToSyncId.put(placementName, syncId);
        }
        save();
    }
}
