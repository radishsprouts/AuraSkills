package dev.aurelium.auraskills.bukkit.skills.foraging;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.damage.DamageType;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.api.event.item.ItemDisableEvent;
import dev.aurelium.auraskills.api.event.item.ItemEnableEvent;
import dev.aurelium.auraskills.api.event.item.ItemToggleEvent;
import dev.aurelium.auraskills.api.item.ModifierType;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.stat.Stats;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier.Operation;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.user.BukkitUser;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.bukkit.util.ChainBreakHelper;
import dev.aurelium.auraskills.bukkit.util.ItemUtils;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public class ForagingAbilities extends BukkitAbilityImpl {

    public static final String VALOR_MODIFIER_NAME = "foraging-valor";

    public ForagingAbilities(AuraSkills plugin) {
        super(plugin, Abilities.LUMBERJACK, Abilities.FORAGER, Abilities.AXE_MASTER, Abilities.SHREDDER, Abilities.VALOR,
                Abilities.TIMBER, Abilities.CANOPY_HARVESTER, Abilities.BERSERKER_AXE, Abilities.NATURE_BLESSING);
    }

    private DamageModifier axeMaster(Player player, User user) {
        var ability = Abilities.AXE_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            if (meta.getDamageType() == DamageType.AXE) {
                var user = plugin.getUser(attacker);
                meta.addAttackModifier(axeMaster(attacker, user));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void shredder(PlayerItemDamageEvent event) {
        var ability = Abilities.SHREDDER;

        if (isDisabled(ability)) return;

        if (event.isCancelled()) return;

        if (!event.getPlayer().isOnline()) return;

        // If is item taking durabilty damage is armor
        if (!ItemUtils.isArmor(event.getItem().getType())) {
            return;
        }
        // If last damage was from entity
        if (!(event.getPlayer().getLastDamageCause() instanceof EntityDamageByEntityEvent e)) return;
        // If last damage was from player
        if (!(e.getDamager() instanceof Player player)) return;

        if (!player.isOnline()) return;

        if (failsChecks(player, ability)) return;
        // If damage was an attack
        if (!e.getCause().equals(EntityDamageEvent.DamageCause.ENTITY_ATTACK)) return;
        // If item used was an axe
        Material mat = player.getInventory().getItemInMainHand().getType();

        boolean isAxe = false;
        switch (mat) {
            case NETHERITE_AXE, DIAMOND_AXE, IRON_AXE, GOLDEN_AXE, STONE_AXE, WOODEN_AXE -> isAxe = true;
        }
        if (!isAxe) {
            return;
        }

        User user = plugin.getUser(player);
        // Checks if shredder is used
        if (user.getAbilityLevel(ability) == 0) return;
        if (rand.nextDouble() < (getValue(ability, user)) / 100) {
            event.setDamage(event.getDamage() * 3);
        }
    }

    @EventHandler
    public void applyValor(ItemEnableEvent event) {
        if (isNotValor(event)) return;

        Player player = event.getPlayer();
        User user = BukkitUser.getUser(event.getUser());

        var ability = Abilities.VALOR;
        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) {
            user.removeStatModifier(VALOR_MODIFIER_NAME);
            return;
        }

        if (user.getAbilityLevel(ability) == 0) return;

        user.addStatModifier(new StatModifier(VALOR_MODIFIER_NAME, Stats.STRENGTH, (int) getValue(ability, user), Operation.ADD));
    }

    @EventHandler
    public void removeValor(ItemDisableEvent event) {
        if (isNotValor(event)) return;

        User user = BukkitUser.getUser(event.getUser());
        user.removeStatModifier(VALOR_MODIFIER_NAME);
    }

    private boolean isNotValor(ItemToggleEvent event) {
        if (!ItemUtils.isAxe(event.getItem().getType())) {
            return true;
        }
        return event.getType() != ModifierType.ITEM;
    }

    private boolean isLog(Material mat) {
        String name = mat.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    private boolean isLeaves(Material mat) {
        return mat.name().endsWith("_LEAVES") || mat == Material.MANGROVE_ROOTS;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForagingBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.hasMetadata(ChainBreakHelper.CHAIN_BREAK_META)) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!ItemUtils.isAxe(tool.getType())) return;

        User user = plugin.getUser(player);

        if (isLog(block.getType())) {
            // 1. Timber (一括伐採)
            var timberAbility = Abilities.TIMBER;
            if (!isDisabled(timberAbility) && !failsChecks(player, timberAbility) && !player.isSneaking()) {
                int maxBlocks = (int) Math.round(getValue(timberAbility, user)); // Lv1: 3, +2/Lv
                if (maxBlocks > 0 && !plugin.getRegionManager().isPlacedBlock(block)) {
                    executeTimber(player, block, tool, maxBlocks);
                }
            }

            // 2. Canopy Harvester (枝葉崩壊)
            var canopyAbility = Abilities.CANOPY_HARVESTER;
            if (!isDisabled(canopyAbility) && !failsChecks(player, canopyAbility)) {
                int maxLeaves = (int) Math.round(getValue(canopyAbility, user)); // Lv1: 8, +4/Lv
                if (maxLeaves > 0) {
                    executeCanopyHarvester(player, block, tool, maxLeaves);
                }
            }

            // 3. Nature's Blessing (満腹度回復＆木材2倍)
            var blessingAbility = Abilities.NATURE_BLESSING;
            if (!isDisabled(blessingAbility) && !failsChecks(player, blessingAbility)) {
                if (rand.nextDouble() < getValue(blessingAbility, user) / 100.0) {
                    player.setFoodLevel(Math.min(20, player.getFoodLevel() + 2));
                    player.setSaturation(Math.min(20f, player.getSaturation() + 2f));
                    double maxHp = player.getAttribute(AttributeCompat.maxHealth) != null
                            ? player.getAttribute(AttributeCompat.maxHealth).getValue() : 20.0;
                    player.setHealth(Math.min(maxHp, player.getHealth() + 1.0));
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(block.getType(), 1));
                    block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBerserkerAxeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!ItemUtils.isAxe(tool.getType())) return;

        var ability = Abilities.BERSERKER_AXE;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        User user = plugin.getUser(player);
        int amplifier = 0;
        PotionEffect currentSpeed = player.getPotionEffect(PotionEffectType.SPEED);
        if (currentSpeed != null && currentSpeed.getDuration() > 0) {
            amplifier = Math.min(2, currentSpeed.getAmplifier() + 1);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, amplifier, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 100, amplifier, false, false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.6f, 1.3f);
    }

    private void executeTimber(Player player, Block startBlock, ItemStack tool, int maxBlocks) {
        Material targetType = startBlock.getType();
        Queue<Block> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

        visited.add(startBlock);
        for (BlockFace face : BlockFace.values()) {
            Block relative = startBlock.getRelative(face);
            if (relative.getType() == targetType && !plugin.getRegionManager().isPlacedBlock(relative)) {
                queue.add(relative);
                visited.add(relative);
            }
        }

        int broken = 0;
        while (!queue.isEmpty() && broken < maxBlocks) {
            Block current = queue.poll();
            ChainBreakHelper.breakBlockWithLuckAndXp(plugin, player, current, tool);
            broken++;
            if (tool.getAmount() <= 0) break;

            for (BlockFace face : BlockFace.values()) {
                Block next = current.getRelative(face);
                if (next.getType() == targetType && visited.add(next) && !plugin.getRegionManager().isPlacedBlock(next)) {
                    queue.add(next);
                }
            }
        }
        if (broken > 0) {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_WOOD_BREAK, SoundCategory.PLAYERS, 0.8f, 1.0f);
        }
    }

    private void executeCanopyHarvester(Player player, Block logBlock, ItemStack tool, int maxLeaves) {
        int broken = 0;
        for (int x = -3; x <= 3 && broken < maxLeaves; x++) {
            for (int y = -1; y <= 6 && broken < maxLeaves; y++) {
                for (int z = -3; z <= 3 && broken < maxLeaves; z++) {
                    Block rel = logBlock.getRelative(x, y, z);
                    if (isLeaves(rel.getType())) {
                        ChainBreakHelper.breakBlockWithLuckAndXp(plugin, player, rel, tool);
                        broken++;
                        if (tool.getAmount() <= 0) return;
                    }
                }
            }
        }
    }

}
