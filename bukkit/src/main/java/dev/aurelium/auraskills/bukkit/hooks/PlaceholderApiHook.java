package dev.aurelium.auraskills.bukkit.hooks;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.user.BukkitUser;
import dev.aurelium.auraskills.common.hooks.Hook;
import dev.aurelium.auraskills.common.hooks.PlaceholderHook;
import dev.aurelium.auraskills.common.user.User;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.spongepowered.configurate.ConfigurationNode;

public class PlaceholderApiHook extends PlaceholderHook {

    public PlaceholderApiHook(AuraSkills plugin, ConfigurationNode config) {
        super(plugin, config);
        if (!plugin.getServer().getPluginManager().isPluginEnabled(Hooks.PLACEHOLDER_API.getPluginName())) {
            // Defer if plugin is loaded but not enabled yet
            plugin.getScheduler().executeSync(() -> {
                new PlaceholderApiProvider(plugin, "auraskills").register();
                new PlaceholderApiProvider(plugin, "aureliumskills").register();
            });
        } else {
            new PlaceholderApiProvider(plugin, "auraskills").register();
            new PlaceholderApiProvider(plugin, "aureliumskills").register();
        }
    }

    @Override
    public String setPlaceholders(User user, String message) {
        Player player = ((BukkitUser) user).getPlayer();
        if (player != null) {
            return PlaceholderAPI.setPlaceholders(player, message);
        } else {
            return message;
        }
    }

    public String setPlaceholders(OfflinePlayer player, String message) {
        if (player == null) {
            return message;
        }
        if (player.isOnline() && player.getPlayer() != null) {
            return PlaceholderAPI.setPlaceholders(player.getPlayer(), message);
        } else {
            return PlaceholderAPI.setPlaceholders(player, message);
        }
    }

    @Override
    public Class<? extends Hook> getTypeClass() {
        return PlaceholderHook.class;
    }

}
