package com.example.server;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zarządza stanem party na serwerze BML w pamięci RAM.
 * Struktura: UUID party -> zbiór UUID graczy online w danym party.
 * Przechowuje też admina (twórcę) każdego party.
 */
public class ServerPartyManager {
    private static final Map<UUID, Set<UUID>> parties = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> partyLeaders = new ConcurrentHashMap<>();

    /**
     * Tworzy nowe party z podanym adminem (twórcą).
     * Admin jest automatycznie dodawany jako pierwszy członek.
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

    /** Rozwiązuje party całkowicie. */
    public static void disbandParty(UUID partyId) {
        parties.remove(partyId);
        partyLeaders.remove(partyId);
    }

    /** Sprawdza czy dany gracz już należy do jakiegokolwiek party na tym serwerze. */
    public static boolean isPlayerInAnyParty(UUID playerUUID) {
        return getPartyIdForPlayer(playerUUID) != null;
    }

    /** Zwraca partyId, w którym znajduje się gracz, lub null jeśli nie jest w żadnym. */
    public static UUID getPartyIdForPlayer(UUID playerUUID) {
        for (Map.Entry<UUID, Set<UUID>> entry : parties.entrySet()) {
            if (entry.getValue().contains(playerUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
