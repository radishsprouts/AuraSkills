package dev.aurelium.auraskills.bukkit.menus;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.menus.shared.GlobalItems;
import dev.aurelium.auraskills.bukkit.party.BukkitPartyNotifier;
import dev.aurelium.auraskills.common.party.PartyActionResult;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.party.PartyInvite;
import dev.aurelium.slate.builder.MenuBuilder;
import dev.aurelium.slate.inv.content.SlotPos;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class PartyInvitesMenu {

    private final AuraSkills plugin;

    public PartyInvitesMenu(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void build(MenuBuilder menu) {
        var globalItems = new GlobalItems(plugin);
        menu.item("back", globalItems::back);
        menu.item("close", globalItems::close);
        menu.fillItem(globalItems::fill);

        menu.properties(m -> Map.of("previous_menu", m.menu().getProperty("previous_menu", "party")));

        menu.item("no_invites", item -> item.modify(info -> {
            if (!plugin.getPartyManager().getPendingInvites(info.player().getUniqueId()).isEmpty()) return null;
            return info.item();
        }));

        menu.template("invite", Integer.class, template -> {
            template.definedContexts(m -> {
                List<PartyInvite> invites = plugin.getPartyManager().getPendingInvites(m.player().getUniqueId());
                int count = Math.min(18, invites.size());
                Set<Integer> indices = new LinkedHashSet<>();
                for (int i = 0; i < count; i++) {
                    indices.add(i);
                }
                return indices;
            });

            template.slotPos(c -> {
                int idx = c.value();
                return SlotPos.of(1 + (idx / 9), idx % 9);
            });

            template.modify(info -> {
                List<PartyInvite> invites = plugin.getPartyManager().getPendingInvites(info.player().getUniqueId());
                int idx = info.value();
                if (idx < 0 || idx >= invites.size()) return null;

                PartyInvite invite = invites.get(idx);
                PartyImpl party = plugin.getPartyManager().getPartyById(invite.partyId());
                if (party == null) return null;

                ItemStack is = info.item();
                is.setType(Material.PLAYER_HEAD);
                ItemMeta rawMeta = is.getItemMeta();
                if (!(rawMeta instanceof SkullMeta meta)) return is;

                meta.setOwningPlayer(Bukkit.getOfflinePlayer(invite.inviterId()));
                BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);
                String inviterName = notifier.getPlayerName(invite.inviterId());
                String leaderName = notifier.getPlayerName(party.getLeader());
                long remainSec = Math.max(1L, (invite.expiresAtMillis() - System.currentTimeMillis()) / 1000L);

                meta.setDisplayName("§b" + leaderName + " のパーティー");
                List<String> lore = new ArrayList<>();
                lore.add("§7招待者: §f" + inviterName);
                lore.add("§7現在人数: §f" + party.getSize() + "人");
                lore.add("§7残り有効時間: §f" + remainSec + "秒");
                lore.add("");
                lore.add("§a▶ 左クリック: 参加する");
                lore.add("§c▶ 右クリック: 辞退する");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            template.onClick(c -> {
                Player viewer = c.player();
                List<PartyInvite> invites = plugin.getPartyManager().getPendingInvites(viewer.getUniqueId());
                int idx = c.value();
                if (idx < 0 || idx >= invites.size()) return;

                PartyInvite invite = invites.get(idx);
                BukkitPartyNotifier notifier = new BukkitPartyNotifier(plugin);

                if (c.event().isRightClick()) {
                    PartyActionResult res = plugin.getPartyManager().denyInvite(viewer.getUniqueId(), invite.partyId());
                    if (res == PartyActionResult.SUCCESS) {
                        BukkitPartyNotifier.playToggleSound(viewer, false);
                        plugin.getSlate().openMenu(viewer, "party_invites", c.menu().getProperties());
                    } else {
                        notifier.sendActionError(viewer, res, "");
                    }
                } else {
                    PartyActionResult res = plugin.getPartyManager().acceptInvite(viewer.getUniqueId(), invite.partyId());
                    if (res == PartyActionResult.SUCCESS) {
                        plugin.getSlate().openMenu(viewer, "party", Map.of("previous_menu", "skills"));
                    } else {
                        notifier.sendActionError(viewer, res, "");
                    }
                }
            });
        });
    }

}
