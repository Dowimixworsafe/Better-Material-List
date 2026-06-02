package com.betterlist.network;

import com.betterlist.data.ContainerDataManager;
import com.betterlist.data.MaterialStateManager;
import com.betterlist.party.PartyManager;
import com.betterlist.party.PlacementSyncHelper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.betterlist.party.FocusManager;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Networking layer — client side.
 *
 * GRACEFUL DEGRADATION:
 *   serverSupported = false → the mod works normally (singleplayer / server without BML)
 *   serverSupported = true  → party + sync active
 *
 * Uses the new Fabric Networking API (MC 1.21+) with CustomPacketPayload.
 */
@Environment(EnvType.CLIENT)
public class BmlClientNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Network");

    /** True once the server replied to BML_HELLO. */
    public static volatile boolean serverSupported = false;

    // ─── Receiver registration ─────────────────────────────────────────────────

    /** Registers the receiver for BML packets. Called once in onInitializeClient(). */
    public static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(
            BmlPackets.BmlPayload.TYPE,
            (payload, context) -> {
                // Read the data.
                byte[] bytes = payload.data();
                // ON THE GAME THREAD — never on netty!
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

    // ─── Dispatcher (incoming) ──────────────────────────────────────────────────

    /** Warn only once per session if a party member has an incompatible protocol version. */
    private static boolean versionMismatchWarned = false;

    private static void handleIncoming(JsonObject json) {
        String type = json.get("type").getAsString();
        LOGGER.debug("[BML-Network] Received: {}", type);

        // Detect an incompatible mod version on another party member (packets carry "v").
        if (json.has("v") && !BmlPackets.PROTOCOL_VERSION.equals(json.get("v").getAsString())
                && !versionMismatchWarned) {
            versionMismatchWarned = true;
            LOGGER.warn("[BML-Network] Party member uses protocol v{} but we use v{} — sync may be unreliable.",
                    json.get("v").getAsString(), BmlPackets.PROTOCOL_VERSION);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§e" + com.betterlist.util.BmlLang.tr("bml.net.version_mismatch")));
            }
        }

        switch (type) {
            case BmlPackets.BML_HELLO_ACK -> {
                serverSupported = true;
                LOGGER.info("[BML-Network] Server supports BML! Party features enabled.");
            }
            case BmlPackets.SYNC_CHECKED -> {
                // "placement" carries the checklistKey directly (enabled-placement names).
                String placement = json.get("placement").getAsString();
                String itemName  = json.get("itemName").getAsString();
                boolean checked  = json.get("checked").getAsBoolean();
                long ts = json.has("ts") ? json.get("ts").getAsLong() : System.currentTimeMillis();
                MaterialStateManager.setCheckedSilentVersioned(placement, itemName, checked, ts);
            }
            case BmlPackets.SYNC_CONTAINER -> {
                // Chests are global (key = containerId); the "placement" field is ignored.
                String containerId = json.get("containerId").getAsString();
                java.util.HashMap<String, Integer> items = new java.util.HashMap<>();
                json.getAsJsonObject("items").entrySet()
                    .forEach(e -> items.put(e.getKey(), e.getValue().getAsInt()));
                ContainerDataManager.updateContainerItemsSilent(containerId, items);
            }
            case BmlPackets.SYNC_CONTAINER_MARKED -> {
                String containerId = json.get("containerId").getAsString();
                boolean marked = json.get("marked").getAsBoolean();
                ContainerDataManager.setContainerMarkedSilent(containerId, marked);
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
        // checkedItems: { checklistKey -> { itemName -> bool } }  (key is opaque)
        if (json.has("checkedItems")) {
            json.getAsJsonObject("checkedItems").entrySet().forEach(keyEntry -> {
                String checklistKey = keyEntry.getKey();
                keyEntry.getValue().getAsJsonObject().entrySet().forEach(itemEntry ->
                    MaterialStateManager.setCheckedSilent(
                        checklistKey, itemEntry.getKey(), itemEntry.getValue().getAsBoolean()));
            });
        }
        // containers (global model): { containerId -> { itemName -> count } }
        if (json.has("containers")) {
            json.getAsJsonObject("containers").entrySet().forEach(containerEntry -> {
                java.util.HashMap<String, Integer> items = new java.util.HashMap<>();
                containerEntry.getValue().getAsJsonObject().entrySet()
                    .forEach(ie -> items.put(ie.getKey(), ie.getValue().getAsInt()));
                ContainerDataManager.updateContainerItemsSilent(containerEntry.getKey(), items);
            });
        }
    }

    // ─── Sending ──────────────────────────────────────────────────────────────

    /**
     * Low-level JSON send to the server.
     * Skips sending if serverSupported == false.
     */
    public static void sendRaw(JsonObject payload) {
        if (!serverSupported) return;
        try {
            // Stamp the protocol version on every packet — lets the receiver detect
            // an incompatible mod version on another party member.
            if (!payload.has("v")) {
                payload.addProperty("v", BmlPackets.PROTOCOL_VERSION);
            }
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            ClientPlayNetworking.send(new BmlPackets.BmlPayload(bytes));
        } catch (Exception e) {
            LOGGER.error("[BML-Network] Failed to send packet: {}", e.getMessage());
        }
    }

    /** Handshake — checks whether the server has BML. We don't check serverSupported for BML_HELLO. */
    public static void sendHello() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", BmlPackets.BML_HELLO);
            payload.addProperty("version", BmlPackets.PROTOCOL_VERSION);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            ClientPlayNetworking.send(new BmlPackets.BmlPayload(bytes));
            LOGGER.info("[BML-Network] Sent BML_HELLO to server.");
        } catch (Exception e) {
            LOGGER.debug("[BML-Network] Server has no BML channel (expected): {}", e.getMessage());
        }
    }

    // ─── Payload-building helpers ───────────────────────────────────────────────

    /**
     * Checkbox sync — called from MaterialStateManager.setChecked().
     * The "placement" field carries the checklistKey directly (enabled-placement names).
     * The "ts" field lets the receiver resolve ordering (last-write-wins).
     */
    public static void sendCheckedSync(String placement, String itemName, boolean checked) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CHECKED);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", placement);
        payload.addProperty("itemName", itemName);
        payload.addProperty("checked", checked);
        payload.addProperty("ts", System.currentTimeMillis());
        sendRaw(payload);
    }

    /** Chest sync — called from ContainerDataManager.updateContainerItems(). */
    public static void sendContainerSync(String placement, String containerId, Map<String, Integer> items) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CONTAINER);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", placement);
        payload.addProperty("containerId", containerId);
        JsonObject itemsJson = new JsonObject();
        items.forEach(itemsJson::addProperty);
        payload.add("items", itemsJson);
        sendRaw(payload);
    }

    /** Broadcasts the local player's current target set to all party members. */
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

    /**
     * Sends full state (checks + tracked-chest contents) to a specific player.
     * Called when someone joins the party and requests a sync — so they don't have to
     * scan everything from scratch.
     *
     * Note: the "targetNick" field is honored only if the server/plugin can route
     * per-player; a relay plugin will broadcast it to the party, which is safe because
     * full-state receipt is merge-only, not overwriting.
     */
    public static void sendFullStateTo(String targetNick) {
        if (!PartyManager.isInParty()) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_FULL_STATE);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        if (targetNick != null) payload.addProperty("targetNick", targetNick);

        JsonObject checkedJson = new JsonObject();
        MaterialStateManager.snapshot().forEach((key, items) -> {
            JsonObject itemsJson = new JsonObject();
            items.forEach(item -> itemsJson.addProperty(item, true));
            if (itemsJson.size() > 0) checkedJson.add(key, itemsJson);
        });
        payload.add("checkedItems", checkedJson);

        JsonObject containersJson = new JsonObject();
        ContainerDataManager.snapshot().forEach((containerId, items) -> {
            JsonObject itemsJson = new JsonObject();
            items.forEach(itemsJson::addProperty);
            containersJson.add(containerId, itemsJson);
        });
        payload.add("containers", containersJson);

        sendRaw(payload);
        LOGGER.info("[BML-Network] Sent full state to {}.", targetNick);
    }

    public static void sendContainerMarkedSync(String placement, String containerId, boolean marked) {
        if (!PartyManager.isInParty()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.SYNC_CONTAINER_MARKED);
        payload.addProperty("partyId", PartyManager.getPartyId().toString());
        payload.addProperty("placement", placement);
        payload.addProperty("containerId", containerId);
        payload.addProperty("marked", marked);
        sendRaw(payload);
    }
}
