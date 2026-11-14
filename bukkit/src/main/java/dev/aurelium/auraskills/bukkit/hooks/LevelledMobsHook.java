package dev.aurelium.auraskills.bukkit.hooks;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ParseException;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.hooks.Hook;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.spongepowered.configurate.ConfigurationNode;

public class LevelledMobsHook extends Hook {

    private final NamespacedKey levelKey;
    private final NamespacedKey chunkKillMaxKey;

    private double xpMultiplier;
    private String expressionString;
    private double maxMultiplier;
    private boolean respectChunkKillMax;

    public LevelledMobsHook(AuraSkills plugin, ConfigurationNode config) {
        super(plugin, config);
        this.levelKey = new NamespacedKey("levelledmobs", "level");
        this.chunkKillMaxKey = new NamespacedKey("levelledmobs", "chunk_kill_max_reached");
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationNode config = getConfig();
        this.xpMultiplier = config.node("xp_multiplier").getDouble(0.1);
        this.expressionString = config.node("expression").getString("1 + (level - 1) * xp_multiplier");
        this.maxMultiplier = config.node("max_multiplier").getDouble(0.0);
        this.respectChunkKillMax = config.node("respect_chunk_kill_max").getBoolean(true);
    }

    @Override
    public void reload(ConfigurationNode config) {
        super.reload(config);
        loadConfig();
    }

    public double getXpMultiplier(LivingEntity entity) {
        if (entity == null) {
            return 1.0;
        }

        PersistentDataContainer pdc = entity.getPersistentDataContainer();

        // If chunk kill max was reached and respect is enabled, give 0 XP to prevent farm abuse
        if (respectChunkKillMax && pdc.has(chunkKillMaxKey, PersistentDataType.INTEGER)) {
            return 0.0;
        }

        if (!pdc.has(levelKey, PersistentDataType.INTEGER)) {
            return 1.0;
        }

        Integer level = pdc.get(levelKey, PersistentDataType.INTEGER);
        if (level == null || level <= 1) {
            return 1.0;
        }

        double multiplier;
        if (expressionString != null && !expressionString.isEmpty()) {
            try {
                Expression expression = new Expression(expressionString)
                        .with("level", level)
                        .with("xp_multiplier", xpMultiplier);
                EvaluationValue value = expression.evaluate();
                multiplier = value.getNumberValue().doubleValue();
            } catch (EvaluationException | ParseException e) {
                multiplier = 1.0 + (level - 1) * xpMultiplier;
            }
        } else {
            multiplier = 1.0 + (level - 1) * xpMultiplier;
        }

        if (maxMultiplier > 0.0 && multiplier > maxMultiplier) {
            multiplier = maxMultiplier;
        }

        if (multiplier < 0.0) {
            multiplier = 0.0;
        }

        return multiplier;
    }

    @Override
    public Class<? extends Hook> getTypeClass() {
        return LevelledMobsHook.class;
    }

}
