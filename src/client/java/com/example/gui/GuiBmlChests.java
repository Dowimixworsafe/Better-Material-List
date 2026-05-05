package com.example.gui;

import com.example.data.ContainerDataManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GuiBmlChests extends GuiBase {
    private final String placementName;
    private final List<String> coordsList = new ArrayList<>();

    public GuiBmlChests(String placementName) {
        this.placementName = placementName;
        this.title = "Lista Skrzyń BML: " + placementName;
        refreshList();
    }

    private void refreshList() {
        this.coordsList.clear();
        Set<String> containers = ContainerDataManager.getMarkedContainers(this.placementName);
        if (containers != null) {
            this.coordsList.addAll(containers);
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        int btnWidth = 200;
        int startX = (this.width - btnWidth) / 2;

        ButtonGeneric btnClear = new ButtonGeneric(startX, this.height - 30, btnWidth, 20, "§cOdznacz wszystkie skrzynie BML");
        this.addButton(btnClear, (btn, mb) -> {
            for (String rawCoord : new ArrayList<>(ContainerDataManager.getMarkedContainers(this.placementName))) {
                ContainerDataManager.setContainerMarked(this.placementName, rawCoord, false);
            }
            refreshList();
            this.initGui();
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                    Component.literal("§a[BML] Wszystkie skrzynie dla tego schematu zostaly odznaczone."));
            }
        });

        int startY = 55;
        for (String rawCoord : this.coordsList) {
            ButtonGeneric btnDel = new ButtonGeneric(startX - 20, startY - 2, 16, 16, "§c✖");
            this.addButton(btnDel, (btn, mb) -> {
                ContainerDataManager.setContainerMarked(this.placementName, rawCoord, false);
                refreshList();
                this.initGui();
            });
            startY += 12;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);

        int startX = (this.width - 200) / 2;
        int startY = 40;

        ctx.drawString(this.font, "§eSkrzynie z zaznaczonym BML:", startX, startY, 0xFFFFFFFF, false);
        startY += 15;

        if (this.coordsList.isEmpty()) {
            ctx.drawString(this.font, "§7Brak śledzonych skrzyń dla tego schematu.", startX, startY, 0xFFFFFFFF, false);
        } else {
            for (String coord : this.coordsList) {
                String display = coord;
                if (coord.contains(";")) {
                    String[] parts = coord.split(";");
                    if (parts.length >= 2) {
                        display = "§b" + parts[1] + " §8(" + parts[0].replace("minecraft:", "") + ")";
                    }
                }
                ctx.drawString(this.font, display, startX, startY, 0xFFFFFFFF, false);
                startY += 12;
            }
        }
    }
}
