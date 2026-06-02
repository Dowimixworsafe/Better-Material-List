package com.betterlist.gui;

import fi.dy.masa.litematica.materials.MaterialListEntry;

public class MaterialListEntryPair {
    private final MaterialListEntry left;
    private final MaterialListEntry right;

    public MaterialListEntryPair(MaterialListEntry left, MaterialListEntry right) {
        this.left = left;
        this.right = right;
    }

    public MaterialListEntry getLeft() {
        return left;
    }

    public MaterialListEntry getRight() {
        return right;
    }
}
