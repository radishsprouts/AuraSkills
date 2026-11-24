package dev.aurelium.auraskills.bukkit.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.party.BukkitPartyNotifier;
import dev.aurelium.auraskills.common.party.PartyActionResult;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.party.PartyManagerImpl;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

@CommandAlias("party")
public class PartyCommand extends BaseCommand {

    private final AuraSkills plugin;

    public PartyCommand(AuraSkills plugin) {
        this.plugin = plugin;
    }

    @Default
    @CommandPermission("auraskills.command.party")
    public void onDefault(Player player) {
        plugin.getSlate().openMenu(player, "party");
    }

    @Subcommand("create")
    @CommandPermission("auraskills.command.party")
    public void onCreate(Player player) {
        PartyActionResult res = plugin.getPartyManager().createParty(player.getUniqueId());
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (res == PartyActionResult.SUCCESS) {
            notifier.sendMessage(player, notifier.msg(plugin.getLocale(player), "party.created", "<green>パーティーを作成しました！"));
            BukkitPartyNotifier.playJoinSound(player);
        } else {
            notifier.sendActionError(player, res, "");
        }
    }

    @Subcommand("invite")
    @CommandPermission("auraskills.command.party")
    @CommandCompletion("@players")
    public void onInvite(Player player, @Single String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (target == null || !target.isOnline()) {
            notifier.sendMessage(player, "<red>プレイヤー " + targetName + " が見つかりません。");
            BukkitPartyNotifier.playErrorSound(player);
            return;
        }
        PartyActionResult res = plugin.getPartyManager().invitePlayer(player.getUniqueId(), target.getUniqueId());
        if (res != PartyActionResult.SUCCESS) {
            notifier.sendActionError(player, res, target.getName());
        }
    }

    @Subcommand("accept")
    @CommandPermission("auraskills.command.party")
    @CommandCompletion("@players")
    public void onAccept(Player player, @Optional String inviterName) {
        UUID inviterId = inviterName != null ? resolvePlayerId(inviterName) : null;
        PartyActionResult res = plugin.getPartyManager().acceptInvite(player.getUniqueId(), inviterId);
        if (res != PartyActionResult.SUCCESS) {
            new BukkitPartyNotifier(plugin).sendActionError(player, res, inviterName != null ? inviterName : "");
        }
    }

    @Subcommand("deny")
    @CommandPermission("auraskills.command.party")
    @CommandCompletion("@players")
    public void onDeny(Player player, @Optional String inviterName) {
        UUID inviterId = inviterName != null ? resolvePlayerId(inviterName) : null;
        PartyActionResult res = plugin.getPartyManager().denyInvite(player.getUniqueId(), inviterId);
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (res == PartyActionResult.SUCCESS) {
            String display = inviterName != null ? inviterName : "相手";
            notifier.sendMessage(player, TextUtil.replace(
                    notifier.msg(plugin.getLocale(player), "party.invite_denied", "<gray>{inviter} からのパーティー招待を辞退しました。"),
                    "{inviter}", display));
            BukkitPartyNotifier.playToggleSound(player, false);
        } else {
            notifier.sendActionError(player, res, inviterName != null ? inviterName : "");
        }
    }

    @Subcommand("leave")
    @CommandPermission("auraskills.command.party")
    public void onLeave(Player player) {
        PartyActionResult res = plugin.getPartyManager().leaveParty(player.getUniqueId());
        if (res != PartyActionResult.SUCCESS) {
            new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
        } else {
            BukkitPartyNotifier.playToggleSound(player, false);
        }
    }

    @Subcommand("kick")
    @CommandPermission("auraskills.command.party")
    @CommandCompletion("@players")
    public void onKick(Player player, @Single String targetName) {
        UUID targetId = resolveMemberId(player, targetName);
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (targetId == null) {
            notifier.sendActionError(player, PartyActionResult.TARGET_NOT_IN_PARTY, targetName);
            return;
        }
        PartyActionResult res = plugin.getPartyManager().kickMember(player.getUniqueId(), targetId);
        if (res != PartyActionResult.SUCCESS) {
            notifier.sendActionError(player, res, targetName);
        }
    }

