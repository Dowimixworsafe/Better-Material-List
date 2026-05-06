package com.example.network;

import com.example.data.ContainerDataManager;
import com.example.data.MaterialStateManager;
import com.example.party.PartyManager;
import com.example.party.PlacementSyncHelper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.example.party.FocusManager;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Warstwa sieciowa – strona klienta.
 *
 * GRACEFUL DEGRADATION:
 *   serverSupported = false → mod działa normalnie (singleplayer / serwer bez BML)
 *   serverSupported = true  → party + sync aktywne
 *
 * Używa nowego Fabric Networking API (MC 1.21+) z CustomPacketPayload.
 */
@Environment(EnvType.CLIENT)
public class BmlClientNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Network");

    /** True gdy serwer odpowiedział na BML_HELLO. */
    public static volatile boolean serverSupported = false;

    // ─── Rejestracja receivera ────────────────────────────────────────────────

    /** Rejestruje receiver dla pakietów BML. Wywoływane raz w onInitializeClient(). */
    public static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(
            BmlPackets.BmlPayload.TYPE,
            (payload, context) -> {
                // Odczyt danych
                byte[] bytes = payload.data();
                // NA GAME THREAD — nigdy na netty!
                context.client().execute(() -> {
                    try {
                        JsonObject json = JsonParser.parseString(
                            new String(bytes, StandardCharsets.UTF_8)
                        ).getAsJsonObject();
                        handleIncoming(json);
                    } catch (Exception e) {
                        LOGGER.error("[BML-Network] Failed to parse incoming packet: {}", e.getMessage());
                    }
                });
            }
        );
        LOGGER.info("[BML-Network] Registered BML payload receiver.");
    }

    // ─── Dispatcher (przychodzące) ─────────────────────────────────────────────

    private static void handleIncoming(JsonObject json) {
        String type = json.get("type").getAsString();
        LOGGER.debug("[BML-Network] Received: {}", type);

        switch (type) {
            case BmlPackets.BML_HELLO_ACK -> {
                serverSupported = true;
                LOGGER.info("[BML-Network] Server supports BML! Party features enabled.");
            }
            case BmlPackets.SYNC_CHECKED -> {
                String syncId = json.get("placement").getAsString();
                String placement = com.example.data.SyncIdManager.getPlacementBySyncId(syncId);
                String itemName  = json.get("itemName").getAsString();
                boolean checked  = json.get("checked").getAsBoolean();
                MaterialStateManager.setCheckedSilent(placement, itemName, checked);
            }
            case BmlPackets.SYNC_CONTAINER -> {
                String syncId = json.get("placement").getAsString();
                String placement = com.example.data.SyncIdManager.getPlacementBySyncId(syncId);
                String containerId = json.get("containerId").getAsString();
                java.util.HashMap<String, Integer> items = new java.util.HashMap<>();
                json.getAsJsonObject("items").entrySet()
                    .forEach(e -> items.put(e.getKey(), e.getValue().getAsInt()));
                ContainerDataManager.updateContainerItemsSilent(placement, containerId, items);
            }
            case BmlPackets.SYNC_CONTAINER_MARKED -> {
                String syncId = json.get("placement").getAsString();
                String placement = com.example.data.SyncIdManager.getPlacementBySyncId(syncId);
                String containerId = json.get("containerId").getAsString();
                boolean marked = json.get("marked").getAsBoolean();
                ContainerDataManager.setContainerMarkedSilent(placement, containerId, marked);
            }
            case BmlPackets.SYNC_PLACEMENT       -> PlacementSyncHelper.applyPlacement(json);
            case BmlPackets.SYNC_PLACEMENT_REQUEST -> PlacementSyncHelper.handlePlacementRequest(json);
            case BmlPackets.SYNC_FULL_STATE      -> applyFullState(json);
            case BmlPackets.PARTY_TARGET_UPDATE  -> {
                String player = json.get("player").getAsString();
                Set<String> targets = new HashSet<>();
                json.getAsJsonArray("targets").forEach(el -> targets.add(el.getAsString()));
                FocusManager.setPlayerTargets(player, targets);
            }
            default                              -> PartyManager.handle(json);
        }
    }

    private static void applyFullState(JsonObject json) {
        if (json.has("checkedItems")) {
            json.getAsJsonObject("checkedItems").entrySet().forEach(placementEntry -> {
                String placement = placementEntry.getKey();
                placementEntry.getValue().getAsJsonObject().entrySet().forEach(itemEntry ->
                    MaterialStateManager.setCheckedSilent(
                        placement, itemEntry.getKey(), itemEntry.getValue().getAsBoolean()));
            });
        }
        if (json.has("containers")) {
            json.getAsJsonObject("containers").entrySet().forEach(placementEntry -> {
                String placement = placementEntry.getKey();
                placementEntry.getValue().getAsJsonObject().entrySet().forEach(containerEntry -> {
                    java.util.HashMap<String, Integer> items = new java.util.HashMap<>();
                    containerEntry.getValue().getAsJsonObject().entrySet()
                        .forEach(ie -> items.put(ie.getKey(), ie.getValue().getAsInt()));
                    ContainerDataManager.updateContainerItemsSilent(
                        placement, containerEntry.getKey(), items);
                });
            });
        }
    }

    // ─── Wysyłanie ────────────────────────────────────────────────────────────

    /**
     * Niskopoziomowe wysyłanie JSON do serwera.
     * Pomija wysyłanie jeśli serverSupported == false.
     */
    public static void sendRaw(JsonObject payload) {
        if (!serverSupported) return;
        try {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            ClientPlayNetworking.send(new BmlPackets.BmlPayload(bytes));
        } catch (Exception e) {
            LOGGER.error("[BML-Network] Failed to send packet: {}", e.getMessage());
        }
    }

    /** Handshake – sprawdza czy serwer ma BML. Przy BML_HELLO nie sprawdzamy serverSupported. */
    public static void sendHello() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", BmlPackets.BML_HELLO);
            payload.addProperty("version", "1");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            ClientPlayNetworking.send(new BmlPackets.BmlPayload(bytes));
            LOGGER.info("[BML-Network] Sent BML_HELLO to server.");
        } catch (Exception e) {
            LOGGER.debug("[BML-Network] Server has no BML channel (expected): {}", e.getMessage());
        }
    }

    // ─── Pomocnicze metody budujące payloady ──────────────────────────────────

    /** Sync checkboxa – wywoływany z MaterialStateManager.setChecked(). */
    public static void sendCheckedSync(String placement, String itemName, boolean checked) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CHECKED);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", com.example.data.SyncIdManager.getSyncId(placement));
        payload.addProperty("itemName", itemName);
        payload.addProperty("checked", checked);
        sendRaw(payload);
    }

    /** Sync skrzynki – wywoływany z ContainerDataManager.updateContainerItems(). */
    public static void sendContainerSync(String placement, String containerId, Map<String, Integer> items) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CONTAINER);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", com.example.data.SyncIdManager.getSyncId(placement));
        payload.addProperty("containerId", containerId);
        JsonObject itemsJson = new JsonObject();
        items.forEach(itemsJson::addProperty);
        payload.add("items", itemsJson);
        sendRaw(payload);
    }

    /** Broadcasts local player's current target set to all party members. */
    public static void sendTargetUpdate() {
        if (!PartyManager.isInParty()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        String self = mc.player.getGameProfile().name();
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.PARTY_TARGET_UPDATE);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("player", self);
        JsonArray targets = new JsonArray();
        FocusManager.getMyTargets().forEach(targets::add);
        payload.add("targets", targets);
        sendRaw(payload);
    }

    public static void sendContainerMarkedSync(String placement, String containerId, boolean marked) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CONTAINER_MARKED);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", com.example.data.SyncIdManager.getSyncId(placement));
        payload.addProperty("containerId", containerId);
        payload.addProperty("marked", marked);
        sendRaw(payload);
    }
}
