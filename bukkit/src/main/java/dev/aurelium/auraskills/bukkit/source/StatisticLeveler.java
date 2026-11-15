package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.source.SkillSource;
import dev.aurelium.auraskills.api.source.type.StatisticXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.scheduler.TaskRunnable;
import dev.aurelium.auraskills.common.source.SourceTypes;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class StatisticLeveler extends SourceLeveler {

    private final Map<UUID, Map<StatisticXpSource, Integer>> tracker = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastCheckLocations = new ConcurrentHashMap<>();

    public StatisticLeveler(AuraSkills plugin) {
        super(plugin, SourceTypes.STATISTIC);
        startTracking();
    }

    public void startTracking() {
        var task = new TaskRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    handlePlayer(player);
                }
            }
        };
        plugin.getScheduler().timerSync(task, 0, plugin.configInt(Option.SOURCE_STATISTIC_GAIN_PERIOD_TICKS) * 50L, TimeUnit.MILLISECONDS);
    }

    private void handlePlayer(Player player) {
        // Disable in spectator mode
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        User user = plugin.getUser(player);

        Location currentLoc = player.getLocation();
        Location lastLoc = lastCheckLocations.put(player.getUniqueId(), currentLoc.clone());

        var sources = plugin.getSkillManager().getSourcesOfType(StatisticXpSource.class);
        for (SkillSource<StatisticXpSource> entry : sources) {
            if (failsChecks(player, player.getLocation(), entry.skill())) return;

            StatisticXpSource source = entry.source();
            try {
                Statistic statistic = Statistic.valueOf(source.getStatistic().toUpperCase(Locale.ROOT));

                // Get the last tracked statistic value and add current value if missing
                int lastValue = tracker.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>())
                        .computeIfAbsent(source, s -> player.getStatistic(statistic));

                int change = player.getStatistic(statistic) - lastValue;

                // Make sure change in value is at least the minimum
                if (change <= 0) continue;
                if (change < source.getMinimumIncrease()) continue;

                // Update tracker with current value regardless of whether XP is granted or blocked
                tracker.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>()).put(source, player.getStatistic(statistic));

                // Anti-AFK movement pool / loop check for movement statistics (walk, sprint, swim)
                String statName = source.getStatistic().toLowerCase(Locale.ROOT);
                if (statName.contains("walk") || statName.contains("sprint") || statName.contains("swim")) {
                    if (lastLoc != null && Objects.equals(lastLoc.getWorld(), currentLoc.getWorld())) {
                        // If moved large distance in statistics but remained within 16m radius over the tracking period
                        if (currentLoc.distanceSquared(lastLoc) <= 16.0 * 16.0) {
                            continue; // Block XP for water pool / circular AFK loop
                        }
                    }
                }

                double xpToAdd = change * source.getMultiplier() * source.getXp();

                plugin.getLevelManager().addXp(user, entry.skill(), source, xpToAdd);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

}
