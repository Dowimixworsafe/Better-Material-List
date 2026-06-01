package com.example.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Jedno źródło prawdy dla identyfikatora serwera używanego jako prefiks plików danych.
 *
 * Wcześniej ta logika była zduplikowana w {@code ContainerDataManager} i
 * {@code MaterialStateManager}, co groziło rozjechaniem się kluczy. Tutaj jest
 * dodatkowo utwardzona: IP normalizujemy do małych liter, dzięki czemu połączenie
 * przez listę serwerów i przez "direct connect" na ten sam adres trafia w ten sam plik.
 */
@Environment(EnvType.CLIENT)
public final class BmlServerId {

    private BmlServerId() {}

    /** Zwraca stabilny identyfikator bieżącego serwera (lub "singleplayer"). */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            String ip = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            return "mp_" + ip.replaceAll("[^a-z0-9_\\-]", "_");
        }
        return "singleplayer";
    }
}
