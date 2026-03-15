package com.example.gui;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import java.util.Collection;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class WidgetMaterialList extends WidgetListBase<MaterialListEntryPair, WidgetBetterMaterialListEntry> {
    private final GuiBetterMaterialList parent;

    public WidgetMaterialList(int x, int y, int width, int height, GuiBetterMaterialList parent) {
        super(x, y, width, height, (ISelectionListener) null);
        this.parent = parent;
        this.browserEntryHeight = 24;
        this.widgetSearchBar = null;
    }

    @Override
    protected WidgetBetterMaterialListEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, MaterialListEntryPair entry) {
        return new WidgetBetterMaterialListEntry(x, y, this.browserEntryWidth, this.browserEntryHeight, isOdd, entry, listIndex, this.parent);
    }

    @Override
    protected Collection<MaterialListEntryPair> getAllEntries() {
        return this.parent.getMaterialListPairs();
    }
}
