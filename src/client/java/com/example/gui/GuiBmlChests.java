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
        this.title = com.example.util.BmlLang.tr("bml.chests.title", 0);
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
        this.title = com.example.util.BmlLang.tr("bml.chests.title", this.coordsList.size());
    }

    /** Total number of items remembered for a given chest. */
    private static int itemCount(String containerId) {
        int sum = 0;
        for (int v : ContainerDataManager.getContainerContents(containerId).values()) sum += v;
        return sum;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = panelLeft();

        // Back arrow — top-left corner, consistent with other screens.
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e" + com.example.util.BmlLang.tr("bml.gui.back")),
                (btn, mb) -> InputHandler.openMaterialList());

        // Global highlight toggle (all chests at once).
        boolean anyHl = !ChestHighlightManager.all().isEmpty();
        this.addButton(new ButtonGeneric(left + PANEL_W - 150, 36, 150, 20,
                anyHl ? "§a" + com.example.util.BmlLang.tr("bml.chests.highlight_all_off")
                      : "§7" + com.example.util.BmlLang.tr("bml.chests.highlight_all_on")), (btn, mb) -> {
            ChestHighlightManager.toggleAll();
            this.initGui();
        });

        int btnSize = 18;
        int startY = LIST_TOP;
        for (String rawCoord : this.coordsList) {
            int colActions = left + PANEL_W - 3 * (btnSize + 4);

            // 🔍 preview
            this.addButton(new ButtonGeneric(colActions, startY, btnSize, btnSize, "🔍"),
                    (btn, mb) -> GuiBase.openGui(new GuiChestPreview(rawCoord, this.placementName)));

            // 💡 highlight (toggle)
            boolean hl = ChestHighlightManager.isHighlighted(rawCoord);
            this.addButton(new ButtonGeneric(colActions + (btnSize + 4), startY, btnSize, btnSize,
                    hl ? "§a💡" : "§7💡"), (btn, mb) -> {
                ChestHighlightManager.toggle(rawCoord);
                this.initGui();
            });

            // ✖ remove from tracking
            this.addButton(new ButtonGeneric(colActions + 2 * (btnSize + 4), startY, btnSize, btnSize, "§c✖"),
                    (btn, mb) -> {
                ContainerDataManager.setContainerMarked(rawCoord, false);
                if (ChestHighlightManager.isHighlighted(rawCoord)) ChestHighlightManager.toggle(rawCoord);
                refreshList();
                this.initGui();
            });

            startY += ROW_H;
        }

        // Bottom bar: unmark all.
        this.addButton(new ButtonGeneric(left, this.height - 30, PANEL_W, 20,
                "§c" + com.example.util.BmlLang.tr("bml.chests.unmark_all")), (btn, mb) -> {
            for (String rawCoord : new ArrayList<>(ContainerDataManager.getMarkedContainers())) {
                ContainerDataManager.setContainerMarked(rawCoord, false);
            }
            ChestHighlightManager.clear();
            refreshList();
            this.initGui();
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                    Component.literal("§a" + com.example.util.BmlLang.tr("bml.chests.all_unmarked")));
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);

        int left = panelLeft();

        // (the centered drawTitle renders the title)

        // Column headers.
        int headerY = LIST_TOP - 12;
        ctx.drawString(this.font, "§7" + com.example.util.BmlLang.tr("bml.chests.col_coords"), left + 4, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7" + com.example.util.BmlLang.tr("bml.chests.col_items"), left + 200, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7" + com.example.util.BmlLang.tr("bml.chests.col_actions"), left + PANEL_W - 3 * 22 - 2, headerY, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§8─────────────────────────────────────",
                left, headerY + 9, 0xFFFFFFFF, false);

        if (this.coordsList.isEmpty()) {
            ctx.drawString(this.font, "§7" + com.example.util.BmlLang.tr("bml.chests.empty"),
                    left, LIST_TOP + 4, 0xFFFFFFFF, false);
            return;
        }

        int y = LIST_TOP;
        int idx = 0;
        for (String coord : this.coordsList) {
            // Alternating row background for readability.
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
        // Centered title at the top — doesn't overlap the corner "Back" button.
        String t = this.getTitleString();
        int x = (this.getScreenWidth() - this.getStringWidth(t)) / 2;
        this.drawString(ctx, t, x, 8, -1);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // ESC returns to the material list instead of closing to the world.
        InputHandler.openMaterialList();
        return false;
    }
}
