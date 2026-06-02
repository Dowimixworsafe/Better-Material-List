package com.betterlist.gui;

import com.betterlist.config.ModConfig;
import com.betterlist.input.InputHandler;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class GuiConfigs extends GuiConfigsBase {

    // Built lazily so the label follows the in-game language; value saved back on close.
    private fi.dy.masa.malilib.config.options.ConfigBoolean cfgHoverTooltip;

    public GuiConfigs() {
        super(10, 50, "Better List", null, "betterlist");
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        // Malilib generates the option list itself based on getConfigs().
        // getConfigs()

        // Back arrow in the top-left corner — returns to the material list.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e" + com.betterlist.util.BmlLang.tr("bml.gui.back")),
                (btn, mb) -> InputHandler.openMaterialList());
    }

    @Override
    protected void drawTitle(fi.dy.masa.malilib.render.GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // Centered title at the top so it doesn't overlap the corner "Back" button.
        String t = this.getTitleString();
        int x = (this.getScreenWidth() - this.getStringWidth(t)) / 2;
        this.drawString(ctx, t, x, 8, -1);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> configs = new ArrayList<>();

        if (this.cfgHoverTooltip == null) {
            this.cfgHoverTooltip = new fi.dy.masa.malilib.config.options.ConfigBoolean(
                    com.betterlist.util.BmlLang.tr("bml.config.hover_tooltip"),
                    ModConfig.SHOW_ITEM_HOVER_TOOLTIP,
                    com.betterlist.util.BmlLang.tr("bml.config.hover_tooltip.comment"));
        }
        configs.add(new ConfigOptionWrapper(this.cfgHoverTooltip));

        // Localize the hotkey labels/comments shown in the config GUI (getName() stays the
        // English storage key, so saved bindings are unaffected).
        localize(ModConfig.OPEN_GUI,         "open_gui");
        localize(ModConfig.RELOAD_LIST,      "reload_list");
        localize(ModConfig.OPEN_CONFIG,      "open_config");
        localize(ModConfig.OPEN_PARTY,       "open_party");
        localize(ModConfig.OPEN_CHESTS,      "open_chests");
        localize(ModConfig.TOGGLE_HIGHLIGHT, "toggle_highlight");
        localize(ModConfig.TOGGLE_HUD,       "toggle_hud");
        localize(ModConfig.HUD_SCROLL_FWD,   "hud_scroll_fwd");
        localize(ModConfig.HUD_SCROLL_BACK,  "hud_scroll_back");

        for (fi.dy.masa.malilib.config.options.ConfigHotkey hotkey : ModConfig.HOTKEYS) {
            configs.add(new ConfigOptionWrapper(hotkey));
        }
        return configs;
    }

    private static void localize(fi.dy.masa.malilib.config.options.ConfigHotkey hk, String slug) {
        hk.setTranslatedName(com.betterlist.util.BmlLang.tr("bml.key." + slug));
        hk.setComment(com.betterlist.util.BmlLang.tr("bml.key." + slug + ".c"));
    }

    @Override
    public void removed() {
        super.removed();
        // Pull the toggle value back into ModConfig before saving.
        if (this.cfgHoverTooltip != null) {
            ModConfig.SHOW_ITEM_HOVER_TOOLTIP = this.cfgHoverTooltip.getBooleanValue();
        }
        // On close, immediately save the new config to disk.
        ModConfig config = new ModConfig();
        config.save();
    }
}