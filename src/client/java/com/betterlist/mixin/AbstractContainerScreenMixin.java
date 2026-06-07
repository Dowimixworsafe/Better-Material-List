package com.betterlist.mixin;

import com.betterlist.data.ContainerDataManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends net.minecraft.client.gui.screens.Screen {

    @Unique
    protected Button bml_TrackingButtonInstance;

    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    // Builds a unique chest id: dimension + coordinates (e.g. minecraft:overworld;[10, 64, -20]).
    // Merges double chests into a single ID!
    private String getContainerId() {
        if (ContainerDataManager.lastInteractedBlockPos == null || Minecraft.getInstance().level == null) {
            return null;
        }

        net.minecraft.core.BlockPos pos = ContainerDataManager.lastInteractedBlockPos;
        net.minecraft.world.level.block.state.BlockState state = Minecraft.getInstance().level.getBlockState(pos);

        // If it is a chest (ChestBlock).
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            net.minecraft.world.level.block.state.properties.ChestType chestType = state
                    .getValue(net.minecraft.world.level.block.ChestBlock.TYPE);

            // If it is not a single chest.
            if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                net.minecraft.core.Direction facing = state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                net.minecraft.core.BlockPos otherHalfPos = pos;

                // Compute the other half's position from the facing.
                if (chestType == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
                    otherHalfPos = pos.relative(facing.getCounterClockWise());
                } else if (chestType == net.minecraft.world.level.block.state.properties.ChestType.LEFT) {
                    otherHalfPos = pos.relative(facing.getClockWise());
                }

                // Always pick the smaller BlockPos (smaller X, ties broken by smaller
                // Z)
                // so both halves produce an identical ID.
                if (otherHalfPos.getX() < pos.getX()
                        || (otherHalfPos.getX() == pos.getX() && otherHalfPos.getZ() < pos.getZ())) {
                    pos = otherHalfPos;
                }
            }
        }

        return Minecraft.getInstance().level.dimension().identifier().toString() + ";" + pos.toShortString();
    }

    // Only chests / shulker boxes / hoppers are trackable containers. The player's own
    // inventory, crafting tables, furnaces, anvils, etc. must NEVER be scanned — their slots
    // would otherwise be written into the last-clicked chest's id and wipe its real contents.
    @Unique
    private boolean bml_isTrackableContainer() {
        Object screen = (Object) this;
        return screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen ||
                screen instanceof net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen ||
                screen instanceof net.minecraft.client.gui.screens.inventory.HopperScreen;
    }

    @Inject(method = "init", at = @At("RETURN"))
    protected void onInit(CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;

        // Avoid injecting into furnaces, anvils, crafting tables, etc.
        if (!bml_isTrackableContainer())
            return;

        String cid = getContainerId();
        if (cid == null)
            return;

        int guiLeft = (screen.width - 176) / 2;
        int guiTop = (screen.height - 166) / 2;

        int btnWidth = 20;
        int btnHeight = 20;
        int btnX = guiLeft + 176 - btnWidth;
        int btnY = guiTop - 50; // Button sits above the player inventory, on the right.

        this.bml_TrackingButtonInstance = Button.builder(Component.literal(""), button -> {
            boolean marked = !ContainerDataManager.isContainerMarked(cid);
            ContainerDataManager.setContainerMarked(cid, marked);
        }).bounds(btnX, btnY, btnWidth, btnHeight).build();

        this.addRenderableWidget(this.bml_TrackingButtonInstance);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    public void onRender(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.bml_TrackingButtonInstance != null && this.bml_TrackingButtonInstance.visible) {
            String cid = getContainerId();
            if (cid != null) {
                boolean isMarked = ContainerDataManager.isContainerMarked(cid);
                if (isMarked) {
                    guiGraphics.item(new ItemStack(net.minecraft.world.item.Items.REDSTONE_TORCH), this.bml_TrackingButtonInstance.getX() + 2, this.bml_TrackingButtonInstance.getY() + 2);
                }
            }
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    public void onRemoved(CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;

        // Same guard as onInit: closing the player inventory / a crafting table / furnace
        // must not scan its slots into the last-clicked chest's id (would zero it out).
        if (!bml_isTrackableContainer())
            return;

        String cid = getContainerId();
        if (cid == null)
            return;

        // Save only if the chest is currently marked for tracking.
        if (!ContainerDataManager.isContainerMarked(cid)) {
            return;
        }

        Map<String, Integer> contents = new HashMap<>();
        var slots = screen.getMenu().slots;

        // Guard (Math.max) to avoid errors with weird custom GUIs
        // from other mods.
        // Drop the last 36 slots since they always belong to the player inventory
        // (we don't want to count them as chest contents).
        int containerSlotsEnd = Math.max(0, slots.size() - 36);

        for (int i = 0; i < containerSlotsEnd; i++) {
            Slot slot = slots.get(i);
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                contents.put(key, contents.getOrDefault(key, 0) + stack.getCount());
            }
        }

        ContainerDataManager.updateContainerItems(cid, contents);
    }
}