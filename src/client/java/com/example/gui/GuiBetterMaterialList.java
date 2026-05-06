package com.example.gui;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import com.example.input.InputHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import fi.dy.masa.malilib.render.GuiContext;

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
    private static boolean globalAutoRefresh = true;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean wasLeftMouseDown = false;
    private ButtonGeneric btnPlacedCheck;
    private ButtonGeneric btnStoredCheck;
    private ButtonGeneric btnAutoRefresh;
    private ButtonGeneric btnChests;
    private ButtonGeneric btnSchematics;
    private fi.dy.masa.litematica.materials.MaterialListEntry hoveredEntry;
    private int hoveredMouseX, hoveredMouseY;
    private boolean wasRightMouseDown = false;
    private ButtonGeneric btnFocusMaster;
    private ButtonGeneric btnPlayers;
    private boolean showPlayerDropdown = false;

    private record FaceRenderRequest(String nick, int x, int y, int size) {}
    private final java.util.List<FaceRenderRequest> faceRenderRequests = new java.util.ArrayList<>();

    public void addFaceRenderRequest(String nick, int x, int y, int size) {
        faceRenderRequests.add(new FaceRenderRequest(nick, x, y, size));
    }

    // Auto-refresh: silently re-reads from Litematica without scheduling a task (no notification)
    private int autoRefreshTick = 0;
    private static final int AUTO_REFRESH_INTERVAL = 200; // ~10s at 20 TPS

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

    public GuiBetterMaterialList(String placementName, List<MaterialListEntry> materialList) {
        this(placementName, materialList, false, null);
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    public List<MaterialListEntryPair> getMaterialListPairs() {
        List<MaterialListEntryPair> pairs = new java.util.ArrayList<>();
        if (this.materialList == null) return pairs;

        List<MaterialListEntry> filtered = new java.util.ArrayList<>();
        for (MaterialListEntry entry : this.materialList) {
            int total     = entry.getCountTotal();
            int missing   = entry.getCountMissing();
            int available = entry.getCountAvailable();
            int actuallyMissing = Math.max(0, missing - available);
            int placed    = Math.max(0, total - missing);

            if (globalHideFullyPlaced && placed >= total && total > 0) continue;
            if (globalHideFullyStored && available >= missing && missing > 0) continue;
            if (globalHideChecked) {
                String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(entry.getStack().getItem()).toString();
                boolean checked = com.example.data.MaterialStateManager.isChecked(this.placementName, itemName);
                if (checked || (actuallyMissing <= 0 && total > 0)) continue;
            }
            if (!globalSearchText.isEmpty()) {
                String name = entry.getStack().getHoverName().getString().toLowerCase();
                if (!name.contains(globalSearchText.toLowerCase())) continue;
            }
            filtered.add(entry);
        }

        filtered.sort((e1, e2) -> {
            int result = 0;
            switch (currentSortMode) {
                case REQUIRED -> result = Integer.compare(e1.getCountTotal(), e2.getCountTotal());
                case PLACED -> {
                    int p1 = Math.max(0, e1.getCountTotal() - e1.getCountMissing());
                    int p2 = Math.max(0, e2.getCountTotal() - e2.getCountMissing());
                    result = Integer.compare(p1, p2);
                }
                case STORED  -> result = Integer.compare(e1.getCountAvailable(), e2.getCountAvailable());
                case MISSING -> {
                    int m1 = Math.max(0, e1.getCountMissing() - e1.getCountAvailable());
                    int m2 = Math.max(0, e2.getCountMissing() - e2.getCountAvailable());
                    result = Integer.compare(m1, m2);
                }
                case CHECKED -> {
                    String n1 = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e1.getStack().getItem()).toString();
                    String n2 = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e2.getStack().getItem()).toString();
                    boolean c1 = com.example.data.MaterialStateManager.isChecked(this.placementName, n1);
                    boolean c2 = com.example.data.MaterialStateManager.isChecked(this.placementName, n2);
                    result = Boolean.compare(c1, c2);
                }
                default -> result = e1.getStack().getHoverName().getString()
                        .compareToIgnoreCase(e2.getStack().getHoverName().getString());
            }
            return sortDescending ? -result : result;
        });

        if (globalLayoutMode == LayoutMode.SINGLE) {
            for (MaterialListEntry e : filtered) pairs.add(new MaterialListEntryPair(e, null));
        } else if (globalLayoutMode == LayoutMode.TWO_VERTICAL) {
            int half = (filtered.size() + 1) / 2;
            for (int i = 0; i < half; i++) {
                MaterialListEntry right = (i + half < filtered.size()) ? filtered.get(i + half) : null;
                pairs.add(new MaterialListEntryPair(filtered.get(i), right));
            }
        } else {
            for (int i = 0; i < filtered.size(); i += 2) {
                MaterialListEntry right = (i + 1 < filtered.size()) ? filtered.get(i + 1) : null;
                pairs.add(new MaterialListEntryPair(filtered.get(i), right));
            }
        }
        return pairs;
    }

    public LayoutMode getLayoutMode() { return globalLayoutMode; }

    // ── GUI init ──────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();

        int guiWidth = getEffectiveListWidth(getRawGuiWidth());
        int startX   = (this.getScreenWidth() - guiWidth) / 2;

        // Top bar: Party | Layout | Auto | Refresh | Settings | Clear Cache
        String partyText = com.example.network.BmlClientNetworking.serverSupported
                ? (com.example.party.PartyManager.isInParty() ? "§a👥 Party" : "§e👥 Party")
                : "§7👥 No Server";
        this.addButton(new ButtonGeneric(startX, 6, 78, 20, partyText),
                (b, mb) -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiParty(this.placementName)));

        String layoutIcon = globalLayoutMode == LayoutMode.SINGLE ? "1-Col"
                : (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "2-Vert" : "2-Horiz");
        ButtonGeneric btnLayout = new ButtonGeneric(startX + 82, 6, 54, 20, layoutIcon);
        this.addButton(btnLayout, (b, mb) -> {
            if      (globalLayoutMode == LayoutMode.TWO_HORIZONTAL) globalLayoutMode = LayoutMode.TWO_VERTICAL;
            else if (globalLayoutMode == LayoutMode.TWO_VERTICAL)   globalLayoutMode = LayoutMode.SINGLE;
            else                                                      globalLayoutMode = LayoutMode.TWO_HORIZONTAL;
            String icon = globalLayoutMode == LayoutMode.SINGLE ? "1-Col"
                    : (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "2-Vert" : "2-Horiz");
            btnLayout.setDisplayString(icon);
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        });

        // Auto-refresh toggle
        this.btnAutoRefresh = new ButtonGeneric(startX + 140, 6, 60, 20,
                globalAutoRefresh ? "§aAuto §2ON" : "§cAuto §4OFF");
        this.addButton(this.btnAutoRefresh, (b, mb) -> {
            globalAutoRefresh = !globalAutoRefresh;
            autoRefreshTick = 0;
            this.btnAutoRefresh.setDisplayString(globalAutoRefresh ? "§aAuto §2ON" : "§cAuto §4OFF");
        });

        // Manual refresh (triggers Litematica task — shows notification, but user-initiated)
        ButtonGeneric btnRefresh = new ButtonGeneric(startX + 204, 6, 20, 20, "⟳");
        this.addButton(btnRefresh, (b, mb) -> triggerFullRefresh());

        // Chests
        this.btnChests = new ButtonGeneric(startX + 228, 6, 68, 20, "   §bChests");
        this.addButton(this.btnChests,
                (b, mb) -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiBmlChests(this.placementName)));

        // Schematics folder
        this.btnSchematics = new ButtonGeneric(startX + 300, 6, 90, 20, "   §eSchematics");
        this.addButton(this.btnSchematics, (b, mb) -> openSchematicsFolder());

        // Settings & Clear Cache (top-right)
        this.addButton(new ButtonGeneric(startX + guiWidth - 126, 6, 62, 20, "§eSettings"),
                (b, mb) -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiConfigs()));
        this.addButton(new ButtonGeneric(startX + guiWidth - 60, 6, 60, 20, "§c🗑 Cache"),
                (b, mb) -> clearCache());

        // Bottom bar: search on its own row when narrow, filters + focus always on one row
        int bottomY = this.getScreenHeight() - 26;
        boolean twoRows = guiWidth < 680;
        int row1Y = twoRows ? bottomY - 24 : bottomY;
        int row2Y = bottomY;

        if (this.searchField == null) {
            this.searchField = new EditBox(this.font, startX, row1Y, 120, 20, Component.literal("Search item..."));
            this.searchField.setResponder(text -> {
                globalSearchText = text;
                if (this.getListWidget() != null) this.getListWidget().refreshEntries();
            });
            this.searchField.setValue(globalSearchText);
        } else {
            this.searchField.setX(startX);
            this.searchField.setY(row1Y);
        }
        if (!this.children().contains(this.searchField)) this.addRenderableWidget(this.searchField);

        // Filter buttons: Placed / Stored / Checked — icons rendered over them in drawContents
        int filterX = twoRows ? startX : startX + 130;
        this.btnPlacedCheck = new ButtonGeneric(filterX, row2Y, 56, 20,
                globalHideFullyPlaced ? "   §aON" : "   §cOFF");
        this.addButton(btnPlacedCheck, (b, mb) -> {
            globalHideFullyPlaced = !globalHideFullyPlaced;
            btnPlacedCheck.setDisplayString(globalHideFullyPlaced ? "   §aON" : "   §cOFF");
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        });

        this.btnStoredCheck = new ButtonGeneric(filterX + 60, row2Y, 56, 20,
                globalHideFullyStored ? "   §aON" : "   §cOFF");
        this.addButton(btnStoredCheck, (b, mb) -> {
            globalHideFullyStored = !globalHideFullyStored;
            btnStoredCheck.setDisplayString(globalHideFullyStored ? "   §aON" : "   §cOFF");
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        });

        ButtonGeneric btnChecked = new ButtonGeneric(filterX + 120, row2Y, 70, 20,
                globalHideChecked ? "§b✔ §aON" : "§b✔ §cOFF");
        this.addButton(btnChecked, (b, mb) -> {
            globalHideChecked = !globalHideChecked;
            btnChecked.setDisplayString(globalHideChecked ? "§b✔ §aON" : "§b✔ §cOFF");
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        });

        // Focus buttons — merged onto the filter row
        this.btnFocusMaster = null;
        this.btnPlayers = null;
        if (hasFocusBar()) {
            int fbx = filterX + 198; // filterX + Grass(56) + gap(4) + Chest(56) + gap(4) + ✔(70) + gap(8)
            this.btnFocusMaster = new ButtonGeneric(fbx, row2Y, 84, 20, focusModeLabel());
            this.addButton(this.btnFocusMaster, (b, mb) -> {
                com.example.party.FocusManager.cycleFocusMode();
                this.btnFocusMaster.setDisplayString(focusModeLabel());
            });

            this.btnPlayers = new ButtonGeneric(fbx + 88, row2Y, 72, 20, "§ePlayers §7▾");
            this.addButton(this.btnPlayers, (b, mb) -> showPlayerDropdown = !showPlayerDropdown);
        }
    }

    private void triggerFullRefresh() {
        if (this.placements == null) return;
        for (SchematicPlacement p : this.placements) {
            if (p.isEnabled() && p.getMaterialList() != null) {
                p.getMaterialList().setCompletionListener(this);
                p.getMaterialList().reCreateMaterialList();
            }
        }
    }

    private void clearCache() {
        if (this.placements != null) {
            com.example.data.MaterialCacheManager.clearCache(
                    com.example.data.MaterialCacheManager.getCacheKey(this.placements));
        }
        com.example.data.ContainerDataManager.clearPlacement(this.placementName);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§a[BML] Cache cleared. Use ⟳ to recount."));
        }
    }

    private void openSchematicsFolder() {
        try {
            java.io.File folder = FabricLoader.getInstance().getGameDir().resolve("schematics").toFile();
            if (!folder.exists()) folder.mkdirs();
            String path = folder.getAbsolutePath();
            try {
                java.awt.Desktop.getDesktop().open(folder);
            } catch (Exception ignored) {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win"))       new ProcessBuilder("explorer.exe", path).start();
                else if (os.contains("mac"))  new ProcessBuilder("open", path).start();
                else                          new ProcessBuilder("xdg-open", path).start();
            }
        } catch (Exception e) {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("§c[BML] Could not open schematics folder."));
        }
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        Path schematicsDir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try {
            Files.createDirectories(schematicsDir);
        } catch (Exception e) {
            return;
        }
        int copied = 0;
        for (Path path : paths) {
            String name = path.getFileName().toString();
            if (!name.endsWith(".litematic")) continue;
            try {
                Files.copy(path, schematicsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (Exception ignored) {}
        }
        if (Minecraft.getInstance().player != null) {
            String msg = copied > 0
                    ? "§a[BML] Copied " + copied + " schematic(s) to schematics folder."
                    : "§c[BML] No .litematic files in dropped items.";
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(msg));
        }
    }

    public void setHoveredEntry(fi.dy.masa.litematica.materials.MaterialListEntry entry, int mouseX, int mouseY) {
        this.hoveredEntry = entry;
        this.hoveredMouseX = mouseX;
        this.hoveredMouseY = mouseY;
    }

    private void renderEntryTooltip(GuiContext guiContext, fi.dy.masa.litematica.materials.MaterialListEntry entry,
            int mouseX, int mouseY) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        net.minecraft.world.item.ItemStack stack = entry.getStack();
        String fullName   = stack.getHoverName().getString();
        int total         = entry.getCountTotal();
        int missingInWorld= entry.getCountMissing();
        int available     = entry.getCountAvailable();
        int placed        = Math.max(0, total - missingInWorld);
        int actualMissing = Math.max(0, missingInWorld - available);

        String needLine  = "Need:   " + WidgetBetterMaterialListEntry.stackBreakdown(total);
        String placeLine = "Placed: " + WidgetBetterMaterialListEntry.stackBreakdown(placed);
        String storeLine = "Stored: " + WidgetBetterMaterialListEntry.stackBreakdown(available);
        String missLine  = "Miss:   " + WidgetBetterMaterialListEntry.stackBreakdown(actualMissing);

        // Focusing-players section (always shown in tooltip regardless of focus mode)
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        java.util.List<com.example.party.FocusManager.PlayerFocus> focusers =
                com.example.party.PartyManager.isInParty()
                ? com.example.party.FocusManager.getTargetersForTooltip(itemId)
                : java.util.Collections.emptyList();

        int pad = 6;
        int iconSize = 16;
        int lineH = 11;
        int faceRowH = 16;
        String focusHeader = "Focused by:";
        int textW = Math.max(font.width(fullName),
                    Math.max(font.width(needLine),
                    Math.max(font.width(placeLine),
                    Math.max(font.width(storeLine), font.width(missLine)))));
        if (!focusers.isEmpty()) {
            textW = Math.max(textW, font.width(focusHeader));
            for (var pf : focusers)
                textW = Math.max(textW, faceRowH + 2 + font.width(pf.nick()));
        }
        int w = pad + iconSize + 4 + textW + pad;
        int h = pad + iconSize + 4 + lineH * 4 + pad;
        if (!focusers.isEmpty()) h += lineH + focusers.size() * faceRowH + 4;

        int tx = mouseX + 14;
        int ty = mouseY - h / 2;
        if (tx + w > this.getScreenWidth()  - 4) tx = mouseX - w - 4;
        if (ty < 4)                               ty = 4;
        if (ty + h > this.getScreenHeight() - 4) ty = this.getScreenHeight() - h - 4;

        guiContext.fill(tx - 1, ty - 1, tx + w + 1, ty + h + 1, 0xFF000000);
        guiContext.fill(tx,     ty,     tx + w,     ty + h,     0xE8111111);

        guiContext.renderItem(stack, tx + pad, ty + pad);
        guiContext.drawString(font, fullName, tx + pad + iconSize + 4, ty + pad + (iconSize - 8) / 2, 0xFFFFFFFF, false);

        int ly = ty + pad + iconSize + 4;
        guiContext.drawString(font, needLine,  tx + pad, ly,              0xFFAAAAAA, false);
        guiContext.drawString(font, placeLine, tx + pad, ly + lineH,      0xFFFFFFFF, false);
        guiContext.drawString(font, storeLine, tx + pad, ly + lineH * 2,  0xFFFFFFFF, false);
        int missColor = actualMissing > 0 ? 0xFFFF5555 : 0xFF55FF55;
        guiContext.drawString(font, missLine,  tx + pad, ly + lineH * 3,  missColor,  false);

        if (!focusers.isEmpty()) {
            int fy = ly + lineH * 4 + 4;
            guiContext.fill(tx + pad, fy - 1, tx + w - pad, fy, 0x40FFFFFF);
            fy += 2;
            guiContext.drawString(font, focusHeader, tx + pad, fy, 0xFFAAAAAA, false);
            fy += lineH;
            for (var pf : focusers) {
                addFaceRenderRequest(pf.nick(), tx + pad, fy, faceRowH);
                guiContext.drawString(font, pf.nick(), tx + pad + faceRowH + 2, fy + (faceRowH - 8) / 2, 0xFFFFFFFF, false);
                fy += faceRowH;
            }
        }
    }

    private boolean hasFocusBar() {
        return com.example.network.BmlClientNetworking.serverSupported
                && com.example.party.PartyManager.isInParty();
    }

    private String focusModeLabel() {
        return switch (com.example.party.FocusManager.getFocusMode()) {
            case com.example.party.FocusManager.MODE_MINE -> "   §aFocus: Mine";
            case com.example.party.FocusManager.MODE_ALL  -> "   §eFocus: All";
            default -> "   §7Focus: Off";
        };
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private int getRawGuiWidth() {
        int w = Math.max(600, (int) (this.getScreenWidth() * 0.9));
        return Math.min(w, this.getScreenWidth() - 20);
    }

    private int getEffectiveListWidth(int guiWidth) {
        return (globalLayoutMode == LayoutMode.SINGLE)
                ? Math.min(guiWidth, BmlLayoutConstants.SINGLE_MODE_MAX_WIDTH)
                : guiWidth;
    }

    @Override
    protected WidgetMaterialList createListWidget(int listX, int listY) {
        int raw    = getRawGuiWidth();
        int width  = getEffectiveListWidth(raw);
        int height = this.getScreenHeight() - 80;
        if (raw < 680) height -= 24;
        int startX = (this.getScreenWidth() - width) / 2;
        return new WidgetMaterialList(startX, 50, width, height, this);
    }

    @Override
    protected int getBrowserWidth() { return getRawGuiWidth(); }

    @Override
    protected int getBrowserHeight() {
        int h = this.getScreenHeight() - 80;
        if (getRawGuiWidth() < 680) h -= 24;
        return h;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void drawContents(GuiContext guiContext, int mouseX, int mouseY, float partialTicks) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.hoveredEntry = null; // reset each frame; entries will set it during super.drawContents
        this.faceRenderRequests.clear();
        super.drawContents(guiContext, mouseX, mouseY, partialTicks);

        int raw          = getRawGuiWidth();
        int effectiveW   = getEffectiveListWidth(raw);
        int startX       = (this.getScreenWidth() - effectiveW) / 2;
        int halfWidth    = effectiveW / 2;
        int headerY      = 38;

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;

        boolean isSingle = globalLayoutMode == LayoutMode.SINGLE;
        int totalColW = isSingle ? BmlLayoutConstants.SINGLE_TOTAL_WIDTH : BmlLayoutConstants.TOTAL_WIDTH;

        int loopCount = isSingle ? 1 : 2;
        for (int i = 0; i < loopCount; i++) {
            int x     = startX + (i * halfWidth);
            int width = isSingle ? effectiveW : halfWidth;
            if (i == 1) { x += 1; width -= 1; }

            int cEnd   = x + width - BmlLayoutConstants.CHECKBOX_MARGIN;
            int cStart = cEnd   - BmlLayoutConstants.CHECKBOX_WIDTH;
            int misEnd = cStart - BmlLayoutConstants.COLUMN_GAP;
            int misS   = misEnd - BmlLayoutConstants.MISSING_WIDTH;
            int avEnd  = misS   - BmlLayoutConstants.COLUMN_GAP;
            int avS    = avEnd  - BmlLayoutConstants.AVAILABLE_WIDTH;
            int plEnd  = avS    - BmlLayoutConstants.COLUMN_GAP;
            int plS    = plEnd  - BmlLayoutConstants.PLACED_WIDTH;
            int toEnd  = plS    - BmlLayoutConstants.COLUMN_GAP;
            int toS    = toEnd  - totalColW;

            String arr = sortDescending ? "▼" : "▲";
            guiContext.drawString(font,
                "Block" + (currentSortMode == SortMode.BLOCK ? " " + arr : ""),
                x + BmlLayoutConstants.NAME_OFFSET_X, headerY,
                currentSortMode == SortMode.BLOCK ? 0xFFFFAA00 : 0xFFCCCCCC, false);
            guiContext.drawString(font,
                "Need" + (currentSortMode == SortMode.REQUIRED ? " " + arr : ""),
                toS + 2, headerY, currentSortMode == SortMode.REQUIRED ? 0xFFFFAA00 : 0xFFAAAAAA, false);
            guiContext.drawString(font,
                "Done" + (currentSortMode == SortMode.PLACED ? " " + arr : ""),
                plS + 2, headerY, currentSortMode == SortMode.PLACED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            guiContext.drawString(font,
                "Have" + (currentSortMode == SortMode.STORED ? " " + arr : ""),
                avS + 2, headerY, currentSortMode == SortMode.STORED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            guiContext.drawString(font,
                "Miss" + (currentSortMode == SortMode.MISSING ? " " + arr : ""),
                misS + 2, headerY, currentSortMode == SortMode.MISSING ? 0xFFFFAA00 : 0xFFFF7777, false);
        }

        if (this.materialList == null || this.materialList.isEmpty()) {
            String msg = "No Litematica schematic selected";
            guiContext.drawString(font, msg,
                startX + (effectiveW - font.width(msg)) / 2, headerY + 50, 0xFFFF5555, false);
            String hint = "§7Drop .litematic files here to add them to your schematics folder";
            String hintPlain = "Drop .litematic files here to add them to your schematics folder";
            guiContext.drawString(font, hint,
                startX + (effectiveW - font.width(hintPlain)) / 2, headerY + 66, 0xFFAAAAAA, false);
        }

        if (this.searchField != null)
            this.searchField.extractWidgetRenderState(guiContext, mouseX, mouseY, partialTicks);

        // Draw item icons over the filter buttons and top-bar buttons
        if (this.btnPlacedCheck != null)
            guiContext.renderItem(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GRASS_BLOCK),
                this.btnPlacedCheck.getX() + 3, this.btnPlacedCheck.getY() + 2);
        if (this.btnStoredCheck != null)
            guiContext.renderItem(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST),
                this.btnStoredCheck.getX() + 3, this.btnStoredCheck.getY() + 2);
        if (this.btnChests != null)
            guiContext.renderItem(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST),
                this.btnChests.getX() + 3, this.btnChests.getY() + 2);
        if (this.btnSchematics != null)
            guiContext.renderItem(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FILLED_MAP),
                this.btnSchematics.getX() + 3, this.btnSchematics.getY() + 2);

        // Eye of Ender icon on the focus master toggle button
        if (this.btnFocusMaster != null)
            guiContext.renderItem(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_EYE),
                this.btnFocusMaster.getX() + 3, this.btnFocusMaster.getY() + 2);

        // Players dropdown panel — rendered before tooltip so tooltip wins z-order
        if (showPlayerDropdown && btnPlayers != null && hasFocusBar()) {
            java.util.List<String> members = com.example.party.PartyManager.getMembers();
            if (!members.isEmpty()) {
                int dpx = btnPlayers.getX();
                int dpw = 150;
                int rowH = 18;
                int dph = 4 + members.size() * rowH + 4;
                int dpy = btnPlayers.getY() - dph - 2;
                guiContext.fill(dpx - 1, dpy - 1, dpx + dpw + 1, dpy + dph + 1, 0xFF000000);
                guiContext.fill(dpx,     dpy,     dpx + dpw,     dpy + dph,     0xE8222222);
                for (int i = 0; i < members.size(); i++) {
                    String nick = members.get(i);
                    boolean hidden = com.example.party.FocusManager.isPlayerHidden(nick);
                    int iy = dpy + 4 + i * rowH;
                    if (mouseX >= dpx && mouseX < dpx + dpw && mouseY >= iy && mouseY < iy + rowH)
                        guiContext.fill(dpx + 1, iy, dpx + dpw - 1, iy + rowH, 0x30FFFFFF);
                    addFaceRenderRequest(nick, dpx + 4, iy + 2, 14);
                    guiContext.drawString(font, (hidden ? "§7" : "§f") + nick, dpx + 22, iy + 5, 0xFFFFFFFF, false);
                    guiContext.drawString(font, hidden ? "§c✖" : "§a●", dpx + dpw - 14, iy + 5, 0xFFFFFFFF, false);
                }
            }
        }

        // Hover tooltip — rendered last so it draws on top of everything
        if (this.hoveredEntry != null && globalLayoutMode != LayoutMode.SINGLE)
            renderEntryTooltip(guiContext, this.hoveredEntry, this.hoveredMouseX, this.hoveredMouseY);
    }

    // ── Player face rendering (requires GuiGraphicsExtractor) ─────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);

        if (Minecraft.getInstance().getConnection() == null) return;
        for (FaceRenderRequest req : faceRenderRequests) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(req.nick());
            if (info != null)
                PlayerFaceExtractor.extractRenderState(drawContext, info.getSkin(), req.x(), req.y(), req.size());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean wasFocused) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (showPlayerDropdown && btnPlayers != null && event.button() == 0) {
            java.util.List<String> members = com.example.party.PartyManager.getMembers();
            int dpx = btnPlayers.getX();
            int dpw = 150;
            int rowH = 18;
            int dph = 4 + members.size() * rowH + 4;
            int dpy = btnPlayers.getY() - dph - 2;
            if (mouseX >= dpx && mouseX < dpx + dpw && mouseY >= dpy && mouseY < dpy + dph) {
                int index = ((int) mouseY - dpy - 4) / rowH;
                if (index >= 0 && index < members.size())
                    com.example.party.FocusManager.togglePlayerHidden(members.get(index));
                return true;
            }
            boolean onBtn = mouseX >= btnPlayers.getX() && mouseX < btnPlayers.getX() + btnPlayers.getWidth()
                    && mouseY >= btnPlayers.getY() && mouseY < btnPlayers.getY() + btnPlayers.getHeight();
            if (!onBtn) showPlayerDropdown = false;
        }
        return super.mouseClicked(event, wasFocused);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @Override
    public boolean shouldCloseOnEsc() {
        if (this.searchField != null && this.searchField.isFocused()) {
            this.searchField.setFocused(false);
            return false;
        }
        return super.shouldCloseOnEsc();
    }

    @Override
    public void tick() {
        super.tick();

        long window = Minecraft.getInstance().getWindow().handle();
        boolean isMouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        if (isMouseDown && !this.wasLeftMouseDown) handleHeaderClick(this.lastMouseX, this.lastMouseY);
        this.wasLeftMouseDown = isMouseDown;

        // Right-click on any item to toggle focus/target (party only)
        boolean isRightDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_2) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (isRightDown && !this.wasRightMouseDown
                && this.hoveredEntry != null
                && com.example.party.PartyManager.isInParty()) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(this.hoveredEntry.getStack().getItem()).toString();
            com.example.party.FocusManager.toggleMyTarget(itemId);
            com.example.network.BmlClientNetworking.sendTargetUpdate();
        }
        this.wasRightMouseDown = isRightDown;

        if (isMouseDown && this.searchField != null) {
            boolean inside = lastMouseX >= searchField.getX()
                    && lastMouseX < searchField.getX() + searchField.getWidth()
                    && lastMouseY >= searchField.getY()
                    && lastMouseY < searchField.getY() + searchField.getHeight();
            if (inside && !searchField.isFocused())       searchField.setFocused(true);
            else if (!inside && searchField.isFocused())  searchField.setFocused(false);
        }

        if (this.searchField != null && this.searchField.isFocused()) return;

        // Silent auto-refresh: re-reads Litematica's current list without scheduling a new task
        // (avoids the "Scheduled task added" notification)
        if (globalAutoRefresh && !isFirstLoad && this.placements != null) {
            autoRefreshTick++;
            if (autoRefreshTick >= AUTO_REFRESH_INTERVAL) {
                autoRefreshTick = 0;
                silentRefresh();
            }
        }

        if (isFirstLoad && !this.isCached) {
            triggerFullRefresh();
            isFirstLoad = false;
        }
    }

    /** Re-reads Litematica's current material data without scheduling a world-rescan task. */
    private void silentRefresh() {
        if (this.placements == null) return;
        List<MaterialListEntry> fresh = InputHandler.collectMaterialsFromPlacements(this.placements);
        if (Minecraft.getInstance().player != null) {
            MaterialListUtils.updateAvailableCounts(fresh, Minecraft.getInstance().player);
            InputHandler.addCachedContainerItems(fresh, this.placementName);
        }
        this.materialList = fresh;
        if (this.getListWidget() != null) this.getListWidget().refreshEntries();
    }

    @Override
    public void onTaskCompleted() {
        if (this.placements != null) {
            this.materialList = InputHandler.collectMaterialsFromPlacements(this.placements);
            if (Minecraft.getInstance().player != null) {
                MaterialListUtils.updateAvailableCounts(this.materialList, Minecraft.getInstance().player);
                InputHandler.addCachedContainerItems(this.materialList, this.placementName);
            }
            this.initGui();
        }
    }

    // ── Header sort clicks ────────────────────────────────────────────────────

    private void handleHeaderClick(double mouseX, double mouseY) {
        int effectiveW = getEffectiveListWidth(getRawGuiWidth());
        int halfWidth  = effectiveW / 2;
        int startX     = (this.getScreenWidth() - effectiveW) / 2;
        int headerY    = 38;

        if (mouseY < headerY - 4 || mouseY > headerY + 12) return;

        boolean isSingle  = globalLayoutMode == LayoutMode.SINGLE;
        int totalColW     = isSingle ? BmlLayoutConstants.SINGLE_TOTAL_WIDTH : BmlLayoutConstants.TOTAL_WIDTH;
        int loopCount     = isSingle ? 1 : 2;
        for (int i = 0; i < loopCount; i++) {
            int x     = startX + (i * halfWidth);
            int width = isSingle ? effectiveW : halfWidth;
            if (i == 1) { x += 1; width -= 1; }

            int cEnd   = x + width - BmlLayoutConstants.CHECKBOX_MARGIN;
            int cStart = cEnd   - BmlLayoutConstants.CHECKBOX_WIDTH;
            int misEnd = cStart - BmlLayoutConstants.COLUMN_GAP;
            int misS   = misEnd - BmlLayoutConstants.MISSING_WIDTH;
            int avEnd  = misS   - BmlLayoutConstants.COLUMN_GAP;
            int avS    = avEnd  - BmlLayoutConstants.AVAILABLE_WIDTH;
            int plEnd  = avS    - BmlLayoutConstants.COLUMN_GAP;
            int plS    = plEnd  - BmlLayoutConstants.PLACED_WIDTH;
            int toEnd  = plS    - BmlLayoutConstants.COLUMN_GAP;
            int toS    = toEnd  - totalColW;

            if      (mouseX >= x + BmlLayoutConstants.NAME_OFFSET_X && mouseX < toS)   { setSortMode(SortMode.BLOCK);    return; }
            else if (mouseX >= toS  && mouseX < plS)   { setSortMode(SortMode.REQUIRED); return; }
            else if (mouseX >= plS  && mouseX < avS)   { setSortMode(SortMode.PLACED);   return; }
            else if (mouseX >= avS  && mouseX < misS)  { setSortMode(SortMode.STORED);   return; }
            else if (mouseX >= misS && mouseX < cStart){ setSortMode(SortMode.MISSING);  return; }
            else if (mouseX >= cStart && mouseX <= cEnd){ setSortMode(SortMode.CHECKED); return; }
        }
    }

    private void setSortMode(SortMode mode) {
        if (currentSortMode == mode) sortDescending = !sortDescending;
        else { currentSortMode = mode; sortDescending = mode != SortMode.BLOCK; }
        if (this.getListWidget() != null) this.getListWidget().refreshEntries();
    }

    public String getPlacementName()  { return this.placementName; }
    public boolean isSearchFocused()  { return this.searchField != null && this.searchField.isFocused(); }
}
