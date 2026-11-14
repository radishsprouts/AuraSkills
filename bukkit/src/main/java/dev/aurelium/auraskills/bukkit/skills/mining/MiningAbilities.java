package dev.aurelium.auraskills.bukkit.skills.mining;

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
import dev.aurelium.auraskills.bukkit.util.ChainBreakHelper;
import dev.aurelium.auraskills.bukkit.util.ItemUtils;
import dev.aurelium.auraskills.bukkit.util.VersionUtils;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public class MiningAbilities extends BukkitAbilityImpl {

    public static final String STAMINA_MODIFIER_NAME = "mining-stamina";

    public MiningAbilities(AuraSkills plugin) {
        super(plugin, Abilities.LUCKY_MINER, Abilities.MINER, Abilities.PICK_MASTER, Abilities.HARDENED_ARMOR, Abilities.STAMINA,
                Abilities.VEIN_MINER, Abilities.AUTO_SMELT, Abilities.TUNNEL_BORE, Abilities.GEM_SEEKER);
    }

    public boolean dropsMineralDirectly(Block block) {
        Material mat = block.getType();
        switch (mat) {
            case DIAMOND_ORE, REDSTONE_ORE, EMERALD_ORE, COAL_ORE, LAPIS_ORE, NETHER_QUARTZ_ORE, NETHER_GOLD_ORE:
                return true;
        }
        if (VersionUtils.isAtLeastVersion(17)) {
            switch (mat) {
                case IRON_ORE, DEEPSLATE_IRON_ORE, GOLD_ORE, DEEPSLATE_GOLD_ORE, COPPER_ORE, DEEPSLATE_COPPER_ORE, DEEPSLATE_DIAMOND_ORE, DEEPSLATE_REDSTONE_ORE, DEEPSLATE_EMERALD_ORE, DEEPSLATE_COAL_ORE, DEEPSLATE_LAPIS_ORE:
                    return true;
            }
        }
        return false;
    }

    private DamageModifier pickMaster(Player player, User user) {
        var ability = Abilities.PICK_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            if (meta.getDamageType() == DamageType.PICKAXE) {
                var user = plugin.getUser(attacker);
                meta.addAttackModifier(pickMaster(attacker, user));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void hardenedArmor(PlayerItemDamageEvent event) {
        var ability = Abilities.HARDENED_ARMOR;

        if (isDisabled(ability)) return;

        Player player = event.getPlayer();

        // Checks if item damaged is armor
        if (!ItemUtils.isArmor(event.getItem().getType())) return;

        User user = plugin.getUser(player);

        if (failsChecks(player, ability)) return;

        // Applies ability
        if (rand.nextDouble() < (getValue(ability, user) / 100)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void applyStamina(ItemEnableEvent event) {
        if (isNotStamina(event)) return;

        Player player = event.getPlayer();
        User user = BukkitUser.getUser(event.getUser());

        var ability = Abilities.STAMINA;

        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) {
            user.removeStatModifier(STAMINA_MODIFIER_NAME);
            return;
        }

        if (user.getAbilityLevel(ability) == 0) return;

        user.addStatModifier(new StatModifier(STAMINA_MODIFIER_NAME, Stats.TOUGHNESS, (int) getValue(ability, user), Operation.ADD));
    }

    @EventHandler
    public void removeStamina(ItemDisableEvent event) {
        if (isNotStamina(event)) return;

        User user = BukkitUser.getUser(event.getUser());
        user.removeStatModifier(STAMINA_MODIFIER_NAME);
    }

    private boolean isNotStamina(ItemToggleEvent event) {
        if (!ItemUtils.isPickaxe(event.getItem().getType())) {
            return true;
        }
        return event.getType() != ModifierType.ITEM;
    }

    private boolean isStoneOrDeepslate(Material mat) {
        return switch (mat) {
            case STONE, DEEPSLATE, ANDESITE, DIORITE, GRANITE, TUFF, NETHERRACK, END_STONE, BLACKSTONE, BASALT -> true;
            default -> false;
        };
    }

    private boolean isOre(Material mat) {
        String name = mat.name();
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMiningBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.hasMetadata(ChainBreakHelper.CHAIN_BREAK_META)) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!ItemUtils.isPickaxe(tool.getType())) return;

        User user = plugin.getUser(player);

        // 1. Vein Miner (鉱脈連鎖掘り)
        if (isOre(block.getType())) {
            var veinAbility = Abilities.VEIN_MINER;
            if (!isDisabled(veinAbility) && !failsChecks(player, veinAbility) && !player.isSneaking()) {
                int maxBlocks = (int) Math.round(getValue(veinAbility, user)); // Lv1: 2, +1/Lv
                if (maxBlocks > 0 && !plugin.getRegionManager().isPlacedBlock(block)) {
                    executeVeinMiner(player, block, tool, maxBlocks);
                }
            }
        }

        // 2. Tunnel Bore (坑道掘り: 周囲の石系ブロック一括掘削)
        if (isStoneOrDeepslate(block.getType())) {
            var tunnelAbility = Abilities.TUNNEL_BORE;
            if (!isDisabled(tunnelAbility) && !failsChecks(player, tunnelAbility) && !player.isSneaking()) {
                int maxBlocks = (int) Math.round(getValue(tunnelAbility, user)); // Lv1: 2, +1/Lv
                if (maxBlocks > 0) {
                    executeTunnelBore(player, block, tool, maxBlocks);
                }
            }
        }

        // 3. Gem Seeker (石掘り時宝石飛び出し)
        if (isStoneOrDeepslate(block.getType())) {
            var gemAbility = Abilities.GEM_SEEKER;
            if (!isDisabled(gemAbility) && !failsChecks(player, gemAbility)) {
                if (rand.nextDouble() < getValue(gemAbility, user) / 100.0) {
                    Material[] gems = {Material.COAL, Material.RAW_IRON, Material.RAW_COPPER, Material.RAW_GOLD, Material.LAPIS_LAZULI, Material.DIAMOND, Material.EMERALD};
                    Material gem = gems[rand.nextInt(gems.length)];
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(gem, 1));
                    block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAutoSmeltDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        var ability = Abilities.AUTO_SMELT;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        User user = plugin.getUser(player);
        double bonusChance = getValue(ability, user) / 100.0;
        boolean smeltedAny = false;

        for (var itemEntity : event.getItems()) {
            ItemStack stack = itemEntity.getItemStack();
            Material smelted = getSmeltedMaterial(stack.getType());
            if (smelted != null) {
                int amount = stack.getAmount();
                if (rand.nextDouble() < bonusChance) {
                    amount++;
                }
                itemEntity.setItemStack(new ItemStack(smelted, amount));
                smeltedAny = true;
            }
        }
        if (smeltedAny) {
            event.getBlock().getWorld().spawn(event.getBlock().getLocation(), ExperienceOrb.class).setExperience(1);
            player.getWorld().spawnParticle(Particle.FLAME, event.getBlock().getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0.02);
        }
    }

    private Material getSmeltedMaterial(Material raw) {
        return switch (raw) {
            case RAW_IRON -> Material.IRON_INGOT;
            case RAW_GOLD -> Material.GOLD_INGOT;
            case RAW_COPPER -> Material.COPPER_INGOT;
            case COBBLESTONE -> Material.STONE;
            case COBBLED_DEEPSLATE -> Material.DEEPSLATE;
            default -> null;
        };
    }

    private void executeVeinMiner(Player player, Block startBlock, ItemStack tool, int maxBlocks) {
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
            if (tool.getAmount() <= 0) break; // ツールの耐久が尽きたら中断

            for (BlockFace face : BlockFace.values()) {
                Block next = current.getRelative(face);
                if (next.getType() == targetType && visited.add(next) && !plugin.getRegionManager().isPlacedBlock(next)) {
                    queue.add(next);
                }
            }
        }
        if (broken > 0) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
    }

    private void executeTunnelBore(Player player, Block centerBlock, ItemStack tool, int maxBlocks) {
        int broken = 0;
        for (int x = -1; x <= 1 && broken < maxBlocks; x++) {
            for (int y = -1; y <= 1 && broken < maxBlocks; y++) {
                for (int z = -1; z <= 1 && broken < maxBlocks; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    Block relative = centerBlock.getRelative(x, y, z);
                    if (isStoneOrDeepslate(relative.getType())) {
                        ChainBreakHelper.breakBlockWithLuckAndXp(plugin, player, relative, tool);
                        broken++;
                        if (tool.getAmount() <= 0) return;
                    }
                }
            }
        }
    }

}
