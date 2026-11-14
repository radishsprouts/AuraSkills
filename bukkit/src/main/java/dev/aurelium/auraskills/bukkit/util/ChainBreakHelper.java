package dev.aurelium.auraskills.bukkit.util;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.source.BlockLeveler;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.concurrent.ThreadLocalRandom;

public class ChainBreakHelper {

    public static final String CHAIN_BREAK_META = "auraskills_chain_breaking";

    public static void breakBlockWithLuckAndXp(AuraSkills plugin, Player player, Block targetBlock, ItemStack tool) {
        if (targetBlock.getType().isAir()) return;
        if (targetBlock.hasMetadata(CHAIN_BREAK_META)) return;

        targetBlock.setMetadata(CHAIN_BREAK_META, new FixedMetadataValue(plugin, true));
        try {
            BlockLeveler blockLeveler = plugin.getLevelManager().getLeveler(BlockLeveler.class);
            // 1. Trigger XP grant and Gathering Luck extra drops
            if (blockLeveler != null) {
                BlockBreakEvent fakeCancelCheck = new BlockBreakEvent(targetBlock, player);
                blockLeveler.handleBreak(player, targetBlock, fakeCancelCheck,
                        trait -> trait.getUniqueDrops(targetBlock, player));
            }

            // 2. Break block naturally with tool enchantments (Fortune/Silk Touch)
            targetBlock.breakNaturally(tool);

            // 3. Consume tool durability respecting Unbreaking
            damageToolRespectingUnbreaking(player, tool, 1);
        } finally {
            targetBlock.removeMetadata(CHAIN_BREAK_META, plugin);
        }
    }

    public static void damageToolRespectingUnbreaking(Player player, ItemStack tool, int amount) {
        if (tool == null || tool.getType().isAir() || tool.getType().getMaxDurability() <= 0) return;
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) return;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int actualDamage = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking <= 0 || ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) {
                actualDamage++;
            }
        }
        if (actualDamage <= 0) return;

        int newDamage = damageable.getDamage() + actualDamage;
        if (newDamage >= tool.getType().getMaxDurability()) {
            tool.setAmount(0);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1.0f, 1.0f);
        } else {
            damageable.setDamage(newDamage);
            tool.setItemMeta(damageable);
        }
    }

    public static void breakConnectedBlocks(AuraSkills plugin, Player player, Block startBlock, int maxBlocks, java.util.function.Predicate<Block> filter) {
        if (maxBlocks <= 0) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        java.util.Queue<Block> queue = new java.util.ArrayDeque<>();
        java.util.Set<org.bukkit.Location> visited = new java.util.HashSet<>();
        queue.add(startBlock);
        visited.add(startBlock.getLocation());
        int broken = 0;

        while (!queue.isEmpty() && broken < maxBlocks) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (!visited.contains(next.getLocation())) {
                            visited.add(next.getLocation());
                            if (filter.test(next) && !next.getType().isAir()) {
                                breakBlockWithLuckAndXp(plugin, player, next, tool);
                                broken++;
                                if (broken >= maxBlocks) return;
                                queue.add(next);
                            }
                        }
                    }
                }
            }
        }
    }

}