    @Subcommand("promote")
    @CommandPermission("auraskills.command.party")
    @CommandCompletion("@players")
    public void onPromote(Player player, @Single String targetName) {
        UUID targetId = resolveMemberId(player, targetName);
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (targetId == null) {
            notifier.sendActionError(player, PartyActionResult.TARGET_NOT_IN_PARTY, targetName);
            return;
        }
        PartyActionResult res = plugin.getPartyManager().promoteLeader(player.getUniqueId(), targetId);
        if (res != PartyActionResult.SUCCESS) {
            notifier.sendActionError(player, res, targetName);
        }
    }

    @Subcommand("disband")
    @CommandPermission("auraskills.command.party")
    public void onDisband(Player player) {
        PartyActionResult res = plugin.getPartyManager().disbandParty(player.getUniqueId());
        if (res != PartyActionResult.SUCCESS) {
            new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
        } else {
            BukkitPartyNotifier.playToggleSound(player, false);
        }
    }

    @Subcommand("info")
    @CommandPermission("auraskills.command.party")
    public void onInfo(Player player) {
        PartyManagerImpl pm = plugin.getPartyManager();
        PartyImpl party = pm.getPartyImpl(player.getUniqueId());
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        if (party == null) {
            notifier.sendActionError(player, PartyActionResult.NOT_IN_PARTY, "");
            return;
        }
        String leaderName = notifier.getPlayerName(party.getLeader());
        StringBuilder members = new StringBuilder();
        for (UUID id : party.getMembers()) {
            if (!members.isEmpty()) members.append("<gray>, ");
            String name = notifier.getPlayerName(id);
            members.append(party.isLeader(id) ? "<gold>★" + name : "<aqua>" + name);
        }
        notifier.sendMessage(player, "<gold>" + leaderName + " のパーティー <gray>(" + party.getSize() + "人)");
        notifier.sendMessage(player, "<gray>メンバー: " + members);
        notifier.sendMessage(player, "<gray>誤爆防止: " + (party.isFriendlyFireProtected() ? "<green>ON" : "<red>OFF")
                + " <gray>| メンバー招待: " + (party.isMemberInviteAllowed() ? "<green>ON" : "<yellow>リーダーのみ"));
    }

    @Subcommand("toggle block")
    @CommandPermission("auraskills.command.party")
    public void onToggleBlock(Player player) {
        boolean blocked = plugin.getPartyManager().toggleInviteBlock(player.getUniqueId());
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        String key = blocked ? "party.block_on" : "party.block_off";
        String fallback = blocked
                ? "<yellow>パーティー招待の受信ブロックを <bold>ON</bold><yellow> にしました。"
                : "<green>パーティー招待の受信ブロックを <bold>OFF</bold><green> にしました。";
        notifier.sendMessage(player, notifier.msg(plugin.getLocale(player), key, fallback));
        BukkitPartyNotifier.playToggleSound(player, !blocked);
    }

    @Subcommand("toggle ff")
    @CommandPermission("auraskills.command.party")
    public void onToggleFf(Player player) {
        PartyActionResult res = plugin.getPartyManager().toggleFriendlyFire(player.getUniqueId());
        if (res != PartyActionResult.SUCCESS) {
            new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
        }
    }

    @Subcommand("toggle memberinvite")
    @CommandPermission("auraskills.command.party")
    public void onToggleMemberInvite(Player player) {
        PartyActionResult res = plugin.getPartyManager().toggleMemberInvite(player.getUniqueId());
        if (res != PartyActionResult.SUCCESS) {
            new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
        }
    }

    private UUID resolvePlayerId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.getUniqueId();
    }

    private UUID resolveMemberId(Player actor, String name) {
        PartyImpl party = plugin.getPartyManager().getPartyImpl(actor.getUniqueId());
        if (party == null) return null;
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        for (UUID memberId : party.getMembers()) {
            if (notifier.getPlayerName(memberId).equalsIgnoreCase(name)) {
                return memberId;
            }
        }
        return null;
    }

}
