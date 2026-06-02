package com.betterlist.server;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages BML party state on the server, in memory.
 * Structure: party UUID -> set of online player UUIDs in that party.
 * Also stores the admin (creator) of each party.
 */
public class ServerPartyManager {
    private static final Map<UUID, Set<UUID>> parties = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> partyLeaders = new ConcurrentHashMap<>();

    /**
     * Creates a new party with the given admin (creator).
     * The admin is automatically added as the first member.
     */
    public static void createParty(UUID partyId, UUID adminUUID) {
        Set<UUID> members = ConcurrentHashMap.newKeySet();
        members.add(adminUUID);
        parties.put(partyId, members);
        partyLeaders.put(partyId, adminUUID);
    }

    public static void addPlayerToParty(UUID partyId, UUID player) {
        parties.computeIfAbsent(partyId, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public static void removePlayerFromParty(UUID partyId, UUID player) {
        Set<UUID> members = parties.get(partyId);
        if (members != null) {
            members.remove(player);
            if (members.isEmpty()) {
                parties.remove(partyId);
                partyLeaders.remove(partyId);
            }
        }
    }

    public static Set<UUID> getPartyMembers(UUID partyId) {
        return parties.getOrDefault(partyId, Collections.emptySet());
    }

    public static UUID getLeader(UUID partyId) {
        return partyLeaders.get(partyId);
    }

    public static boolean isLeader(UUID partyId, UUID playerUUID) {
        UUID leader = partyLeaders.get(partyId);
        return leader != null && leader.equals(playerUUID);
    }

    /** Sprawdza czy party o danym ID istnieje na serwerze. */
    public static boolean partyExists(UUID partyId) {
        return parties.containsKey(partyId);
    }

    /** Disbands the party entirely. */
    public static void disbandParty(UUID partyId) {
        parties.remove(partyId);
        partyLeaders.remove(partyId);
    }

    /** Whether the given player already belongs to any party on this server. */
    public static boolean isPlayerInAnyParty(UUID playerUUID) {
        return getPartyIdForPlayer(playerUUID) != null;
    }

    /** Returns the partyId the player is in, or null if none. */
    public static UUID getPartyIdForPlayer(UUID playerUUID) {
        for (Map.Entry<UUID, Set<UUID>> entry : parties.entrySet()) {
            if (entry.getValue().contains(playerUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
