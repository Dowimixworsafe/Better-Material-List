package com.example.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * Tiny localization helper. The mod's GUIs (malilib) work with raw {@code String}s, so we
 * resolve translation keys to strings via {@link I18n}. Keys live in
 * {@code assets/bettermateriallist/lang/<locale>.json}; English (en_us) is the default and
 * players switch language in Options → Language.
 */
@Environment(EnvType.CLIENT)
public final class BmlLang {

    private BmlLang() {}

    /** Translated string for {@code key} with optional format args. */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    /** Translated component (for chat messages / system messages). */
    public static Component comp(String key, Object... args) {
        return Component.literal(I18n.get(key, args));
    }
}
