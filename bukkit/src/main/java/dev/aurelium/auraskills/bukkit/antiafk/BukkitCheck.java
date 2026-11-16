package dev.aurelium.auraskills.bukkit.antiafk;

import dev.aurelium.auraskills.api.event.skill.XpGainEvent;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.level.BukkitLevelManager;
import dev.aurelium.auraskills.bukkit.source.XpReductionReason;
import dev.aurelium.auraskills.common.antiafk.AntiAfkManager;
import dev.aurelium.auraskills.common.antiafk.Check;
import dev.aurelium.auraskills.common.antiafk.CheckData;
import dev.aurelium.auraskills.common.antiafk.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import static dev.aurelium.auraskills.bukkit.ref.BukkitPlayerRef.wrap;

public class BukkitCheck extends Check implements Listener {

    public BukkitCheck(CheckType type, AntiAfkManager manager) {
        super(type, manager);
    }

    protected CheckData getCheckData(Player player) {
        return getCheckData(wrap(player));
    }

    protected void logFail(Player player) {
        logFail(wrap(player));
    }

    /**
     * 判定に失敗した XpGainEvent を取り消し、ログを残し、プレイヤーへ理由を伝える（理由は同じプレイヤーに3秒に1回まで）。
     */
    protected void cancelXpGain(XpGainEvent event, Player player, XpReductionReason reason) {
        event.setCancelled(true);
        logFail(player);
        if (plugin instanceof AuraSkills auraSkills && auraSkills.getLevelManager() instanceof BukkitLevelManager levelManager) {
            levelManager.getXpReductionNotifier().notifyReduction(player, reason, 0);
        }
    }

}
