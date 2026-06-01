package com.example.gui;

import com.example.data.ChestHighlightManager;
import com.example.data.ContainerDataManager;
import com.example.input.InputHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GuiBmlChests extends GuiBase {
    private final String placementName;
    private final List<String> coordsList = new ArrayList<>();

    // Layout
    private static final int ROW_H = 22;
    private static final int LIST_TOP = 66;
    private static final int PANEL_W = 360;

    public GuiBmlChests(String placementName) {
        this.placementName = placementName;
        this.title = "Lista Skrzyń BML";
        refreshList();
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    private void refreshList() {
        this.coordsList.clear();
        Set<String> containers = ContainerDataManager.getMarkedContainers();
        if (containers != null) {
            this.coordsList.addAll(containers);
            this.coordsList.sort(String::compareTo);
        }
        this.title = "Śledzone skrzynie (" + this.coordsList.size() + ")";
    }

    /** Łączna liczba przedmiotów zapamiętanych w danej skrzyni. */
    private static int itemCount(String containerId) {
        int sum = 0;
        for (int v : ContainerDataManager.getContainerContents(containerId).values()) sum += v;
        return sum;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = panelLeft();

        // Strzałka wstecz — lewy górny róg, spójnie z innymi ekranami.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e← Wróć"),
                (btn, mb) -> InputHandler.openMaterialList());

        // Globalny przełącznik podświetleń (wszystkie skrzynie naraz).
        boolean anyHl = !ChestHighlightManager.all().isEmpty();
        this.addButton(new ButtonGeneric(left + PANEL_W - 150, 36, 150, 20,
                anyHl ? "§a💡 Zgaś wszystkie" : "§7💡 Podświetl wszystkie"), (btn, mb) -> {
            ChestHighlightManager.toggleAll();
            this.initGui();
        });

        int btnSize = 18;
        int startY = LIST_TOP;
        for (String rawCoord : this.coordsList) {
            int colActions = left + PANEL_W - 3 * (btnSize + 4);

            // 🔍 podgląd
            this.addButton(new ButtonGeneric(colActions, startY, btnSize, btnSize, "🔍"),
                    (btn, mb) -> GuiBase.openGui(new GuiChestPreview(rawCoord, this.placementName)));

            // 💡 podświetlenie (toggle)
            boolean hl = ChestHighlightManager.isHighlighted(rawCoord);
            this.addButton(new ButtonGeneric(colActions + (btnSize + 4), startY, btnSize, btnSize,
                    hl ? "§a💡" : "§7💡"), (btn, mb) -> {
                ChestHighlightManager.toggle(rawCoord);
                this.initGui();
            });

            // ✖ usuń ze śledzenia
            this.addButton(new ButtonGeneric(colActions + 2 * (btnSize + 4), startY, btnSize, btnSize, "§c✖"),
                    (btn, mb) -> {
                ContainerDataManager.setContainerMarked(rawCoord, false);
                if (ChestHighlightManager.isHighlighted(rawCoord)) ChestHighlightManager.toggle(rawCoord);
                refreshList();
                this.initGui();
            });

            startY += ROW_H;
        }

        // Dolny pasek: odznacz wszystkie.
        this.addButton(new ButtonGeneric(left, this.height - 30, PANEL_W, 20,
                "§cOdznacz wszystkie skrzynie"), (btn, mb) -> {
            for (String rawCoord : new ArrayList<>(ContainerDataManager.getMarkedContainers())) {
                ContainerDataManager.setContainerMarked(rawCoord, false);
            }
            ChestHighlightManager.clear();
            refreshList();
            this.initGui();
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                    Component.literal("§a[BML] Wszystkie skrzynie zostały odznaczone."));
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);

        int left = panelLeft();

        // (tytuł rysuje wyśrodkowany drawTitle)

        // Nagłówki kolumn.
        int headerY = LIST_TOP - 12;
        ctx.drawString(this.font, "§7Koordynaty", left + 4, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7Itemy",      left + 200, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7Akcje",      left + PANEL_W - 3 * 22 - 2, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§8─────────────────────────────────────",
                left, headerY + 9, 0xFFFFFFFF, false);

        if (this.coordsList.isEmpty()) {
            ctx.drawString(this.font, "§7Brak śledzonych skrzyń. Oznacz skrzynię przyciskiem przy jej otwarciu.",
                    left, LIST_TOP + 4, 0xFFFFFFFF, false);
            return;
        }

        int y = LIST_TOP;
        int idx = 0;
        for (String coord : this.coordsList) {
            // Tło co drugi wiersz dla czytelności.
            if ((idx & 1) == 0)
                ctx.fill(left, y - 2, left + PANEL_W, y + ROW_H - 4, 0x18FFFFFF);

            String display = coord;
            if (coord.contains(";")) {
                String[] parts = coord.split(";");
                if (parts.length >= 2)
                    display = "§b" + parts[1].trim() + " §8(" + parts[0].replace("minecraft:", "") + ")";
            }
            ctx.drawString(this.font, display, left + 4, y + 5, 0xFFFFFFFF, false);

            int count = itemCount(coord);
            String countStr = count > 0 ? "§f" + count : "§8—";
            ctx.drawString(this.font, countStr, left + 200, y + 5, 0xFFFFFFFF, false);

            y += ROW_H;
            idx++;
        }
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // Tytuł wyśrodkowany u góry — nie nachodzi na przycisk "Wróć" w rogu.
        String t = this.getTitleString();
        int x = (this.getScreenWidth() - this.getStringWidth(t)) / 2;
        this.drawString(ctx, t, x, 8, -1);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // ESC wraca do listy materiałów zamiast zamykać do świata.
        InputHandler.openMaterialList();
        return false;
    }
}
