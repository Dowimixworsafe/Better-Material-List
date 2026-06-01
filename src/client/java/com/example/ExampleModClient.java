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

	// Handshake: odliczamy ticki po wejściu na świat zamiast tworzyć osobny wątek.
	// -1 = nieaktywne; >=0 = liczy w górę do HELLO_DELAY_TICKS, potem wysyła BML_HELLO.
	private static int helloCountdown = -1;
	private static final int HELLO_DELAY_TICKS = 40; // ~2s at 20 TPS

	// Debounce zapisu danych: flush na dysk co ~1s, jeśli coś się zmieniło.
	private static int saveTick = 0;
	private static final int SAVE_INTERVAL = 20; // ~1s at 20 TPS

	// Przeliczanie HUD zaznaczonych itemów (gdy włączony) co ~1s.
	private static int hudTick = 0;
	private static final int HUD_INTERVAL = 20;

	// Kolor ramki podświetlonych skrzyń (jasny zielony, pełne alpha).
	private static final Color4f CHEST_HIGHLIGHT_COLOR = Color4f.fromColor(0x55FF55, 1.0f);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[BetterMaterialList] Client initializing...");

		// Konfiguracja ogólna malilib
		ModConfig config = new ModConfig();
		ConfigManager.getInstance().registerConfigHandler("bettermateriallist", config);
		config.load();

		// Klawiszologia
		InputHandler.getInstance().registerKeyCallbacks();
		InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

		// Rejestracja receivera dla pakietów BML (party + sync)
		BmlClientNetworking.registerReceiver();

		// Rejestracja eventów sieciowych
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("[BetterMaterialList] Joined world. Loading data...");
			ContainerDataManager.load();
			MaterialStateManager.load();
			com.example.party.FocusManager.load(); // moje targety + flaga HUD (per serwer)
			// Handshake – sprawdza czy serwer ma BML Mod/Plugin. Odraczamy o ~2s
			// (na ticku klienta, bez osobnego wątku), żeby serwer zdążył się zainicjować.
			helloCountdown = 0;
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LOGGER.info("[BetterMaterialList] Disconnected. Saving and clearing data...");
			ContainerDataManager.flush();
			MaterialStateManager.flush();
			// Czyścimy pamięć, by nie brudzić stanu przy zmianie serwera
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

		// Podświetlanie śledzonych skrzyń w świecie (przez ściany), sterowane z GuiBmlChests.
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
			if (ChestHighlightManager.all().isEmpty()) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;

			String currentDim = mc.level.dimension().identifier().toString();
			float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

			for (String containerId : ChestHighlightManager.all()) {
				// Rysujemy tylko skrzynie z bieżącego wymiaru (kordy z innego wymiaru
				// nie mają sensu w obecnej kamerze).
				String dim = ChestHighlightManager.dimensionOf(containerId);
				if (dim != null && !dim.equals(currentDim)) continue;
				BlockPos pos = ChestHighlightManager.posOf(containerId);
				if (pos == null) continue;
				// true = render przez ściany (pipeline NO_DEPTH).
				RenderUtils.renderBlockOutline(pos, 0.002f, 2.0f, CHEST_HIGHLIGHT_COLOR, true);
			}
		});

		// HUD w prawym górnym rogu: zaznaczone (targetowane) itemy, których brakuje.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("bml", "targeted_items_hud"),
				(drawContext, tickCounter) -> renderTargetedItemsHud(drawContext));

		// Schematic placement autosync: detect moves/adds/removes and broadcast to party
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Handshake po wejściu na świat (zastępuje dawny new Thread + sleep).
			if (helloCountdown >= 0) {
				if (++helloCountdown >= HELLO_DELAY_TICKS) {
					helloCountdown = -1;
					BmlClientNetworking.sendHello();
				}
			}

			// Debounce zapisu danych — niezależny od party/serverSupported.
			if (++saveTick >= SAVE_INTERVAL) {
				saveTick = 0;
				ContainerDataManager.flush();
				MaterialStateManager.flush();
			}

			// Przeliczanie HUD zaznaczonych itemów (tylko gdy włączony).
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
	 * Rysuje HUD zaznaczonych (targetowanych) itemów w prawym górnym rogu: ikona + ile
	 * mam / ile potrzeba. Niewidoczny gdy wyłączony, gdy gracz jest w GUI/F3, albo gdy
	 * brak wierszy.
	 */
	private static void renderTargetedItemsHud(net.minecraft.client.gui.GuiGraphicsExtractor drawContext) {
		if (!HudOverlayManager.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) return;
		// Nie zasłaniaj ekranów (np. otwartej listy materiałów) — HUD tylko w grze.
		if (mc.screen != null) return;

		List<HudOverlayManager.Row> rows = HudOverlayManager.getRows();
		if (rows.isEmpty()) return;

		GuiContext ctx = GuiContext.fromGuiGraphics(drawContext);
		var font = mc.font;

		int rowH = 18;
		int pad = 4;
		int iconW = 16;
		// Szerokość panelu = ikona + najszerszy tekst "have / need".
		int maxTextW = font.width("Targety");
		for (HudOverlayManager.Row r : rows) {
			maxTextW = Math.max(maxTextW, font.width(r.have() + " / " + (r.have() + r.need())));
		}
		int panelW = pad + iconW + 4 + maxTextW + pad;
		int panelH = pad + 12 + rows.size() * rowH + pad;

		int screenW = drawContext.guiWidth();
		int x = screenW - panelW - 4;
		int y = 4;

		// Tło.
		ctx.fill(x, y, x + panelW, y + panelH, 0xC0101010);
		ctx.fill(x, y, x + panelW, y + 1, 0xFF55FF55);
		ctx.drawString(font, "§aTargety §7(" + rows.size() + ")", x + pad, y + pad, 0xFFFFFFFF, false);

		int ry = y + pad + 12;
		for (HudOverlayManager.Row r : rows) {
			ItemStack stack = r.stack();
			ctx.renderItem(stack, x + pad, ry);
			ctx.renderItemDecorations(font, stack, x + pad, ry);
			int total = r.have() + r.need();
			// have/total: czerwone gdy czegoś brak.
			String txt = "§f" + r.have() + " §7/ §f" + total;
			ctx.drawString(font, txt, x + pad + iconW + 4, ry + (iconW - 8) / 2, 0xFFFFFFFF, false);
			ry += rowH;
		}
	}
}
