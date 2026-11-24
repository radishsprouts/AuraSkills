package dev.aurelium.auraskills.common.party;

import java.util.UUID;

public record PartyInvite(
        UUID partyId,
        UUID inviterId,
        UUID targetId,
        long expiresAtMillis
) {

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

}
