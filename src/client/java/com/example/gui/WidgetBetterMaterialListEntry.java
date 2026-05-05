package com.example.gui;

import com.example.data.MaterialStateManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

@Environment(EnvType.CLIENT)
public class WidgetBetterMaterialListEntry extends WidgetListEntryBase<MaterialListEntryPair> {
    private final GuiBetterMaterialList parent;
    private ButtonGeneric leftCheckbox;
    private ButtonGeneric rightCheckbox;
    private String leftItemName;
    private String rightItemName;

    public WidgetBetterMaterialListEntry(int x, int y, int width, int height, boolean isOdd,
            MaterialListEntryPair entryPair, int listIndex,
            GuiBetterMaterialList parent) {
        super(x, y, width, height, entryPair, listIndex);
        this.parent = parent;

        int entryWidth = parent.getLayoutMode() == GuiBetterMaterialList.LayoutMode.SINGLE ? width : width / 2;

        setupLeftEntry(entryPair.getLeft(), x, y, entryWidth, height);
        if (entryPair.getRight() != null) {
            setupRightEntry(entryPair.getRight(), x + entryWidth, y, entryWidth, height);
        }
    }

    private void setupLeftEntry(MaterialListEntry entry, int x, int y, int width, int height) {
        if (entry == null) return;
        ItemStack stack = entry.getStack();
        this.leftItemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        boolean userChecked = MaterialStateManager.isChecked(parent.getPlacementName(), this.leftItemName);
        int actuallyMissing = Math.max(0, entry.getCountMissing() - entry.getCountAvailable());
        // Three-state glyph: green = user-checked, yellow = auto-fulfilled, red = missing
        String glyph = userChecked ? "§a✔" : (actuallyMissing <= 0 ? "§e✔" : "§c✖");

        int bx = x + width - BmlLayoutConstants.CHECKBOX_WIDTH - BmlLayoutConstants.CHECKBOX_MARGIN;
        int by = y + (height - BmlLayoutConstants.CHECKBOX_WIDTH) / 2;
        this.leftCheckbox = new ButtonGeneric(bx, by, BmlLayoutConstants.CHECKBOX_WIDTH, BmlLayoutConstants.CHECKBOX_WIDTH, glyph, new String[0]);
        this.addButton(this.leftCheckbox, new IButtonActionListener() {
            @Override
            public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
                boolean newState = !MaterialStateManager.isChecked(parent.getPlacementName(),
                        WidgetBetterMaterialListEntry.this.leftItemName);
                MaterialStateManager.setChecked(parent.getPlacementName(),
                        WidgetBetterMaterialListEntry.this.leftItemName, newState);
                WidgetBetterMaterialListEntry.this.leftCheckbox.setDisplayString(newState ? "§a✔" : "§c✖");
            }
        });
    }

    private void setupRightEntry(MaterialListEntry entry, int x, int y, int width, int height) {
        if (entry == null) return;
        ItemStack stack = entry.getStack();
        this.rightItemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        boolean userChecked = MaterialStateManager.isChecked(parent.getPlacementName(), this.rightItemName);
        int actuallyMissing = Math.max(0, entry.getCountMissing() - entry.getCountAvailable());
        String glyph = userChecked ? "§a✔" : (actuallyMissing <= 0 ? "§e✔" : "§c✖");

        int bx = x + width - BmlLayoutConstants.CHECKBOX_WIDTH - BmlLayoutConstants.CHECKBOX_MARGIN;
        int by = y + (height - BmlLayoutConstants.CHECKBOX_WIDTH) / 2;
        this.rightCheckbox = new ButtonGeneric(bx, by, BmlLayoutConstants.CHECKBOX_WIDTH, BmlLayoutConstants.CHECKBOX_WIDTH, glyph, new String[0]);
        this.addButton(this.rightCheckbox, new IButtonActionListener() {
            @Override
            public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
                boolean newState = !MaterialStateManager.isChecked(parent.getPlacementName(),
                        WidgetBetterMaterialListEntry.this.rightItemName);
                MaterialStateManager.setChecked(parent.getPlacementName(),
                        WidgetBetterMaterialListEntry.this.rightItemName, newState);
                WidgetBetterMaterialListEntry.this.rightCheckbox.setDisplayString(newState ? "§a✔" : "§c✖");
            }
        });
    }

    @Override
    public void render(GuiContext guiContext, int mouseX, int mouseY, boolean selected) {
        int bgColor = (this.listIndex % 2 == 0) ? 0x20FFFFFF : 0x30FFFFFF;
        guiContext.fill(this.x, this.y, this.x + this.width, this.y + this.height, bgColor);

        super.render(guiContext, mouseX, mouseY, selected);

        MaterialListEntryPair pair = (MaterialListEntryPair) this.entry;
        int entryWidth = parent.getLayoutMode() == GuiBetterMaterialList.LayoutMode.SINGLE ? this.width : this.width / 2;

        if (pair.getLeft() != null) {
            renderHalf(guiContext, pair.getLeft(), this.x, this.y, entryWidth, this.height);
        }

        if (parent.getLayoutMode() != GuiBetterMaterialList.LayoutMode.SINGLE) {
            guiContext.fill(this.x + entryWidth, this.y, this.x + entryWidth + 1, this.y + this.height, 0x40FFFFFF);
        }

        if (pair.getRight() != null) {
            renderHalf(guiContext, pair.getRight(), this.x + entryWidth + 1, this.y, entryWidth - 1, this.height);
        }
    }

    private void renderHalf(GuiContext guiContext, MaterialListEntry matEntry, int x, int y, int width, int height) {
        ItemStack stack = matEntry.getStack();
        Font font = Minecraft.getInstance().font;

        String name = stack.getHoverName().getString();
        int total = matEntry.getCountTotal();
        int missingInWorld = matEntry.getCountMissing();
        int available = matEntry.getCountAvailable();
        int actuallyMissing = Math.max(0, missingInWorld - available);
        int textY = y + (height - 8) / 2;

        int iconX = x + BmlLayoutConstants.ICON_PADDING;
        int iconY = y + (height - BmlLayoutConstants.ICON_SIZE) / 2;
        guiContext.renderItem(stack, iconX, iconY);
        guiContext.renderItemDecorations(font, stack, iconX, iconY);

        int checkboxEnd   = x + width - BmlLayoutConstants.CHECKBOX_MARGIN;
        int checkboxStart = checkboxEnd - BmlLayoutConstants.CHECKBOX_WIDTH;

        int missingEnd   = checkboxStart - BmlLayoutConstants.COLUMN_GAP;
        int missingStart = missingEnd - BmlLayoutConstants.MISSING_WIDTH;

        int availableEnd   = missingStart - BmlLayoutConstants.COLUMN_GAP;
        int availableStart = availableEnd - BmlLayoutConstants.AVAILABLE_WIDTH;

        int placedEnd   = availableStart - BmlLayoutConstants.COLUMN_GAP;
        int placedStart = placedEnd - BmlLayoutConstants.PLACED_WIDTH;

        int totalEnd   = placedStart - BmlLayoutConstants.COLUMN_GAP;
        int totalStart = totalEnd - BmlLayoutConstants.TOTAL_WIDTH;

        int nameX = x + BmlLayoutConstants.NAME_OFFSET_X;
        int maxNameWidth = totalStart - nameX - BmlLayoutConstants.COLUMN_GAP;

        String displayName = name;
        if (font.width(displayName) > maxNameWidth && maxNameWidth > 0) {
            while (font.width(displayName + "...") > maxNameWidth && displayName.length() > 1) {
                displayName = displayName.substring(0, displayName.length() - 1);
            }
            displayName = displayName + "...";
        }
        guiContext.drawString(font, displayName, nameX, textY, 0xFFFFFFFF, false);

        guiContext.drawString(font, String.valueOf(total), totalStart, textY, 0xFFAAAAAA, false);

        int placed = Math.max(0, total - missingInWorld);
        int colIconY = y + (height - 16) / 2;

        guiContext.renderItem(new ItemStack(Items.GRASS_BLOCK), placedStart, colIconY);
        guiContext.drawString(font, String.valueOf(placed), placedStart + 18, textY, 0xFFFFFFFF, false);

        guiContext.renderItem(new ItemStack(Items.CHEST), availableStart, colIconY);
        guiContext.drawString(font, String.valueOf(available), availableStart + 18, textY, 0xFFFFFFFF, false);

        int missColor = actuallyMissing > 0 ? 0xFFFF5555 : 0xFF55FF55;
        guiContext.drawString(font, String.valueOf(actuallyMissing), missingStart + 2, textY, missColor, false);
    }
}
