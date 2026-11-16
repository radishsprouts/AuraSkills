package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.message.MessageKey;
import dev.aurelium.auraskills.common.ui.ActionBarType;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 経験値が減額・0になった理由を、プレイヤーのアクションバーへ出す。
 * 同じプレイヤーへの同じ理由は3秒に1回まで。送信はプレイヤーのスケジューラ上で行う（Folia 対応）。
 */
public class XpReductionNotifier {

    /** 理由をアクションバーに出し続ける時間。読める長さにするため2秒 */
    private static final int NOTICE_DURATION_MS = 2000;

    private final AuraSkills plugin;
    private final XpNoticeRateLimiter limiter = new XpNoticeRateLimiter(XpNoticeRateLimiter.DEFAULT_INTERVAL_MILLIS);

    public XpReductionNotifier(AuraSkills plugin) {
        this.plugin = plugin;
    }

    /**
     * @param percent 0 なら「経験値なし」。1〜99 なら割合つきの減額。100以上・負なら何も出さない。
     */
    public void notifyReduction(Player player, XpReductionReason reason, int percent) {
        if (percent < 0 || percent >= 100) {
            log(player, reason, "送らない: 割合が範囲外 (" + percent + "%)");
            return;
        }
        if (!limiter.tryAcquire(player.getUniqueId(), reason.name(), System.currentTimeMillis())) {
            log(player, reason, "送らない: 同じ理由を3秒以内に通知済み");
            return;
        }
        send(player, reason, percent);
    }

    private void send(Player player, XpReductionReason reason, int percent) {
        if (!plugin.configBoolean(Option.ACTION_BAR_ENABLED)) {
            log(player, reason, "送らない: action_bar.enabled が false");
            return;
        }
        plugin.getScheduler().executeAtEntity(player, task -> {
            User user = plugin.getUser(player);
            if (user == null) {
                log(player, reason, "送らない: ユーザーデータが無い");
                return;
            }
            // プレイヤーが経験値のアクションバーを無効にしている時は出さない（/skills の個人設定を尊重する）
            if (!user.isActionBarEnabled(ActionBarType.XP)) {
                log(player, reason, "送らない: プレイヤーが経験値のアクションバーを無効にしている");
                return;
            }
            Locale locale = user.getLocale();
            String format = plugin.getMsg(MessageKey.of(percent <= 0
                    ? "xp_reduction.format_zero" : "xp_reduction.format_reduced"), locale);
            String reasonText = plugin.getMsg(MessageKey.of(reason.getMessagePath()), locale);
            String text = TextUtil.replace(format, "{percent}", String.valueOf(percent), "{reason}", reasonText);
            // 理由を優先して出す。期限内は待機・経験値獲得の表示が上書きせず、周期の更新が再送する。
            // 期限が切れたら、通常の表示（経験値獲得中ならその表示）へ戻る
            plugin.getUiProvider().getActionBarManager().showNotice(user, text, NOTICE_DURATION_MS, TimeUnit.MILLISECONDS);
            log(player, reason, "送った: 割合=" + percent + "% 表示=" + NOTICE_DURATION_MS + "ms 文言=" + text);
        });
    }

    /** 切り分け用のログ。判定ログと同じ設定（anti_afk.logging_enabled）が有効な時だけ1行出す。 */
    private void log(Player player, XpReductionReason reason, String detail) {
        if (!plugin.configBoolean(Option.ANTI_AFK_LOGGING_ENABLED)) return;
        plugin.logger().info("[CombatXp] 通知 " + player.getName() + " 理由=" + reason.name() + " " + detail);
    }
}
