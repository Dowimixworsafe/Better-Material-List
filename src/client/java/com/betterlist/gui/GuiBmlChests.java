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

    // Manual scroll model: rows render in a fixed viewport [LIST_TOP, listBottom()]; the
    // window [scrollOffset, scrollOffset+visibleRows()) is what's drawn and gets buttons.
    // initGui() and extractRenderState() MUST derive geometry from the same helpers.
    private int scrollOffset = 0;
    private int listBottom()  { return this.height - 34; }                     // above the bottom bar
    private int visibleRows() { return Math.max(1, (listBottom() - LIST_TOP) / ROW_H); }
    private int maxScroll()   { return Math.max(0, this.coordsList.size() - visibleRows()); }
    private void clampScroll() { scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll())); }

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
                com.betterlist.util.BmlButtons.leftClick(InputHandler::openMaterialList));

        // Global highlight toggle (all chests at once).
        boolean anyHl = !ChestHighlightManager.all().isEmpty();
        this.addButton(new ButtonGeneric(left + PANEL_W - 170, HL_BTN_Y, 170, 20,
                anyHl ? "§a" + com.betterlist.util.BmlLang.tr("bml.chests.highlight_all_off")
                      : "§7" + com.betterlist.util.BmlLang.tr("bml.chests.highlight_all_on")),
                com.betterlist.util.BmlButtons.leftClick(() -> {
            ChestHighlightManager.toggleAll();
            this.initGui();
        }));

        int colActions = actionsX();
        clampScroll();
        int end = Math.min(this.coordsList.size(), scrollOffset + visibleRows());
        int startY = LIST_TOP;
        for (int i = scrollOffset; i < end; i++) {
            final String rawCoord = this.coordsList.get(i);
            // 🔍 preview
            this.addButton(new ButtonGeneric(colActions, startY, BTN_SIZE, BTN_SIZE, "🔍"),
                    com.betterlist.util.BmlButtons.leftClick(
                            () -> GuiBase.openGui(new GuiChestPreview(rawCoord, this.placementName))));

            // 💡 highlight (toggle)
            boolean hl = ChestHighlightManager.isHighlighted(rawCoord);
            this.addButton(new ButtonGeneric(colActions + (BTN_SIZE + 4), startY, BTN_SIZE, BTN_SIZE,
                    hl ? "§a💡" : "§7💡"), com.betterlist.util.BmlButtons.leftClick(() -> {
                ChestHighlightManager.toggle(rawCoord);
                this.initGui();
            }));

            // ✖ remove from tracking
            this.addButton(new ButtonGeneric(colActions + 2 * (BTN_SIZE + 4), startY, BTN_SIZE, BTN_SIZE, "§c✖"),
                    com.betterlist.util.BmlButtons.leftClick(() -> {
                ContainerDataManager.setContainerMarked(rawCoord, false);
                if (ChestHighlightManager.isHighlighted(rawCoord)) ChestHighlightManager.toggle(rawCoord);
                refreshList();
                this.initGui();
            }));

            startY += ROW_H;
        }

        // Bottom bar: unmark all.
        this.addButton(new ButtonGeneric(left, this.height - 30, PANEL_W, 20,
                "§c" + com.betterlist.util.BmlLang.tr("bml.chests.unmark_all")),
                com.betterlist.util.BmlButtons.leftClick(() -> {
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
        }));
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

        int end = Math.min(this.coordsList.size(), scrollOffset + visibleRows());
        int y = LIST_TOP;
        for (int idx = scrollOffset; idx < end; idx++) {
            String coord = this.coordsList.get(idx);
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
        }

        // Scroll affordance — show ▲ / ▼ when there's more above / below the viewport.
        if (scrollOffset > 0)
            ctx.drawString(this.font, "§7▲", left + PANEL_W / 2, LIST_TOP - 10, 0xFFFFFFFF, false);
        if (scrollOffset < maxScroll())
            ctx.drawString(this.font, "§7▼", left + PANEL_W / 2, listBottom() + 2, 0xFFFFFFFF, false);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll() > 0 && verticalAmount != 0) {
            scrollOffset += (verticalAmount > 0 ? -1 : 1);
            clampScroll();
            this.initGui();
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
