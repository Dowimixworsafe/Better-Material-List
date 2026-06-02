package com.betterlist.gui;

public final class BmlLayoutConstants {
    private BmlLayoutConstants() {}

    public static final int ICON_SIZE       = 16;
    public static final int ICON_PADDING    = 4;
    public static final int NAME_OFFSET_X   = ICON_PADDING + ICON_SIZE + 6; // = 26
    public static final int CHECKBOX_WIDTH  = 16;
    public static final int CHECKBOX_MARGIN = 6;
    public static final int COLUMN_GAP      = 5;
    public static final int MISSING_WIDTH   = 45;
    public static final int AVAILABLE_WIDTH = 45;
    public static final int PLACED_WIDTH    = 45;
    public static final int TOTAL_WIDTH           = 55;
    public static final int SINGLE_TOTAL_WIDTH    = 145; // fits "99999 = 1562 x 64 + 31"
    public static final int SINGLE_MODE_MAX_WIDTH = 680;
}
