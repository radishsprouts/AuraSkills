package dev.aurelium.auraskills.api.party;

import java.util.Set;
import java.util.UUID;

/**
 * Represents a read-only view of an AuraSkills party.
 */
public interface Party {

    /**
     * Gets the unique id of this party.
     *
     * @return the party UUID
     */
    UUID getId();

    /**
     * Gets the UUID of the current party leader.
     *
     * @return the leader UUID
     */
    UUID getLeader();

    /**
     * Gets an unmodifiable snapshot of the member UUIDs in this party (including the leader).
     *
     * @return an unmodifiable set of member UUIDs
     */
    Set<UUID> getMembers();

    /**
     * Checks whether the specified player is a member of this party.
     *
     * @param playerId the player UUID
     * @return true if the player is a member
     */
    boolean isMember(UUID playerId);

    /**
     * Checks whether the specified player is the leader of this party.
     *
     * @param playerId the player UUID
     * @return true if the player is the leader
     */
    boolean isLeader(UUID playerId);

    /**
     * Checks whether friendly-fire protection is enabled for this party.
     *
     * @return true if friendly-fire is prevented
     */
    boolean isFriendlyFireProtected();

    /**
     * Checks whether non-leader members are allowed to invite other players.
     *
     * @return true if member invites are allowed
     */
    boolean isMemberInviteAllowed();

    /**
     * Gets the number of members currently in this party.
     *
     * @return the member count
     */
    int getSize();

    /**
     * Gets the epoch timestamp in milliseconds when this party was created.
     *
     * @return creation timestamp in millis
     */
    long getCreatedAtMillis();

}
