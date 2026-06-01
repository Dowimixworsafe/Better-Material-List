package com.example.gui;

import com.example.config.ModConfig;
import com.example.input.InputHandler;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class GuiConfigs extends GuiConfigsBase {

    public GuiConfigs() {
        super(10, 50, "BetterMaterialList", null, "bettermateriallist");
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        // Malilib generates the option list itself based on getConfigs().
        // getConfigs()

        // Back arrow in the top-left corner — returns to the material list.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e" + com.example.util.BmlLang.tr("bml.gui.back")),
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
        // Pobieramy wszystkie nasze klawisze i wrzucamy do wrappera maliliba
        for (fi.dy.masa.malilib.config.options.ConfigHotkey hotkey : ModConfig.HOTKEYS) {
            configs.add(new ConfigOptionWrapper(hotkey));
        }
        return configs;
    }

    @Override
    public void removed() {
        super.removed();
        // On close, immediately save the new config to disk.
        ModConfig config = new ModConfig();
        config.save();
    }
}