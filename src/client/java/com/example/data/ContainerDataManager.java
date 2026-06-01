package com.example.data;

import com.example.network.BmlClientNetworking;
import com.example.party.PartyManager;
import com.example.util.BmlServerId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Przechowuje zawartość oznaczonych ("śledzonych") skrzyń, aby doliczać ją do kolumny
 * "stored" na liście materiałów.
 *
 * MODEL DANYCH (po przebudowie):
 *   Map<containerId, Map<itemName, count>>
 *
 *   Skrzynia jest identyfikowana WYŁĄCZNIE przez swoje fizyczne położenie
 *   (wymiar + pozycja, patrz {@code AbstractContainerScreenMixin#getContainerId}).
 *   Dane NIE są już kluczowane po placemencie.
 *
 * DLACZEGO:
 *   Wcześniej skrzynie były zagnieżdżone pod "sklejoną nazwą aktywnych placementów"
 *   (np. "A, B (+2 more)"). Ta nazwa zależała od kolejności i liczby placementów i była
 *   liczona w dwóch rozjeżdżających się miejscach (zapis w mixinie vs odczyt w GUI),
 *   przez co przy każdym otwarciu listy "stored" potrafiło pokazać co innego — nawet
 *   solo i bez otwierania skrzyni. Skrzynia ma jedno, fizyczne położenie wspólne dla
 *   wszystkich w party, więc klucz globalny per serwer jest naturalny i stabilny.
 *
 * Plik: {@code config/bettermateriallist_data/<serverId>_containers_v2.json}.
 * Stary format jest jednorazowo migrowany przy starcie (spłaszczany), a oryginał
 * zachowywany jako {@code .bak}.
 */
@Environment(EnvType.CLIENT)
public class ContainerDataManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Containers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Stała wstawiana w pole "placement" pakietów sync — skrzynie są globalne. */
    public static final String GLOBAL_PLACEMENT = "__global__";

    // Ostatnio kliknięty blok — ustawiany przez MultiPlayerGameModeMixin, używany do
    // wyznaczenia containerId otwartej skrzyni.
    public static net.minecraft.core.BlockPos lastInteractedBlockPos = null;

    // Map<containerId, Map<itemName, count>>
    private static Map<String, Map<String, Integer>> containers = new HashMap<>();

    // Debounce zapisu: mutacje ustawiają dirty=true, a faktyczny zapis na dysk robi
    // flush() wołany okresowo z ClientTickEvents oraz na disconnect. Dzięki temu seria
    // kliknięć/skanów nie wywołuje serii synchronicznych zapisów na wątku głównym.
    private static volatile boolean dirty = false;

    private static void markDirty() {
        dirty = true;
    }

    /** Zapisuje na dysk tylko jeśli były zmiany od ostatniego flush. */
    public static void flush() {
        if (dirty) {
            dirty = false;
            save();
        }
    }

    private static File dataDir() {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bettermateriallist_data");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File getSaveFile() {
        return new File(dataDir(), BmlServerId.current() + "_containers_v2.json");
    }

    private static File getLegacyFile() {
        return new File(dataDir(), BmlServerId.current() + "_containers.json");
    }

    public static void load() {
        containers.clear();
        File saveFile = getSaveFile();

        if (saveFile.exists()) {
            try (FileReader reader = new FileReader(saveFile)) {
                Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
                Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    containers = loaded;
                }
            } catch (Exception e) {
                LOGGER.error("[BML] Failed to read {} — keeping a backup and starting empty: {}",
                        saveFile.getName(), e.getMessage());
                backup(saveFile);
            }
            return;
        }

        // Brak nowego pliku — spróbuj zmigrować stary (zagnieżdżony per-placement) format.
        migrateLegacy();
    }

    /**
     * Spłaszcza stary format {@code placement -> containerId -> items} do
     * {@code containerId -> items}, sumując zawartość gdyby ta sama skrzynia występowała
     * pod wieloma placementami.
     */
    private static void migrateLegacy() {
        File legacy = getLegacyFile();
        if (!legacy.exists()) return;

        try (FileReader reader = new FileReader(legacy)) {
            Type type = new TypeToken<Map<String, Map<String, Map<String, Integer>>>>() {}.getType();
            Map<String, Map<String, Map<String, Integer>>> old = GSON.fromJson(reader, type);
            if (old != null) {
                for (Map<String, Map<String, Integer>> perPlacement : old.values()) {
                    if (perPlacement == null) continue;
                    for (Map.Entry<String, Map<String, Integer>> ce : perPlacement.entrySet()) {
                        Map<String, Integer> target = containers.computeIfAbsent(ce.getKey(), k -> new HashMap<>());
                        if (ce.getValue() != null) {
                            ce.getValue().forEach((item, count) ->
                                    target.merge(item, count, Integer::sum));
                        }
                    }
                }
                LOGGER.info("[BML] Migrated {} container(s) from legacy format.", containers.size());
                save();
            }
        } catch (Exception e) {
            LOGGER.error("[BML] Legacy container migration failed: {}", e.getMessage());
        } finally {
            // Niezależnie od wyniku — odsuń stary plik, by nie migrować go w kółko.
            backup(legacy);
        }
    }

    private static void backup(File file) {
        try {
            File bak = new File(file.getParentFile(), file.getName() + ".bak");
            if (bak.exists()) bak.delete();
            file.renameTo(bak);
        } catch (Exception ignored) {}
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(getSaveFile())) {
            GSON.toJson(containers, writer);
        } catch (Exception e) {
            LOGGER.error("[BML] Failed to save containers: {}", e.getMessage());
        }
    }

    public static void clear() {
        containers.clear();
    }

    public static boolean isContainerMarked(String containerId) {
        return containerId != null && containers.containsKey(containerId);
    }

    public static void setContainerMarked(String containerId, boolean marked) {
        if (containerId == null) return;
        if (marked) {
            containers.putIfAbsent(containerId, new HashMap<>());
        } else {
            containers.remove(containerId);
        }
        markDirty();
        if (PartyManager.isInParty()) {
            BmlClientNetworking.sendContainerMarkedSync(GLOBAL_PLACEMENT, containerId, marked);
        }
    }

    public static void setContainerMarkedSilent(String containerId, boolean marked) {
        if (containerId == null) return;
        if (marked) {
            containers.putIfAbsent(containerId, new HashMap<>());
        } else {
            containers.remove(containerId);
        }
        markDirty();
    }

    /** Aktualizuje zawartość skrzyni (po lokalnym skanie) — tylko jeśli jest oznaczona. */
    public static void updateContainerItems(String containerId, Map<String, Integer> items) {
        if (containerId == null) return;
        if (isContainerMarked(containerId)) {
            containers.put(containerId, new HashMap<>(items));
            markDirty();
            if (PartyManager.isInParty()) {
                BmlClientNetworking.sendContainerSync(GLOBAL_PLACEMENT, containerId, items);
            }
        }
    }

    /**
     * Aktualizuje zawartość skrzyni z danych odebranych od członka party (bez ponownego
     * wysyłania). Nie nadpisujemy istniejących danych PUSTĄ wersją — zapobiega to utracie
     * "stored", gdy ktoś prześle pełny stan zanim sam zeskanował daną skrzynię.
     */
    public static void updateContainerItemsSilent(String containerId, Map<String, Integer> items) {
        if (containerId == null) return;
        if ((items == null || items.isEmpty()) && isContainerMarked(containerId)
                && !containers.get(containerId).isEmpty()) {
            return;
        }
        containers.put(containerId, items == null ? new HashMap<>() : new HashMap<>(items));
        markDirty();
    }

    /** Suma zawartości WSZYSTKICH oznaczonych skrzyń na serwerze. */
    public static Map<String, Integer> getTotalItems() {
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Integer> contents : containers.values()) {
            if (contents == null) continue;
            for (Map.Entry<String, Integer> entry : contents.entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return totals;
    }

    public static void clearAll() {
        if (!containers.isEmpty()) {
            containers.clear();
            markDirty();
        }
    }

    public static java.util.Set<String> getMarkedContainers() {
        return new java.util.HashSet<>(containers.keySet());
    }

    /** Zawartość pojedynczej oznaczonej skrzyni (lub pusta mapa). */
    public static Map<String, Integer> getContainerContents(String containerId) {
        if (containerId == null) return new HashMap<>();
        Map<String, Integer> c = containers.get(containerId);
        return c == null ? new HashMap<>() : new HashMap<>(c);
    }

    /** Pełna migawka skrzyń: { containerId -> { item -> count } }. Do SYNC_FULL_STATE. */
    public static Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        containers.forEach((id, items) -> copy.put(id, new HashMap<>(items)));
        return copy;
    }
}
