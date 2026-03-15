package com.example.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

@Environment(EnvType.CLIENT)
public class MaterialCacheManager {
    private static final File CACHE_FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "bettermateriallist_cache.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static JsonObject cacheRoot = new JsonObject();

    static {
        loadCacheFile();
    }

    /**
     * Loads the entire cache JSON file from disk into memory.
     */
    private static void loadCacheFile() {
        if (CACHE_FILE.exists() && CACHE_FILE.isFile() && CACHE_FILE.canRead()) {
            try (FileReader reader = new FileReader(CACHE_FILE)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) {
                    cacheRoot = element.getAsJsonObject();
                }
            } catch (Exception e) {
                e.printStackTrace();
                cacheRoot = new JsonObject();
            }
        }
    }

    /**
     * Writes the entire cache JSON to disk.
     */
    private static void saveCacheFile() {
        try {
            File dir = CACHE_FILE.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CACHE_FILE)) {
                GSON.toJson(cacheRoot, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Builds a cache key from the current server/world and the given placement
     * names.
     */
    public static String getCacheKey(List<SchematicPlacement> placements) {
        Minecraft mc = Minecraft.getInstance();
        String worldId;

        ServerData serverData = mc.getCurrentServer();
        if (serverData != null) {
            worldId = "server_" + serverData.ip.replace(":", "_");
        } else if (mc.getSingleplayerServer() != null) {
            worldId = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            worldId = "unknown";
        }

        StringBuilder sb = new StringBuilder(worldId);
        sb.append("|");
        List<String> names = new ArrayList<>();
        for (SchematicPlacement placement : placements) {
            names.add(placement.getName());
        }
        names.sort(String::compareTo);
        sb.append(String.join("+", names));

        return sb.toString();
    }

    /**
     * Saves a material list to the cache under the given key.
     */
    public static void saveCache(String cacheKey, List<MaterialListEntry> entries) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", System.currentTimeMillis());

        JsonArray items = new JsonArray();
        for (MaterialListEntry mle : entries) {
            JsonObject itemObj = new JsonObject();
            Identifier id = BuiltInRegistries.ITEM.getKey(mle.getStack().getItem());
            itemObj.addProperty("item", id.toString());
            itemObj.addProperty("total", mle.getCountTotal());
            itemObj.addProperty("missing", mle.getCountMissing());
            items.add(itemObj);
        }
        entry.add("entries", items);

        cacheRoot.add(cacheKey, entry);
        saveCacheFile();
    }

    /**
     * Loads a cached material list for the given key.
     * Returns null if no cache exists for this key.
     */
    public static List<MaterialListEntry> loadCache(String cacheKey) {
        if (!cacheRoot.has(cacheKey)) {
            return null;
        }

        JsonObject cached = cacheRoot.getAsJsonObject(cacheKey);
        if (!cached.has("entries")) {
            return null;
        }

        JsonArray items = cached.getAsJsonArray("entries");
        List<MaterialListEntry> result = new ArrayList<>();

        for (JsonElement el : items) {
            JsonObject itemObj = el.getAsJsonObject();
            String itemId = itemObj.get("item").getAsString();
            int total = itemObj.get("total").getAsInt();
            int missing = itemObj.get("missing").getAsInt();

            try {
                Identifier loc = Identifier.parse(itemId);
                Item item = BuiltInRegistries.ITEM.get(loc)
                        .map(ref -> ref.value())
                        .orElse(null);
                if (item != null) {
                    ItemStack stack = new ItemStack(item);
                    MaterialListEntry mle = new MaterialListEntry(stack, total, missing, total - missing, 0);
                    result.add(mle);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Get the timestamp of when the cache was last saved for a given key.
     * Returns -1 if no cache exists.
     */
    public static long getCacheTimestamp(String cacheKey) {
        if (!cacheRoot.has(cacheKey)) {
            return -1;
        }
        JsonObject cached = cacheRoot.getAsJsonObject(cacheKey);
        return cached.has("timestamp") ? cached.get("timestamp").getAsLong() : -1;
    }

    public static void clearCache(String cacheKey) {
        if (cacheRoot.has(cacheKey)) {
            cacheRoot.remove(cacheKey);
            saveCacheFile();
        }
    }
}
