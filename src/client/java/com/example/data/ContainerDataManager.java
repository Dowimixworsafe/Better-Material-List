package com.example.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class ContainerDataManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Store the last interacted block position globally on the client to track
    // which container is opened
    public static net.minecraft.core.BlockPos lastInteractedBlockPos = null;

    // Map of Placement -> (Map of ContainerID -> (Map of Item Name -> Count))
    private static Map<String, Map<String, Map<String, Integer>>> containerItems = new HashMap<>();

    // Zwraca unikalną nazwę dla serwera (np. mp_hypixel_net) lub singleplayer
    private static String getServerId() {
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            return "mp_" + client.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        }
        return "singleplayer";
    }

    // Dynamicznie generuje ścieżkę do pliku zależnie od serwera
    private static File getSaveFile() {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bettermateriallist_data");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, getServerId() + "_containers.json");
    }

    public static void load() {
        containerItems.clear(); // Czyścimy dane z poprzedniego serwera
        File saveFile = getSaveFile();
        if (saveFile.exists()) {
            try (FileReader reader = new FileReader(saveFile)) {
                Type type = new TypeToken<Map<String, Map<String, Map<String, Integer>>>>() {
                }.getType();
                Map<String, Map<String, Map<String, Integer>>> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    containerItems = loaded;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(getSaveFile())) {
            GSON.toJson(containerItems, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void clear() {
        containerItems.clear();
    }

    public static boolean isContainerMarked(String placementName, String containerId) {
        if (placementName == null || containerId == null)
            return false;
        Map<String, Map<String, Integer>> pMap = containerItems.get(placementName);
        return pMap != null && pMap.containsKey(containerId);
    }

    public static void setContainerMarked(String placementName, String containerId, boolean marked) {
        if (placementName == null || containerId == null)
            return;
        containerItems.putIfAbsent(placementName, new HashMap<>());
        Map<String, Map<String, Integer>> pMap = containerItems.get(placementName);

        if (marked) {
            pMap.putIfAbsent(containerId, new HashMap<>());
        } else {
            pMap.remove(containerId);
        }
        save();
    }

    public static void updateContainerItems(String placementName, String containerId, Map<String, Integer> items) {
        if (placementName == null || containerId == null)
            return;
        // Only update if it's currently marked
        if (isContainerMarked(placementName, containerId)) {
            containerItems.get(placementName).put(containerId, new HashMap<>(items));
            save();
        }
    }

    public static Map<String, Integer> getTotalItemsForPlacement(String placementName) {
        Map<String, Integer> totals = new HashMap<>();
        if (placementName == null)
            return totals;

        Map<String, Map<String, Integer>> pMap = containerItems.get(placementName);
        if (pMap != null) {
            for (Map<String, Integer> containerContents : pMap.values()) {
                for (Map.Entry<String, Integer> entry : containerContents.entrySet()) {
                    totals.put(entry.getKey(), totals.getOrDefault(entry.getKey(), 0) + entry.getValue());
                }
            }
        }
        return totals;
    }

    public static void clearPlacement(String placementName) {
        if (placementName != null && containerItems.containsKey(placementName)) {
            containerItems.remove(placementName);
            save();
        }
    }
}