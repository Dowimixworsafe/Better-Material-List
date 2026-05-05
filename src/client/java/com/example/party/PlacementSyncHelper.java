package com.example.party;

import com.example.network.BmlClientNetworking;
import com.example.network.BmlPackets;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Obsługuje synchronizację pozycji/orientacji schematów Litematiki w ramach party.
 *
 * CO JEST SYNCHRONIZOWANE:
 *   - Nazwa pliku .litematic i nazwa placement
 *   - Współrzędne origin (X, Y, Z)
 *   - Rotation i Mirror (jako stringi enum)
 *
 * CO NIE JEST SYNCHRONIZOWANE:
 *   - Sam plik .litematic (zbyt duży, musi być przekazany ręcznie)
 *
 * Przy odbiorze: jeśli odbiorca ma plik lokalnie → pokazujemy instrukcję jak go wczytać.
 * Automatyczne tworzenie placement przez API jest niestabilne między wersjami Litematiki,
 * więc ograniczamy się do powiadomienia z gotowymi do skopiowania danymi.
 */
@Environment(EnvType.CLIENT)
public class PlacementSyncHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-PlacementSync");

    /**
     * Wysyła dane o WSZYSTKICH aktywnych schematach do party.
     * Wywoływane z Party GUI po kliknięciu "Sync Schematics".
     */
    public static void sendAllPlacements() {
        if (!PartyManager.isInParty()) return;

        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        List<SchematicPlacement> allPlacements = mgr.getAllSchematicsPlacements();

        if (allPlacements == null || allPlacements.isEmpty()) {
            LOGGER.info("[BML-PlacementSync] No placements to sync.");
            return;
        }

        int sent = 0;
        for (SchematicPlacement placement : allPlacements) {
            if (!placement.isEnabled()) continue;
            sendSinglePlacement(placement, null);
            sent++;
        }
        LOGGER.info("[BML-PlacementSync] Sent {} placements to party.", sent);

        // Powiadom własny chat
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                Component.literal("§a[BML] §2Wysłano " + sent + " placement(ów) do party."));
        }
    }

    private static void sendSinglePlacement(SchematicPlacement placement, String targetNick) {
        try {
            net.minecraft.core.BlockPos origin = placement.getOrigin();
            String schematicFileName = getSchematicFileName(placement);

            JsonObject payload = new JsonObject();
            payload.addProperty("type", BmlPackets.SYNC_PLACEMENT);
            payload.addProperty("partyId", PartyManager.getPartyId().toString());
            payload.addProperty("placementName", placement.getName());
            payload.addProperty("schematicName", schematicFileName);
            payload.addProperty("originX", origin.getX());
            payload.addProperty("originY", origin.getY());
            payload.addProperty("originZ", origin.getZ());
            payload.addProperty("rotation", placement.getRotation().name());
            payload.addProperty("mirror", placement.getMirror().name());
            
            if (targetNick != null) {
                payload.addProperty("targetNick", targetNick);
            }

            BmlClientNetworking.sendRaw(payload);
        } catch (Exception e) {
            LOGGER.error("[BML-PlacementSync] Error building placement packet for '{}': {}",
                placement.getName(), e.getMessage());
        }
    }

    /**
     * Prosi konkretnego gracza o wyslanie swoich placementow.
     */
    public static void requestPlacementsFromPlayer(String targetNick) {
        if (!PartyManager.isInParty()) return;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", BmlPackets.SYNC_PLACEMENT_REQUEST);
            payload.addProperty("partyId", PartyManager.getPartyId().toString());
            payload.addProperty("targetNick", targetNick);
            BmlClientNetworking.sendRaw(payload);
            LOGGER.info("[BML-PlacementSync] Requested placements from player {}", targetNick);
        } catch (Exception e) {
            LOGGER.error("[BML-PlacementSync] Error requesting placements: {}", e.getMessage());
        }
    }

    /**
     * Zostalismy poproszeni o odeslanie naszych placementow do "requestingNick".
     */
    public static void handlePlacementRequest(JsonObject json) {
        if (!json.has("requestingNick")) return;
        String requestingNick = json.get("requestingNick").getAsString();
        
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        List<SchematicPlacement> allPlacements = mgr.getAllSchematicsPlacements();

        if (allPlacements == null || allPlacements.isEmpty()) {
            return; // nie mamy nic do odeslania
        }

        for (SchematicPlacement placement : allPlacements) {
            if (!placement.isEnabled()) continue;
            // Odsylamy BEZPOSREDNIO do tego gracza
            sendSinglePlacement(placement, requestingNick);
        }
        LOGGER.info("[BML-PlacementSync] Responded to placement request from {}", requestingNick);
    }

    /**
     * Stosuje odebrany pakiet SYNC_PLACEMENT.
     * Wywoływane przez BmlClientNetworking na game thread.
     *
     * Zamiast automatycznie tworzyć placement przez API (niestabilne między wersjami Litematiki),
     * wyświetlamy graczowi czytelną instrukcję z gotowymi danymi.
     */
    public static void applyPlacement(JsonObject json) {
        String schematicName  = json.get("schematicName").getAsString();
        String placementName  = json.get("placementName").getAsString();
        int ox = json.get("originX").getAsInt();
        int oy = json.get("originY").getAsInt();
        int oz = json.get("originZ").getAsInt();
        String rotation = json.has("rotation") ? json.get("rotation").getAsString() : "NONE";
        String mirror   = json.has("mirror")   ? json.get("mirror").getAsString()   : "NONE";

        LOGGER.info("[BML-PlacementSync] Received placement '{}' (file: {}) at {},{},{} rot={} mir={}",
            placementName, schematicName, ox, oy, oz, rotation, mirror);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Sprawdź czy gracz posiada plik schematu lokalnie
        java.io.File file = findSchematicFile(schematicName);

        if (file == null) {
            // Plik nie istnieje — poproś o ręczne skopiowanie
            mc.player.sendSystemMessage(
                Component.literal("§c[BML] §eBrakuje schematu: §f" + schematicName +
                    " §7– skopiuj plik .litematic do folderu schematics/, a potem wczytaj go w Litematice."));
        } else {
            try {
                // Usuń istniejący placement o tej samej nazwie (aby nie duplikować)
                SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
                List<SchematicPlacement> existing = mgr.getAllSchematicsPlacements();
                for (SchematicPlacement p : existing) {
                    if (p.getName().equals(placementName)) {
                        mgr.removeSchematicPlacement(p);
                        break;
                    }
                }

                fi.dy.masa.litematica.schematic.LitematicaSchematic schematic =
                    fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromFile(file.getParentFile().toPath(), file.getName());
                if (schematic != null) {
                    net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(ox, oy, oz);
                    SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, placementName, true, true);
                    
                    fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, true);
                    
                    mc.player.sendSystemMessage(
                        Component.literal("§a[BML Party] Automatycznie załadowano i ustawiono schemat od znajomego: §e" + placementName));
                } else {
                     LOGGER.error("Schematic returned null during createFromFile.");
                }
            } catch (Exception e) {
                LOGGER.error("[BML-PlacementSync] Błąd auto-placementu: {}", e.getMessage());
                mc.player.sendSystemMessage(
                    Component.literal("§c[BML] Błąd wczytywania automatycznego z pliku: " + schematicName));
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Pobiera nazwę pliku schematu (tylko basename, z rozszerzeniem). */
    private static String getSchematicFileName(SchematicPlacement placement) {
        try {
            java.nio.file.Path schematicPath = placement.getSchematicFile();
            if (schematicPath != null) return schematicPath.toFile().getName();
        } catch (Exception ignored) {}
        return placement.getName() + ".litematic";
    }

    /**
     * Szuka pliku .litematic:
     *   1. {gamedir}/schematics/{fileName}
     *   2. {gamedir}/{fileName}
     */
    private static File findSchematicFile(String fileName) {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File candidate = new File(gameDir, "schematics" + File.separator + fileName);
        if (candidate.exists()) return candidate;
        candidate = new File(gameDir, fileName);
        if (candidate.exists()) return candidate;
        return null;
    }
}
