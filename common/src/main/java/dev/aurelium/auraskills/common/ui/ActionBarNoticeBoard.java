package dev.aurelium.auraskills.common.ui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 経験値の減額理由など、通常の表示（待機・経験値獲得）より優先して見せたい文言を、期限つきで持つ。
 * 期限内は、周期の更新が文言を再送できるようにする（別の送信が一瞬上書きしても、次の周期で戻る）。
 * 時刻は呼び出し側から渡す（試験しやすくするため）。
 */
public class ActionBarNoticeBoard {

    private record Notice(String text, long expiresAtMillis) {
    }

    private final Map<UUID, Notice> notices = new ConcurrentHashMap<>();

    /** 文言を登録する。同じプレイヤーの既存の文言は置き換える。 */
    public void post(UUID uuid, String text, long nowMillis, long durationMillis) {
        notices.put(uuid, new Notice(text, nowMillis + durationMillis));
    }

    /** 期限内の文言を返す。無い・期限切れなら null（期限切れは取り除く）。 */
    public String active(UUID uuid, long nowMillis) {
        Notice notice = notices.get(uuid);
        if (notice == null) return null;
        if (nowMillis >= notice.expiresAtMillis()) {
            notices.remove(uuid, notice);
            return null;
        }
        return notice.text();
    }

    public void clear(UUID uuid) {
        notices.remove(uuid);
    }

    public void clearAll() {
        notices.clear();
    }
}
