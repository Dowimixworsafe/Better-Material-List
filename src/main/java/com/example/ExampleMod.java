package com.example;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "bettermateriallist";
	public static final Logger LOGGER = LoggerFactory.getLogger("BetterMaterialList");

	@Override
	public void onInitialize() {
		LOGGER.info("[BetterMaterialList] Main mod initialized!");

		// Register the CustomPacketPayload type for both directions.
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(com.example.network.BmlPackets.BmlPayload.TYPE, com.example.network.BmlPackets.BmlPayload.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(com.example.network.BmlPackets.BmlPayload.TYPE, com.example.network.BmlPackets.BmlPayload.CODEC);

		// Server-side relay that forwards packets between party members.
		com.example.server.BmlServerNetworking.register();
	}
}