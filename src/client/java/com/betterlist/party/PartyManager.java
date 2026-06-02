package com.betterlist.party;

import com.betterlist.network.BmlPackets;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.JsonParser;

/**
 * Manages party state on the client side.
 * Holds: current party UUID, member list, pending invites.
 * Sends packets via BmlClientNetworking (to avoid cyclic dependencies,
 * this class builds the JsonObject and calls BmlClientNetworking.sendRaw).
 */
@Environment(EnvType.CLIENT)
public class PartyManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BML-Party");

    private static UUID currentPartyId = null;
    private static String adminNick = null;
    private static final List<String> members = new ArrayList<>();

    /** Pending invites (a few at most; the player can accept/decline them). */
    public record PendingInvite(String fromNick, UUID partyId) {}
    private static final List<PendingInvite> pendingInvites = new ArrayList<>();

    private static final int MAX_RECENT_PLAYERS = 10;
    private static final List<String> recentPlayers = new ArrayList<>();
    private static boolean recentPlayersLoaded = false;

    // ─── Sending ──────────────────────────────────────────────────────────────

    /** Sends an invite to the named player. Creates a new party if we're not in one. */
    public static void sendInvite(String targetNick) {
        if (targetNick == null || targetNick.isBlank()) return;
        // If we're not in a party yet, create one (we become the leader).
        if (currentPartyId == null) {
            currentPartyId = UUID.randomUUID();
            members.clear();
            String selfNick = getSelfNick();
            if (selfNick != null) members.add(selfNick);
            LOGGER.info("[BML-Party] Created party {} as leader", currentPartyId);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.PARTY_INVITE);
        payload.addProperty("partyId", currentPartyId.toString());
        payload.addProperty("targetNick", targetNick);
        sendRaw(payload);
        LOGGER.info("[BML-Party] Sent invite to {}", targetNick);
    }

    /** Accepts an invite and joins the party. */
    public static void acceptInvite(UUID partyId) {
        // Remove from the pending list.
        pendingInvites.removeIf(inv -> inv.partyId().equals(partyId));

        currentPartyId = partyId;
        members.clear();

        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.PARTY_ACCEPT);
        payload.addProperty("partyId", partyId.toString());
        sendRaw(payload);
        LOGGER.info("[BML-Party] Accepted invite to party {}", partyId);
    }

    /** Declines an invite (sends no packet — the server just times it out). */
    public static void declineInvite(UUID partyId) {
        pendingInvites.removeIf(inv -> inv.partyId().equals(partyId));
        LOGGER.info("[BML-Party] Declined invite to party {}", partyId);
    }

    /** Leaves the current party. */
    public static void leaveParty() {
        if (currentPartyId == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.PARTY_LEAVE);
        payload.addProperty("partyId", currentPartyId.toString());
        sendRaw(payload);
        LOGGER.info("[BML-Party] Left party {}", currentPartyId);
        reset();
    }

    /** Kicks a player from the party (requires admin rights — verified by the server). */
    public static void kickPlayer(String targetNick) {
        if (currentPartyId == null || !isAdmin()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", BmlPackets.PARTY_KICK);
        payload.addProperty("partyId", currentPartyId.toString());
        payload.addProperty("targetNick", targetNick);
        sendRaw(payload);
        LOGGER.info("[BML-Party] Sent kick request for {}", targetNick);
    }

    // ─── Receiving ────────────────────────────────────────────────────────────

    /**
     * Main dispatcher for party packets coming from the server.
     * Called by BmlClientNetworking on the game thread.
     */
    public static void handle(JsonObject json) {
        String type = json.get("type").getAsString();
        switch (type) {
            case BmlPackets.PARTY_INVITE_NOTIFY -> onInviteReceived(json);
            case BmlPackets.PARTY_UPDATE        -> onPartyUpdate(json);
            case BmlPackets.PARTY_LEAVE         -> onPartyLeave(json);
            case BmlPackets.PARTY_ERROR         -> onPartyError(json);
            default -> LOGGER.warn("[BML-Party] Unhandled packet type: {}", type);
        }
    }

    private static void onPartyError(JsonObject json) {
        String msg = json.get("message").getAsString();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§c[BML] " + msg));
        }
    }

    private static void onInviteReceived(JsonObject json) {
        String fromNick = json.get("fromNick").getAsString();
        UUID partyId = UUID.fromString(json.get("partyId").getAsString());
        
        // Prevent duplicate invites from the same party or same player
        if (pendingInvites.stream().anyMatch(inv -> inv.partyId().equals(partyId) || inv.fromNick().equals(fromNick))) {
            return;
        }
        
        pendingInvites.add(new PendingInvite(fromNick, partyId));
        LOGGER.info("[BML-Party] Received invite from {} (party {})", fromNick, partyId);

        // Notify the player.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                Component.literal("§a" + com.betterlist.util.BmlLang.tr("bml.party.invite_received", fromNick)));
        }
        refreshGui();
    }

    private static void onPartyUpdate(JsonObject json) {
        boolean wasInParty = (currentPartyId != null);

        currentPartyId = UUID.fromString(json.get("partyId").getAsString());
        if (json.has("adminNick")) {
            adminNick = json.get("adminNick").getAsString();
        }
        members.clear();
        JsonArray arr = json.getAsJsonArray("members");
        arr.forEach(el -> {
            String nick = el.getAsString();
            members.add(nick);
            addRecentPlayer(nick);
        });
        LOGGER.info("[BML-Party] Party updated: {} admin: {} members: {}", currentPartyId, adminNick, members);

        // Auto-request schematics from party leader when first joining as non-admin
        String self = getSelfNick();
        boolean justJoined = !wasInParty && currentPartyId != null;
        boolean isNotAdmin = self != null && adminNick != null && !self.equalsIgnoreCase(adminNick);
        if (justJoined && isNotAdmin) {
            PlacementSyncHelper.requestPlacementsFromPlayer(adminNick);
            LOGGER.info("[BML-Party] Auto-requesting placements from leader: {}", adminNick);
        }

        // Announce our current targets so new members see them
        com.betterlist.network.BmlClientNetworking.sendTargetUpdate();

        refreshGui();
    }

    private static void onPartyLeave(JsonObject json) {
        LOGGER.info("[BML-Party] Received command from server to leave party.");
        reset();
        refreshGui();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§c" + com.betterlist.util.BmlLang.tr("bml.party.disbanded")));
        }
    }

    private static void refreshGui() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.betterlist.gui.GuiParty currentGui) {
            String pName = currentGui.getPlacementName();
            mc.execute(() -> mc.setScreen(new com.betterlist.gui.GuiParty(pName)));
        }
    }

    // ─── State ────────────────────────────────────────────────────────────────

    public static boolean isInParty() {
        return currentPartyId != null;
    }

    public static UUID getPartyId() {
        return currentPartyId;
    }

    public static List<String> getMembers() {
        List<String> sorted = new ArrayList<>(members);
        if (adminNick != null) {
            sorted.remove(adminNick);
            sorted.add(0, adminNick);
        }
        return Collections.unmodifiableList(sorted);
    }

    public static String getAdminNick() {
        return adminNick;
    }

    public static boolean isAdmin() {
        String self = getSelfNick();
        return self != null && self.equalsIgnoreCase(adminNick);
    }

    public static List<PendingInvite> getPendingInvites() {
        return Collections.unmodifiableList(pendingInvites);
    }

    /** Resets all party state (e.g. on disconnect). */
    public static void reset() {
        currentPartyId = null;
        adminNick = null;
        members.clear();
        pendingInvites.clear();
        FocusManager.clear();
    }

    public static void addRecentPlayer(String nick) {
        if (nick == null || nick.isBlank() || nick.equals(getSelfNick())) return;
        if (!recentPlayersLoaded) loadRecentPlayers();
        recentPlayers.remove(nick);
        recentPlayers.add(0, nick); // Add to the very top.
        if (recentPlayers.size() > MAX_RECENT_PLAYERS) {
            recentPlayers.remove(recentPlayers.size() - 1);
        }
        saveRecentPlayers();
    }

    public static void removeRecentPlayer(String nick) {
        if (!recentPlayersLoaded) loadRecentPlayers();
        if (recentPlayers.remove(nick)) {
            saveRecentPlayers();
        }
    }

    public static List<String> getRecentPlayers() {
        if (!recentPlayersLoaded) loadRecentPlayers();
        return Collections.unmodifiableList(recentPlayers);
    }

    private static void loadRecentPlayers() {
        recentPlayersLoaded = true;
        try {
            File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bml_recent_party.json");
            if (configFile.exists()) {
                String jsonStr = new String(Files.readAllBytes(configFile.toPath()));
                JsonArray arr = JsonParser.parseString(jsonStr).getAsJsonArray();
                recentPlayers.clear();
                arr.forEach(el -> recentPlayers.add(el.getAsString()));
            }
        } catch (Exception e) {
            LOGGER.error("[BML-Party] Failed to load recent players", e);
        }
    }

    private static void saveRecentPlayers() {
        try {
            File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bml_recent_party.json");
            JsonArray arr = new JsonArray();
            recentPlayers.forEach(arr::add);
            Files.write(configFile.toPath(), arr.toString().getBytes());
        } catch (Exception e) {
            LOGGER.error("[BML-Party] Failed to save recent players", e);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String getSelfNick() {
        Minecraft mc = Minecraft.getInstance();
        return (mc.player != null) ? mc.player.getGameProfile().name() : null;
    }

    /** Internal sendRaw — delegates to BmlClientNetworking to avoid a cyclic import. */
    private static void sendRaw(JsonObject payload) {
        com.betterlist.network.BmlClientNetworking.sendRaw(payload);
    }
}
