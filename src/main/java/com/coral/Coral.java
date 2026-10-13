package com.coral;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Coral extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final long DASH_COOLDOWN_MS = 20_000;
    private static final long SILENCE_COOLDOWN_MS = 20_000;
    private static final long RAISER_COOLDOWN_MS = 20_000;
    private static final long EYE_COOLDOWN_MS = 20_000;
    private static final long TIDE_COOLDOWN_MS = 60_000;

    private static final long INVIS_COOLDOWN_MS = 30_000;
    private static final long INVIS_TICKS = 100L;
    private static final long STUN_COOLDOWN_MS = 30_000;
    private static final long STUN_DURATION_MS = 3_000;
    private static final long STUN_TICKS = STUN_DURATION_MS / 50;
    private static final int STUN_SLOWNESS_AMPLIFIER = 255;
    private static final double STUN_RANGE = 15.0;

    private static final NamespacedKey RIFT_MODEL = NamespacedKey.fromString("coral:speed_rift");
    private static final Key ICON_FONT = Key.key("coral", "icons");
    private static final String ICON_DASH = "\uE001";
    private static final String ICON_INVIS = "\uE002";
    private static final String ICON_STUN = "\uE003";
    private static final String RIFT_NAME = "Speed Rift";
    private static final String RIFT_LORE = "Right-click to Use";

    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, Long> invisCooldowns = new HashMap<>();
    private final Map<UUID, Long> stunCooldowns = new HashMap<>();
    private final Map<UUID, Long> stunnedUntil = new HashMap<>();
    private final Map<UUID, float[]> stunLook = new HashMap<>();
    private final Set<UUID> riftInvisible = new HashSet<>();
    private final Set<UUID> riftHolders = new HashSet<>();
    private final Set<UUID> riftCarriers = new HashSet<>();
    private final Map<UUID, Long> silenceCooldowns = new HashMap<>();
    private final Map<UUID, Long> raiserCooldowns = new HashMap<>();
    private final Map<UUID, Long> eyeCooldowns = new HashMap<>();
    private final Map<UUID, Long> tideCooldowns = new HashMap<>();
    private final Set<UUID> dashPending = new HashSet<>();
    private final Set<UUID> silenceActive = new HashSet<>();
    private final Set<UUID> abilityBypassArmor = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("givealltrims") != null) {
            getCommand("givealltrims").setExecutor(this);
            getCommand("givealltrims").setTabCompleter(this);
        }
        if (getCommand("use") != null) {
            getCommand("use").setExecutor(this);
            getCommand("use").setTabCompleter(this);
        }
        for (Player p : getServer().getOnlinePlayers()) refreshTrimPowers(p);
        getServer().getScheduler().runTaskTimer(this, this::tickDisplays, 10L, 10L);
    }

    private void refreshTrimPowers(Player p) {
        ItemStack[] armor = p.getInventory().getArmorContents();
        boolean hasSilence = false, hasEye = false, hasRaiser = false, hasTide = false;
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
            if (pattern.equals(TrimPattern.SILENCE)) hasSilence = true;
            else if (pattern.equals(TrimPattern.EYE)) hasEye = true;
            else if (pattern.equals(TrimPattern.RAISER)) hasRaiser = true;
            else if (pattern.equals(TrimPattern.TIDE)) hasTide = true;
        }
        if (changed) p.getInventory().setArmorContents(armor);

        boolean grantsSpeed = carriesSpeedRift(p) || hasTide;
        boolean grantsStrength = hasSilence || hasTide;
        syncTrimEffect(p, PotionEffectType.SPEED, 1, grantsSpeed);
        syncTrimEffect(p, PotionEffectType.STRENGTH, 1, grantsStrength);
        syncTrimEffect(p, PotionEffectType.RESISTANCE, 0, hasEye);
        syncTrimEffect(p, PotionEffectType.REGENERATION, 0, hasRaiser);
    }

    private void syncTrimEffect(Player p, PotionEffectType type, int amplifier, boolean grant) {
        PotionEffect current = p.getPotionEffect(type);
        boolean ours = current != null && current.getDuration() > 1_000_000_000;
        if (grant) {
            if (ours && current.getAmplifier() == amplifier) return;
            p.addPotionEffect(new PotionEffect(type, Integer.MAX_VALUE, amplifier, true, false, false));
        } else if (ours) {
            p.removePotionEffect(type);
        }
    }

    private boolean isSpeedRift(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemModel() && RIFT_MODEL.equals(meta.getItemModel());
    }

    private boolean carriesSpeedRift(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isSpeedRift(item)) return true;
        }
        return false;
    }

    private boolean holdsSpeedRift(Player p) {
        PlayerInventory inv = p.getInventory();
        return isSpeedRift(inv.getItemInMainHand()) || isSpeedRift(inv.getItemInOffHand());
    }

    private ItemStack speedRift() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(RIFT_MODEL);
        Component name = Component.text(RIFT_NAME, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false);
        meta.customName(name);
        meta.itemName(name);
        meta.lore(List.of(Component.text(RIFT_LORE, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private Component gradient(String text, int from, int to, TextDecoration.State italic) {
        TextComponent.Builder out = Component.text();
        int last = Math.max(text.length() - 1, 1);
        for (int i = 0; i < text.length(); i++) {
            double t = (double) i / last;
            int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
            int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
            int b = lerp(from & 0xFF, to & 0xFF, t);
            out.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, b))
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, italic));
        }
        return out.build();
    }

    private int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();
        for (UUID id : riftInvisible) {
            Player hidden = getServer().getPlayer(id);
            if (hidden != null && !hidden.equals(joiner)) joiner.hidePlayer(this, hidden);
        }
        refreshTrimPowers(joiner);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        riftHolders.remove(id);
        riftCarriers.remove(id);
        endInvisibility(id);
        endStun(id);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        endInvisibility(id);
        endStun(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStunnedMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!isStunned(id)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        float[] look = stunLook.get(id);
        float yaw = look != null ? look[0] : from.getYaw();
        float pitch = look != null ? look[1] : from.getPitch();
        boolean moved = to.getX() != from.getX() || to.getZ() != from.getZ() || to.getY() > from.getY();
        boolean turned = Math.abs(to.getYaw() - yaw) > 0.01f || Math.abs(to.getPitch() - pitch) > 0.01f;
        if (!moved && !turned) return;
        Location fixed = new Location(from.getWorld(), from.getX(), Math.min(to.getY(), from.getY()), from.getZ(), yaw, pitch);
        e.setTo(fixed);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStunnedInteract(PlayerInteractEvent e) {
        if (!isStunned(e.getPlayer().getUniqueId())) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStunnedInteractEntity(PlayerInteractEntityEvent e) {
        if (isStunned(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStunnedAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player damager)) return;
        if (abilityBypassArmor.contains(e.getEntity().getUniqueId())) return;
        if (isStunned(damager.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStunnedBreak(BlockBreakEvent e) {
        if (isStunned(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    private boolean isStunned(UUID id) {
        Long until = stunnedUntil.get(id);
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            stunnedUntil.remove(id);
            return false;
        }
        return true;
    }

    private void endStun(UUID id) {
        stunnedUntil.remove(id);
        stunLook.remove(id);
        Player p = getServer().getPlayer(id);
        if (p == null) return;
        PotionEffect slow = p.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null && slow.getAmplifier() == STUN_SLOWNESS_AMPLIFIER) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
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
        boolean armorChanged = e.getEquipmentChanges().keySet().stream().anyMatch(s ->
                s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST
                        || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET);
        if (!armorChanged) return;
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
        if (isStunned(p.getUniqueId())) return;
        ItemStack[] armor = p.getInventory().getArmorContents();
        boolean hasSilence = false, hasEye = false, hasRaiser = false;
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType() == Material.AIR || !(piece.getItemMeta() instanceof ArmorMeta meta) || !meta.hasTrim())
                continue;
            TrimPattern pattern = meta.getTrim().getPattern();
            if (pattern.equals(TrimPattern.SILENCE)) hasSilence = true;
            else if (pattern.equals(TrimPattern.EYE)) hasEye = true;
            else if (pattern.equals(TrimPattern.RAISER)) hasRaiser = true;
        }
        if (holdsSpeedRift(p)) tryActivateDash(p);
        if (hasSilence) tryActivateSilence(p);
        if (hasEye) tryActivateEye(p);
        if (hasRaiser) tryActivateRaiser(p);
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;
        Player p = e.getPlayer();
        if (isStunned(p.getUniqueId())) return;
        if (!p.isSneaking()) return;
        if (!hasTrimPattern(p, TrimPattern.TIDE)) return;
        e.setCancelled(true);
        tryActivateTide(p);
    }

    private boolean hasTrimPattern(Player p, TrimPattern pattern) {
        for (ItemStack piece : p.getInventory().getArmorContents()) {
            if (piece != null && piece.getItemMeta() instanceof ArmorMeta meta && meta.hasTrim()
                    && meta.getTrim().getPattern().equals(pattern)) return true;
        }
        return false;
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player damager)) return;
        if (!silenceActive.contains(damager.getUniqueId())) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
        }
        e.setDamage(e.getDamage() * 1.5);
        e.getEntity().getWorld().spawnParticle(Particle.CRIT, e.getEntity().getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.02);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAbilityDamageBypassArmor(EntityDamageEvent e) {
        if (!abilityBypassArmor.contains(e.getEntity().getUniqueId())) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) e.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0.0);
        if (e.isApplicable(EntityDamageEvent.DamageModifier.RESISTANCE)) e.setDamage(EntityDamageEvent.DamageModifier.RESISTANCE, 0.0);
        if (e.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) e.setDamage(EntityDamageEvent.DamageModifier.MAGIC, 0.0);
    }

    private void damageIgnoringArmor(LivingEntity target, double amount, Player source) {
        UUID id = target.getUniqueId();
        abilityBypassArmor.add(id);
        try {
            target.damage(amount, source);
        } finally {
            abilityBypassArmor.remove(id);
        }
    }

    private void tryActivateDash(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = dashCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        dashCooldowns.put(id, now + DASH_COOLDOWN_MS);

        Vector dir = p.getLocation().getDirection().normalize();
        p.setVelocity(dir.multiply(3.0).setY(0.6));
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation(), 40, 0.3, 0.1, 0.3, 0.02);
        dashPending.add(id);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline() || !dashPending.contains(id)) {
                    dashPending.remove(id);
                    cancel();
                    return;
                }
                ticks++;
                if (ticks > 100) {
                    dashPending.remove(id);
                    cancel();
                    return;
                }
                if (ticks > 3 && p.isOnGround()) {
                    dashPending.remove(id);
                    Location loc = p.getLocation();
                    loc.getWorld().spawnParticle(Particle.SOUL, loc, 60, 1.2, 0.2, 1.2, 0.03);
                    for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 3.5, 3.5, 3.5)) {
                        if (nearby instanceof LivingEntity le && nearby != p) {
                            damageIgnoringArmor(le, 10.0, p);
                        }
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void tryActivateInvisibility(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = invisCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        invisCooldowns.put(id, now + INVIS_COOLDOWN_MS);

        riftInvisible.add(id);
        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        for (Player other : getServer().getOnlinePlayers()) {
            if (!other.equals(p)) other.hidePlayer(this, p);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) INVIS_TICKS, 0, true, false, false));
        p.sendMessage(c("&bYou vanish for " + (INVIS_TICKS / 20) + " seconds."));

        new BukkitRunnable() {
            @Override public void run() {
                endInvisibility(id);
            }
        }.runTaskLater(this, INVIS_TICKS);
    }

    private void endInvisibility(UUID id) {
        if (!riftInvisible.remove(id)) return;
        Player p = getServer().getPlayer(id);
        if (p == null) return;
        for (Player other : getServer().getOnlinePlayers()) {
            if (!other.equals(p)) other.showPlayer(this, p);
        }
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.sendMessage(c("&7You're visible again."));
    }

    private void tryActivateStun(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = stunCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTrace(eye, eye.getDirection(), STUN_RANGE,
                FluidCollisionMode.NEVER, true, 0.5,
                en -> en instanceof Player other && !other.equals(p) && other.getGameMode() != GameMode.SPECTATOR);
        if (hit == null || !(hit.getHitEntity() instanceof Player target)) {
            p.sendMessage(c("&cNo one in sight to stun."));
            return;
        }
        stunCooldowns.put(id, now + STUN_COOLDOWN_MS);

        UUID targetId = target.getUniqueId();
        stunnedUntil.put(targetId, now + STUN_DURATION_MS);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) STUN_TICKS, STUN_SLOWNESS_AMPLIFIER, true, false, false));
        Location tl = target.getLocation();
        stunLook.put(targetId, new float[]{tl.getYaw(), tl.getPitch()});
        dashPending.remove(targetId);
        Vector vel = target.getVelocity();
        target.setVelocity(new Vector(0, Math.min(vel.getY(), 0), 0));
        target.clearActiveItem();
        target.sendMessage(c("&cYou've been stunned!"));
        p.sendMessage(c("&dYou stunned " + target.getName() + " for " + (STUN_DURATION_MS / 1000) + " seconds."));
        target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);

        new BukkitRunnable() {
            @Override public void run() {
                if (!target.isOnline() || !isStunned(targetId)) {
                    cancel();
                    return;
                }
                target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0, 2.1, 0), 6, 0.3, 0.1, 0.3, 0.01);
            }
        }.runTaskTimer(this, 0L, 5L);

        new BukkitRunnable() {
            @Override public void run() {
                Long until = stunnedUntil.get(targetId);
                if (until != null && until > System.currentTimeMillis() + 100) return;
                endStun(targetId);
            }
        }.runTaskLater(this, STUN_TICKS);
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

        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 200, 4, true, true, true));
        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) p.setHealth(40.0);
            }
        }.runTaskLater(this, 1L);
    }

    private void tryActivateTide(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = tideCooldowns.get(id);
        if (cd != null && cd > now) {
            p.sendMessage(c("&cPlease wait until the cooldown finishes."));
            return;
        }
        tideCooldowns.put(id, now + TIDE_COOLDOWN_MS);

        Vector dir = p.getLocation().getDirection().normalize();
        Location origin = p.getEyeLocation();
        Set<UUID> hitAlready = new HashSet<>();
        new BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                if (!p.isOnline() || step > 16) {
                    cancel();
                    return;
                }
                Location point = origin.clone().add(dir.clone().multiply(step * 0.75));
                point.getWorld().spawnParticle(Particle.SPLASH, point, 14, 0.4, 0.4, 0.4, 0.05);
                for (Entity nearby : point.getWorld().getNearbyEntities(point, 1.5, 1.5, 1.5)) {
                    if (nearby instanceof LivingEntity le && nearby != p && hitAlready.add(le.getUniqueId())) {
                        damageIgnoringArmor(le, 10.0, p);
                        le.setVelocity(le.getVelocity().add(dir.clone().multiply(0.6).setY(0.2)));
                    }
                }
                step++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void tickDisplays() {
        long now = System.currentTimeMillis();
        for (Player p : getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean holding = holdsSpeedRift(p);
            boolean carrying = holding || carriesSpeedRift(p);
            if (holding != riftHolders.contains(id)) {
                if (holding) {
                    riftHolders.add(id);
                } else {
                    riftHolders.remove(id);
                    p.sendActionBar(Component.empty());
                }
            }
            if (carrying != riftCarriers.contains(id)) {
                if (carrying) riftCarriers.add(id);
                else riftCarriers.remove(id);
                refreshTrimPowers(p);
            }
            if (holding) {
                p.sendActionBar(riftBar(id, now));
                continue;
            }
            long otherLeft = Math.max(silenceCooldowns.getOrDefault(id, 0L) - now,
                    Math.max(raiserCooldowns.getOrDefault(id, 0L) - now,
                    Math.max(eyeCooldowns.getOrDefault(id, 0L) - now,
                            tideCooldowns.getOrDefault(id, 0L) - now)));
            if (otherLeft > 0) {
                p.sendActionBar(Component.text("⌚ ").color(NamedTextColor.GOLD).append(slot(otherLeft)));
            }
        }
    }

    private Component riftBar(UUID id, long now) {
        Component gap = Component.text("   ");
        return Component.text()
                .append(entry(ICON_DASH, dashCooldowns.getOrDefault(id, 0L) - now))
                .append(gap)
                .append(entry(ICON_INVIS, invisCooldowns.getOrDefault(id, 0L) - now))
                .append(gap)
                .append(entry(ICON_STUN, stunCooldowns.getOrDefault(id, 0L) - now))
                .build();
    }

    private Component entry(String glyph, long remainingMs) {
        return Component.text()
                .append(Component.text(glyph).font(ICON_FONT).color(NamedTextColor.WHITE))
                .append(Component.text(" "))
                .append(slot(remainingMs))
                .build();
    }

    private Component slot(long remainingMs) {
        if (remainingMs <= 0) return Component.text("READY").color(NamedTextColor.GREEN);
        long totalSeconds = (remainingMs + 999) / 1000;
        return Component.text(String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)).color(NamedTextColor.YELLOW);
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
        inv.setBoots(trimmedPiece(Material.NETHERITE_BOOTS, TrimPattern.RAISER));
        inv.addItem(speedRift());
        p.sendMessage(c("&dYou have been given a trim-powered netherite set (Eye / Silence / Raiser) and a Speed Rift."));
        refreshTrimPowers(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("use")) return handleUse(sender, args);
        if (!cmd.getName().equalsIgnoreCase("givealltrims")) return false;
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

    private boolean handleUse(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(c("&cThis command can only be used by players."));
            return true;
        }
        if (!p.hasPermission("coral.use")) {
            p.sendMessage(c("&cYou do not have permission to use this command."));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("ultimate")) {
            p.sendMessage(c("&cUsage: /use ultimate <2|3>"));
            return true;
        }
        if (isStunned(p.getUniqueId())) {
            p.sendMessage(c("&cYou can't use abilities while stunned."));
            return true;
        }
        if (!holdsSpeedRift(p)) {
            p.sendMessage(c("&cYou need to hold a Speed Rift to use that."));
            return true;
        }
        switch (args[1]) {
            case "2" -> tryActivateInvisibility(p);
            case "3" -> tryActivateStun(p);
            default -> p.sendMessage(c("&cUsage: /use ultimate <2|3>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("use")) {
            if (args.length == 1) {
                return List.of("ultimate").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("ultimate")) {
                return List.of("2", "3").stream().filter(s -> s.startsWith(args[1])).toList();
            }
            return Collections.emptyList();
        }
        return cmd.getName().equalsIgnoreCase("givealltrims") ? Collections.emptyList() : null;
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
