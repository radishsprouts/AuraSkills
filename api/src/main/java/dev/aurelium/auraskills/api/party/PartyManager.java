package dev.aurelium.auraskills.api.party;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * API interface for querying AuraSkills party state.
 */
public interface PartyManager {

    /**
     * Gets the party the player currently belongs to, or null if not in a party.
     *
     * @param playerId the player UUID
     * @return the party, or null
     */
    @Nullable
    Party getParty(UUID playerId);

    /**
     * Checks whether the player is currently in a party.
     *
     * @param playerId the player UUID
     * @return true if in a party
     */
    boolean isInParty(UUID playerId);

    /**
     * Checks whether two players belong to the same party.
     *
     * @param playerA the first player UUID
     * @param playerB the second player UUID
     * @return true if both players are in the same party
     */
    boolean isInSameParty(UUID playerA, UUID playerB);

    /**
     * Gets an unmodifiable snapshot of the UUIDs of all members in the player's party,
     * or an empty set if the player is not in a party.
     *
     * @param playerId the player UUID
     * @return an unmodifiable set of member UUIDs
     */
    Set<UUID> getPartyMembers(UUID playerId);

    /**
     * Gets the configured maximum party size, or 0 if unlimited.
     *
     * @return max party size, or 0 for unlimited
     */
    int getMaxPartySize();

}
