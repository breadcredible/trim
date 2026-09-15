package com.coral;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
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
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Coral extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final long FLOW_COOLDOWN_MS = 60_000;
    private static final long SILENCE_COOLDOWN_MS = 60_000;
    private static final long RAISER_COOLDOWN_MS = 60_000;
    private static final long EYE_COOLDOWN_MS = 60_000;

    private final Map<UUID, Long> flowCooldowns = new HashMap<>();
    private final Map<UUID, Long> silenceCooldowns = new HashMap<>();
    private final Map<UUID, Long> raiserCooldowns = new HashMap<>();
    private final Map<UUID, Long> eyeCooldowns = new HashMap<>();
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Set<UUID> flowPending = new HashSet<>();
    private final Set<UUID> silenceActive = new HashSet<>();

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
    public void onEquipmentChange(EntityEquipmentChangedEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        refreshTrimPowers(p);
    }

    @EventHandler
    public void onPotionEffectChange(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityPotionEffectEvent.Cause.PLUGIN) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.REMOVED && e.getAction() != EntityPotionEffectEvent.Action.CLEARED) return;
        PotionEffect old = e.getOldEffect();
        if (old == null) return;
        PotionEffectType type = old.getType();
        if (!type.equals(PotionEffectType.SPEED) && !type.equals(PotionEffectType.STRENGTH)
                && !type.equals(PotionEffectType.RESISTANCE) && !type.equals(PotionEffectType.REGENERATION)) return;
        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) refreshTrimPowers(p);
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        e.setCancelled(true);
        ItemStack[] armor = p.getInventory().getArmorContents();
        boolean hasFlow = false, hasSilence = false, hasEye = false, hasRaiser = false;
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType() == Material.AIR || !(piece.getItemMeta() instanceof ArmorMeta meta) || !meta.hasTrim())
                continue;
            TrimPattern pattern = meta.getTrim().getPattern();
            if (pattern.equals(TrimPattern.FLOW)) hasFlow = true;
            else if (pattern.equals(TrimPattern.SILENCE)) hasSilence = true;
            else if (pattern.equals(TrimPattern.EYE)) hasEye = true;
            else if (pattern.equals(TrimPattern.RAISER)) hasRaiser = true;
        }
        if (hasFlow) tryActivateFlow(p);
        if (hasSilence) tryActivateSilence(p);
        if (hasEye) tryActivateEye(p);
        if (hasRaiser) tryActivateRaiser(p);
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player damager)) return;
        if (!silenceActive.contains(damager.getUniqueId())) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
        }
        e.setDamage(e.getDamage() * 1.5);
    }

    private void tryActivateFlow(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = flowCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        flowCooldowns.put(id, now + FLOW_COOLDOWN_MS);
        startCooldownDisplay(p);

        Vector dir = p.getLocation().getDirection().normalize();
        p.setVelocity(dir.multiply(1.8).setY(0.45));
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation(), 40, 0.3, 0.1, 0.3, 0.02);
        flowPending.add(id);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline() || !flowPending.contains(id)) {
                    flowPending.remove(id);
                    cancel();
                    return;
                }
                ticks++;
                if (ticks > 100) {
                    flowPending.remove(id);
                    cancel();
                    return;
                }
                if (ticks > 3 && p.isOnGround()) {
                    flowPending.remove(id);
                    Location loc = p.getLocation();
                    loc.getWorld().spawnParticle(Particle.SOUL, loc, 60, 1.2, 0.2, 1.2, 0.03);
                    for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 3.5, 3.5, 3.5)) {
                        if (nearby instanceof LivingEntity le && nearby != p) {
                            le.damage(6.0, p);
                        }
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void tryActivateSilence(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = silenceCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        silenceCooldowns.put(id, now + SILENCE_COOLDOWN_MS);
        startCooldownDisplay(p);

        silenceActive.add(id);
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
        new BukkitRunnable() {
            @Override public void run() {
                silenceActive.remove(id);
            }
        }.runTaskLater(this, 60L);
    }

    private void tryActivateEye(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = eyeCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        eyeCooldowns.put(id, now + EYE_COOLDOWN_MS);
        startCooldownDisplay(p);

        p.setInvulnerable(true);
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) p.setInvulnerable(false);
            }
        }.runTaskLater(this, 100L);
    }

    private void tryActivateRaiser(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = raiserCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        raiserCooldowns.put(id, now + RAISER_COOLDOWN_MS);
        startCooldownDisplay(p);

        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 200, 4, true, true, true));
        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) p.setHealth(40.0);
            }
        }.runTaskLater(this, 1L);
    }

    private void startCooldownDisplay(Player p) {
        UUID id = p.getUniqueId();
        if (actionBarTasks.containsKey(id)) return;
        BukkitRunnable task = new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline()) {
                    actionBarTasks.remove(id);
                    cancel();
                    return;
                }
                long now = System.currentTimeMillis();
                long remaining = Math.max(0, flowCooldowns.getOrDefault(id, 0L) - now);
                remaining = Math.max(remaining, silenceCooldowns.getOrDefault(id, 0L) - now);
                remaining = Math.max(remaining, raiserCooldowns.getOrDefault(id, 0L) - now);
                remaining = Math.max(remaining, eyeCooldowns.getOrDefault(id, 0L) - now);
                if (remaining <= 0) {
                    actionBarTasks.remove(id);
                    cancel();
                    return;
                }
                long totalSeconds = (remaining + 999) / 1000;
                long minutes = totalSeconds / 60;
                long seconds = totalSeconds % 60;
                Component bar = Component.text("⌚ ").color(NamedTextColor.GOLD)
                        .append(Component.text(String.format("%02d:%02d", minutes, seconds)).color(NamedTextColor.YELLOW));
                p.sendActionBar(bar);
            }
        };
        actionBarTasks.put(id, task);
        task.runTaskTimer(this, 0L, 20L);
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
