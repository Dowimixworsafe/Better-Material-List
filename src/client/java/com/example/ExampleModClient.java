package com.example;

import com.example.config.ModConfig;
import com.example.data.ContainerDataManager;
import com.example.data.MaterialStateManager;
import com.example.input.InputHandler;
import com.example.network.BmlClientNetworking;
import com.example.party.PartyManager;
import com.example.party.PlacementSyncHelper;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class ExampleModClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("BetterMaterialList");

	// Schematic placement autosync state
	private static final Map<String, BlockPos> placementSnapshot = new HashMap<>();
	private static int placementCheckTick = 0;
	private static final int PLACEMENT_CHECK_INTERVAL = 100; // ~5s at 20 TPS

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
			// Wyślij handshake – sprawdza czy serwer ma BML Mod/Plugin
			// Odroczenie o 2 sekundy żeby serwer zdążył się zainicjować
			new Thread(() -> {
				try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
				client.execute(BmlClientNetworking::sendHello);
			}, "BML-Hello").start();
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LOGGER.info("[BetterMaterialList] Disconnected. Saving and clearing data...");
			ContainerDataManager.save();
			MaterialStateManager.save();
			// Czyścimy pamięć, by nie brudzić stanu przy zmianie serwera
			ContainerDataManager.clear();
			MaterialStateManager.clear();
			// Reset stanu party i flagi serverSupported
			BmlClientNetworking.serverSupported = false;
			PartyManager.reset();
			// Reset snapshot for placement autosync
			placementSnapshot.clear();
			placementCheckTick = 0;
		});

		// Schematic placement autosync: detect moves/adds/removes and broadcast to party
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
}
