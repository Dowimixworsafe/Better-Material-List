package com.betterlist.gui;

import com.betterlist.data.ChestHighlightManager;
import com.betterlist.data.ContainerDataManager;
import com.betterlist.input.InputHandler;
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

    // Layout. Vertical zones (top→bottom): title (y≈8) · highlight-all button (y=34) ·
    // column headers (HEADER_Y) · separator · rows (LIST_TOP).
    private static final int ROW_H = 22;
    private static final int HL_BTN_Y = 34;
    private static final int HEADER_Y = 62;
    private static final int LIST_TOP = 78;
    private static final int PANEL_W = 360;
    private static final int BTN_SIZE = 18;
    // Left edge of the 3-button action column (preview / highlight / remove).
    private int actionsX() { return panelLeft() + PANEL_W - 3 * (BTN_SIZE + 4); }

    public GuiBmlChests(String placementName) {
        this.placementName = placementName;
        this.title = com.betterlist.util.BmlLang.tr("bml.chests.title", 0);
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
        this.title = com.betterlist.util.BmlLang.tr("bml.chests.title", this.coordsList.size());
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
        this.addButton(new ButtonGeneric(6, 6, 40, 20, "§e" + com.betterlist.util.BmlLang.tr("bml.gui.back")),
                (btn, mb) -> InputHandler.openMaterialList());

        // Global highlight toggle (all chests at once).
        boolean anyHl = !ChestHighlightManager.all().isEmpty();
        this.addButton(new ButtonGeneric(left + PANEL_W - 170, HL_BTN_Y, 170, 20,
                anyHl ? "§a" + com.betterlist.util.BmlLang.tr("bml.chests.highlight_all_off")
                      : "§7" + com.betterlist.util.BmlLang.tr("bml.chests.highlight_all_on")), (btn, mb) -> {
            ChestHighlightManager.toggleAll();
            this.initGui();
        });

        int colActions = actionsX();
        int startY = LIST_TOP;
        for (String rawCoord : this.coordsList) {
            // 🔍 preview
            this.addButton(new ButtonGeneric(colActions, startY, BTN_SIZE, BTN_SIZE, "🔍"),
                    (btn, mb) -> GuiBase.openGui(new GuiChestPreview(rawCoord, this.placementName)));

            // 💡 highlight (toggle)
            boolean hl = ChestHighlightManager.isHighlighted(rawCoord);
            this.addButton(new ButtonGeneric(colActions + (BTN_SIZE + 4), startY, BTN_SIZE, BTN_SIZE,
                    hl ? "§a💡" : "§7💡"), (btn, mb) -> {
                ChestHighlightManager.toggle(rawCoord);
                this.initGui();
            });

            // ✖ remove from tracking
            this.addButton(new ButtonGeneric(colActions + 2 * (BTN_SIZE + 4), startY, BTN_SIZE, BTN_SIZE, "§c✖"),
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
                "§c" + com.betterlist.util.BmlLang.tr("bml.chests.unmark_all")), (btn, mb) -> {
            for (String rawCoord : new ArrayList<>(ContainerDataManager.getMarkedContainers())) {
                ContainerDataManager.setContainerMarked(rawCoord, false);
            }
            ChestHighlightManager.clear();
            refreshList();
            this.initGui();
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                    Component.literal("§a" + com.betterlist.util.BmlLang.tr("bml.chests.all_unmarked")));
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);

        int left = panelLeft();
        int colItems = left + 230;        // "Items" column x
        int colActions = actionsX();      // action buttons column x (headers align to it)

        // (the centered drawTitle renders the title)

        // Column headers + a separator line spanning exactly the panel width.
        ctx.drawString(this.font, "§7" + com.betterlist.util.BmlLang.tr("bml.chests.col_coords"), left + 4, HEADER_Y, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7" + com.betterlist.util.BmlLang.tr("bml.chests.col_items"), colItems, HEADER_Y, 0xFFFFFFFF, false);
        ctx.drawString(this.font, "§7" + com.betterlist.util.BmlLang.tr("bml.chests.col_actions"), colActions, HEADER_Y, 0xFFFFFFFF, false);
        ctx.fill(left, HEADER_Y + 11, left + PANEL_W, HEADER_Y + 12, 0x40FFFFFF);

        if (this.coordsList.isEmpty()) {
            ctx.drawString(this.font, "§7" + com.betterlist.util.BmlLang.tr("bml.chests.empty"),
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
            // Truncate coords so they never run into the Items column.
            display = trimToWidth(display, colItems - (left + 4) - 6);
            ctx.drawString(this.font, display, left + 4, y + 5, 0xFFFFFFFF, false);

            int count = itemCount(coord);
            String countStr = count > 0 ? "§f" + count : "§8—";
            ctx.drawString(this.font, countStr, colItems, y + 5, 0xFFFFFFFF, false);

            y += ROW_H;
            idx++;
        }
    }

    /** Trims a string (keeping color codes readable enough) so it fits within maxWidth px. */
    private String trimToWidth(String s, int maxWidth) {
        if (this.font.width(s) <= maxWidth) return s;
        while (s.length() > 1 && this.font.width(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
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
