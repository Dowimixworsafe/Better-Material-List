package com.betterlist.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Single source of truth for the server id used as the data-file prefix.
 *
 * This logic used to be duplicated in {@code ContainerDataManager} and
 * {@code MaterialStateManager}, which risked the keys diverging. Here it is also
 * hardened: the IP is lowercased so that connecting via the server list and via
 * "direct connect" to the same address resolves to the same file.
 */
@Environment(EnvType.CLIENT)
public final class BmlServerId {

    private BmlServerId() {}

    /** Returns a stable id for the current server (or "singleplayer"). */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            String ip = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            return "mp_" + ip.replaceAll("[^a-z0-9_\\-]", "_");
        }
        return "singleplayer";
    }
}
