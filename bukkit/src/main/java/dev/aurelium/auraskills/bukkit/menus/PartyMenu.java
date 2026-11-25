package dev.aurelium.auraskills.bukkit.menus;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.menus.shared.GlobalItems;
import dev.aurelium.auraskills.bukkit.party.BukkitPartyNotifier;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.party.PartyActionResult;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.party.PartyInvite;
import dev.aurelium.auraskills.common.party.PartyManagerImpl;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.slate.builder.MenuBuilder;
import dev.aurelium.slate.inv.content.SlotPos;
import dev.aurelium.slate.menu.ActiveMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class PartyMenu {

    private static final int MEMBERS_PER_PAGE = 27;
    private final AuraSkills plugin;

    public PartyMenu(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void build(MenuBuilder menu) {
        var globalItems = new GlobalItems(plugin);
        menu.item("back", globalItems::back);
        menu.item("close", globalItems::close);
        menu.item("previous_page", globalItems::previousPage);
        menu.item("next_page", globalItems::nextPage);
        menu.fillItem(globalItems::fill);

        menu.properties(m -> {
            Map<String, Object> props = new HashMap<>();
            Object prev = m.menu().getProperty("previous_menu");
            if (prev != null) props.put("previous_menu", prev);
            return props;
        });

        menu.replaceTitle("party_title", p -> {
            PartyImpl party = plugin.getPartyManager().getPartyImpl(p.player().getUniqueId());
            if (party == null) {
                return "パーティー";
            }
            String leaderName = new BukkitPartyNotifier(plugin).getPlayerName(party.getLeader());
            return leaderName + " のパーティー";
        });

        menu.pages(m -> {
            PartyImpl party = plugin.getPartyManager().getPartyImpl(m.player().getUniqueId());
            if (party == null) return 1;
            return Math.max(1, (party.getSize() - 1) / MEMBERS_PER_PAGE + 1);
        });

        // 概要アイテム (0,4)
        menu.item("party_info", item -> item.modify(info -> {
            Player player = info.player();
            PartyManagerImpl pm = plugin.getPartyManager();
            PartyImpl party = pm.getPartyImpl(player.getUniqueId());
            ItemStack is = info.item();
            ItemMeta meta = is.getItemMeta();
            if (meta == null) return is;

            if (party == null) {
                is.setType(Material.OAK_SIGN);
                meta.setDisplayName("§e§lパーティー未所属");
                List<String> lore = new ArrayList<>();
                lore.add("§7仲間とパーティーを組むと、");
                lore.add("§a・スキル経験値の共有＆ボーナス");
                lore.add("§a・仲間への誤爆（攻撃）防止");
                lore.add("§7などのメリットが得られます！");
                meta.setLore(lore);
            } else {
                is.setType(Material.NETHER_STAR);
                String leaderName = new BukkitPartyNotifier(plugin).getPlayerName(party.getLeader());
                int maxSize = pm.getMaxPartySize();
                String sizeStr = maxSize > 0 ? party.getSize() + " / " + maxSize : party.getSize() + "人 (上限なし)";
                meta.setDisplayName("§b§l" + leaderName + " のパーティー");
                List<String> lore = new ArrayList<>();
                lore.add("§7メンバー数: §f" + sizeStr);
                lore.add("§7XP共有範囲: §f" + (int) plugin.configDouble(Option.PARTY_XP_SHARE_RADIUS) + "m");
                lore.add("§7誤爆防止 (FF無効): " + (party.isFriendlyFireProtected() ? "§aON" : "§cOFF"));
                lore.add("§7メンバーの招待許可: " + (party.isMemberInviteAllowed() ? "§aON" : "§eリーダーのみ"));
                meta.setLore(lore);
            }
            is.setItemMeta(meta);
            return is;
        }));

        // 未所属用: パーティー作成 (2,2)
        menu.item("create_party", item -> {
            item.modify(info -> {
                if (plugin.getPartyManager().isInParty(info.player().getUniqueId())) return null;
                return info.item();
            });
            item.onClick(c -> {
                Player player = c.player();
                PartyActionResult res = plugin.getPartyManager().createParty(player.getUniqueId());
                if (res == PartyActionResult.SUCCESS) {
                    BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
                    notifier.sendMessage(player, notifier.msg(plugin.getLocale(player), "party.created", "<green>パーティーを作成しました！"));
                    BukkitPartyNotifier.playJoinSound(player);
                    reopenSameMenu(player, c.menu());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
                }
            });
        });

        // 未所属用: 届いている招待一覧 (2,4)
        menu.item("pending_invites", item -> {
            item.modify(info -> {
                if (plugin.getPartyManager().isInParty(info.player().getUniqueId())) return null;
                List<PartyInvite> invites = plugin.getPartyManager().getPendingInvites(info.player().getUniqueId());
                ItemStack is = info.item();
                ItemMeta meta = is.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§a§l届いている招待 §7(" + invites.size() + "件)");
                    List<String> lore = new ArrayList<>();
                    if (invites.isEmpty()) {
                        lore.add("§7現在届いている招待はありません。");
                    } else {
                        lore.add("§e▶ クリックして招待一覧を開く");
                    }
                    meta.setLore(lore);
                    is.setItemMeta(meta);
                }
                return is;
            });
            item.onClick(c -> {
                BukkitPartyNotifier.playClickSound(c.player());
                Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                props.put("previous_menu", "party");
                plugin.getSlate().openMenu(c.player(), "party_invites", props);
            });
        });

        // 未所属用・所属用 共通: 招待ブロック切替
        menu.item("toggle_block_guest", item -> {
            item.modify(info -> {
                if (plugin.getPartyManager().isInParty(info.player().getUniqueId())) return null;
                return buildBlockToggleItem(info.item(), info.player());
            });
            item.onClick(c -> handleToggleBlock(c.player(), c.menu()));
        });

        menu.item("toggle_block", item -> {
            item.modify(info -> {
                if (!plugin.getPartyManager().isInParty(info.player().getUniqueId())) return null;
                return buildBlockToggleItem(info.item(), info.player());
            });
            item.onClick(c -> handleToggleBlock(c.player(), c.menu()));
        });

        // 所属用: プレイヤーを招待 (4,0)
        menu.item("invite_player", item -> {
            item.modify(info -> {
                PartyImpl party = plugin.getPartyManager().getPartyImpl(info.player().getUniqueId());
                if (party == null) return null;
                if (!party.isLeader(info.player().getUniqueId()) && !party.isMemberInviteAllowed()) return null;
                return info.item();
            });
            item.onClick(c -> {
                BukkitPartyNotifier.playClickSound(c.player());
                Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                props.put("previous_menu", "party");
                plugin.getSlate().openMenu(c.player(), "party_invite", props);
            });
        });

        // 所属用: FF保護切替 (4,1) リーダーのみ
        menu.item("toggle_ff", item -> {
            item.modify(info -> {
                PartyImpl party = plugin.getPartyManager().getPartyImpl(info.player().getUniqueId());
                if (party == null || !party.isLeader(info.player().getUniqueId())) return null;
                ItemStack is = info.item();
                is.setType(party.isFriendlyFireProtected() ? Material.SHIELD : Material.IRON_SWORD);
                ItemMeta meta = is.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§e§l誤爆防止 (FF無効): " + (party.isFriendlyFireProtected() ? "§aON" : "§cOFF"));
                    List<String> lore = new ArrayList<>();
                    lore.add("§7パーティーメンバー同士の攻撃・");
                    lore.add("§7範囲スキル・有害ポーションを無効化します。");
                    lore.add("");
                    lore.add("§e▶ クリックして切り替え");
                    meta.setLore(lore);
                    is.setItemMeta(meta);
                }
                return is;
            });
            item.onClick(c -> {
                PartyActionResult res = plugin.getPartyManager().toggleFriendlyFire(c.player().getUniqueId());
                if (res == PartyActionResult.SUCCESS) {
                    reopenSameMenu(c.player(), c.menu());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(c.player(), res, "");
                }
            });
        });

        // 所属用: メンバー招待許可切替 (4,2) リーダーのみ
        menu.item("toggle_member_invite", item -> {
            item.modify(info -> {
                PartyImpl party = plugin.getPartyManager().getPartyImpl(info.player().getUniqueId());
                if (party == null || !party.isLeader(info.player().getUniqueId())) return null;
                ItemStack is = info.item();
                is.setType(party.isMemberInviteAllowed() ? Material.LIME_DYE : Material.GRAY_DYE);
                ItemMeta meta = is.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§e§lメンバーの招待許可: " + (party.isMemberInviteAllowed() ? "§aON" : "§cOFF"));
                    List<String> lore = new ArrayList<>();
                    lore.add("§7ONにするとリーダー以外のメンバーも");
                    lore.add("§7他プレイヤーを招待できるようになります。");
                    lore.add("");
                    lore.add("§e▶ クリックして切り替え");
                    meta.setLore(lore);
                    is.setItemMeta(meta);
                }
                return is;
            });
            item.onClick(c -> {
                PartyActionResult res = plugin.getPartyManager().toggleMemberInvite(c.player().getUniqueId());
                if (res == PartyActionResult.SUCCESS) {
                    reopenSameMenu(c.player(), c.menu());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(c.player(), res, "");
                }
            });
        });

        // 所属用: 脱退 (4,7)
        menu.item("leave_party", item -> {
            item.modify(info -> {
                if (!plugin.getPartyManager().isInParty(info.player().getUniqueId())) return null;
                return info.item();
            });
            item.onClick(c -> {
                Player player = c.player();
                PartyActionResult res = plugin.getPartyManager().leaveParty(player.getUniqueId());
                if (res == PartyActionResult.SUCCESS) {
                    BukkitPartyNotifier.playToggleSound(player, false);
                    reopenSameMenu(player, c.menu());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
                }
            });
        });

        // 所属用: 解散 (4,8) リーダーのみ・2回クリック確認
        menu.item("disband_party", item -> {
            item.modify(info -> {
                PartyImpl party = plugin.getPartyManager().getPartyImpl(info.player().getUniqueId());
                if (party == null || !party.isLeader(info.player().getUniqueId())) return null;
                ItemStack is = info.item();
                boolean confirming = "disband".equals(info.menu().getProperty("confirm_action"));
                ItemMeta meta = is.getItemMeta();
                if (meta != null) {
                    if (confirming) {
                        meta.setDisplayName("§c§l【確認】もう一度クリックで解散");
                        meta.setLore(List.of("§7クリックするとパーティーを解散します。"));
                    } else {
                        meta.setDisplayName("§c§lパーティーを解散");
                        meta.setLore(List.of("§7リーダー権限でパーティーを解散します。", "", "§e▶ クリックで確認"));
                    }
                    is.setItemMeta(meta);
                }
                return is;
            });
            item.onClick(c -> {
                Player player = c.player();
                if (!"disband".equals(c.menu().getProperty("confirm_action"))) {
                    Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                    props.put("confirm_action", "disband");
                    BukkitPartyNotifier.playClickSound(player);
                    plugin.getSlate().openMenu(player, "party", props, c.menu().getCurrentPage());
                    return;
                }
                PartyActionResult res = plugin.getPartyManager().disbandParty(player.getUniqueId());
                if (res == PartyActionResult.SUCCESS) {
                    BukkitPartyNotifier.playToggleSound(player, false);
                    Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                    props.remove("confirm_action");
                    plugin.getSlate().openMenu(player, "party", props, 0);
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(player, res, "");
                }
            });
        });

        // メンバー一覧テンプレート (1,0 〜 3,8)
        menu.template("member", Integer.class, template -> {
            template.definedContexts(m -> {
                PartyImpl party = plugin.getPartyManager().getPartyImpl(m.player().getUniqueId());
                if (party == null) return Collections.emptySet();
                int page = m.menu().getCurrentPage();
                int start = page * MEMBERS_PER_PAGE;
                int count = Math.min(MEMBERS_PER_PAGE, Math.max(0, party.getSize() - start));
                Set<Integer> indices = new LinkedHashSet<>();
                for (int i = 0; i < count; i++) {
                    indices.add(i);
                }
                return indices;
            });

            template.slotPos(c -> {
                int idx = c.value();
                int row = 1 + (idx / 9);
                int col = idx % 9;
                return SlotPos.of(row, col);
            });

            template.modify(info -> {
                Player viewer = info.player();
                PartyImpl party = plugin.getPartyManager().getPartyImpl(viewer.getUniqueId());
                if (party == null) return null;
                List<UUID> sorted = getSortedMembers(party);
                int globalIdx = info.menu().getCurrentPage() * MEMBERS_PER_PAGE + info.value();
                if (globalIdx < 0 || globalIdx >= sorted.size()) return null;
                UUID memberId = sorted.get(globalIdx);

                ItemStack is = info.item();
                is.setType(Material.PLAYER_HEAD);
                ItemMeta rawMeta = is.getItemMeta();
                if (!(rawMeta instanceof SkullMeta meta)) return is;

                OfflinePlayer offline = Bukkit.getOfflinePlayer(memberId);
                meta.setOwningPlayer(offline);

                String name = new BukkitPartyNotifier(plugin).getPlayerName(memberId);
                boolean isLeader = party.isLeader(memberId);
                Player online = Bukkit.getPlayer(memberId);

                String prefix = isLeader ? "§6★ [リーダー] " : "§b";
                String status = (online != null && online.isOnline()) ? "§a● オンライン" : "§7● オフライン";
                meta.setDisplayName(prefix + name);

                List<String> lore = new ArrayList<>();
                lore.add("§7状態: " + status);
                if (online != null && online.isOnline()) {
                    User user = plugin.getUser(online);
                    lore.add("§7パワーレベル: §e" + user.getPowerLevel());
                    if (!memberId.equals(viewer.getUniqueId())) {
                        if (Objects.equals(viewer.getWorld(), online.getWorld())) {
                            int dist = (int) Math.round(viewer.getLocation().distance(online.getLocation()));
                            lore.add("§7距離: §f" + dist + "m");
                        } else {
                            lore.add("§7ワールド: §f別ワールド");
                        }
                    }
                } else {
                    Long offSince = party.getOfflineSince(memberId);
                    if (offSince != null) {
                        long elapsedSec = Math.max(0L, (System.currentTimeMillis() - offSince) / 1000L);
                        lore.add("§8オフライン経過: " + elapsedSec + "秒");
                    }
                }

                if (party.isLeader(viewer.getUniqueId()) && !memberId.equals(viewer.getUniqueId())) {
                    String confirmTarget = (String) info.menu().getProperty("confirm_member");
                    String confirmType = (String) info.menu().getProperty("confirm_action");
                    lore.add("");
                    if (memberId.toString().equals(confirmTarget) && "promote".equals(confirmType)) {
                        lore.add("§6▶ もう一度左クリックでリーダーを委譲");
                    } else {
                        lore.add("§e▶ 左クリック: リーダーを委譲");
                    }
                    if (memberId.toString().equals(confirmTarget) && "kick".equals(confirmType)) {
                        lore.add("§c▶ もう一度右クリックでキック確定");
                    } else {
                        lore.add("§c▶ 右クリック: パーティーからキック");
                    }
                }

                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            template.onClick(c -> {
                Player viewer = c.player();
                PartyImpl party = plugin.getPartyManager().getPartyImpl(viewer.getUniqueId());
                if (party == null) return;
                List<UUID> sorted = getSortedMembers(party);
                int globalIdx = c.menu().getCurrentPage() * MEMBERS_PER_PAGE + c.value();
                if (globalIdx < 0 || globalIdx >= sorted.size()) return;
                UUID targetId = sorted.get(globalIdx);
                if (!party.isLeader(viewer.getUniqueId()) || targetId.equals(viewer.getUniqueId())) {
                    return;
                }

                boolean isRight = c.event().isRightClick();
                String action = isRight ? "kick" : "promote";
                String prevAction = (String) c.menu().getProperty("confirm_action");
                String prevTarget = (String) c.menu().getProperty("confirm_member");

                if (!action.equals(prevAction) || !targetId.toString().equals(prevTarget)) {
                    Map<String, Object> props = new HashMap<>(c.menu().getProperties());
                    props.put("confirm_action", action);
                    props.put("confirm_member", targetId.toString());
                    BukkitPartyNotifier.playClickSound(viewer);
                    plugin.getSlate().openMenu(viewer, "party", props, c.menu().getCurrentPage());
                    return;
                }

                PartyActionResult res = isRight
                        ? plugin.getPartyManager().kickMember(viewer.getUniqueId(), targetId)
                        : plugin.getPartyManager().promoteLeader(viewer.getUniqueId(), targetId);

                if (res == PartyActionResult.SUCCESS) {
                    reopenSameMenu(viewer, c.menu());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(viewer, res, new BukkitPartyNotifier(plugin).getPlayerName(targetId));
                }
            });
        });
    }

    private List<UUID> getSortedMembers(PartyImpl party) {
        List<UUID> list = new ArrayList<>(party.getMembers());
        list.sort((a, b) -> {
            if (party.isLeader(a) && !party.isLeader(b)) return -1;
            if (!party.isLeader(a) && party.isLeader(b)) return 1;
            boolean aOn = Bukkit.getPlayer(a) != null;
            boolean bOn = Bukkit.getPlayer(b) != null;
            if (aOn != bOn) return aOn ? -1 : 1;
            return a.compareTo(b);
        });
        return list;
    }

    private ItemStack buildBlockToggleItem(ItemStack is, Player player) {
        boolean blocked = plugin.getPartyManager().isInviteBlocked(player.getUniqueId());
        is.setType(blocked ? Material.RED_DYE : Material.BELL);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e§l招待ブロック: " + (blocked ? "§cON (受信拒否)" : "§aOFF (受信可)"));
            List<String> lore = new ArrayList<>();
            lore.add("§7ONにすると他プレイヤーからの");
            lore.add("§7パーティー招待をすべてブロックします。");
            lore.add("");
            lore.add("§e▶ クリックして切り替え");
            meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private void handleToggleBlock(Player player, ActiveMenu menu) {
        boolean blocked = plugin.getPartyManager().toggleInviteBlock(player.getUniqueId());
        BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
        String key = blocked ? "party.block_on" : "party.block_off";
        String fallback = blocked
                ? "<yellow>パーティー招待の受信ブロックを <bold>ON</bold><yellow> にしました。"
                : "<green>パーティー招待の受信ブロックを <bold>OFF</bold><green> にしました。";
        notifier.sendMessage(player, notifier.msg(plugin.getLocale(player), key, fallback));
        BukkitPartyNotifier.playToggleSound(player, !blocked);
        reopenSameMenu(player, menu);
    }

    private void reopenSameMenu(Player player, ActiveMenu menu) {
        Map<String, Object> props = new HashMap<>(menu.getProperties());
        props.remove("confirm_action");
        props.remove("confirm_member");
        plugin.getSlate().openMenu(player, "party", props, menu.getCurrentPage());
    }

}
