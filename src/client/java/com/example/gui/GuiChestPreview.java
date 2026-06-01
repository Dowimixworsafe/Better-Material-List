package com.example.gui;

import com.example.data.ContainerDataManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Podgląd zapamiętanej zawartości śledzonej skrzyni (z {@link ContainerDataManager}).
 * Renderuje sloty jak prawdziwa skrzynia, używając malilib {@link InventoryOverlay}.
 *
 * Dane pochodzą z ostatniego skanu skrzyni (to samo, co liczy się do "stored"), więc
 * działa nawet gdy skrzynia jest daleko / chunk niezaładowany.
 */
@Environment(EnvType.CLIENT)
public class GuiChestPreview extends GuiBase {

    private final String containerId;
    private final String returnPlacementName;
    private SimpleContainer container;
    private InventoryOverlayType type = InventoryOverlayType.GENERIC;
    private InventoryOverlay.InventoryProperties props;

    public GuiChestPreview(String containerId, String returnPlacementName) {
        this.containerId = containerId;
        this.returnPlacementName = returnPlacementName;
        this.title = "Zawartość skrzyni";
        buildContainer();
    }

    /** Zamienia zapamiętaną mapę item->count na SimpleContainer (po 64 na slot). */
    private void buildContainer() {
        Map<String, Integer> contents = ContainerDataManager.getContainerContents(this.containerId);

        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(Identifier.parse(e.getKey()))
                    .map(ref -> ref.value()).orElse(null);
            if (item == null) continue;
            int remaining = e.getValue();
            int max = Math.max(1, new ItemStack(item).getMaxStackSize());
            while (remaining > 0) {
                int n = Math.min(max, remaining);
                stacks.add(new ItemStack(item, n));
                remaining -= n;
            }
        }

        // Skrzynia ma 27 slotów; podwójna 54. Dobieramy najmniejszy pasujący rozmiar
        // (wielokrotność 27), żeby zmieścić wszystkie stacki.
        int size = 27;
        while (stacks.size() > size) size += 27;
        this.type = InventoryOverlayType.GENERIC;

        this.container = new SimpleContainer(size);
        for (int i = 0; i < stacks.size() && i < size; i++) {
            this.container.setItem(i, stacks.get(i));
        }
        this.props = InventoryOverlay.getInventoryPropsTemp(this.type, this.container.getContainerSize());
    }

    @Override
    public void initGui() {
        super.initGui();
        ButtonGeneric btnBack = new ButtonGeneric(6, 6, 60, 20, "§e← Wróć");
        this.addButton(btnBack, (b, mb) -> GuiBase.openGui(new GuiBmlChests(this.returnPlacementName)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(drawContext, mouseX, mouseY, partialTicks);
        GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);

        if (this.container == null || this.props == null) return;

        int x = (this.width - this.props.width) / 2;
        int y = Math.max(40, (this.height - this.props.height) / 2);

        String coordLabel = this.containerId;
        if (coordLabel.contains(";")) {
            String[] parts = coordLabel.split(";");
            if (parts.length >= 2)
                coordLabel = parts[1] + " (" + parts[0].replace("minecraft:", "") + ")";
        }
        ctx.drawString(this.font, "§e" + coordLabel, x, y - 12, 0xFFFFFFFF, false);

        InventoryOverlay.renderInventoryBackground(ctx, this.type, x, y,
                this.props.slotsPerRow, this.container.getContainerSize());
        InventoryOverlay.renderInventoryStacks(ctx, this.type, this.container,
                x + this.props.slotOffsetX, y + this.props.slotOffsetY,
                this.props.slotsPerRow, 0, this.container.getContainerSize());

        if (this.container.isEmpty()) {
            String msg = "§7(Skrzynia pusta lub jeszcze niezeskanowana)";
            ctx.drawString(this.font, msg, x, y + this.props.height + 6, 0xFFFFFFFF, false);
        }
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // Tytuł wyśrodkowany u góry — spójnie z innymi podekranami, omija przycisk "Wróć".
        String t = this.getTitleString();
        int tx = (this.getScreenWidth() - this.getStringWidth(t)) / 2;
        this.drawString(ctx, t, tx, 8, -1);
    }
}
