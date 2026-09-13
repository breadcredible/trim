package com.coral;

import io.papermc.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.List;

public class Coral extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("givealltrims") != null) {
            getCommand("givealltrims").setExecutor(this);
            getCommand("givealltrims").setTabCompleter(this);
        }
        for (Player p : getServer().getOnlinePlayers()) refreshTrimPowers(p);
    }

    private void refreshTrimPowers(Player p) {
        ItemStack[] armor = p.getInventory().getArmorContents();
        boolean hasFlow = false, hasSilence = false, hasEye = false, hasRaiser = false;
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType() == Material.AIR || !(piece.getItemMeta() instanceof ArmorMeta meta) || !meta.hasTrim())
                continue;
            if (!meta.isUnbreakable()) {
                meta.setUnbreakable(true);
                piece.setItemMeta(meta);
                armor[i] = piece;
                changed = true;
            }
            ArmorTrim trim = meta.getTrim();
            TrimPattern pattern = trim.getPattern();
            if (pattern.equals(TrimPattern.FLOW)) hasFlow = true;
            else if (pattern.equals(TrimPattern.SILENCE)) hasSilence = true;
            else if (pattern.equals(TrimPattern.EYE)) hasEye = true;
            else if (pattern.equals(TrimPattern.RAISER)) hasRaiser = true;
        }
        if (changed) p.getInventory().setArmorContents(armor);

        int inf = Integer.MAX_VALUE;
        if (hasFlow) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, inf, 1, true, false, false));
        else p.removePotionEffect(PotionEffectType.SPEED);
        if (hasSilence) p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, inf, 1, true, false, false));
        else p.removePotionEffect(PotionEffectType.STRENGTH);
        if (hasEye) p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, inf, 0, true, false, false));
        else p.removePotionEffect(PotionEffectType.RESISTANCE);
        if (hasRaiser) p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, inf, 0, true, false, false));
        else p.removePotionEffect(PotionEffectType.REGENERATION);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        refreshTrimPowers(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) refreshTrimPowers(p);
            }
        }.runTask(this);
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent e) {
        refreshTrimPowers(e.getPlayer());
    }

    private ItemStack trimmedPiece(Material material, TrimPattern pattern) {
        ItemStack piece = new ItemStack(material);
        ArmorMeta meta = (ArmorMeta) piece.getItemMeta();
        meta.setTrim(new ArmorTrim(TrimMaterial.NETHERITE, pattern));
        meta.setUnbreakable(true);
        piece.setItemMeta(meta);
        return piece;
    }

    private void giveAllTrims(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setHelmet(trimmedPiece(Material.NETHERITE_HELMET, TrimPattern.EYE));
        inv.setChestplate(trimmedPiece(Material.NETHERITE_CHESTPLATE, TrimPattern.SILENCE));
        inv.setLeggings(trimmedPiece(Material.NETHERITE_LEGGINGS, TrimPattern.FLOW));
        inv.setBoots(trimmedPiece(Material.NETHERITE_BOOTS, TrimPattern.RAISER));
        p.sendMessage(c("&dYou have been given a full trim-powered netherite set (Eye / Silence / Flow / Raiser)."));
        refreshTrimPowers(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!label.equalsIgnoreCase("givealltrims")) return false;
        if (!(sender instanceof Player p)) {
            sender.sendMessage(c("&cThis command can only be used by players."));
            return true;
        }
        if (!p.hasPermission("coral.givealltrims") && !p.isOp()) {
            p.sendMessage(c("&cYou do not have permission to use this command."));
            return true;
        }
        giveAllTrims(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        return label.equalsIgnoreCase("givealltrims") ? Collections.emptyList() : null;
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
