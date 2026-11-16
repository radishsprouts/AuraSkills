package dev.aurelium.auraskills.bukkit.source;

/**
 * 経験値が減額・0になった理由。メッセージファイルの {@code xp_reduction.reason.<messageKey>} に対応する。
 */
public enum XpReductionReason {

    // 確定的な罠（経験値は0）
    NO_AI("no_ai", true),
    PLAYER_GOLEM("player_golem", true),
    STATIONARY("stationary", true),
    CHUNK_KILL_MAX("chunk_kill_max", true),
    OTHER_TRAP("other_trap", true),
    // 棘の鎧・反撃などの中立のダメージだけで倒した（対AFKの経験値稼ぎ対策。経験値は0）
    THORNS_ONLY("thorns_only", true),

    // 不正な攻撃（その分だけ減額）
    VEHICLE_TRAPPED("vehicle_trapped", false),
    FROM_BOAT("from_boat", false),
    HELD_IN_PLACE("held_in_place", false),
    CRAMMED("crammed", false),
    NO_SIGHT("no_sight", false),
    WATER_CURRENT("water_current", false),
    UNREACHABLE("unreachable", false),

    // 環境（他のモブ・ペット・爆発・落下など）
    ENVIRONMENT("environment", false),

    // 対AFK（経験値は0）
    AFK_POSITION("afk_position", true),
    AFK_FACING("afk_facing", true),
    AFK_TARGET("afk_target", true),
    AFK_BLOCK("afk_block", true),
    AFK_FISHING("afk_fishing", true);

    private final String messageKey;
    private final boolean zero;

    XpReductionReason(String messageKey, boolean zero) {
        this.messageKey = messageKey;
        this.zero = zero;
    }

    public String getMessageKey() {
        return messageKey;
    }

    /** 経験値が0になる（確定的な罠・対AFK）理由かどうか。 */
    public boolean isZero() {
        return zero;
    }

    public String getMessagePath() {
        return "xp_reduction.reason." + messageKey;
    }
}
