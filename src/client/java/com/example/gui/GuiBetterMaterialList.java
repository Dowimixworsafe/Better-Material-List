package com.example.gui;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import com.example.input.InputHandler;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class GuiBetterMaterialList
        extends GuiListBase<MaterialListEntryPair, WidgetBetterMaterialListEntry, WidgetMaterialList>
        implements ICompletionListener {
    private final String placementName;
    private List<MaterialListEntry> materialList;
    private final boolean isCached;
    private final List<SchematicPlacement> placements;
    private static boolean isFirstLoad = true;
    private EditBox searchField;
    private static boolean globalHideFullyPlaced = false;
    private static boolean globalHideFullyStored = false;
    private static boolean globalHideChecked = false;
    private static String globalSearchText = "";
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean wasLeftMouseDown = false;
    private ButtonGeneric btnPlacedCheck;
    private ButtonGeneric btnStoredCheck;
    public enum LayoutMode {
        TWO_HORIZONTAL, TWO_VERTICAL, SINGLE
    }

    private static LayoutMode globalLayoutMode = LayoutMode.TWO_HORIZONTAL;

    public enum SortMode {
        BLOCK, REQUIRED, PLACED, STORED, MISSING, CHECKED
    }

    private static SortMode currentSortMode = SortMode.REQUIRED;
    private static boolean sortDescending = true;

    public GuiBetterMaterialList(String placementName, List<MaterialListEntry> materialList, boolean isCached,
            List<SchematicPlacement> placements) {
        super(10, 30);
        this.placementName = placementName;
        this.materialList = materialList;
        this.isCached = isCached;
        this.placements = placements;
    }

    // Overload for backwards compatibility
    public GuiBetterMaterialList(String placementName, List<MaterialListEntry> materialList) {
        this(placementName, materialList, false, null);
    }

    public List<MaterialListEntryPair> getMaterialListPairs() {
        List<MaterialListEntryPair> pairs = new java.util.ArrayList<>();
        if (this.materialList == null)
            return pairs;

        List<MaterialListEntry> filtered = new java.util.ArrayList<>();
        for (MaterialListEntry entry : this.materialList) {
            int total = entry.getCountTotal();
            int missing = entry.getCountMissing();
            int available = entry.getCountAvailable();
            int actuallyMissing = Math.max(0, missing - available);
            int placed = Math.max(0, total - missing);

            if (globalHideFullyPlaced && placed >= total && total > 0)
                continue;
            if (globalHideFullyStored && available >= missing && missing > 0)
                continue;
            if (globalHideChecked) {
                String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(entry.getStack().getItem()).toString();
                boolean checked = com.example.data.MaterialStateManager.isChecked(this.placementName, itemName);
                if (checked || (actuallyMissing <= 0 && total > 0)) {
                    continue;
                }
            }

            if (!globalSearchText.isEmpty()) {
                String name = entry.getStack().getHoverName().getString().toLowerCase();
                if (!name.contains(globalSearchText.toLowerCase()))
                    continue;
            }
            filtered.add(entry);
        }

        filtered.sort((e1, e2) -> {
            int result = 0;
            switch (currentSortMode) {
                case REQUIRED:
                    result = Integer.compare(e1.getCountTotal(), e2.getCountTotal());
                    break;
                case PLACED:
                    int placed1 = Math.max(0, e1.getCountTotal() - e1.getCountMissing());
                    int placed2 = Math.max(0, e2.getCountTotal() - e2.getCountMissing());
                    result = Integer.compare(placed1, placed2);
                    break;
                case STORED:
                    result = Integer.compare(e1.getCountAvailable(), e2.getCountAvailable());
                    break;
                case MISSING:
                    int actuallyMissing1 = Math.max(0, e1.getCountMissing() - e1.getCountAvailable());
                    int actuallyMissing2 = Math.max(0, e2.getCountMissing() - e2.getCountAvailable());
                    result = Integer.compare(actuallyMissing1, actuallyMissing2);
                    break;
                case CHECKED:
                    String name1 = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e1.getStack().getItem())
                            .toString();
                    String name2 = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e2.getStack().getItem())
                            .toString();
                    boolean c1 = com.example.data.MaterialStateManager.isChecked(this.placementName, name1);
                    boolean c2 = com.example.data.MaterialStateManager.isChecked(this.placementName, name2);
                    result = Boolean.compare(c1, c2);
                    break;
                case BLOCK:
                default:
                    String disp1 = e1.getStack().getHoverName().getString();
                    String disp2 = e2.getStack().getHoverName().getString();
                    result = disp1.compareToIgnoreCase(disp2);
                    break;
            }
            return sortDescending ? -result : result;
        });

        // --- ŁĄCZENIE W PARY (LAYOUT) ---
        if (globalLayoutMode == LayoutMode.SINGLE) {
            for (MaterialListEntry entry : filtered) {
                pairs.add(new MaterialListEntryPair(entry, null));
            }
        } else if (globalLayoutMode == LayoutMode.TWO_VERTICAL) {
            int half = (filtered.size() + 1) / 2;
            for (int i = 0; i < half; i++) {
                MaterialListEntry left = filtered.get(i);
                MaterialListEntry right = (i + half < filtered.size()) ? filtered.get(i + half) : null;
                pairs.add(new MaterialListEntryPair(left, right));
            }
        } else {
            // TWO_HORIZONTAL
            for (int i = 0; i < filtered.size(); i += 2) {
                MaterialListEntry left = filtered.get(i);
                MaterialListEntry right = (i + 1 < filtered.size()) ? filtered.get(i + 1) : null;
                pairs.add(new MaterialListEntryPair(left, right));
            }
        }

        return pairs;
    }

    public LayoutMode getLayoutMode() {
        return globalLayoutMode;
    }

    @Override
    public void initGui() {
        super.initGui();
        int guiWidth = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        guiWidth = Math.min(guiWidth, this.getScreenWidth() - 20);
        int startX = (this.getScreenWidth() - guiWidth) / 2;
        int bottomY = this.getScreenHeight() - 26;

        boolean twoRows = this.getScreenWidth() < 680;
        int row1Y = twoRows ? bottomY - 24 : bottomY;
        int row2Y = bottomY;

        if (this.searchField == null) {
            this.searchField = new EditBox(this.font, startX, row1Y, 120, 20, Component.literal("Szukaj Itemu"));
            this.searchField.setResponder(text -> {
                globalSearchText = text;
                if (this.getListWidget() != null) {
                    this.getListWidget().refreshEntries();
                }
            });
            this.searchField.setValue(globalSearchText);
        } else {
            this.searchField.setX(startX);
            this.searchField.setY(row1Y);
        }
        if (!this.children().contains(this.searchField)) {
            this.addRenderableWidget(this.searchField);
        }

        ButtonGeneric btnRefresh = new ButtonGeneric(startX + 130, row1Y, 20, 20, "⟳");
        this.addButton(btnRefresh, (button, mouseButton) -> {
            if (this.placements != null) {
                for (SchematicPlacement p : this.placements) {
                    if (p.isEnabled() && p.getMaterialList() != null) {
                        p.getMaterialList().setCompletionListener(this);
                        p.getMaterialList().reCreateMaterialList();
                    }
                }
            }
        });

        int btnConfigX = twoRows ? startX + 210 : startX + 160;
        ButtonGeneric btnConfig = new ButtonGeneric(startX + guiWidth - 125, 12, 60, 20, "§eUstawienia");
        this.addButton(btnConfig, (button, mouseButton) -> {
            fi.dy.masa.malilib.gui.GuiBase.openGui(new com.example.gui.GuiConfigs());
        });

        int btnPlacedX = twoRows ? startX : startX + 230;
        this.btnPlacedCheck = new ButtonGeneric(btnPlacedX, row2Y, 60, 20,
                globalHideFullyPlaced ? "    §a[ON]" : "    §c[OFF]");
        this.addButton(btnPlacedCheck, (button, mouseButton) -> {
            globalHideFullyPlaced = !globalHideFullyPlaced;
            btnPlacedCheck.setDisplayString(globalHideFullyPlaced ? "    §a[ON]" : "    §c[OFF]");
            if (this.getListWidget() != null)
                this.getListWidget().refreshEntries();
        });

        int btnStoredX = twoRows ? startX + 40 : startX + 300;
        this.btnStoredCheck = new ButtonGeneric(btnStoredX, row2Y, 60, 20,
                globalHideFullyStored ? "    §a[ON]" : "    §c[OFF]");
        this.addButton(btnStoredCheck, (button, mouseButton) -> {
            globalHideFullyStored = !globalHideFullyStored;
            btnStoredCheck.setDisplayString(globalHideFullyStored ? "    §a[ON]" : "    §c[OFF]");
            if (this.getListWidget() != null)
                this.getListWidget().refreshEntries();
        });

        int btnCheckedX = twoRows ? startX + 40 : startX + 370;
        ButtonGeneric btnChecked = new ButtonGeneric(btnCheckedX, row2Y, 60, 20,
                globalHideChecked ? "§b✔ §a[ON]" : "§b✔ §c[OFF]");
        this.addButton(btnChecked, (button, mouseButton) -> {
            globalHideChecked = !globalHideChecked;
            btnChecked.setDisplayString(globalHideChecked ? "§b✔ §a[ON]" : "§b✔ §c[OFF]");
            if (this.getListWidget() != null)
                this.getListWidget().refreshEntries();
        });
        this.addButton(btnConfig, (button, mouseButton) -> {
            // Otwiera nasze nowe GUI konfiguracyjne
            fi.dy.masa.malilib.gui.GuiBase.openGui(new com.example.gui.GuiConfigs());
        });

        // --- PRZYCISK: Wyczyść Cache ---
        // Umieszczamy go w prawym górnym rogu GUI (Y = 12)
        ButtonGeneric btnClearCache = new ButtonGeneric(startX + guiWidth - 60, 12, 60, 20, "§c🗑 Cache");
        this.addButton(btnClearCache, (button, mouseButton) -> {
            // 1. Czyścimy cache z przedmiotami schematu
            if (this.placements != null) {
                String cacheKey = com.example.data.MaterialCacheManager.getCacheKey(this.placements);
                com.example.data.MaterialCacheManager.clearCache(cacheKey);
            }

            // 2. Czyścimy zapisane skrzynie dla tego schematu
            com.example.data.ContainerDataManager.clearPlacement(this.placementName);

            // 3. Informujemy gracza
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§a[BML] Cache wyczyszczony! Użyj przycisku 'Odśwież', aby przeliczyć na nowo."),
                        false);
            }
        });

        // --- PRZYCISK: Skrzynie BML ---
        ButtonGeneric btnChests = new ButtonGeneric(btnConfigX, row1Y, 60, 20, "§bSkrzynie");
        this.addButton(btnChests, (button, mouseButton) -> {
            fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiBmlChests(this.placementName));
        });
        int layoutBtnX = startX + guiWidth - 55 - 5 - 120;

        String layoutText = globalLayoutMode == LayoutMode.SINGLE ? "Układ: 🔲" :
                (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "Układ: ⬇" : "Układ: ➡");
        ButtonGeneric btnLayout = new ButtonGeneric(layoutBtnX, 12, 50, 20, layoutText);

        this.addButton(btnLayout, (button, mouseButton) -> {
            if (globalLayoutMode == LayoutMode.TWO_HORIZONTAL) {
                globalLayoutMode = LayoutMode.TWO_VERTICAL;
            } else if (globalLayoutMode == LayoutMode.TWO_VERTICAL) {
                globalLayoutMode = LayoutMode.SINGLE;
            } else {
                globalLayoutMode = LayoutMode.TWO_HORIZONTAL;
            }

            String newLayoutText = globalLayoutMode == LayoutMode.SINGLE ? "Układ: 🔲" :
                    (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "Układ: ⬇" : "Układ: ➡");
            btnLayout.setDisplayString(newLayoutText);

            if (this.getListWidget() != null) {
                this.getListWidget().refreshEntries();
            }
        });

        String partyText = com.example.network.BmlClientNetworking.serverSupported ? 
            (com.example.party.PartyManager.isInParty() ? "§a👥 Party [ON]" : "§e👥 Party") : "§7👥 Brak Serwera";
        ButtonGeneric btnParty = new ButtonGeneric(startX, 12, 80, 20, partyText);
        this.addButton(btnParty, (button, mouseButton) -> {
            fi.dy.masa.malilib.gui.GuiBase.openGui(new com.example.gui.GuiParty(this.placementName));
        });
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (this.searchField != null && this.searchField.isFocused()) {
            this.searchField.setFocused(false);
            return false; // Nie zamykaj okna
        }
        return super.shouldCloseOnEsc();
    }

    @Override
    protected WidgetMaterialList createListWidget(int listX, int listY) {
        // Dynamic sizing: at least 600px wide for 2 columns, up to 90% of screen
        int guiWidth = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        guiWidth = Math.min(guiWidth, this.getScreenWidth() - 20);

        int guiHeight = this.getScreenHeight() - 80;
        if (this.getScreenWidth() < 680) {
            guiHeight -= 24;
        }

        int startX = (this.getScreenWidth() - guiWidth) / 2;
        int startY = 50;
        return new WidgetMaterialList(startX, startY, guiWidth, guiHeight, this);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.render(drawContext, mouseX, mouseY, partialTicks);

        int guiWidth = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        guiWidth = Math.min(guiWidth, this.getScreenWidth() - 20);
        int halfWidth = guiWidth / 2;
        int startX = (this.getScreenWidth() - guiWidth) / 2;
        int startY = 38; // Above the list widget

        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;

        // Let's use the same width constants from the entry widget to align headers
        int NAME_OFFSET_X = 4 + 16 + 6;
        int CHECKBOX_WIDTH = 16;
        int CHECKBOX_MARGIN = 6;
        int COLUMN_GAP = 5;
        int MISSING_WIDTH = 45;
        int AVAILABLE_WIDTH = 45;
        int PLACED_WIDTH = 45;
        int TOTAL_WIDTH = 55;

        int loopCount = globalLayoutMode == LayoutMode.SINGLE ? 1 : 2;
        for (int i = 0; i < loopCount; i++) {
            int x = startX + (i * halfWidth);
            int width = globalLayoutMode == LayoutMode.SINGLE ? guiWidth : halfWidth;
            if (i == 1) { // right side offset
                x += 1;
                width -= 1;
            }

            int checkboxEnd = x + width - CHECKBOX_MARGIN;
            int checkboxStart = checkboxEnd - CHECKBOX_WIDTH;
            int missingEnd = checkboxStart - COLUMN_GAP;
            int missingStart = missingEnd - MISSING_WIDTH;
            int availableEnd = missingStart - COLUMN_GAP;
            int availableStart = availableEnd - AVAILABLE_WIDTH;
            int placedEnd = availableStart - COLUMN_GAP;
            int placedStart = placedEnd - PLACED_WIDTH;
            int totalEnd = placedStart - COLUMN_GAP;
            int totalStart = totalEnd - TOTAL_WIDTH;

            String arrow = sortDescending ? " ▼" : " ▲";

            String blockStr = "Block" + (currentSortMode == SortMode.BLOCK ? arrow : "");
            String reqStr = "Required" + (currentSortMode == SortMode.REQUIRED ? arrow : "");
            String placedStr = "Placed" + (currentSortMode == SortMode.PLACED ? arrow : "");
            String storedStr = "Stored" + (currentSortMode == SortMode.STORED ? arrow : "");
            String missingStr = "Missing" + (currentSortMode == SortMode.MISSING ? arrow : "");

            drawContext.drawString(font, blockStr, x + NAME_OFFSET_X, startY,
                    currentSortMode == SortMode.BLOCK ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            drawContext.drawString(font, reqStr, totalStart + 2, startY,
                    currentSortMode == SortMode.REQUIRED ? 0xFFFFAA00 : 0xFFAAAAAA, false);
            drawContext.drawString(font, placedStr, placedStart + 2, startY,
                    currentSortMode == SortMode.PLACED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            drawContext.drawString(font, storedStr, availableStart + 2, startY,
                    currentSortMode == SortMode.STORED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            drawContext.drawString(font, missingStr, missingStart + 2, startY,
                    currentSortMode == SortMode.MISSING ? 0xFFFFAA00 : 0xFFFFFFFF, false);
        }

        if (this.materialList == null || this.materialList.isEmpty()) {
            String text = "Nie wybrano schematu Litematiki";
            int textWidth = font.width(text);
            drawContext.drawString(font, text, startX + (guiWidth - textWidth) / 2, startY + 50, 0xFFFF5555, false);
        }

        if (this.searchField != null) {
            this.searchField.render(drawContext, mouseX, mouseY, partialTicks);
        }

        if (this.btnPlacedCheck != null) {
            drawContext.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GRASS_BLOCK), this.btnPlacedCheck.getX() + 4, this.btnPlacedCheck.getY() + 2);
        }
        if (this.btnStoredCheck != null) {
            drawContext.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST), this.btnStoredCheck.getX() + 4, this.btnStoredCheck.getY() + 2);
        }
    }

    @Override
    protected int getBrowserWidth() {
        int guiWidth = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        return Math.min(guiWidth, this.getScreenWidth() - 20);
    }

    @Override
    protected int getBrowserHeight() {
        int height = this.getScreenHeight() - 80;
        if (this.getScreenWidth() < 680) {
            height -= 24;
        }
        return height;
    }

    public String getPlacementName() {
        return this.placementName;
    }

    private boolean wasRDown = false;

    @Override
    public void tick() {
        super.tick();

        long window = net.minecraft.client.Minecraft.getInstance().getWindow().handle();

        boolean isMouseLeftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        if (isMouseLeftDown && !this.wasLeftMouseDown) {
            handleHeaderClick(this.lastMouseX, this.lastMouseY);
        }
        this.wasLeftMouseDown = isMouseLeftDown;

        if (isMouseLeftDown && this.searchField != null) {
            boolean isInside = this.lastMouseX >= this.searchField.getX()
                    && this.lastMouseX < (this.searchField.getX() + this.searchField.getWidth()) &&
                    this.lastMouseY >= this.searchField.getY()
                    && this.lastMouseY < (this.searchField.getY() + this.searchField.getHeight());
            if (isInside && !this.searchField.isFocused()) {
                this.searchField.setFocused(true);
            } else if (!isInside && this.searchField.isFocused()) {
                this.searchField.setFocused(false);
            }
        }

        if (this.searchField != null && this.searchField.isFocused()) {
            return;
        }

        if (GuiBetterMaterialList.isFirstLoad && !this.isCached) {
            if (this.placements != null) {
                for (SchematicPlacement p : this.placements) {
                    if (p.isEnabled() && p.getMaterialList() != null) {
                        p.getMaterialList().setCompletionListener(this);
                        p.getMaterialList().reCreateMaterialList();
                    }
                }
            }
            GuiBetterMaterialList.isFirstLoad = false;
        }
    }

    @Override
    public void onTaskCompleted() {
        if (this.placements != null) {
            this.materialList = InputHandler.collectMaterialsFromPlacements(this.placements);
            if (Minecraft.getInstance().player != null) {
                fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(this.materialList,
                        Minecraft.getInstance().player);
                InputHandler.addCachedContainerItems(this.materialList, this.placementName);
            }
            this.initGui();
        }
    }

    private void handleHeaderClick(double mouseX, double mouseY) {
        int guiWidth = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        guiWidth = Math.min(guiWidth, this.getScreenWidth() - 20);
        int halfWidth = guiWidth / 2;
        int startX = (this.getScreenWidth() - guiWidth) / 2;
        int startY = 38;

        if (mouseY >= startY - 4 && mouseY <= startY + 12) {
            int loopCount = globalLayoutMode == LayoutMode.SINGLE ? 1 : 2;
            for (int i = 0; i < loopCount; i++) {
                int x = startX + (i * halfWidth);
                int width = globalLayoutMode == LayoutMode.SINGLE ? guiWidth : halfWidth;
                if (i == 1) {
                    x += 1;
                    width -= 1;
                }

                int CHECKBOX_WIDTH = 16;
                int CHECKBOX_MARGIN = 6;
                int COLUMN_GAP = 5;
                int MISSING_WIDTH = 45;
                int AVAILABLE_WIDTH = 45;
                int PLACED_WIDTH = 45;
                int TOTAL_WIDTH = 55;
                int NAME_OFFSET_X = 4 + 16 + 6;

                int checkboxEnd = x + width - CHECKBOX_MARGIN;
                int checkboxStart = checkboxEnd - CHECKBOX_WIDTH;
                int missingEnd = checkboxStart - COLUMN_GAP;
                int missingStart = missingEnd - MISSING_WIDTH;
                int availableEnd = missingStart - COLUMN_GAP;
                int availableStart = availableEnd - AVAILABLE_WIDTH;
                int placedEnd = availableStart - COLUMN_GAP;
                int placedStart = placedEnd - PLACED_WIDTH;
                int totalEnd = placedStart - COLUMN_GAP;
                int totalStart = totalEnd - TOTAL_WIDTH;

                if (mouseX >= x + NAME_OFFSET_X && mouseX < totalStart) {
                    setSortMode(SortMode.BLOCK);
                    return;
                } else if (mouseX >= totalStart && mouseX < placedStart) {
                    setSortMode(SortMode.REQUIRED);
                    return;
                } else if (mouseX >= placedStart && mouseX < availableStart) {
                    setSortMode(SortMode.PLACED);
                    return;
                } else if (mouseX >= availableStart && mouseX < missingStart) {
                    setSortMode(SortMode.STORED);
                    return;
                } else if (mouseX >= missingStart && mouseX < checkboxStart) {
                    setSortMode(SortMode.MISSING);
                    return;
                } else if (mouseX >= checkboxStart && mouseX <= checkboxEnd) {
                    setSortMode(SortMode.CHECKED);
                    return;
                }
            }
        }
    }

    private void setSortMode(SortMode mode) {
        if (currentSortMode == mode) {
            sortDescending = !sortDescending;
        } else {
            currentSortMode = mode;
            sortDescending = mode != SortMode.BLOCK;
        }
        if (this.getListWidget() != null) {
            this.getListWidget().refreshEntries();
        }
    }

    public boolean isSearchFocused() {
        return this.searchField != null && this.searchField.isFocused();
    }
}
