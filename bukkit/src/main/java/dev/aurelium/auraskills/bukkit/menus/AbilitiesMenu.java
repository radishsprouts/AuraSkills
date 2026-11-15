package dev.aurelium.auraskills.bukkit.menus;

import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.ability.AbstractAbility;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.menus.shared.EquippedAbilitiesItem;
import dev.aurelium.auraskills.bukkit.menus.shared.GlobalItems;
import dev.aurelium.auraskills.common.ability.AbilityUtil;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.data.DataUtil;
import dev.aurelium.auraskills.common.util.math.RomanNumber;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import dev.aurelium.slate.builder.MenuBuilder;
import dev.aurelium.slate.function.TemplateSlot;
import dev.aurelium.slate.inv.content.SlotPos;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class AbilitiesMenu {

    private final AuraSkills plugin;

    public AbilitiesMenu(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void build(MenuBuilder menu) {
        var globalItems = new GlobalItems(plugin);
        menu.item("back", globalItems::backToLevelProgression);
        menu.fillItem(globalItems::fill);

        // Equipped passive slots 1, 2, 3
        new EquippedAbilitiesItem(plugin).register(menu, "abilities");

        menu.replaceTitle("skill", p -> ((Skill) p.menu().getProperty("skill")).getDisplayName(p.locale(), false));

        menu.properties(m -> Map.of(
                "skill", m.menu().getProperty("skill", Skills.FARMING),
                "previous_menu", "level_progression"));

        menu.template("locked_ability", Ability.class, template -> {
            template.replace("name", p -> p.value().getDisplayName(p.locale(), false));
            template.replace("desc", p -> TextUtil.replace(plugin.getAbilityManager().getBaseDescription(p.value(), plugin.getUser(p.player()), false),
                    "{value}", NumberUtil.format1(p.value().getValue(1)),
                    "{value_2}", NumberUtil.format1(p.value().getSecondaryValue(1)),
                    "{chance_value}", plugin.getAbilityManager().getChanceValue(p.value(), 1),
                    "{guaranteed_value}", plugin.getAbilityManager().getGuaranteedValue(p.value(), 1)));
            template.replace("skill", p -> ((Skill) p.menu().getProperty("skill")).getDisplayName(p.locale(), false));
            template.replace("level", p -> RomanNumber.toRoman(p.value().getUnlock(), plugin));

            template.definedContexts(m -> {
                Skill skill = (Skill) m.menu().getProperty("skill");
                User user = plugin.getUser(m.player());
                Set<Ability> lockedAbilities = new HashSet<>();
                // Add abilities that player has not unlocked yet
                for (Ability ability : skill.getAbilities()) {
                    if (user.getAbilityLevel(ability) <= 0 && ability.isEnabled()) {
                        lockedAbilities.add(ability);
                    }
                }
                return lockedAbilities;
            });

            template.slotPos(abilitySlotPos("locked_ability"));
        });

        menu.template("unlocked_ability", Ability.class, template -> {
            template.replace("name", p -> p.value().getDisplayName(p.locale(), false));

            template.definedContexts(m -> {
                Skill skill = (Skill) m.menu().getProperty("skill");
                User user = plugin.getUser(m.player());
                Set<Ability> unlockedAbilities = new HashSet<>();
                for (Ability ability : skill.getAbilities()) {
                    if (user.getAbilityLevel(ability) >= 1 && ability.isEnabled()) {
                        unlockedAbilities.add(ability);
                    }
                }
                return unlockedAbilities;
            });

            template.modify(t -> {
                User user = plugin.getUser(t.player());
                ItemStack is = t.item();
                if (user.isAbilityEquipped(t.value())) {
                    is.setType(Material.EMERALD);
                    ItemMeta meta = is.getItemMeta();
                    if (meta != null) {
                        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                        is.setItemMeta(meta);
                    }
                }
                return is;
            });

            template.onClick(c -> {
                Player player = c.player();
                User user = plugin.getUser(player);
                Ability ability = c.value();

                if (user.isInCombat()) {
                    long remainingMs = 5000L - (System.currentTimeMillis() - user.getLastCombatTime());
                    double sec = Math.max(0.1, remainingMs / 1000.0);
                    player.sendMessage("§c戦闘中はパッシブスキルを変更できません！ (残り " + String.format(Locale.ROOT, "%.1f", sec) + "秒)");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                    return;
                }

                if (user.isAbilityEquipped(ability)) {
                    user.unequipAbility(ability);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, SoundCategory.PLAYERS, 0.7f, 1.2f);
                } else {
                    if (user.getEquippedAbilities().size() >= 3) {
                        player.sendMessage("§cパッシブスキルは最大3個までしかセットできません！先に装備を解除してください。");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                        return;
                    }
                    user.equipAbility(ability);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
                }
                plugin.getSlate().openMenu(player, "abilities", c.menu().getProperties());
            });

            template.slotPos(abilitySlotPos("unlocked_ability"));
        });

        menu.component("equip_status", Ability.class, component -> {
            component.replace("status", p -> {
                User user = plugin.getUser(p.player());
                if (user.isAbilityEquipped(p.value())) {
                    return "§a[セット中] (クリックで解除)";
                } else {
                    return "§e(クリックでセット)";
                }
            });
        });

        menu.component("your_level", AbstractAbility.class, component -> {
            component.replace("level", p -> String.valueOf(plugin.getUser(p.player()).getAbstractAbilityLevel(p.value())));
            component.shouldShow(t -> isNotMaxed(plugin.getUser(t.player()), t.value()));
        });

        menu.component("your_level_maxed", AbstractAbility.class, component -> {
            component.replace("level", p -> String.valueOf(plugin.getUser(p.player()).getAbstractAbilityLevel(p.value())));
            component.shouldShow(t -> !isNotMaxed(plugin.getUser(t.player()), t.value()));
        });

        menu.component("unlocked_desc", AbstractAbility.class, component -> {
            component.replace("desc", p -> {
                User user = plugin.getUser(p.player());
                String format = p.menu().getFormat("desc_upgrade_value");
                if (p.value() instanceof Ability ability) {
                    int level = user.getAbilityLevel(ability);
                    String desc = plugin.getAbilityManager().getBaseDescription(ability, user, false);
                    return TextUtil.replace(desc,
                            "{value}", AbilityUtil.getUpgradeValue(ability, level, format),
                            "{value_2}", AbilityUtil.getUpgradeValue2(ability, level, format),
                            "{chance_value}", plugin.getAbilityManager().getChanceValue(ability, level),
                            "{guaranteed_value}", plugin.getAbilityManager().getGuaranteedValue(ability, level));
                }
                return null;
            });
            component.replace("skill", p -> p.value().getSkill().getDisplayName(p.locale(), false));
            component.replace("level", p -> RomanNumber.toRoman(getNextUpgradeLevel(p.value(), plugin.getUser(p.player())), plugin));

            component.shouldShow(t -> isNotMaxed(plugin.getUser(t.player()), t.value()));
        });

        menu.component("unlocked_desc_maxed", AbstractAbility.class, component -> {
            component.replace("desc", p -> {
                User user = plugin.getUser(p.player());
                if (p.value() instanceof Ability ability) {
                    int level = user.getAbilityLevel(ability);
                    String desc = plugin.getAbilityManager().getBaseDescription(ability, user, false);
                    return TextUtil.replace(desc,
                            "{value}", NumberUtil.format1(ability.getValue(level)),
                            "{value_2}", NumberUtil.format1(ability.getSecondaryValue(level)),
                            "{chance_value}", plugin.getAbilityManager().getChanceValue(ability, level),
                            "{guaranteed_value}", plugin.getAbilityManager().getGuaranteedValue(ability, level));
                }
                return null;
            });

            component.shouldShow(t -> !isNotMaxed(plugin.getUser(t.player()), t.value()));
        });
    }

    private int getNextUpgradeLevel(AbstractAbility ability, User user) {
        int unlock = ability.getUnlock();
        int levelUp = ability.getLevelUp();
        return unlock + levelUp * user.getAbstractAbilityLevel(ability);
    }

    private boolean isNotMaxed(User user, AbstractAbility ability) {
        int maxLevel = ability.getMaxLevel();
        int unlock = ability.getUnlock();
        int levelUp = ability.getLevelUp();
        int maxAllowedBySkill = (ability.getSkill().getMaxLevel() - unlock) / levelUp + 1;
        if (maxLevel == 0 || maxLevel > maxAllowedBySkill) {
            maxLevel = maxAllowedBySkill;
        }
        return user.getAbstractAbilityLevel(ability) < maxLevel;
    }

    private TemplateSlot<Ability> abilitySlotPos(String itemName) {
        return t -> {
            Skill skill = (Skill) t.menu().getProperty("skill");
            Object obj = t.menu().getItemOption(itemName, "slots");
            if (obj instanceof List<?>) {
                List<String> slots = DataUtil.castStringList(obj);
                // Get the index of the ability
                int index = 0;
                for (Ability skillAbility : skill.getAbilities()) {
                    if (skillAbility.isEnabled() && skill.isEnabled()) {
                        if (skillAbility.equals(t.value())) {
                            break;
                        }
                        index++;
                    }
                }
                if (slots.size() > index) {
                    String slot = slots.get(index);
                    return GlobalItems.parseSlot(slot);
                }
            }
            // Default slots
            List<Ability> abilityList = new ArrayList<>();
            for (Ability skillAbility : skill.getAbilities()) {
                if (skillAbility.isEnabled() && skill.isEnabled()) {
                    abilityList.add(skillAbility);
                }
            }
            int index = abilityList.indexOf(t.value());
            if (index < 5) {
                return SlotPos.of(1, 2 + index);
            } else {
                return SlotPos.of(2, 2 + (index - 5));
            }
        };
    }

}
