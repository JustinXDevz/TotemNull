package com.example.totemnull;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class TotemNull extends JavaPlugin implements Listener, CommandExecutor {

    private static final long TARGET_WINDOW_MS = 30_000; // a hit/attack counts as "target" for this long before summoning
    private static final long HORN_DEBOUNCE_MS = 700;

    private static class Army {
        final List<NPC> npcs = new ArrayList<>();
        final Map<NPC, Location> lastPos = new HashMap<>();
        final Map<NPC, Integer> stuck = new HashMap<>();
        final List<Block> placed = new ArrayList<>();
        Material placedType = Material.COBBLESTONE;
        UUID targetId;      // null = following owner
        BukkitTask task;
        int ticks = 0;
    }

    private NamespacedKey totemKey;
    private NPCRegistry registry; // anonymous: NPCs are never saved to Citizens' data

    private final Map<UUID, Army> armies = new HashMap<>();
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, Long> lastTargetTime = new HashMap<>();
    private final Map<UUID, Long> lastHorn = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        totemKey = new NamespacedKey(this, "totem_of_null");
        registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("totemnull").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(armies.keySet())) dismiss(id, false);
        if (registry != null) registry.deregisterAll();
    }

    // ---------- command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Usage: /totemnull <player>");
            return true;
        }
        target.getInventory().addItem(createTotem()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        target.sendMessage(Component.text("You now command NULL.", NamedTextColor.DARK_GRAY));
        return true;
    }

    private ItemStack createTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Totem of NULL", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Blow the Call horn to command NULL.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(totemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTotem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(totemKey, PersistentDataType.BYTE);
    }

    private boolean hasTotem(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isTotem(item)) return true;
        }
        return false;
    }

    // A goat horn counts if it is the "Call" horn OR renamed to "call"
    private boolean isCallHorn(ItemStack item) {
        if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof MusicInstrumentMeta mim && mim.getInstrument() != null) {
            NamespacedKey key = Registry.INSTRUMENT.getKey(mim.getInstrument());
            if (key != null && key.getKey().equals("call")) return true;
        }
        if (meta.hasDisplayName()) {
            String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            return name.trim().equalsIgnoreCase("call");
        }
        return false;
    }

    // ---------- targets: whatever the holder hits, or whatever hits the holder ----------
    private boolean isNpc(Entity e) {
        return e.hasMetadata("NPC");
    }

    private Entity shooterOf(Entity damager) {
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) return shooter;
        return damager;
    }

    private boolean canTarget(Player owner, Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof ArmorStand) return false;
        if (e.equals(owner) || e.isDead() || isNpc(e)) return false;
        if (e instanceof Tameable t && t.getOwner() != null
                && t.getOwner().getUniqueId().equals(owner.getUniqueId())) return false;
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity attacker = shooterOf(e.getDamager());

        // holder attacks something
        if (attacker instanceof Player holder && !isNpc(holder) && hasTotem(holder) && canTarget(holder, victim)) {
            noteTarget(holder, victim);
        }
        // something attacks the holder
        if (victim instanceof Player defender && !isNpc(defender) && hasTotem(defender) && canTarget(defender, attacker)) {
            noteTarget(defender, attacker);
        }
    }

    private void noteTarget(Player owner, Entity target) {
        UUID oid = owner.getUniqueId();
        lastTarget.put(oid, target.getUniqueId());
        lastTargetTime.put(oid, System.currentTimeMillis());
        Army army = armies.get(oid);
        if (army != null && !target.getUniqueId().equals(army.targetId)) {
            command(owner, army, (LivingEntity) target);
        }
    }

    private LivingEntity liveTarget(Player owner, UUID id) {
        if (id == null) return null;
        Entity e = Bukkit.getEntity(id);
        if (e instanceof LivingEntity le && le.isValid() && !le.isDead() && le.getWorld().equals(owner.getWorld())) {
            return le;
        }
        return null;
    }

    private LivingEntity recentTarget(Player owner) {
        UUID id = lastTarget.get(owner.getUniqueId());
        if (id == null) return null;
        long age = System.currentTimeMillis() - lastTargetTime.getOrDefault(owner.getUniqueId(), 0L);
        if (age > TARGET_WINDOW_MS) return null;
        return liveTarget(owner, id);
    }

    // ---------- horn: blow once = summon, blow again = vanish ----------
    @EventHandler
    public void onHorn(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCallHorn(e.getItem())) return;

        Player owner = e.getPlayer();
        if (!hasTotem(owner)) return;

        UUID id = owner.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - lastHorn.getOrDefault(id, 0L) < HORN_DEBOUNCE_MS) return;
        lastHorn.put(id, now);

        if (armies.containsKey(id)) {
            dismiss(id, true);
        } else {
            summon(owner);
        }
    }

    // ---------- army ----------
    private ItemStack nullSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = sword.getItemMeta();
        m.addEnchant(Enchantment.SHARPNESS, 5, true);
        m.addEnchant(Enchantment.UNBREAKING, 3, true);
        m.addEnchant(Enchantment.MENDING, 1, true);
        m.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        m.addEnchant(Enchantment.LOOTING, 3, true);
        m.addEnchant(Enchantment.KNOCKBACK, 2, true);
        m.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        sword.setItemMeta(m);
        return sword;
    }

    // names like "Xgafdiwn12": capital letter, lowercase letters, then 1-2 digits
    private String gibberishName() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int letters = r.nextInt(6, 10);
        int digits = r.nextInt(1, 3);
        StringBuilder sb = new StringBuilder();
        sb.append((char) ('A' + r.nextInt(26)));
        for (int i = 1; i < letters; i++) sb.append((char) ('a' + r.nextInt(26)));
        for (int i = 0; i < digits; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    private void applySkin(NPC npc) {
        String value = getConfig().getString("skin.value", "");
        String signature = getConfig().getString("skin.signature", "");
        if (!value.isEmpty() && !signature.isEmpty()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent("null_skin", signature, value);
        }
    }

    private Location nearOwner(Location o) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return o.clone().add(r.nextDouble(-4, 4), 0, r.nextDouble(-4, 4));
    }

    private void summon(Player owner) {
        Army army = new Army();
        int size = Math.max(1, Math.min(60, getConfig().getInt("army.size", 15)));

        for (int i = 0; i < size; i++) {
            NPC npc = registry.createNPC(EntityType.PLAYER, gibberishName());
            npc.setProtected(false); // can be killed
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, nullSword());
            applySkin(npc);
            npc.getNavigator().getDefaultParameters().range(100f).speedModifier(1.2f);
            npc.spawn(nearOwner(owner.getLocation()));
            army.npcs.add(npc);
        }
        armies.put(owner.getUniqueId(), army);
        command(owner, army, recentTarget(owner));
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);

        army.task = Bukkit.getScheduler().runTaskTimer(this, () -> tickArmy(owner, army), 10L, 10L);
    }

    // target == null -> follow the owner, otherwise attack target
    private void command(Player owner, Army army, LivingEntity target) {
        army.targetId = target == null ? null : target.getUniqueId();
        for (NPC npc : army.npcs) {
            if (!npc.isSpawned()) continue;
            if (target == null) npc.getNavigator().setTarget(owner, false);
            else npc.getNavigator().setTarget(target, true);
        }
    }

    // runs every 0.5s
    private void tickArmy(Player owner, Army army) {
        if (!owner.isOnline() || owner.isDead()) {
            dismiss(owner.getUniqueId(), false);
            return;
        }
        army.ticks += 10;

        LivingEntity target = liveTarget(owner, army.targetId);
        if (army.targetId != null && target == null) {
            command(owner, army, null);                 // target died / left: follow owner again
        } else if (army.ticks % 100 == 0) {
            command(owner, army, target);               // refresh navigation now and then
        }

        for (NPC npc : army.npcs) {
            if (!npc.isSpawned()) continue;
            Location here = npc.getEntity().getLocation();
            if (target == null) {
                army.stuck.remove(npc);
                army.lastPos.remove(npc);
                keepNearOwner(owner, npc, here);
            } else {
                pursue(owner, army, npc, here, target);
            }
        }
    }

    private void keepNearOwner(Player owner, NPC npc, Location here) {
        Location o = owner.getLocation();
        if (!here.getWorld().equals(o.getWorld()) || here.distanceSquared(o) > 40 * 40) {
            npc.teleport(nearOwner(o), PlayerTeleportEvent.TeleportCause.PLUGIN);
            npc.getNavigator().setTarget(owner, false);
        }
    }

    private void pursue(Player owner, Army army, NPC npc, Location here, LivingEntity target) {
        Location to = target.getLocation();
        if (!here.getWorld().equals(to.getWorld())) {
            npc.teleport(nearOwner(owner.getLocation()), PlayerTeleportEvent.TeleportCause.PLUGIN);
            npc.getNavigator().setTarget(target, true);
            return;
        }

        Location last = army.lastPos.put(npc, here.clone());
        if (here.distanceSquared(to) <= 9) {          // close enough: it is fighting
            army.stuck.put(npc, 0);
            return;
        }
        boolean moved = last != null && last.getWorld().equals(here.getWorld())
                && last.distanceSquared(here) > 0.09;
        if (moved) {
            army.stuck.put(npc, 0);
            return;
        }
        int stuckFor = army.stuck.merge(npc, 1, Integer::sum);
        if (stuckFor < 2) return;                     // stuck for about 1 second
        army.stuck.put(npc, 0);

        clearPath(owner, army, npc, here, to);
    }

    // stuck NPC: dig through, bridge, pillar up or dig down
    private void clearPath(Player owner, Army army, NPC npc, Location here, Location to) {
        boolean breakOn = getConfig().getBoolean("army.break-blocks", true);
        boolean placeOn = getConfig().getBoolean("army.place-blocks", true);

        double dx = to.getX() - here.getX();
        double dz = to.getZ() - here.getZ();
        double dy = to.getY() - here.getY();
        BlockFace face = Math.abs(dx) > Math.abs(dz)
                ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);

        Block feet = here.getBlock();
        Block front = feet.getRelative(face);
        Block frontHead = front.getRelative(BlockFace.UP);

        // 1) wall ahead: dig through
        if (!front.isPassable() || !frontHead.isPassable()) {
            if (breakOn) {
                boolean didHead = breakBlock(owner, frontHead);
                boolean didFeet = breakBlock(owner, front);
                if (didHead || didFeet) return;
            }
            if (dy >= 1.5 && placeOn) pillar(owner, army, npc, here, breakOn);
            return;
        }

        // 2) gap ahead: bridge
        Block groundFront = front.getRelative(BlockFace.DOWN);
        if (placeOn && dy > -2 && !groundFront.getType().isSolid()) {
            placeBlock(army, groundFront);
            return;
        }

        // 3) target is higher: pillar up
        if (dy >= 1.5 && placeOn) {
            pillar(owner, army, npc, here, breakOn);
            return;
        }

        // 4) target is far below: dig down
        if (dy <= -2.5 && breakOn) {
            breakBlock(owner, feet.getRelative(BlockFace.DOWN));
        }
    }

    private void pillar(Player owner, Army army, NPC npc, Location here, boolean breakOn) {
        Block feet = here.getBlock();
        Block above2 = feet.getRelative(BlockFace.UP, 2);
        if (!above2.isPassable()) {
            if (!breakOn || !breakBlock(owner, above2)) return;
        }
        if (placeBlock(army, feet)) {
            npc.teleport(here.clone().add(0, 1, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    private boolean breakBlock(Player owner, Block b) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid() || t.getHardness() < 0) return false;
        if (t == Material.SPAWNER || t == Material.BEACON) return false;
        if (b.getState(false) instanceof Container) return false;

        BlockBreakEvent ev = new BlockBreakEvent(b, owner); // lets protection plugins say no
        ev.setDropItems(false);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;

        Sound sound = b.getBlockData().getSoundGroup().getBreakSound();
        b.getWorld().playSound(b.getLocation(), sound, 1f, 0.8f);
        b.setType(Material.AIR);
        return true;
    }

    private boolean placeBlock(Army army, Block b) {
        if (army.placed.size() >= 300) return false;
        if (!(b.getType().isAir() || b.isLiquid())) return false;

        Material m = Material.matchMaterial(getConfig().getString("army.place-block", "COBBLESTONE"));
        if (m == null || !m.isBlock() || m.isAir()) m = Material.COBBLESTONE;

        b.setType(m);
        army.placedType = m;
        army.placed.add(b);
        Sound sound = b.getBlockData().getSoundGroup().getPlaceSound();
        b.getWorld().playSound(b.getLocation(), sound, 1f, 1f);
        return true;
    }

    private void dismiss(UUID ownerId, boolean sound) {
        Army army = armies.remove(ownerId);
        if (army == null) return;
        if (army.task != null) army.task.cancel();

        Location where = null;
        for (NPC npc : army.npcs) {
            if (where == null && npc.isSpawned()) where = npc.getEntity().getLocation();
            npc.destroy();
        }
        // remove blocks the army placed
        for (Block b : army.placed) {
            if (b.getType() == army.placedType) b.setType(Material.AIR);
        }
        if (sound && where != null) {
            where.getWorld().playSound(where, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dismiss(e.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        dismiss(e.getPlayer().getUniqueId(), false);
    }
            }
