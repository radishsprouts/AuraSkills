package dev.aurelium.auraskills.bukkit.party;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.message.MessageKey;
import dev.aurelium.auraskills.common.party.PartyActionResult;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.party.PartyNotifier;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

public class BukkitPartyNotifier implements PartyNotifier {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final AuraSkills plugin;

    public BukkitPartyNotifier(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public static void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.45f, 1.35f);
    }

    public static void playToggleSound(Player player, boolean enabled) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.6f, enabled ? 1.5f : 0.8f);
    }

    public static void playErrorSound(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    public static void playJoinSound(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
    }

    public String getPlayerName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        String name = offline.getName();
        return name != null ? name : playerId.toString().substring(0, 8);
    }

    public String msg(Locale locale, String keyPath, String fallback) {
        String raw = plugin.getMsg(MessageKey.of(keyPath), locale);
        if (raw == null || raw.isEmpty() || raw.equals(keyPath)) {
            return fallback;
        }
        return raw;
    }

    public void sendMessage(Player player, String miniMessageText) {
        plugin.getScheduler().executeAtEntity(player, task -> {
            if (!player.isOnline()) return;
            Locale locale = plugin.getLocale(player);
            String prefix = plugin.getPrefix(locale);
            Component component = MINI.deserialize(prefix + miniMessageText);
            plugin.getAudiences().player(player).sendMessage(component);
        });
    }

    public void sendActionError(Player player, PartyActionResult result, String targetName) {
        Locale locale = plugin.getLocale(player);
        String text = switch (result) {
            case DISABLED -> msg(locale, "party.error.disabled", "<red>現在パーティー機能は無効化されています。");
            case ALREADY_IN_PARTY -> msg(locale, "party.error.already_in_party", "<red>すでにパーティーに所属しています。");
            case NOT_IN_PARTY -> msg(locale, "party.error.not_in_party", "<red>パーティーに所属していません。");
            case NOT_LEADER -> msg(locale, "party.error.not_leader", "<red>この操作はパーティーリーダーのみ実行できます。");
            case NO_INVITE_PERMISSION -> msg(locale, "party.error.no_invite_permission", "<red>現在、リーダーのみがプレイヤーを招待できます。");
            case CANNOT_TARGET_SELF -> msg(locale, "party.error.cannot_target_self", "<red>自分自身を対象にすることはできません。");
            case TARGET_ALREADY_IN_PARTY -> TextUtil.replace(msg(locale, "party.error.target_already_in_party", "<red>{player} はすでに別のパーティーに所属しています。"), "{player}", targetName);
            case TARGET_NOT_IN_PARTY -> TextUtil.replace(msg(locale, "party.error.target_not_in_party", "<red>{player} はこのパーティーのメンバーではありません。"), "{player}", targetName);
            case TARGET_INVITE_BLOCKED -> TextUtil.replace(msg(locale, "party.error.target_invite_blocked", "<red>{player} はパーティー招待をブロックしています。"), "{player}", targetName);
            case ALREADY_INVITED -> TextUtil.replace(msg(locale, "party.error.already_invited", "<yellow>{player} にはすでに招待を送信済みです。"), "{player}", targetName);
            case PARTY_FULL -> msg(locale, "party.error.party_full", "<red>パーティーの最大人数に達しています。");
            case NO_PENDING_INVITE -> msg(locale, "party.error.no_pending_invite", "<red>有効なパーティー招待が見つかりません。");
            case INVITE_EXPIRED -> msg(locale, "party.error.invite_expired", "<red>そのパーティー招待は期限切れです。");
            case CANNOT_KICK_LEADER -> msg(locale, "party.error.cannot_kick_leader", "<red>リーダーをキックすることはできません。");
            case SUCCESS -> "";
        };
        if (!text.isEmpty()) {
            sendMessage(player, text);
            plugin.getScheduler().executeAtEntity(player, task -> playErrorSound(player));
        }
    }

    @Override
    public void onInviteSent(UUID inviterId, UUID targetId, PartyImpl party) {
        String inviterName = getPlayerName(inviterId);
        String targetName = getPlayerName(targetId);

        Player inviter = Bukkit.getPlayer(inviterId);
        if (inviter != null && inviter.isOnline()) {
            Locale loc = plugin.getLocale(inviter);
            String sent = TextUtil.replace(msg(loc, "party.invite_sent", "<green>{player} にパーティー招待を送信しました。"), "{player}", targetName);
            sendMessage(inviter, sent);
            plugin.getScheduler().executeAtEntity(inviter, task -> playClickSound(inviter));
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            plugin.getScheduler().executeAtEntity(target, task -> {
                if (!target.isOnline()) return;
                Locale loc = plugin.getLocale(target);
                String prefix = plugin.getPrefix(loc);
                String received = TextUtil.replace(msg(loc, "party.invite_received", "<aqua>{inviter} <gray>からパーティーに招待されました！"), "{inviter}", inviterName);
                String acceptBtn = msg(loc, "party.invite_accept_button", "<green><bold>[参加する]");
                String denyBtn = msg(loc, "party.invite_deny_button", "<red><bold>[辞退する]");

                Component acceptComp = MINI.deserialize(acceptBtn)
                        .clickEvent(ClickEvent.runCommand("/party accept " + inviterName))
                        .hoverEvent(HoverEvent.showText(MINI.deserialize("<green>/party accept " + inviterName)));
                Component denyComp = MINI.deserialize(denyBtn)
                        .clickEvent(ClickEvent.runCommand("/party deny " + inviterName))
                        .hoverEvent(HoverEvent.showText(MINI.deserialize("<red>/party deny " + inviterName)));

                Component full = MINI.deserialize(prefix + received + " ")
                        .append(acceptComp)
                        .append(Component.text(" "))
                        .append(denyComp);

                plugin.getAudiences().player(target).sendMessage(full);
                playJoinSound(target);
            });
        }
    }

    @Override
    public void onMemberJoined(PartyImpl party, UUID joinedPlayerId) {
        String joinedName = getPlayerName(joinedPlayerId);
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) continue;
            Locale loc = plugin.getLocale(member);
            String text = TextUtil.replace(msg(loc, "party.joined", "<green>{player} がパーティーに参加しました！"), "{player}", joinedName);
            sendMessage(member, text);
            plugin.getScheduler().executeAtEntity(member, task -> playJoinSound(member));
        }
    }

    @Override
    public void onMemberLeft(PartyImpl party, UUID leftPlayerId, boolean timedOut) {
        String leftName = getPlayerName(leftPlayerId);
        String key = timedOut ? "party.left_timeout" : "party.left";
        String fallback = timedOut
                ? "<gray>{player} はオフライン時間超過のためパーティーから自動離脱しました。"
                : "<yellow>{player} がパーティーから脱退しました。";

        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, TextUtil.replace(msg(loc, key, fallback), "{player}", leftName));
            }
        }
        if (!timedOut) {
            Player left = Bukkit.getPlayer(leftPlayerId);
            if (left != null && left.isOnline()) {
                Locale loc = plugin.getLocale(left);
                sendMessage(left, TextUtil.replace(msg(loc, key, fallback), "{player}", leftName));
            }
        }
    }

    @Override
    public void onMemberKicked(PartyImpl party, UUID kickedPlayerId) {
        String kickedName = getPlayerName(kickedPlayerId);
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, TextUtil.replace(msg(loc, "party.kicked", "<red>{player} がパーティーからキックされました。"), "{player}", kickedName));
            }
        }
        Player kicked = Bukkit.getPlayer(kickedPlayerId);
        if (kicked != null && kicked.isOnline()) {
            Locale loc = plugin.getLocale(kicked);
            sendMessage(kicked, msg(loc, "party.kicked_self", "<red>パーティーからキックされました。"));
            plugin.getScheduler().executeAtEntity(kicked, task -> playErrorSound(kicked));
        }
    }

    @Override
    public void onLeaderPromoted(PartyImpl party, UUID newLeaderId, boolean autoPromoted) {
        String leaderName = getPlayerName(newLeaderId);
        String key = autoPromoted ? "party.promoted_auto" : "party.promoted";
        String fallback = autoPromoted
                ? "<gold>リーダーが不在となったため、{player} が新しいリーダーに引き継がれました！"
                : "<gold>{player} が新しいパーティーリーダーになりました！";
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, TextUtil.replace(msg(loc, key, fallback), "{player}", leaderName));
                plugin.getScheduler().executeAtEntity(member, task -> playJoinSound(member));
            }
        }
    }

    @Override
    public void onPartyDisbanded(Collection<UUID> formerMembers, UUID leaderId) {
        String leaderName = getPlayerName(leaderId);
        for (UUID memberId : formerMembers) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, TextUtil.replace(msg(loc, "party.disbanded", "<yellow>{leader} のパーティーが解散されました。"), "{leader}", leaderName));
            }
        }
    }

    @Override
    public void onFriendlyFireToggled(PartyImpl party, boolean protectedState) {
        String key = protectedState ? "party.ff_toggled_on" : "party.ff_toggled_off";
        String fallback = protectedState
                ? "<green>パーティー内の誤爆防止（フレンドリーファイア無効）を <bold>ON</bold><green> にしました。"
                : "<red>パーティー内の誤爆防止（フレンドリーファイア無効）を <bold>OFF</bold><red> にしました。";
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, msg(loc, key, fallback));
                plugin.getScheduler().executeAtEntity(member, task -> playToggleSound(member, protectedState));
            }
        }
    }

    @Override
    public void onMemberInviteToggled(PartyImpl party, boolean allowed) {
        String key = allowed ? "party.member_invite_on" : "party.member_invite_off";
        String fallback = allowed
                ? "<green>メンバーによる招待許可を <bold>ON</bold><green> にしました。"
                : "<yellow>メンバーによる招待許可を <bold>OFF</bold><yellow> にしました。";
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                Locale loc = plugin.getLocale(member);
                sendMessage(member, msg(loc, key, fallback));
                plugin.getScheduler().executeAtEntity(member, task -> playToggleSound(member, allowed));
            }
        }
    }

}
