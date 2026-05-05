package com.example.gui;

import com.example.config.ModConfig;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
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
        // Malilib sam zajmie się wygenerowaniem listy opcji na podstawie metody
        // getConfigs()
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
        // Gdy zamykamy okno, od razu zapisujemy nową konfigurację na dysk
        ModConfig config = new ModConfig();
        config.save();
    }
}