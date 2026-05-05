package com.example;

import com.example.config.ModConfig;
import com.example.data.ContainerDataManager;
import com.example.data.MaterialStateManager;
import com.example.input.InputHandler;
import com.example.network.BmlClientNetworking;
import com.example.party.PartyManager;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class ExampleModClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("BetterMaterialList");

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
		});

		LOGGER.info("[BetterMaterialList] Client initialized! Press PERIOD (.) to open material list GUI.");
	}
}