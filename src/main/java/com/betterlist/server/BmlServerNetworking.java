package com.betterlist.server;

import com.betterlist.network.BmlPackets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Main server-side packet courier.
 * Receives, validates intent, and forwards to the appropriate party members.
 */
public class BmlServerNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Server");

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(BmlPackets.BmlPayload.TYPE, (payload, context) -> {
            byte[] bytes = payload.data();
            context.server().execute(() -> {
                try {
                    String jsonStr = new String(bytes, StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                    handleIncoming(json, context.player(), context);
                } catch (Exception e) {
                    LOGGER.error("[BML-Server] Failed to handle packet: {}", e.getMessage());
                }
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            UUID playerUUID = player.getUUID();
            UUID partyId = ServerPartyManager.getPartyIdForPlayer(playerUUID);
            if (partyId != null) {
                if (ServerPartyManager.isLeader(partyId, playerUUID)) {
                    // Admin leaves — the whole party is disbanded.
                    for (UUID member : ServerPartyManager.getPartyMembers(partyId)) {
                        if (!member.equals(playerUUID)) {
                            ServerPlayer mPlayer = server.getPlayerList().getPlayer(member);
                            if (mPlayer != null) {
                                JsonObject kicked = new JsonObject();
                                kicked.addProperty("type", BmlPackets.PARTY_LEAVE);
                                kicked.addProperty("partyId", partyId.toString());
                                sendToPlayer(mPlayer, kicked);
                            }
                        }
                    }
                    ServerPartyManager.disbandParty(partyId);
                    LOGGER.info("[BML-Server] Party {} disbanded due to leader {} disconnect.", partyId, player.getName().getString());
                } else {
                    // A regular member leaves.
                    ServerPartyManager.removePlayerFromParty(partyId, playerUUID);
                    // Build the party update manually and send it to remaining members
                    // (no networking context needed here):
                    JsonObject update = new JsonObject();
                    update.addProperty("type", BmlPackets.PARTY_UPDATE);
                    update.addProperty("partyId", partyId.toString());
                    
                    UUID leaderId = ServerPartyManager.getLeader(partyId);
                    if (leaderId != null) {
                        ServerPlayer leader = server.getPlayerList().getPlayer(leaderId);
                        if (leader != null) {
                            update.addProperty("adminNick", leader.getGameProfile().name());
                        }
                    }

                    com.google.gson.JsonArray members = new com.google.gson.JsonArray();
                    for (UUID uuid : ServerPartyManager.getPartyMembers(partyId)) {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p != null) {
                            members.add(p.getGameProfile().name());
                        }
                    }
                    update.add("members", members);

                    for (UUID uuid : ServerPartyManager.getPartyMembers(partyId)) {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p != null) {
                            sendToPlayer(p, update);
                        }
                    }
                    LOGGER.info("[BML-Server] Player {} left party {} due to disconnect.", player.getName().getString(), partyId);
                }
            }
        });

        LOGGER.info("[BML-Server] Networking handlers registered.");
    }

    private static void handleIncoming(JsonObject json, ServerPlayer sender, ServerPlayNetworking.Context context) {
        String type = json.get("type").getAsString();
        LOGGER.debug("[BML-Server] Received packet: {} from {}", type, sender.getName().getString());

        switch (type) {
            case BmlPackets.BML_HELLO -> {
                JsonObject ack = new JsonObject();
                ack.addProperty("type", BmlPackets.BML_HELLO_ACK);
                ack.addProperty("version", "1");
                sendToPlayer(sender, ack);
            }
            case BmlPackets.PARTY_INVITE -> {
                String targetNick = json.get("targetNick").getAsString();
                boolean targetFound = false;
                ServerPlayer targetPlayer = null;
                UUID partyId = UUID.fromString(json.get("partyId").getAsString());

                for (ServerPlayer player : context.server().getPlayerList().getPlayers()) {
                    if (player.getGameProfile().name().equalsIgnoreCase(targetNick)) {
                        targetFound = true;
                        targetPlayer = player;
                        break;
                    }
                }

                if (!targetFound) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", BmlPackets.PARTY_ERROR);
                    err.addProperty("message", "Gracz " + targetNick + " nie jest online.");
                    sendToPlayer(sender, err);
                    return;
                }

                if (ServerPartyManager.isPlayerInAnyParty(targetPlayer.getUUID())) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", BmlPackets.PARTY_ERROR);
                    err.addProperty("message", "Gracz " + targetNick + " jest juz w party.");
                    sendToPlayer(sender, err);
                    return;
                }

                if (!ServerPartyManager.partyExists(partyId) && ServerPartyManager.isPlayerInAnyParty(sender.getUUID())) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", BmlPackets.PARTY_ERROR);
                    err.addProperty("message", "Nalezysz juz do innej grupy.");
                    sendToPlayer(sender, err);
                    return;
                }

                // Create the party on the server if it doesn't exist yet (on the first invite).
                if (!ServerPartyManager.partyExists(partyId)) {
                    ServerPartyManager.createParty(partyId, sender.getUUID());
                    broadcastPartyUpdate(partyId, context);
                }

                JsonObject notify = new JsonObject();
                notify.addProperty("type", BmlPackets.PARTY_INVITE_NOTIFY);
                notify.addProperty("partyId", partyId.toString());
                notify.addProperty("fromNick", sender.getGameProfile().name());
                sendToPlayer(targetPlayer, notify);
            }
            case BmlPackets.PARTY_ACCEPT -> {
                UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                ServerPartyManager.addPlayerToParty(partyId, sender.getUUID());
                broadcastPartyUpdate(partyId, context);
            }
            case BmlPackets.PARTY_LEAVE -> {
                UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                if (ServerPartyManager.isLeader(partyId, sender.getUUID())) {
                    // Leader leaves — disband the party
                    for (UUID member : ServerPartyManager.getPartyMembers(partyId)) {
                        if (!member.equals(sender.getUUID())) {
                            ServerPlayer mPlayer = context.server().getPlayerList().getPlayer(member);
                            if (mPlayer != null) {
                                JsonObject kicked = new JsonObject();
                                kicked.addProperty("type", BmlPackets.PARTY_LEAVE);
                                kicked.addProperty("partyId", partyId.toString());
                                sendToPlayer(mPlayer, kicked);
                            }
                        }
                    }
                    ServerPartyManager.disbandParty(partyId);
                } else {
                    // Regular member leaves
                    ServerPartyManager.removePlayerFromParty(partyId, sender.getUUID());
                    broadcastPartyUpdate(partyId, context);
                }
            }
            case BmlPackets.PARTY_KICK -> {
                UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                // Only the leader may kick
                if (ServerPartyManager.isLeader(partyId, sender.getUUID())) {
                    String targetNick = json.get("targetNick").getAsString();
                    ServerPlayer targetPlayer = null;
                    for (ServerPlayer p : context.server().getPlayerList().getPlayers()) {
                        if (p.getGameProfile().name().equalsIgnoreCase(targetNick)) {
                            targetPlayer = p;
                            break;
                        }
                    }
                    if (targetPlayer != null) {
                        ServerPartyManager.removePlayerFromParty(partyId, targetPlayer.getUUID());
                        
                        // Notify the kicked player (same as a leave)
                        JsonObject kicked = new JsonObject();
                        kicked.addProperty("type", BmlPackets.PARTY_LEAVE);
                        kicked.addProperty("partyId", partyId.toString());
                        sendToPlayer(targetPlayer, kicked);
                        
                        broadcastPartyUpdate(partyId, context);
                    }
                }
            }
            case BmlPackets.SYNC_PLACEMENT_REQUEST -> {
                if (json.has("partyId") && json.has("targetNick")) {
                    UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                    String targetNick = json.get("targetNick").getAsString();
                    
                    // Forward the REQUEST only to the named player
                    for (UUID memberUuid : ServerPartyManager.getPartyMembers(partyId)) {
                        ServerPlayer member = context.server().getPlayerList().getPlayer(memberUuid);
                        if (member != null && member.getGameProfile().name().equalsIgnoreCase(targetNick)) {
                            // Tag who's asking, so the receiver knows where to reply
                            json.addProperty("requestingNick", sender.getGameProfile().name());
                            sendToPlayer(member, json);
                            break;
                        }
                    }
                }
            }
            case BmlPackets.SYNC_PLACEMENT -> {
                if (json.has("partyId")) {
                    UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                    // If targetNick is set (a reply to a request), send only to that player
                    if (json.has("targetNick")) {
                        String targetNick = json.get("targetNick").getAsString();
                        for (UUID memberUuid : ServerPartyManager.getPartyMembers(partyId)) {
                            ServerPlayer member = context.server().getPlayerList().getPlayer(memberUuid);
                            if (member != null && member.getGameProfile().name().equalsIgnoreCase(targetNick)) {
                                sendToPlayer(member, json);
                                break;
                            }
                        }
                    } else {
                        // Tradycyjny broadcast do wszystkich poza nadawca
                        for (UUID memberUuid : ServerPartyManager.getPartyMembers(partyId)) {
                            if (!memberUuid.equals(sender.getUUID())) {
                                ServerPlayer member = context.server().getPlayerList().getPlayer(memberUuid);
                                if (member != null) {
                                    sendToPlayer(member, json);
                                }
                            }
                        }
                    }
                }
            }
            default -> {
                // Other packets (data sync) — relay to the party.
                if (json.has("partyId")) {
                    UUID partyId = UUID.fromString(json.get("partyId").getAsString());
                    for (UUID memberUuid : ServerPartyManager.getPartyMembers(partyId)) {
                        if (!memberUuid.equals(sender.getUUID())) {
                            ServerPlayer member = context.server().getPlayerList().getPlayer(memberUuid);
                            if (member != null) {
                                sendToPlayer(member, json);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void broadcastPartyUpdate(UUID partyId, ServerPlayNetworking.Context context) {
        JsonObject update = new JsonObject();
        update.addProperty("type", BmlPackets.PARTY_UPDATE);
        update.addProperty("partyId", partyId.toString());
        
        UUID leaderId = ServerPartyManager.getLeader(partyId);
        if (leaderId != null) {
            ServerPlayer leader = context.server().getPlayerList().getPlayer(leaderId);
            if (leader != null) {
                update.addProperty("adminNick", leader.getGameProfile().name());
            }
        }

        com.google.gson.JsonArray members = new com.google.gson.JsonArray();
        for (UUID uuid : ServerPartyManager.getPartyMembers(partyId)) {
            ServerPlayer p = context.server().getPlayerList().getPlayer(uuid);
            if (p != null) {
                members.add(p.getGameProfile().name());
            }
        }
        update.add("members", members);

        for (UUID uuid : ServerPartyManager.getPartyMembers(partyId)) {
            ServerPlayer p = context.server().getPlayerList().getPlayer(uuid);
            if (p != null) {
                sendToPlayer(p, update);
            }
        }
    }

    private static void sendToPlayer(ServerPlayer target, JsonObject payload) {
        try {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            ServerPlayNetworking.send(target, new BmlPackets.BmlPayload(bytes));
        } catch (Exception e) {
            LOGGER.error("[BML-Server] Failed to send packet to {}: {}", target.getName().getString(), e.getMessage());
        }
    }
}
