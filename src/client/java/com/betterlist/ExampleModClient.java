package com.betterlist;

import com.betterlist.config.ModConfig;
import com.betterlist.data.ChestHighlightManager;
import com.betterlist.data.ContainerDataManager;
import com.betterlist.data.HudOverlayManager;
import com.betterlist.data.MaterialStateManager;
import com.betterlist.input.InputHandler;
import com.betterlist.network.BmlClientNetworking;
import com.betterlist.party.PartyManager;
import com.betterlist.party.PlacementSyncHelper;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class ExampleModClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("BetterList");

	// Schematic placement autosync state
	private static final Map<String, BlockPos> placementSnapshot = new HashMap<>();
	private static int placementCheckTick = 0;
	private static final int PLACEMENT_CHECK_INTERVAL = 100; // ~5s at 20 TPS

	// Handshake: we count ticks after joining the world instead of spawning a thread.
	// -1 = inactive; >=0 = counts up to HELLO_DELAY_TICKS, then sends BML_HELLO.
	private static int helloCountdown = -1;
	private static final int HELLO_DELAY_TICKS = 40; // ~2s at 20 TPS

	// Write debounce: flush to disk every ~1s if something changed.
	private static int saveTick = 0;
	private static final int SAVE_INTERVAL = 20; // ~1s at 20 TPS

	// Recompute the targeted-items HUD (when enabled) every ~1s.
	private static int hudTick = 0;
	private static final int HUD_INTERVAL = 20;
	private static int autoRecountTick = 0;
	private static final int AUTO_RECOUNT_INTERVAL = 200;

	// Last dimension seen, to detect an in-place world swap (which does NOT fire DISCONNECT).
	private static String lastDimensionId = null;

	// Outline color for highlighted chests (bright green, full alpha).
	private static final Color4f CHEST_HIGHLIGHT_COLOR = Color4f.fromColor(0x55FF55, 1.0f);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[BetterList] Client initializing...");

		// General malilib config.
		ModConfig config = new ModConfig();
		ConfigManager.getInstance().registerConfigHandler("betterlist", config);
		config.load();

		// Keybinds
		InputHandler.getInstance().registerKeyCallbacks();
		InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

		// Register the receiver for BML packets (party + sync).
		BmlClientNetworking.registerReceiver();

		// Register networking lifecycle events.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("[BetterList] Joined world. Loading data...");
			ContainerDataManager.load();
			MaterialStateManager.load();
			com.betterlist.party.FocusManager.load(); // my targets + HUD flag (per server)
			// Handshake — detect whether the server has the BML mod/plugin. Deferred ~2s
			// (on a client tick, no separate thread) so the server has time to initialize.
			helloCountdown = 0;
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LOGGER.info("[BetterList] Disconnected. Saving and clearing data...");
			ContainerDataManager.flush();
			MaterialStateManager.flush();
			// Clear memory so state doesn't leak across servers.
			ContainerDataManager.clear();
			MaterialStateManager.clear();
			// Reset party state + serverSupported flag
			BmlClientNetworking.serverSupported = false;
			PartyManager.reset();
			// Reset snapshot for placement autosync
			placementSnapshot.clear();
			placementCheckTick = 0;
			helloCountdown = -1;
			saveTick = 0;
			hudTick = 0;
			lastDimensionId = null;
			InputHandler.clearLastGoodEntries();
			ChestHighlightManager.clear();
			HudOverlayManager.disable();
		});

		// In-world highlighting of tracked chests (through walls), driven by GuiBmlChests.
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
			if (ChestHighlightManager.all().isEmpty()) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;

			String currentDim = mc.level.dimension().identifier().toString();
			float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

			for (String containerId : ChestHighlightManager.all()) {
				// Draw only chests in the current dimension (coords from another dimension
				// make no sense for the current camera).
				String dim = ChestHighlightManager.dimensionOf(containerId);
				if (dim != null && !dim.equals(currentDim)) continue;
				BlockPos pos = ChestHighlightManager.posOf(containerId);
				if (pos == null) continue;
				// true = render through walls (NO_DEPTH pipeline).
				RenderUtils.renderBlockOutline(pos, 0.002f, 2.0f, CHEST_HIGHLIGHT_COLOR, true);
			}
		});

		// Top-right HUD: targeted items that are still missing.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("bml", "targeted_items_hud"),
				(drawContext, tickCounter) -> renderTargetedItemsHud(drawContext));

		// Schematic placement autosync: detect moves/adds/removes and broadcast to party
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Handshake after joining the world (replaces the old new Thread + sleep).
			if (helloCountdown >= 0) {
				if (++helloCountdown >= HELLO_DELAY_TICKS) {
					helloCountdown = -1;
					BmlClientNetworking.sendHello();
				}
			}

			// Write debounce — independent of party/serverSupported.
			if (++saveTick >= SAVE_INTERVAL) {
				saveTick = 0;
				ContainerDataManager.flush();
				MaterialStateManager.flush();
			}

			// On a dimension swap, re-read so the list and HUD repopulate promptly (no state cleared).
			if (client.level != null) {
				String dim = client.level.dimension().identifier().toString();
				if (lastDimensionId != null && !lastDimensionId.equals(dim)) {
					HudOverlayManager.recompute();
					if (client.screen instanceof com.betterlist.gui.GuiBetterMaterialList gui) {
						gui.externalRefresh();
					}
				}
				lastDimensionId = dim;
			}

			// Recompute the targeted-items HUD (only when enabled).
			if (HudOverlayManager.isEnabled() && ++hudTick >= HUD_INTERVAL) {
				hudTick = 0;
				HudOverlayManager.recompute();
			}

			if (HudOverlayManager.isEnabled() && client.screen == null
					&& com.betterlist.gui.GuiBetterMaterialList.isAutoRefreshEnabled()
					&& ++autoRecountTick >= AUTO_RECOUNT_INTERVAL) {
				autoRecountTick = 0;
				com.betterlist.input.InputHandler.scheduleQuietRecount(
						fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
			}

			if (!BmlClientNetworking.serverSupported) return;
			if (!PartyManager.isInParty()) return;
			if (client.level == null) return;

			if (++placementCheckTick < PLACEMENT_CHECK_INTERVAL) return;
			placementCheckTick = 0;

			Map<String, BlockPos> current = new HashMap<>();
			try {
				var all = fi.dy.masa.litematica.data.DataManager
						.getSchematicPlacementManager().getAllSchematicsPlacements();
				if (all != null) {
					for (var p : all) {
						if (p.isEnabled()) current.put(p.getName(), p.getOrigin());
					}
				}
			} catch (Exception e) {
				return;
			}

			if (!current.equals(placementSnapshot)) {
				placementSnapshot.clear();
				placementSnapshot.putAll(current);
				LOGGER.info("[BetterList] Placement change detected — auto-syncing to party.");
				PlacementSyncHelper.sendAllPlacements();
			}
		});

		LOGGER.info("[BetterList] Client initialized! Press PERIOD (.) to open material list GUI.");
	}

	/**
	 * Draws the targeted-items HUD in the top-right corner: icon + how much
	 * I have / how much I need. Hidden when disabled, when in a screen/F3, or when
	 * there are no rows.
	 */
	private static void renderTargetedItemsHud(net.minecraft.client.gui.GuiGraphicsExtractor drawContext) {
		if (!HudOverlayManager.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) return;
		// Don't cover screens (e.g. the open material list) — HUD only in-game.
		if (mc.screen != null) return;

		List<HudOverlayManager.Row> rows = HudOverlayManager.getRows();
		if (rows.isEmpty()) return;

		GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);
		var font = mc.font;

		int rowH = 18;
		int pad = 4;
		int iconW = 16;
		// Title + scroll arrows (only when there are more targets above/below the window).
		String arrows = (HudOverlayManager.hasMoreAbove() ? " ▲" : "") + (HudOverlayManager.hasMoreBelow() ? " ▼" : "");
		String title = "§a" + com.betterlist.util.BmlLang.tr("bml.hud.title") + "§e" + arrows;
		int maxTextW = font.width(title);
		for (HudOverlayManager.Row r : rows) {
			maxTextW = Math.max(maxTextW, font.width(Math.min(r.have(), r.required()) + " / " + r.required()));
		}
		int panelW = pad + iconW + 4 + maxTextW + pad;
		int panelH = pad + 12 + rows.size() * rowH + pad;

		int screenW = drawContext.guiWidth();
		int x = screenW - panelW - 4;
		int y = 4;

		// Background.
		ctx.fill(x, y, x + panelW, y + panelH, 0xC0101010);
		ctx.fill(x, y, x + panelW, y + 1, 0xFF55FF55);
		ctx.drawString(font, title, x + pad, y + pad, 0xFFFFFFFF, false);

		int ry = y + pad + 12;
		for (HudOverlayManager.Row r : rows) {
			ItemStack stack = r.stack();
			ctx.renderItem(stack, x + pad, ry);
			ctx.renderItemDecorations(font, stack, x + pad, ry);
			int shown = Math.min(r.have(), r.required());
			String txt = r.done()
					? "§a" + shown + " §7/ §a" + r.required()
					: "§f" + shown + " §7/ §f" + r.required();
			ctx.drawString(font, txt, x + pad + iconW + 4, ry + (iconW - 8) / 2, 0xFFFFFFFF, false);
			ry += rowH;
		}
	}
}
