package com.betterlist.config;

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
   private static final String CONFIG_FILE_NAME = "betterlist.json";
   public static final ConfigHotkey OPEN_GUI = new ConfigHotkey("Open Material List", "PERIOD",
         "betterlist.hotkeys.open_gui");
   public static final ConfigHotkey RELOAD_LIST = new ConfigHotkey(
         "Reload Material List",
         "R",
         fi.dy.masa.malilib.hotkeys.KeybindSettings.create(
               fi.dy.masa.malilib.hotkeys.KeybindSettings.Context.ANY,
               fi.dy.masa.malilib.hotkeys.KeyAction.PRESS,
               false, false, false, false),
         "betterlist.hotkeys.reload_list");
   public static final ConfigHotkey OPEN_CONFIG = new ConfigHotkey("Open Config GUI", "COMMA",
         "betterlist.hotkeys.open_config");
   public static final ConfigHotkey OPEN_PARTY = new ConfigHotkey("Open Party GUI", "O",
         "betterlist.hotkeys.open_party");
   public static final ConfigHotkey OPEN_CHESTS = new ConfigHotkey("Open Chests GUI", "K",
         "betterlist.hotkeys.open_chests");
   public static final ConfigHotkey TOGGLE_HIGHLIGHT = new ConfigHotkey("Toggle Chest Highlight", "H",
         "betterlist.hotkeys.toggle_highlight");
   public static final ConfigHotkey TOGGLE_HUD = new ConfigHotkey("Toggle Targeted-Items HUD", "J",
         "betterlist.hotkeys.toggle_hud");
   public static final ConfigHotkey HUD_SCROLL_FWD = new ConfigHotkey("HUD Scroll Forward", "LEFT_CONTROL,J",
         "betterlist.hotkeys.hud_scroll_fwd");
   public static final ConfigHotkey HUD_SCROLL_BACK = new ConfigHotkey("HUD Scroll Back", "LEFT_ALT,J",
         "betterlist.hotkeys.hud_scroll_back");
   public static final List<ConfigHotkey> HOTKEYS;

   // General options (stored under "Options" in betterlist.json).
   public static boolean SHOW_ITEM_HOVER_TOOLTIP = true;

   public void load() {
      File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "betterlist.json");
      if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
         JsonElement element = JsonUtils.parseJsonFile(configFile.toPath());
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

            if (root.has("Options")) {
               JsonObject optionsObj = root.getAsJsonObject("Options");
               if (optionsObj.has("showItemHoverTooltip")) {
                  SHOW_ITEM_HOVER_TOOLTIP = optionsObj.get("showItemHoverTooltip").getAsBoolean();
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

         JsonObject optionsObj = new JsonObject();
         optionsObj.addProperty("showItemHoverTooltip", SHOW_ITEM_HOVER_TOOLTIP);
         root.add("Options", optionsObj);

         JsonUtils.writeJsonToFile(root, new File(dir, "betterlist.json").toPath());
      }

   }

   static {
      HOTKEYS = ImmutableList.of(OPEN_GUI, RELOAD_LIST, OPEN_CONFIG, OPEN_PARTY, OPEN_CHESTS, TOGGLE_HIGHLIGHT, TOGGLE_HUD, HUD_SCROLL_FWD, HUD_SCROLL_BACK);
   }
}
