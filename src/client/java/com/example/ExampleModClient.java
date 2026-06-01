package com.example;

import com.example.config.ModConfig;
import com.example.data.ChestHighlightManager;
import com.example.data.ContainerDataManager;
import com.example.data.HudOverlayManager;
import com.example.data.MaterialStateManager;
import com.example.input.InputHandler;
import com.example.network.BmlClientNetworking;
import com.example.party.PartyManager;
import com.example.party.PlacementSyncHelper;
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
	private static final Logger LOGGER = LoggerFactory.getLogger("BetterMaterialList");

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

	// Outline color for highlighted chests (bright green, full alpha).
	private static final Color4f CHEST_HIGHLIGHT_COLOR = Color4f.fromColor(0x55FF55, 1.0f);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[BetterMaterialList] Client initializing...");

		// General malilib config.
		ModConfig config = new ModConfig();
		ConfigManager.getInstance().registerConfigHandler("bettermateriallist", config);
		config.load();

		// Klawiszologia
		InputHandler.getInstance().registerKeyCallbacks();
		InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

		// Register the receiver for BML packets (party + sync).
		BmlClientNetworking.registerReceiver();

		// Register networking lifecycle events.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("[BetterMaterialList] Joined world. Loading data...");
			ContainerDataManager.load();
			MaterialStateManager.load();
			com.example.party.FocusManager.load(); // moje targety + flaga HUD (per serwer)
			// Handshake – sprawdza czy serwer ma BML Mod/Plugin. Odraczamy o ~2s
			// (on a client tick, no separate thread) so the server has time to initialize.
			helloCountdown = 0;
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LOGGER.info("[BetterMaterialList] Disconnected. Saving and clearing data...");
			ContainerDataManager.flush();
			MaterialStateManager.flush();
			// Clear memory so state doesn't leak across servers.
			ContainerDataManager.clear();
			MaterialStateManager.clear();
			// Reset stanu party i flagi serverSupported
			BmlClientNetworking.serverSupported = false;
			PartyManager.reset();
			// Reset snapshot for placement autosync
			placementSnapshot.clear();
			placementCheckTick = 0;
			helloCountdown = -1;
			saveTick = 0;
			hudTick = 0;
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

			// Recompute the targeted-items HUD (only when enabled).
			if (HudOverlayManager.isEnabled() && ++hudTick >= HUD_INTERVAL) {
				hudTick = 0;
				HudOverlayManager.recompute();
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
				LOGGER.info("[BetterMaterialList] Placement change detected — auto-syncing to party.");
				PlacementSyncHelper.sendAllPlacements();
			}
		});

		LOGGER.info("[BetterMaterialList] Client initialized! Press PERIOD (.) to open material list GUI.");
	}

	/**
	 * Draws the targeted-items HUD in the top-right corner: icon + how much
	 * I have / how much I need. Hidden when disabled, when in a screen/F3, or when
	 * brak wierszy.
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
		// Panel width = icon + widest "have / need" text.
		int maxTextW = font.width("Targety");
		for (HudOverlayManager.Row r : rows) {
			maxTextW = Math.max(maxTextW, font.width(r.have() + " / " + (r.have() + r.need())));
		}
		int panelW = pad + iconW + 4 + maxTextW + pad;
		int panelH = pad + 12 + rows.size() * rowH + pad;

		int screenW = drawContext.guiWidth();
		int x = screenW - panelW - 4;
		int y = 4;

		// Background.
		ctx.fill(x, y, x + panelW, y + panelH, 0xC0101010);
		ctx.fill(x, y, x + panelW, y + 1, 0xFF55FF55);
		ctx.drawString(font, "§a" + com.example.util.BmlLang.tr("bml.hud.title") + " §7(" + rows.size() + ")", x + pad, y + pad, 0xFFFFFFFF, false);

		int ry = y + pad + 12;
		for (HudOverlayManager.Row r : rows) {
			ItemStack stack = r.stack();
			ctx.renderItem(stack, x + pad, ry);
			ctx.renderItemDecorations(font, stack, x + pad, ry);
			int total = r.have() + r.need();
			// have/total.
			String txt = "§f" + r.have() + " §7/ §f" + total;
			ctx.drawString(font, txt, x + pad + iconW + 4, ry + (iconW - 8) / 2, 0xFFFFFFFF, false);
			ry += rowH;
		}
	}
}
