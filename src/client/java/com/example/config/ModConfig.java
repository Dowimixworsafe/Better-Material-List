package com.example.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.JsonUtils;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public class ModConfig implements IConfigHandler {
   private static final String CONFIG_FILE_NAME = "bettermateriallist.json";
   public static final ConfigHotkey OPEN_GUI = new ConfigHotkey("Open Material List", "PERIOD",
         "bettermateriallist.hotkeys.open_gui");
   public static final ConfigHotkey RELOAD_LIST = new ConfigHotkey(
         "Reload Material List",
         "R",
         fi.dy.masa.malilib.hotkeys.KeybindSettings.create(
               fi.dy.masa.malilib.hotkeys.KeybindSettings.Context.ANY,
               fi.dy.masa.malilib.hotkeys.KeyAction.PRESS,
               false, false, false, false),
         "bettermateriallist.hotkeys.reload_list");
   public static final ConfigHotkey OPEN_CONFIG = new ConfigHotkey("Open Config GUI", "UNKNOWN",
         "bettermateriallist.hotkeys.open_config");
   public static final ConfigHotkey OPEN_PARTY = new ConfigHotkey("Open Party GUI", "O",
         "bettermateriallist.hotkeys.open_party");
   public static final List<ConfigHotkey> HOTKEYS;

   public void load() {
      File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bettermateriallist.json");
      if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
         JsonElement element = JsonUtils.parseJsonFile(configFile);
         if (element != null && element.isJsonObject()) {
            JsonObject root = element.getAsJsonObject();
            if (root.has("Hotkeys")) {
               JsonObject hotkeysObj = root.getAsJsonObject("Hotkeys");
               Iterator var5 = HOTKEYS.iterator();

               while (var5.hasNext()) {
                  ConfigHotkey hotkey = (ConfigHotkey) var5.next();
                  String name = hotkey.getName();
                  if (hotkeysObj.has(name)) {
                     hotkey.setValueFromString(hotkeysObj.get(name).getAsString());
                  }
               }
            }
         }
      }

   }

   public void save() {
      File dir = FabricLoader.getInstance().getConfigDir().toFile();
      if (dir.exists() && dir.isDirectory() || dir.mkdirs()) {
         JsonObject root = new JsonObject();
         JsonObject hotkeysObj = new JsonObject();
         Iterator var4 = HOTKEYS.iterator();

         while (var4.hasNext()) {
            ConfigHotkey hotkey = (ConfigHotkey) var4.next();
            hotkeysObj.addProperty(hotkey.getName(), hotkey.getStringValue());
         }

         root.add("Hotkeys", hotkeysObj);
         JsonUtils.writeJsonToFile(root, new File(dir, "bettermateriallist.json"));
      }

   }

   static {
      HOTKEYS = ImmutableList.of(OPEN_GUI, RELOAD_LIST, OPEN_CONFIG, OPEN_PARTY);
   }
}
