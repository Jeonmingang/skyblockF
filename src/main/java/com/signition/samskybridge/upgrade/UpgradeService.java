package com.signition.samskybridge.upgrade;

import com.signition.samskybridge.Main;
import com.signition.samskybridge.data.DataStore;
import com.signition.samskybridge.data.IslandData;
import com.signition.samskybridge.feature.FeatureService;
import com.signition.samskybridge.integration.BentoSync;
import com.signition.samskybridge.level.LevelService;
import com.signition.samskybridge.util.Text;
import com.signition.samskybridge.util.VaultHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Minimal, Java 8–compatible upgrade GUI + click handler.
 * This class intentionally avoids newer Java syntax/features so it compiles on JDK 8
 * and only uses Bukkit API available on 1.16.5.
 *
 * NOTE: This replaces a corrupted source region that produced many
 * "illegal start of expression" and "unclosed string literal" errors.
 * Functionally, it preserves the visible behavior:
 *  - /섬 업그레이드 opens a 3x9 GUI showing 광산/농장 업그레이드
 *  - Clicking an item attempts to upgrade via FeatureService
 *  - Lore and names are read from config if present, with safe defaults
 */
public class UpgradeService {
    private final Main plugin;
    private final DataStore store;
    private final LevelService level;
    private final VaultHook vault;
    private final BentoSync bento;
    private final FeatureService features;

    public UpgradeService(Main plugin, DataStore store, LevelService level, VaultHook vault, BentoSync bento){
        this.plugin = plugin;
        this.store = store;
        this.level = level;
        this.vault = vault;
        this.bento = bento;
        this.features = plugin.getFeatures();
    }

    public UpgradeService(Main plugin, DataStore store, LevelService level, VaultHook vault){
        this(plugin, store, level, vault, plugin.getBento());
    }

    /* ===================== GUI ===================== */
    public void openGui(Player p){
        Inventory inv = Bukkit.createInventory(null, 27, plugin.getConfig().getString("gui.title-upgrade","섬 업그레이드"));
        // slots (defaults chosen to match old layout)
        int slotMine = plugin.getConfig().getInt("upgrade.gui.slots.mine", 12);
        int slotFarm = plugin.getConfig().getInt("upgrade.gui.slots.farm", 14);

        inv.setItem(slotMine, buildFeatureItem(p, "mine"));
        inv.setItem(slotFarm, buildFeatureItem(p, "farm"));

        p.openInventory(inv);
    }

    public void click(Player p, int slot, boolean shift){
        int slotMine = plugin.getConfig().getInt("upgrade.gui.slots.mine", 12);
        int slotFarm = plugin.getConfig().getInt("upgrade.gui.slots.farm", 14);

        if (slot == slotMine){
            if (!isOwner(p)) { p.sendMessage(Text.color("&c섬장만 업그레이드할 수 있습니다.")); return; }
            boolean ok = features.upgradeMine(p);
            if (!ok) p.sendMessage(Text.color("&c업그레이드 조건이 충족되지 않았습니다.")); 
            openGui(p);
            return;
        }
        if (slot == slotFarm){
            if (!isOwner(p)) { p.sendMessage(Text.color("&c섬장만 업그레이드할 수 있습니다.")); return; }
            boolean ok = features.upgradeFarm(p);
            if (!ok) p.sendMessage(Text.color("&c업그레이드 조건이 충족되지 않았습니다."));
            openGui(p);
            return;
        }
    }

    /* ===================== Helpers ===================== */

    private boolean isOwner(Player p){
        IslandData is = level.getIslandOf(p);
        return is != null && is.getOwner() != null && is.getOwner().equals(p.getUniqueId());
    }

    private ItemStack buildFeatureItem(Player p, String feature){
        IslandData is = level.getIslandOf(p);
        UUID id = is != null ? is.getId() : p.getUniqueId();

        int now = 0;
        int max = getMaxLevel(feature);
        if ("mine".equalsIgnoreCase(feature)){
            now = features.getMineLevel(id);
        } else if ("farm".equalsIgnoreCase(feature)){
            now = features.getFarmLevel(id);
        }

        int next = Math.min(max, now + 1);
        int reqLevel = getInt("features."+feature+".levels."+next+".require-island-level", 0);
        long cost = getLong("features."+feature+".levels."+next+".cost", 0L);
        String bonus = plugin.getConfig().getString("features."+feature+".levels."+next+".bonus", "");
        int regen = plugin.getConfig().getInt("features."+feature+".levels."+next+".regen", 0);

        String nameKey = "upgrades."+feature+".name";
        String defName = "mine".equals(feature) ? "&b광산 업그레이드" : "&a농장 업그레이드";
        String displayName = Text.color(plugin.getConfig().getString(nameKey, defName));

        List<String> lore = readLoreTemplate(feature);
        if (lore.isEmpty()){
            // fallback default lore
            lore = new ArrayList<String>();
            lore.add("&7현재 레벨: &f{now}/{max}");
            lore.add("&7요구 레벨: &fLv.{need}");
            lore.add("&7필요 금액: &a{cost}");
            lore.add("&7다음 보너스: &d{nextBonus}");
            if (regen > 0) lore.add("&7재생성 시간: &e{nextRegen}초");
            lore.add("&8클릭: 업그레이드");
        }
        List<String> colored = new ArrayList<String>();
        for (String s : lore){
            String r = s.replace("{now}", String.valueOf(now))
                        .replace("{max}", String.valueOf(max))
                        .replace("{need}", String.valueOf(reqLevel))
                        .replace("{cost}", String.valueOf(cost))
                        .replace("{nextBonus}", String.valueOf(bonus))
                        .replace("{nextRegen}", String.valueOf(regen));
            colored.add(Text.color(r));
        }

        Material mat = "mine".equals(feature) ? Material.IRON_PICKAXE : Material.WHEAT;
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null){
            meta.setDisplayName(displayName);
            meta.setLore(colored);
            it.setItemMeta(meta);
        }
        return it;
    }

    private int getMaxLevel(String feature){
        return plugin.getConfig().getInt("features."+feature+".max-level", 5);
    }

    private List<String> readLoreTemplate(String feature){
        String base = "upgrades."+feature+".gui.lore-template";
        if (plugin.getConfig().isList(base)){
            return plugin.getConfig().getStringList(base);
        }
        String legacy = "upgrades-ui."+feature+".lore";
        if (plugin.getConfig().isList(legacy)){
            return plugin.getConfig().getStringList(legacy);
        }
        return Collections.emptyList();
    }

    private int getInt(String path, int def){
        try{
            return plugin.getConfig().getInt(path, def);
        }catch(Throwable t){ return def; }
    }

    private long getLong(String path, long def){
        try{
            return plugin.getConfig().getLong(path, def);
        }catch(Throwable t){ return def; }
    }
}
