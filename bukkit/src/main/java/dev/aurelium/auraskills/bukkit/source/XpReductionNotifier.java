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

    private static final int XP_BAR_PAUSE_MS = 1500;

    private final AuraSkills plugin;
    private final XpNoticeRateLimiter limiter = new XpNoticeRateLimiter(XpNoticeRateLimiter.DEFAULT_INTERVAL_MILLIS);

    public XpReductionNotifier(AuraSkills plugin) {
        this.plugin = plugin;
    }

    /**
     * @param percent 0 なら「経験値なし」。1〜99 なら割合つきの減額。100以上・負なら何も出さない。
     */
    public void notifyReduction(Player player, XpReductionReason reason, int percent) {
        if (percent < 0 || percent >= 100) return;
        if (!limiter.tryAcquire(player.getUniqueId(), reason.name(), System.currentTimeMillis())) return;
        send(player, reason, percent);
    }

    private void send(Player player, XpReductionReason reason, int percent) {
        if (!plugin.configBoolean(Option.ACTION_BAR_ENABLED)) return;
        plugin.getScheduler().executeAtEntity(player, task -> {
            User user = plugin.getUser(player);
            if (user == null || !user.isActionBarEnabled(ActionBarType.XP)) return;
            Locale locale = user.getLocale();
            String format = plugin.getMsg(MessageKey.of(percent <= 0
                    ? "xp_reduction.format_zero" : "xp_reduction.format_reduced"), locale);
            String reasonText = plugin.getMsg(MessageKey.of(reason.getMessagePath()), locale);
            String text = TextUtil.replace(format, "{percent}", String.valueOf(percent), "{reason}", reasonText);
            plugin.getUiProvider().sendActionBar(user, text);
            // 通常の経験値のアクションバーの代わりに出すため、直後の経験値表示を短く止める
            plugin.getUiProvider().getActionBarManager().setPaused(user, XP_BAR_PAUSE_MS, TimeUnit.MILLISECONDS);
        });
    }
}
