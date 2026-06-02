package com.betterlist;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "betterlist";
	public static final Logger LOGGER = LoggerFactory.getLogger("BetterList");

	@Override
	public void onInitialize() {
		LOGGER.info("[BetterList] Main mod initialized!");

		// Register the CustomPacketPayload type for both directions.
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(com.betterlist.network.BmlPackets.BmlPayload.TYPE, com.betterlist.network.BmlPackets.BmlPayload.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(com.betterlist.network.BmlPackets.BmlPayload.TYPE, com.betterlist.network.BmlPackets.BmlPayload.CODEC);

		// Server-side relay that forwards packets between party members.
		com.betterlist.server.BmlServerNetworking.register();
	}
}