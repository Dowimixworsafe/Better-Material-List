package com.betterlist.gui;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import com.betterlist.input.InputHandler;
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
    private ButtonGeneric btnParty;
    private ButtonGeneric btnLayout;
    private ButtonGeneric btnRefresh;
    private ButtonGeneric btnSettings;
    private ButtonGeneric btnCache;
    private ButtonGeneric btnChecked;
    private ButtonGeneric btnClearTargets;
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
                boolean checked = com.betterlist.data.MaterialStateManager.isChecked(getChecklistKey(), itemName);
                if (checked || (actuallyMissing <= 0 && total > 0)) continue;
            }
            if (!globalSearchText.isEmpty()) {
                String name = entry.getStack().getHoverName().getString().toLowerCase();
                if (!name.contains(globalSearchText.toLowerCase())) continue;
            }
            // Player filter: when active, show only items targeted by the selected players.
            if (com.betterlist.party.FocusManager.isListFilterActive()) {
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(entry.getStack().getItem()).toString();
                if (!com.betterlist.party.FocusManager.passesPlayerFilter(itemId)) continue;
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
                    boolean c1 = com.betterlist.data.MaterialStateManager.isChecked(getChecklistKey(), n1);
                    boolean c2 = com.betterlist.data.MaterialStateManager.isChecked(getChecklistKey(), n2);
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
        String partyText = com.betterlist.network.BmlClientNetworking.serverSupported
                ? (com.betterlist.party.PartyManager.isInParty() ? "§a👥 " + com.betterlist.util.BmlLang.tr("bml.list.party") : "§e👥 " + com.betterlist.util.BmlLang.tr("bml.list.party"))
                : "§7👥 " + com.betterlist.util.BmlLang.tr("bml.list.no_server");
        this.btnParty = new ButtonGeneric(startX, 6, 78, 20, partyText);
        this.addButton(this.btnParty, com.betterlist.util.BmlButtons.leftClick(
                () -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiParty(this.placementName))));

        String layoutIcon = globalLayoutMode == LayoutMode.SINGLE ? "1-Col"
                : (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "2-Vert" : "2-Horiz");
        this.btnLayout = new ButtonGeneric(startX + 82, 6, 54, 20, layoutIcon);
        ButtonGeneric btnLayout = this.btnLayout;
        this.addButton(btnLayout, com.betterlist.util.BmlButtons.leftClick(() -> {
            if      (globalLayoutMode == LayoutMode.TWO_HORIZONTAL) globalLayoutMode = LayoutMode.TWO_VERTICAL;
            else if (globalLayoutMode == LayoutMode.TWO_VERTICAL)   globalLayoutMode = LayoutMode.SINGLE;
            else                                                      globalLayoutMode = LayoutMode.TWO_HORIZONTAL;
            String icon = globalLayoutMode == LayoutMode.SINGLE ? "1-Col"
                    : (globalLayoutMode == LayoutMode.TWO_VERTICAL ? "2-Vert" : "2-Horiz");
            btnLayout.setDisplayString(icon);
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        }));

        // Auto-refresh toggle
        this.btnAutoRefresh = new ButtonGeneric(startX + 140, 6, 60, 20,
                globalAutoRefresh ? "§a" + com.betterlist.util.BmlLang.tr("bml.list.auto_on") : "§c" + com.betterlist.util.BmlLang.tr("bml.list.auto_off"));
        this.addButton(this.btnAutoRefresh, com.betterlist.util.BmlButtons.leftClick(() -> {
            globalAutoRefresh = !globalAutoRefresh;
            autoRefreshTick = 0;
            this.btnAutoRefresh.setDisplayString(globalAutoRefresh ? "§a" + com.betterlist.util.BmlLang.tr("bml.list.auto_on") : "§c" + com.betterlist.util.BmlLang.tr("bml.list.auto_off"));
        }));

        // Manual refresh (triggers Litematica task — shows notification, but user-initiated)
        this.btnRefresh = new ButtonGeneric(startX + 204, 6, 20, 20, "⟳");
        this.addButton(this.btnRefresh, com.betterlist.util.BmlButtons.leftClick(this::triggerFullRefresh));

        // Chests
        this.btnChests = new ButtonGeneric(startX + 228, 6, 68, 20, "   §b" + com.betterlist.util.BmlLang.tr("bml.list.chests"));
        this.addButton(this.btnChests, com.betterlist.util.BmlButtons.leftClick(
                () -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiBmlChests(this.placementName))));

        // Schematics folder
        this.btnSchematics = new ButtonGeneric(startX + 300, 6, 90, 20, "   §e" + com.betterlist.util.BmlLang.tr("bml.list.schematics"));
        this.addButton(this.btnSchematics, com.betterlist.util.BmlButtons.leftClick(this::openSchematicsFolder));

        // Settings & Clear Cache (top-right)
        this.btnSettings = new ButtonGeneric(startX + guiWidth - 126, 6, 62, 20, "§e" + com.betterlist.util.BmlLang.tr("bml.list.settings"));
        this.addButton(this.btnSettings, com.betterlist.util.BmlButtons.leftClick(
                () -> fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiConfigs())));
        this.btnCache = new ButtonGeneric(startX + guiWidth - 60, 6, 60, 20, "§c🗑 " + com.betterlist.util.BmlLang.tr("bml.list.cache"));
        this.addButton(this.btnCache, com.betterlist.util.BmlButtons.leftClick(this::clearCache));

        // Bottom bar: search on its own row when narrow, filters + focus always on one row
        int bottomY = this.getScreenHeight() - 26;
        boolean twoRows = guiWidth < 680;
        int row1Y = twoRows ? bottomY - 24 : bottomY;
        int row2Y = bottomY;

        if (this.searchField == null) {
            this.searchField = new EditBox(this.font, startX, row1Y, 120, 20,
                    Component.literal(com.betterlist.util.BmlLang.tr("bml.gui.search_placeholder")));
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
                globalHideFullyPlaced ? "   §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "   §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
        this.addButton(btnPlacedCheck, com.betterlist.util.BmlButtons.leftClick(() -> {
            globalHideFullyPlaced = !globalHideFullyPlaced;
            btnPlacedCheck.setDisplayString(globalHideFullyPlaced ? "   §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "   §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        }));

        this.btnStoredCheck = new ButtonGeneric(filterX + 60, row2Y, 56, 20,
                globalHideFullyStored ? "   §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "   §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
        this.addButton(btnStoredCheck, com.betterlist.util.BmlButtons.leftClick(() -> {
            globalHideFullyStored = !globalHideFullyStored;
            btnStoredCheck.setDisplayString(globalHideFullyStored ? "   §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "   §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        }));

        this.btnChecked = new ButtonGeneric(filterX + 120, row2Y, 70, 20,
                globalHideChecked ? "§b✔ §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "§b✔ §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
        ButtonGeneric btnChecked = this.btnChecked;
        this.addButton(btnChecked, com.betterlist.util.BmlButtons.leftClick(() -> {
            globalHideChecked = !globalHideChecked;
            btnChecked.setDisplayString(globalHideChecked ? "§b✔ §a" + com.betterlist.util.BmlLang.tr("bml.list.on") : "§b✔ §c" + com.betterlist.util.BmlLang.tr("bml.list.off"));
            if (this.getListWidget() != null) this.getListWidget().refreshEntries();
        }));

        // Focus buttons — merged onto the filter row. Shown solo too (targeting is local).
        this.btnFocusMaster = null;
        this.btnPlayers = null;
        this.btnClearTargets = null;
        if (hasFocusBar()) {
            int fbx = filterX + 198; // filterX + Grass(56) + gap(4) + Chest(56) + gap(4) + ✔(70) + gap(8)
            this.btnFocusMaster = new ButtonGeneric(fbx, row2Y, 84, 20, focusModeLabel());
            this.addButton(this.btnFocusMaster, com.betterlist.util.BmlButtons.leftClick(() -> {
                com.betterlist.party.FocusManager.cycleFocusMode();
                this.btnFocusMaster.setDisplayString(focusModeLabel());
            }));

            this.btnPlayers = new ButtonGeneric(fbx + 88, row2Y, 72, 20, "§e" + com.betterlist.util.BmlLang.tr("bml.list.players") + " §7▾");
            this.addButton(this.btnPlayers, com.betterlist.util.BmlButtons.leftClick(() -> showPlayerDropdown = !showPlayerDropdown));

            this.btnClearTargets = new ButtonGeneric(fbx + 164, row2Y, 64, 20,
                    "§c✖ " + com.betterlist.util.BmlLang.tr("bml.list.clear_targets"));
            this.addButton(this.btnClearTargets, com.betterlist.util.BmlButtons.leftClick(() -> {
                com.betterlist.party.FocusManager.clearMyTargets();
                com.betterlist.data.HudOverlayManager.recompute();
                if (com.betterlist.party.PartyManager.isInParty())
                    com.betterlist.network.BmlClientNetworking.sendTargetUpdate();
                if (this.getListWidget() != null) this.getListWidget().refreshEntries();
            }));
        }
    }

    public static boolean isAutoRefreshEnabled() { return globalAutoRefresh; }

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
            com.betterlist.data.MaterialCacheManager.clearCache(
                    com.betterlist.data.MaterialCacheManager.getCacheKey(this.placements));
        }
        com.betterlist.data.ContainerDataManager.clearAll();
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§a" + com.betterlist.util.BmlLang.tr("bml.chest.cache_cleared")));
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
                        Component.literal("§c" + com.betterlist.util.BmlLang.tr("bml.chest.folder_error")));
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
                    ? "§a" + com.betterlist.util.BmlLang.tr("bml.chest.copied", copied)
                    : "§c" + com.betterlist.util.BmlLang.tr("bml.chest.no_litematic");
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

        String needLine  = com.betterlist.util.BmlLang.tr("bml.tt.need")   + ": " + WidgetBetterMaterialListEntry.stackBreakdown(total);
        String placeLine = com.betterlist.util.BmlLang.tr("bml.tt.placed") + ": " + WidgetBetterMaterialListEntry.stackBreakdown(placed);
        String storeLine = com.betterlist.util.BmlLang.tr("bml.tt.stored") + ": " + WidgetBetterMaterialListEntry.stackBreakdown(available);
        String missLine  = com.betterlist.util.BmlLang.tr("bml.tt.miss")   + ": " + WidgetBetterMaterialListEntry.stackBreakdown(actualMissing);

        // Focusing-players section (always shown in tooltip regardless of focus mode)
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        java.util.List<com.betterlist.party.FocusManager.PlayerFocus> focusers =
                com.betterlist.party.PartyManager.isInParty()
                ? com.betterlist.party.FocusManager.getTargetersForTooltip(itemId)
                : java.util.Collections.emptyList();

        int pad = 6;
        int iconSize = 16;
        int lineH = 11;
        int faceRowH = 16;
        String focusHeader = com.betterlist.util.BmlLang.tr("bml.tt.focused_by");
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
        return true; // local targeting works solo; party just adds the other members
    }

    // Party members when in a party, otherwise just the local player (filter to your own targets).
    private java.util.List<String> getFocusMembers() {
        if (com.betterlist.party.PartyManager.isInParty()) {
            return com.betterlist.party.PartyManager.getMembers();
        }
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        return p != null ? java.util.List.of(p.getGameProfile().name()) : java.util.List.of();
    }

    private String focusModeLabel() {
        return switch (com.betterlist.party.FocusManager.getFocusMode()) {
            case com.betterlist.party.FocusManager.MODE_MINE -> "   §a" + com.betterlist.util.BmlLang.tr("bml.list.focus_mine");
            case com.betterlist.party.FocusManager.MODE_ALL  -> "   §e" + com.betterlist.util.BmlLang.tr("bml.list.focus_all");
            default -> "   §7" + com.betterlist.util.BmlLang.tr("bml.list.focus_off");
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
                com.betterlist.util.BmlLang.tr("bml.col.block") + (currentSortMode == SortMode.BLOCK ? " " + arr : ""),
                x + BmlLayoutConstants.NAME_OFFSET_X, headerY,
                currentSortMode == SortMode.BLOCK ? 0xFFFFAA00 : 0xFFCCCCCC, false);
            guiContext.drawString(font,
                com.betterlist.util.BmlLang.tr("bml.col.need") + (currentSortMode == SortMode.REQUIRED ? " " + arr : ""),
                toS + 2, headerY, currentSortMode == SortMode.REQUIRED ? 0xFFFFAA00 : 0xFFAAAAAA, false);
            guiContext.drawString(font,
                com.betterlist.util.BmlLang.tr("bml.col.done") + (currentSortMode == SortMode.PLACED ? " " + arr : ""),
                plS + 2, headerY, currentSortMode == SortMode.PLACED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            guiContext.drawString(font,
                com.betterlist.util.BmlLang.tr("bml.col.have") + (currentSortMode == SortMode.STORED ? " " + arr : ""),
                avS + 2, headerY, currentSortMode == SortMode.STORED ? 0xFFFFAA00 : 0xFFFFFFFF, false);
            guiContext.drawString(font,
                com.betterlist.util.BmlLang.tr("bml.col.miss") + (currentSortMode == SortMode.MISSING ? " " + arr : ""),
                misS + 2, headerY, currentSortMode == SortMode.MISSING ? 0xFFFFAA00 : 0xFFFF7777, false);
        }

        if (this.materialList == null || this.materialList.isEmpty()) {
            String msg = com.betterlist.util.BmlLang.tr("bml.list.no_schematic");
            guiContext.drawString(font, msg,
                startX + (effectiveW - font.width(msg)) / 2, headerY + 50, 0xFFFF5555, false);
            String hint = "§7" + com.betterlist.util.BmlLang.tr("bml.list.drop_hint");
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
            java.util.List<String> members = getFocusMembers();
            if (!members.isEmpty()) {
                int dpx = btnPlayers.getX();
                int dpw = 150;
                int rowH = 18;
                int headerH = 14;
                int dph = 4 + headerH + members.size() * rowH + 4;
                int dpy = btnPlayers.getY() - dph - 2;
                guiContext.fill(dpx - 1, dpy - 1, dpx + dpw + 1, dpy + dph + 1, 0xFF000000);
                guiContext.fill(dpx,     dpy,     dpx + dpw,     dpy + dph,     0xE8222222);
                // Header: explains that clicking filters the list to a player's items.
                guiContext.drawString(font, "§7" + com.betterlist.util.BmlLang.tr("bml.list.filter_hint"), dpx + 4, dpy + 4, 0xFFFFFFFF, false);
                int rowsTop = dpy + 4 + headerH;
                for (int i = 0; i < members.size(); i++) {
                    String nick = members.get(i);
                    boolean filtered = com.betterlist.party.FocusManager.isPlayerFiltered(nick);
                    int iy = rowsTop + i * rowH;
                    if (mouseX >= dpx && mouseX < dpx + dpw && mouseY >= iy && mouseY < iy + rowH)
                        guiContext.fill(dpx + 1, iy, dpx + dpw - 1, iy + rowH, 0x30FFFFFF);
                    if (filtered)
                        guiContext.fill(dpx + 1, iy, dpx + dpw - 1, iy + rowH, 0x4055FF55);
                    addFaceRenderRequest(nick, dpx + 4, iy + 2, 14);
                    guiContext.drawString(font, (filtered ? "§a" : "§f") + nick, dpx + 22, iy + 5, 0xFFFFFFFF, false);
                    guiContext.drawString(font, filtered ? "§a🔎" : "§7○", dpx + dpw - 16, iy + 5, 0xFFFFFFFF, false);
                }
            }
        }

        // Hover tooltip — rendered last so it draws on top of everything (config-toggleable)
        if (this.hoveredEntry != null && globalLayoutMode != LayoutMode.SINGLE
                && com.betterlist.config.ModConfig.SHOW_ITEM_HOVER_TOOLTIP)
            renderEntryTooltip(guiContext, this.hoveredEntry, this.hoveredMouseX, this.hoveredMouseY);

        drawButtonTooltips(guiContext, mouseX, mouseY);
    }

    private void drawButtonTooltips(GuiContext ctx, int mouseX, int mouseY) {
        drawButtonTooltip(ctx, btnParty,        com.betterlist.util.BmlLang.tr("bml.tip.party"),          mouseX, mouseY);
        drawButtonTooltip(ctx, btnLayout,       com.betterlist.util.BmlLang.tr("bml.tip.layout"),         mouseX, mouseY);
        drawButtonTooltip(ctx, btnAutoRefresh,  com.betterlist.util.BmlLang.tr("bml.tip.auto"),           mouseX, mouseY);
        drawButtonTooltip(ctx, btnRefresh,      com.betterlist.util.BmlLang.tr("bml.tip.refresh"),        mouseX, mouseY);
        drawButtonTooltip(ctx, btnChests,       com.betterlist.util.BmlLang.tr("bml.tip.chests"),         mouseX, mouseY);
        drawButtonTooltip(ctx, btnSchematics,   com.betterlist.util.BmlLang.tr("bml.tip.schematics"),     mouseX, mouseY);
        drawButtonTooltip(ctx, btnSettings,     com.betterlist.util.BmlLang.tr("bml.tip.settings"),       mouseX, mouseY);
        drawButtonTooltip(ctx, btnCache,        com.betterlist.util.BmlLang.tr("bml.tip.cache"),          mouseX, mouseY);
        drawButtonTooltip(ctx, btnPlacedCheck,  com.betterlist.util.BmlLang.tr("bml.tip.filter_placed"),  mouseX, mouseY);
        drawButtonTooltip(ctx, btnStoredCheck,  com.betterlist.util.BmlLang.tr("bml.tip.filter_stored"),  mouseX, mouseY);
        drawButtonTooltip(ctx, btnChecked,      com.betterlist.util.BmlLang.tr("bml.tip.filter_checked"), mouseX, mouseY);
        drawButtonTooltip(ctx, btnFocusMaster,  com.betterlist.util.BmlLang.tr("bml.tip.focus_mode"),     mouseX, mouseY);
        drawButtonTooltip(ctx, btnPlayers,      com.betterlist.util.BmlLang.tr("bml.tip.players"),        mouseX, mouseY);
        drawButtonTooltip(ctx, btnClearTargets, com.betterlist.util.BmlLang.tr("bml.tip.clear_targets"),  mouseX, mouseY);
    }

    private void drawButtonTooltip(GuiContext ctx, ButtonGeneric b, String text, int mouseX, int mouseY) {
        if (b == null || text == null) return;
        if (mouseX < b.getX() || mouseX >= b.getX() + b.getWidth()
                || mouseY < b.getY() || mouseY >= b.getY() + b.getHeight()) return;
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int pad = 4;
        int w = font.width(text) + pad * 2;
        int h = 8 + pad * 2;
        int tx = mouseX + 10;
        int ty = mouseY + 10;
        if (tx + w > this.getScreenWidth() - 4)  tx = mouseX - w - 6;
        if (ty + h > this.getScreenHeight() - 4) ty = this.getScreenHeight() - h - 4;
        ctx.fill(tx - 1, ty - 1, tx + w + 1, ty + h + 1, 0xFF000000);
        ctx.fill(tx, ty, tx + w, ty + h, 0xF0202020);
        ctx.drawString(font, text, tx + pad, ty + pad, 0xFFFFFFFF, false);
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
            java.util.List<String> members = getFocusMembers();
            int dpx = btnPlayers.getX();
            int dpw = 150;
            int rowH = 18;
            int headerH = 14;
            int dph = 4 + headerH + members.size() * rowH + 4;
            int dpy = btnPlayers.getY() - dph - 2;
            int rowsTop = dpy + 4 + headerH;
            if (mouseX >= dpx && mouseX < dpx + dpw && mouseY >= dpy && mouseY < dpy + dph) {
                int index = ((int) mouseY - rowsTop) / rowH;
                if (index >= 0 && index < members.size()) {
                    com.betterlist.party.FocusManager.togglePlayerFilter(members.get(index));
                    if (this.getListWidget() != null) this.getListWidget().refreshEntries();
                }
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

        // Right-click on any item to toggle focus/target. Works solo too (the target is
        // local); party sync is only sent when actually in a party.
        boolean isRightDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_2) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (isRightDown && !this.wasRightMouseDown && this.hoveredEntry != null) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(this.hoveredEntry.getStack().getItem()).toString();
            com.betterlist.party.FocusManager.toggleMyTarget(itemId);
            if (com.betterlist.party.PartyManager.isInParty()) {
                com.betterlist.network.BmlClientNetworking.sendTargetUpdate();
            }
            // Refresh the HUD immediately after a target change.
            com.betterlist.data.HudOverlayManager.recompute();
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
                InputHandler.scheduleQuietRecount(this.placements);
                silentRefresh();
            }
        }

        if (isFirstLoad && !this.isCached) {
            triggerFullRefresh();
            isFirstLoad = false;
        }
    }

    // Re-reads via the cache fallback; never blanks the list with an empty read (keeps last good).
    private void silentRefresh() {
        List<MaterialListEntry> fresh = InputHandler.collectEntriesWithCacheFallback(this.placements);
        if (fresh == null || fresh.isEmpty()) return;
        if (Minecraft.getInstance().player != null) {
            InputHandler.applyAvailableCounts(fresh, Minecraft.getInstance().player);
        }
        this.materialList = fresh;
        if (this.getListWidget() != null) this.getListWidget().refreshEntries();
    }

    public void externalRefresh() {
        silentRefresh();
    }

    @Override
    public void onTaskCompleted() {
        if (this.placements != null) {
            List<MaterialListEntry> fresh = InputHandler.collectMaterialsFromPlacements(this.placements);
            if (fresh != null && !fresh.isEmpty()) { // don't blank a good list with an empty result
                if (Minecraft.getInstance().player != null) {
                    InputHandler.applyAvailableCounts(fresh, Minecraft.getInstance().player);
                }
                this.materialList = fresh;
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

    /**
     * Stable key for checkboxes. Computed from the currently ENABLED placements
     * (sorted), so it is independent of order and of how the title label
     * {@link #placementName} looks. See {@link com.betterlist.util.BmlPlacementKeys}.
     */
    public String getChecklistKey() {
        return com.betterlist.util.BmlPlacementKeys.checklistKey(this.placements);
    }

    public boolean isSearchFocused()  { return this.searchField != null && this.searchField.isFocused(); }
}
