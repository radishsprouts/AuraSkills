package dev.aurelium.auraskills.bukkit.menus;

import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.menus.shared.EquippedAbilitiesItem;
import dev.aurelium.auraskills.bukkit.menus.shared.GlobalItems;
import dev.aurelium.auraskills.common.user.AbilityPreset;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import dev.aurelium.slate.builder.MenuBuilder;
import dev.aurelium.slate.inv.content.SlotPos;
import dev.aurelium.slate.menu.ActiveMenu;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PresetsMenu {

    public enum Category {
        ALL("すべて"),
        COMBAT("戦闘系"),
        GATHERING("採取・生産系"),
        UTILITY("補助・移動系");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Category next() {
            Category[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public boolean matches(Skill skill) {
            if (this == ALL) return true;
            if (this == COMBAT) {
                return skill.equals(Skills.FIGHTING) || skill.equals(Skills.ARCHERY) || skill.equals(Skills.DEFENSE);
            }
            if (this == GATHERING) {
                return skill.equals(Skills.FARMING) || skill.equals(Skills.FORAGING) || skill.equals(Skills.MINING)
                        || skill.equals(Skills.FISHING) || skill.equals(Skills.EXCAVATION);
            }
            if (this == UTILITY) {
                return skill.equals(Skills.AGILITY) || skill.equals(Skills.ENDURANCE) || skill.equals(Skills.ALCHEMY)
                        || skill.equals(Skills.ENCHANTING) || skill.equals(Skills.SORCERY) || skill.equals(Skills.HEALING)
                        || skill.equals(Skills.FORGING);
            }
            return false;
        }
    }

    public static final int ITEMS_PER_PAGE = 27;
    private final AuraSkills plugin;

    public PresetsMenu(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void build(MenuBuilder menu) {
        var globalItems = new GlobalItems(plugin);
        menu.item("back", globalItems::back);
        menu.item("close", globalItems::close);
        menu.item("previous_page", globalItems::previousPage);
        menu.item("next_page", globalItems::nextPage);
        menu.fillItem(globalItems::fill);

        // 装備中スロット (0,3〜0,5) および下段クイックプリセット (4,2〜4,6) の登録
        new EquippedAbilitiesItem(plugin).register(menu, "presets");

        menu.replaceTitle("current_page", p -> String.valueOf(p.menu().getCurrentPage() + 1));
        menu.replaceTitle("total_pages", p -> String.valueOf(Math.max(1, p.menu().getTotalPages())));
        menu.replaceTitle("preset_num", p -> {
            User user = plugin.getUser(p.player());
            return String.valueOf(user.getSelectedPresetIndex() + 1);
        });
        menu.replaceTitle("preset_name", p -> {
            User user = plugin.getUser(p.player());
            return user.getPreset(user.getSelectedPresetIndex()).getName();
        });

        menu.properties(m -> Map.of(
                "category", m.menu().getProperty("category", Category.ALL),
                "previous_menu", m.menu().getProperty("previous_menu", "skills")));

        // ページ数計算
        menu.pages(m -> {
            User user = plugin.getUser(m.player());
            Category category = (Category) m.menu().getProperty("category", Category.ALL);
            List<Ability> list = getFilteredAbilities(user, category);
            return Math.max(1, (list.size() - 1) / ITEMS_PER_PAGE + 1);
        });

        // プリセット名変更ボタン (pos: 0,1)
        menu.item("rename_preset", item -> {
            item.modify(info -> {
                Player player = info.player();
                User user = plugin.getUser(player);
                AbilityPreset preset = user.getPreset(user.getSelectedPresetIndex());

                ItemStack is = info.item();
                if (is.getType() == Material.AIR) is.setType(Material.NAME_TAG);
                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;

                meta.setDisplayName("§e§lプリセット名の変更");
                List<String> lore = new ArrayList<>();
                lore.add("§7対象: §bプリセット #" + (preset.getIndex() + 1) + "「§f" + preset.getName() + "§b」");
                lore.add("§7金床GUIを開いて新しい名前を入力します。");
                lore.add("§8(最大16文字・経験値消費なし)");
                lore.add("");
                lore.add("§e▶ クリックして名前を変更");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            item.onClick(c -> {
                Player player = c.player();
                User user = plugin.getUser(player);
                plugin.getPresetRenameGui().openPresetRename(player, user.getSelectedPresetIndex(), c.menu().getProperties());
            });
        });

        // プリセットアイコン変更ボタン (pos: 0,2)
        menu.item("change_icon", item -> {
            item.modify(info -> {
                Player player = info.player();
                User user = plugin.getUser(player);
                AbilityPreset preset = user.getPreset(user.getSelectedPresetIndex());

                ItemStack is = info.item();
                Material mat = Material.matchMaterial(preset.getIconMaterial());
                if (mat != null && mat.isItem()) {
                    is.setType(mat);
                } else {
                    is.setType(Material.BOOK);
                }

                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;

                meta.setDisplayName("§b§lプリセットアイコンの変更");
                List<String> lore = new ArrayList<>();
                lore.add("§7現在: §f" + preset.getIconMaterial());
                lore.add("");
                lore.add("§e▶ 左クリック: 次のアイコンへ");
                lore.add("§e▶ 右クリック: 前のアイコンへ");
                lore.add("§8(全14種類から選択可能)");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            item.onClick(c -> {
                Player player = c.player();
                User user = plugin.getUser(player);
                AbilityPreset preset = user.getPreset(user.getSelectedPresetIndex());

                boolean forward = !c.event().isRightClick();
                preset.cycleIcon(forward);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.7f, 1.2f);
                plugin.getSlate().openMenu(player, "presets", c.menu().getProperties(), c.menu().getCurrentPage());
            });
        });

        // カテゴリ絞り込みボタン (pos: 0,6)
        menu.item("category_filter", item -> {
            item.modify(info -> {
                Category cat = (Category) info.menu().getProperty("category", Category.ALL);

                ItemStack is = info.item();
                if (is.getType() == Material.AIR) is.setType(Material.HOPPER);
                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;

                meta.setDisplayName("§a§lカテゴリ絞り込み");
                List<String> lore = new ArrayList<>();
                lore.add("§7表示するパッシブスキルを系統ごとに絞り込みます。");
                lore.add("");
                for (Category c : Category.values()) {
                    if (c == cat) {
                        lore.add("  §a✔ §f" + c.getDisplayName() + " §7(選択中)");
                    } else {
                        lore.add("  §8• " + c.getDisplayName());
                    }
                }
                lore.add("");
                lore.add("§e▶ クリックして切り替え");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            item.onClick(c -> {
                Player player = c.player();
                Category current = (Category) c.menu().getProperty("category", Category.ALL);
                Category next = current.next();

                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.7f, 1.0f);
                Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                props.put("category", next);
                plugin.getSlate().openMenu(player, "presets", props, 0);
            });
        });

        // 一括全解除ボタン (pos: 0,7)
        menu.item("clear_equipped", item -> {
            item.modify(info -> {
                ItemStack is = info.item();
                if (is.getType() == Material.AIR) is.setType(Material.MILK_BUCKET);
                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;

                meta.setDisplayName("§c§lパッシブスキルの全解除");
                List<String> lore = new ArrayList<>();
                lore.add("§7現在セットされているパッシブスキル（最大3個）を");
                lore.add("§7すべて外します。");
                lore.add("");
                lore.add("§c▶ クリックして全解除");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            item.onClick(c -> {
                Player player = c.player();
                User user = plugin.getUser(player);

                if (user.isInCombat()) {
                    long remainingMs = 5000L - (System.currentTimeMillis() - user.getLastCombatTime());
                    double sec = Math.max(0.1, remainingMs / 1000.0);
                    player.sendMessage("§c戦闘中はパッシブスキルを変更できません！ (残り " + String.format(Locale.ROOT, "%.1f", sec) + "秒)");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                    return;
                }

                if (user.getEquippedAbilities().isEmpty()) {
                    player.sendMessage("§e現在装備中のパッシブスキルはありません。");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                    return;
                }

                user.clearEquippedAbilities();
                // 選択中のプリセットも同期クリア
                user.getPreset(user.getSelectedPresetIndex()).clearAbilities();

                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.8f, 1.0f);
                player.sendMessage("§aパッシブスキルをすべて解除しました。");
                plugin.getSlate().openMenu(player, "presets", c.menu().getProperties(), c.menu().getCurrentPage());
            });
        });

        // 中央パッシブスキル一覧テンプレート (Row 1〜3: 1,0〜3,8 計27スロット)
        menu.template("passive_ability", Ability.class, template -> {
            template.definedContexts(m -> {
                User user = plugin.getUser(m.player());
                Category category = (Category) m.menu().getProperty("category", Category.ALL);
                List<Ability> all = getFilteredAbilities(user, category);

                int page = m.menu().getCurrentPage();
                int fromIndex = page * ITEMS_PER_PAGE;
                if (fromIndex >= all.size()) return Set.of();
                int toIndex = Math.min(fromIndex + ITEMS_PER_PAGE, all.size());
                return new LinkedHashSet<>(all.subList(fromIndex, toIndex));
            });

            template.slotPos(t -> {
                User user = plugin.getUser(t.player());
                Category category = (Category) t.menu().getProperty("category", Category.ALL);
                List<Ability> all = getFilteredAbilities(user, category);
                int page = t.menu().getCurrentPage();
                int fromIndex = page * ITEMS_PER_PAGE;
                int toIndex = Math.min(fromIndex + ITEMS_PER_PAGE, all.size());
                List<Ability> pageList = all.subList(fromIndex, toIndex);

                int index = pageList.indexOf(t.value());
                if (index < 0) return null;
                int row = 1 + (index / 9);
                int col = index % 9;
                return SlotPos.of(row, col);
            });

            template.modify(t -> {
                Player player = t.player();
                User user = plugin.getUser(player);
                Ability ability = t.value();
                boolean equipped = user.isAbilityEquipped(ability);

                ItemStack is = t.item();
                if (equipped) {
                    is.setType(Material.EMERALD);
                } else {
                    is.setType(Material.BOOK);
                }

                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;

                String prefix = equipped ? "§a✔ " : "§f";
                meta.setDisplayName(prefix + ability.getDisplayName(user.getLocale(), false));

                List<String> lore = new ArrayList<>();
                lore.add("§7スキル: §b" + ability.getSkill().getDisplayName(user.getLocale(), false));
                lore.add("");

                int level = Math.max(1, user.getAbilityLevel(ability));
                String desc = plugin.getAbilityManager().getBaseDescription(ability, user, false);
                if (desc != null) {
                    desc = TextUtil.replace(desc,
                            "{value}", NumberUtil.format1(ability.getValue(level)),
                            "{value_2}", NumberUtil.format1(ability.getSecondaryValue(level)),
                            "{chance_value}", plugin.getAbilityManager().getChanceValue(ability, level),
                            "{guaranteed_value}", plugin.getAbilityManager().getGuaranteedValue(ability, level));
                    for (String line : desc.split("\n")) {
                        lore.add("§7" + line);
                    }
                }
                lore.add("");
                if (equipped) {
                    lore.add("§a[セット中] クリックで解除");
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                } else {
                    lore.add("§e▶ クリックしてセット (プリセットにも自動反映)");
                    meta.removeEnchant(Enchantment.UNBREAKING);
                }

                meta.setLore(lore);
                is.setItemMeta(meta);
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

                AbilityPreset preset = user.getPreset(user.getSelectedPresetIndex());

                if (user.isAbilityEquipped(ability)) {
                    user.unequipAbility(ability);
                    // 選択中プリセットからも外して同期
                    List<Ability> current = new ArrayList<>(preset.getAbilities());
                    current.remove(ability);
                    preset.setAbilities(current);

                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, SoundCategory.PLAYERS, 0.7f, 1.2f);
                } else {
                    if (user.getEquippedAbilities().size() >= User.MAX_EQUIPPED_ABILITIES) {
                        player.sendMessage("§cパッシブスキルは最大3個までしかセットできません！先に装備を解除してください。");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                        return;
                    }
                    user.equipAbility(ability);
                    // 選択中プリセットにも追加して同期
                    List<Ability> current = new ArrayList<>(preset.getAbilities());
                    if (!current.contains(ability)) {
                        current.add(ability);
                        preset.setAbilities(current);
                    }

                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
                }

                plugin.getSlate().openMenu(player, "presets", c.menu().getProperties(), c.menu().getCurrentPage());
            });
        });
    }

    private List<Ability> getFilteredAbilities(User user, Category category) {
        List<Ability> list = new ArrayList<>();
        for (Skill skill : plugin.getSkillRegistry().getValues()) {
            if (!category.matches(skill)) continue;
            for (Ability ability : skill.getAbilities()) {
                if (ability.isEnabled() && user.getAbilityLevel(ability) >= 1) {
                    list.add(ability);
                }
            }
        }
        list.sort(Comparator.comparing((Ability a) -> a.getSkill().getId().toString())
                .thenComparingInt(Ability::getUnlock)
                .thenComparing(a -> a.getId().toString()));
        return list;
    }
}
