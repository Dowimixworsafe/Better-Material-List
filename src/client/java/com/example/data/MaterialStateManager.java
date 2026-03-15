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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Environment(EnvType.CLIENT)
public class MaterialStateManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static Map<String, Set<String>> checkedItems = new HashMap<>();

   private static String getServerId() {
      Minecraft client = Minecraft.getInstance();
      if (client.getCurrentServer() != null) {
         return "mp_" + client.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9_\\-]", "_");
      }
      return "singleplayer";
   }

   private static File getSaveFile() {
      File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bettermateriallist_data");
      if (!dir.exists()) {
         dir.mkdirs();
      }
      return new File(dir, getServerId() + "_checks.json");
   }

   public static void load() {
      checkedItems.clear();
      File saveFile = getSaveFile();
      if (saveFile.exists()) {
         try (FileReader reader = new FileReader(saveFile)) {
            Type type = new TypeToken<Map<String, Set<String>>>() {
            }.getType();
            Map<String, Set<String>> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
               checkedItems = loaded;
            }
         } catch (Exception e) {
            e.printStackTrace();
         }
      }
   }

   public static void save() {
      try (FileWriter writer = new FileWriter(getSaveFile())) {
         GSON.toJson(checkedItems, writer);
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public static void clear() {
      checkedItems.clear();
   }

   public static boolean isChecked(String placementName, String itemName) {
      return placementName != null && itemName != null &&
            checkedItems.getOrDefault(placementName, new HashSet<>()).contains(itemName);
   }

   public static void setChecked(String placementName, String itemName, boolean checked) {
      if (placementName != null && itemName != null) {
         checkedItems.putIfAbsent(placementName, new HashSet<>());
         if (checked) {
            checkedItems.get(placementName).add(itemName);
         } else {
            checkedItems.get(placementName).remove(itemName);
         }
         save();
      }
   }
}