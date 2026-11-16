package dev.aurelium.auraskills.bukkit.source;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同じプレイヤーへの同じ理由の通知を、一定間隔に1回までに絞る。時刻を引数で受けるので単体試験できる。
 */
public class XpNoticeRateLimiter {

    public static final long DEFAULT_INTERVAL_MILLIS = 3_000L;
    private static final int PRUNE_THRESHOLD = 512;

    private final long intervalMillis;
    private final Map<String, Long> last = new ConcurrentHashMap<>();

    public XpNoticeRateLimiter(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** 通知してよければ true を返し、時刻を記録する。 */
    public boolean tryAcquire(UUID player, String reasonKey, long nowMillis) {
        if (last.size() > PRUNE_THRESHOLD) {
            last.values().removeIf(t -> nowMillis - t > intervalMillis);
        }
        String key = player + "|" + reasonKey;
        Long previous = last.get(key);
        if (previous != null && nowMillis - previous < intervalMillis) {
            return false;
        }
        last.put(key, nowMillis);
        return true;
    }
}
