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
        // Malilib sam zajmie się wygenerowaniem listy opcji na podstawie metody
        // getConfigs()

        // Strzałka wstecz w lewym górnym rogu — wraca do listy materiałów.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e← Wróć"),
                (btn, mb) -> InputHandler.openMaterialList());
    }

    @Override
    protected void drawTitle(fi.dy.masa.malilib.render.GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // Tytuł wyśrodkowany u góry, żeby nie nachodził na przycisk "Wróć" w rogu.
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
        // Gdy zamykamy okno, od razu zapisujemy nową konfigurację na dysk
        ModConfig config = new ModConfig();
        config.save();
    }
}