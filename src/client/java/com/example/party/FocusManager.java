package com.example.party;

import com.example.data.HudOverlayManager;
import com.example.util.BmlServerId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

/**
 * Tracks which party members are "targeting" (focusing on) which items.
 * Right-click on a material list entry toggles your own target; state syncs via PARTY_TARGET_UPDATE.
 *
 * Persystencja: zestaw MOICH targetów + flaga HUD są zapisywane per serwer w
 * {@code <serverId>_focus.json}. Targety innych graczy NIE są zapisywane (przychodzą
 * przez sync). Load następuje na JOIN, save na każdej lokalnej zmianie i na disconnect.
 */
@Environment(EnvType.CLIENT)
public class FocusManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Focus");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Up to 8 distinct ARGB colors for party members
    private static final int[] PLAYER_COLORS = {
        0xFFFF6B6B, // red
        0xFF6BCB77, // green
        0xFF4D96FF, // blue
        0xFFFFD93D, // yellow
        0xFFFF922B, // orange
        0xFFCC5DE8, // purple
        0xFF20C997, // teal
        0xFFFF8FA3, // pink
    };

    // playerNick → set of item IDs they're targeting
    private static final Map<String, Set<String>> partyTargets = new LinkedHashMap<>();
    // players whose targets are hidden by local toggle
    private static final Set<String> hiddenPlayers = new HashSet<>();
    // players selected as a LIST FILTER: when non-empty, the material list shows only
    // items targeted by at least one of these players (union). Separate from hiddenPlayers
    // (which only affects head icons).
    private static final Set<String> filterPlayers = new HashSet<>();
    // playerNick → stable color index
    private static final Map<String, Integer> colorAssignment = new LinkedHashMap<>();
    // 3-mode focus: 0=Off, 1=Mine, 2=All
    public static final int MODE_OFF  = 0;
    public static final int MODE_MINE = 1;
    public static final int MODE_ALL  = 2;
    private static int focusMode = MODE_OFF;

    // ── State mutations ───────────────────────────────────────────────────────

    public static void setPlayerTargets(String nick, Set<String> itemIds) {
        partyTargets.put(nick, new HashSet<>(itemIds));
        ensureColor(nick);
    }

    public static void toggleMyTarget(String itemId) {
        String self = selfNick();
        if (self == null) return;
        Set<String> mine = partyTargets.computeIfAbsent(self, k -> new HashSet<>());
        if (!mine.remove(itemId)) mine.add(itemId);
        ensureColor(self);
        save();
    }

    public static void togglePlayerHidden(String nick) {
        if (!hiddenPlayers.remove(nick)) hiddenPlayers.add(nick);
    }

    /** Włącza/wyłącza danego gracza jako filtr listy materiałów. */
    public static void togglePlayerFilter(String nick) {
        if (!filterPlayers.remove(nick)) filterPlayers.add(nick);
    }

    public static boolean isPlayerFiltered(String nick) { return filterPlayers.contains(nick); }

    public static boolean isListFilterActive() { return !filterPlayers.isEmpty(); }

    /**
     * Czy dany item ma być pokazany przy aktywnym filtrze graczy. Zwraca true gdy filtr
     * nieaktywny, albo gdy któryś z wybranych graczy targetuje ten item.
     */
    public static boolean passesPlayerFilter(String itemId) {
        if (filterPlayers.isEmpty()) return true;
        for (String nick : filterPlayers) {
            Set<String> t = partyTargets.get(nick);
            if (t != null && t.contains(itemId)) return true;
        }
        return false;
    }

    public static void cycleFocusMode() { focusMode = (focusMode + 1) % 3; }

    public static void clear() {
        partyTargets.clear();
        hiddenPlayers.clear();
        filterPlayers.clear();
        colorAssignment.clear();
        focusMode = MODE_OFF;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public static int getFocusMode() { return focusMode; }
    public static boolean isFocusVisible() { return focusMode > MODE_OFF; }

    public static boolean isPlayerHidden(String nick) { return hiddenPlayers.contains(nick); }

    public static boolean isLocalPlayerTargeting(String itemId) {
        if (focusMode == MODE_OFF) return false;
        return isLocalPlayerTargetingRaw(itemId);
    }

    /** Czy lokalny gracz targetuje item — BEZ zależności od trybu focus (dla borderu). */
    public static boolean isLocalPlayerTargetingRaw(String itemId) {
        String self = selfNick();
        if (self == null) return false;
        Set<String> mine = partyTargets.get(self);
        return mine != null && mine.contains(itemId);
    }

    public static Set<String> getMyTargets() {
        String self = selfNick();
        if (self == null) return Collections.emptySet();
        return Collections.unmodifiableSet(partyTargets.getOrDefault(self, Collections.emptySet()));
    }

    /**
     * Returns (nick, color) for each VISIBLE party member targeting this item (MODE_ALL only),
     * excluding the local player (shown separately via icon border).
     */
    public static List<PlayerFocus> getTargetersWithNames(String itemId) {
        if (focusMode != MODE_ALL) return Collections.emptyList();
        String self = selfNick();
        List<PlayerFocus> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : partyTargets.entrySet()) {
            String nick = e.getKey();
            if (nick.equalsIgnoreCase(self)) continue;
            if (hiddenPlayers.contains(nick)) continue;
            if (e.getValue().contains(itemId))
                result.add(new PlayerFocus(nick, getColor(nick)));
        }
        return result;
    }

    /** Returns all party members targeting this item regardless of mode/hidden — for hover tooltip. */
    public static List<PlayerFocus> getTargetersForTooltip(String itemId) {
        String self = selfNick();
        List<PlayerFocus> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : partyTargets.entrySet()) {
            String nick = e.getKey();
            if (nick.equalsIgnoreCase(self)) continue;
            if (e.getValue().contains(itemId))
                result.add(new PlayerFocus(nick, getColor(nick)));
        }
        return result;
    }

    public static int getColor(String nick) {
        ensureColor(nick);
        return PLAYER_COLORS[colorAssignment.get(nick) % PLAYER_COLORS.length];
    }

    public static Map<String, Set<String>> getAllTargets() {
        return Collections.unmodifiableMap(partyTargets);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void ensureColor(String nick) {
        colorAssignment.computeIfAbsent(nick, k -> colorAssignment.size());
    }

    private static String selfNick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getGameProfile().name() : null;
    }

    public record PlayerFocus(String nick, int color) {}

    // ── Persystencja (per serwer) ──────────────────────────────────────────────

    private static File getSaveFile() {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bettermateriallist_data");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, BmlServerId.current() + "_focus.json");
    }

    /** Zapisuje MOJE targety + flagę HUD. Wołane przy każdej lokalnej zmianie i na disconnect. */
    public static void save() {
        try (FileWriter w = new FileWriter(getSaveFile())) {
            JsonObject root = new JsonObject();
            JsonArray targets = new JsonArray();
            getMyTargets().forEach(targets::add);
            root.add("myTargets", targets);
            root.addProperty("hudEnabled", HudOverlayManager.isEnabled());
            GSON.toJson(root, w);
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to save focus state: {}", e.getMessage());
        }
    }

    /** Wczytuje moje targety + flagę HUD. Wołane na JOIN (po ustaleniu nicku gracza). */
    public static void load() {
        File f = getSaveFile();
        if (!f.exists()) return;
        String self = selfNick();
        if (self == null) return;
        try (FileReader r = new FileReader(f)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("myTargets")) {
                Set<String> mine = new HashSet<>();
                root.getAsJsonArray("myTargets").forEach(el -> mine.add(el.getAsString()));
                partyTargets.put(self, mine);
                ensureColor(self);
            }
            if (root.has("hudEnabled") && root.get("hudEnabled").getAsBoolean()) {
                HudOverlayManager.setEnabled(true);
            }
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to load focus state: {}", e.getMessage());
        }
    }
}
