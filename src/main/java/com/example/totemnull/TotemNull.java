package com.example.totemnull;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class TotemNull extends JavaPlugin implements Listener, CommandExecutor {

    private static final long TARGET_WINDOW_MS = 30_000; // a hit/attack counts as "target" for this long before summoning
    private static final long HORN_DEBOUNCE_MS = 700;
    private static final long MARK_LOCK_MS = 10_000;     // a manual mark beats automatic targeting for this long
    private static final long TICK = 5L;                 // how often the army "thinks" (5 ticks = 0.25 s)
    private static final double ARCHER_RANGE = 16;
    private static final long ARCHER_COOLDOWN_MS = 1_500;
    private static final double ARROW_DAMAGE = 4.0;
    private static final long PORTAL_OPEN_TICKS = 15;    // portal opens this long before the NULL steps out
    private static final long PORTAL_STAY_TICKS = 45;    // ...and stays open this long after
    private static final double PORTAL_MIN_DIST = 6;
    private static final double PORTAL_MAX_DIST = 14;
    private static final double MACE_LAUNCH_MIN = 14;    // elytra-mace NULL take off when the target is this far...
    private static final double MACE_LAUNCH_MAX = 60;    // ...and not farther than this
    private static final int MACE_FLIGHT_MAX_TICKS = 80;
    private static final int JOB_MAX_TICKS = 20 * 40;    // an order is dropped after 40 seconds
    private static final int BRIDGE_BLOCKS_PER_STEP = 6; // blocks placed every 2 ticks while bridging

    private enum Formation { RING, CHARGE, SCATTER }

    private enum Role { CAPTAIN, SWORD, AXE, ARCHER, MACE }

    private enum JobType { BURN, BLAST, BRIDGE }

    // a temporary nether portal used as an entrance
    private static class Portal {
        final List<BlockState> originals = new ArrayList<>();
        Location center;
        boolean closed = false;
    }

    // one elytra-mace dive
    private static class Flight {
        int phase = 0;        // 0 = climbing, 1 = diving
        int ticks = 0;
        int diveTicks = 0;
        double apexY;
    }

    // an order given with flint and steel / TNT / a wither skull
    private static class Job {
        final JobType type;
        final Location where;
        final List<NPC> workers = new ArrayList<>();
        int ticks = 0;
        boolean working = false;   // false = still walking over
        int placed = 0;            // fires lit / TNT placed
        int misses = 0;
        int tntLeft = 0;
        List<Block> path;          // bridge blocks, in building order
        int next = 0;

        Job(JobType type, Location where) {
            this.type = type;
            this.where = where;
        }
    }

    // area where fire does not hurt the commander or their NULL
    private static class BurnZone {
        final UUID owner;
        final Location center;
        final long until;

        BurnZone(UUID owner, Location center, long until) {
            this.owner = owner;
            this.center = center;
            this.until = until;
        }
    }

    private static class Army {
        final List<NPC> npcs = new ArrayList<>();
        final Map<NPC, Role> roles = new HashMap<>();
        final Map<UUID, NPC> byEntity = new HashMap<>();
        final Map<NPC, Location> lastPos = new HashMap<>();
        final Map<NPC, Location> lastDest = new HashMap<>();
        final Map<NPC, Integer> stuck = new HashMap<>();
        final Map<NPC, Integer> idle = new HashMap<>();          // checks in a row where it could do nothing about being stuck
        final Map<NPC, Long> climbUntil = new HashMap<>();       // stacking blocks upward: keep going without pausing
        final Map<NPC, Long> lastShot = new HashMap<>();
        final Map<NPC, Location> focus = new HashMap<>();   // block an NPC is working on
        final Map<NPC, Long> busyUntil = new HashMap<>();
        final Map<NPC, Flight> flights = new HashMap<>();   // elytra-mace NULL that are in the air
        final Map<NPC, Long> maceReady = new HashMap<>();   // when a mace NULL may take off again
        final Set<NPC> engaged = new HashSet<>();
        final Set<NPC> busy = new HashSet<>();              // NULL carrying out an order
        final List<Job> jobs = new ArrayList<>();
        final List<Block> placed = new ArrayList<>();
        final List<Portal> portals = new ArrayList<>();
        Material placedType = Material.COBBLESTONE;
        Formation formation = Formation.RING;
        Location rally;      // null = follow the owner
        boolean captain = true;
        boolean dismissed = false;
        int size = 20;       // total NULL (normal + mace)
        int baseCount = 15;  // NULL before the mace ones
        int pending = 0;     // NULL whose portal is open but who has not stepped out yet
        int replacements = 0;
        UUID targetId;       // null = no target
        BukkitTask task;
        BukkitTask spawnTask;
        BukkitTask lookTask;
        BukkitTask flightTask;
        BukkitTask jobTask;
        int ticks = 0;
    }

    private NamespacedKey totemKey;
    private NPCRegistry registry; // anonymous: NPCs are never saved to Citizens' data

    private final Map<UUID, Army> armies = new HashMap<>();
    private final Set<Portal> activePortals = new HashSet<>();
    private final List<BurnZone> burnZones = new ArrayList<>();
    private final Map<UUID, UUID> npcOwner = new HashMap<>();      // NULL entity -> who commands it
    private final Map<UUID, Formation> formations = new HashMap<>();
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, Long> lastTargetTime = new HashMap<>();
    private final Map<UUID, Long> markLock = new HashMap<>();
    private final Map<UUID, Long> lastMark = new HashMap<>();
    private final Map<UUID, Long> lastRally = new HashMap<>();
    private final Map<UUID, Long> lastOrder = new HashMap<>();
    private final Map<UUID, Long> lastHorn = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        totemKey = new NamespacedKey(this, "totem_of_null");
        registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("totemnull").setExecutor(this);
        getCommand("nullcall").setExecutor(this);
        getCommand("nullform").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(armies.keySet())) dismiss(id, false);
        for (Portal p : new ArrayList<>(activePortals)) closePortal(p);
        if (registry != null) registry.deregisterAll();
    }

    // ---------- commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // /nullcall: same as blowing the horn, works on Java and Bedrock
        if (cmd.getName().equalsIgnoreCase("nullcall")) {
            if (sender instanceof Player caller) {
                toggle(caller, true);
            } else {
                sender.sendMessage("Players only.");
            }
            return true;
        }

        // /nullform <ring|charge|scatter>
        if (cmd.getName().equalsIgnoreCase("nullform")) {
            if (sender instanceof Player fp) {
                Formation current = formations.getOrDefault(fp.getUniqueId(), configFormation());
                if (args.length == 0) {
                    fp.sendMessage(Component.text("Formation: " + current.name().toLowerCase()
                            + " (use ring, charge or scatter)", NamedTextColor.GRAY));
                    return true;
                }
                Formation f;
                try {
                    f = Formation.valueOf(args[0].toUpperCase());
                } catch (IllegalArgumentException ex) {
                    fp.sendMessage(Component.text("Use: ring, charge or scatter", NamedTextColor.RED));
                    return true;
                }
                formations.put(fp.getUniqueId(), f);
                Army army = armies.get(fp.getUniqueId());
                if (army != null) {
                    army.formation = f;
                    army.engaged.clear();
                    army.lastDest.clear();
                }
                fp.sendMessage(Component.text("NULL formation: " + f.name().toLowerCase(), NamedTextColor.DARK_GRAY));
            } else {
                sender.sendMessage("Players only.");
            }
            return true;
        }

        // /totemnull [player]: give the totem
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

    private Formation configFormation() {
        try {
            return Formation.valueOf(getConfig().getString("army.formation", "RING").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Formation.RING;
        }
    }

    private ItemStack createTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Totem of NULL", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Blow the Call horn or use /nullcall.", NamedTextColor.GRAY)
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

    // registry key of the horn's sound, e.g. "minecraft:call_goat_horn"
    private String instrumentKey(ItemStack item) {
        if (item != null && item.hasItemMeta()
                && item.getItemMeta() instanceof MusicInstrumentMeta mim && mim.getInstrument() != null) {
            NamespacedKey key = Registry.INSTRUMENT.getKey(mim.getInstrument());
            return key == null ? "unknown" : key.toString();
        }
        return "none";
    }

    // A goat horn counts if it is the "Call" horn OR renamed to "call"
    private boolean isCallHorn(ItemStack item) {
        if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) return false;
        if (instrumentKey(item).contains("call")) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasDisplayName()) {
            String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            return name.trim().equalsIgnoreCase("call");
        }
        return false;
    }

    // ---------- targets: whatever the holder hits, whatever hits the holder, or what they right-click ----------
    private boolean isNpc(Entity e) {
        return e.hasMetadata("NPC");
    }

    // who really did the damage: the shooter of an arrow, the NULL who lit a TNT
    private Entity shooterOf(Entity damager) {
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) return shooter;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() != null) return tnt.getSource();
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

        // friendly fire: NULL never hurt their commander, each other, or the commander's pets
        UUID armyOwner = npcOwner.get(attacker.getUniqueId());
        if (armyOwner != null) {
            boolean ally = victim.getUniqueId().equals(armyOwner)
                    || armyOwner.equals(npcOwner.get(victim.getUniqueId()))
                    || (victim instanceof Tameable pet && pet.getOwner() != null
                        && pet.getOwner().getUniqueId().equals(armyOwner));
            if (ally) {
                e.setCancelled(true);
                return;
            }
        }

        // holder attacks something
        if (attacker instanceof Player holder && !isNpc(holder) && hasTotem(holder) && canTarget(holder, victim)) {
            noteTarget(holder, victim, false);
        }
        // something attacks the holder
        if (victim instanceof Player defender && !isNpc(defender) && hasTotem(defender) && canTarget(defender, attacker)) {
            noteTarget(defender, attacker, false);
        }
    }

    // fire from the army's own burn orders never hurts the commander or their NULL
    @EventHandler(ignoreCancelled = true)
    public void onFireDamage(EntityDamageEvent e) {
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.FIRE && cause != EntityDamageEvent.DamageCause.FIRE_TICK) return;

        Entity entity = e.getEntity();
        UUID ownerId = npcOwner.get(entity.getUniqueId());
        if (ownerId == null && entity instanceof Player p && !isNpc(p)) ownerId = p.getUniqueId();
        if (ownerId == null) return;

        if (inBurnZone(ownerId, entity.getLocation())) {
            e.setCancelled(true);
            entity.setFireTicks(0);
        }
    }

    private boolean inBurnZone(UUID ownerId, Location l) {
        long now = System.currentTimeMillis();
        burnZones.removeIf(z -> z.until < now);
        for (BurnZone z : burnZones) {
            if (z.owner.equals(ownerId) && z.center.getWorld().equals(l.getWorld())
                    && z.center.distanceSquared(l) <= 100) return true;
        }
        return false;
    }

    // right-click something while holding the totem = mark it as the target
    @EventHandler
    public void onMark(PlayerInteractEntityEvent e) {
        Player owner = e.getPlayer();
        if (isNpc(owner)) return;
        ItemStack inHand = owner.getInventory().getItem(e.getHand());
        if (!isTotem(inHand)) return;

        Entity clicked = e.getRightClicked();
        if (!canTarget(owner, clicked)) return;

        e.setCancelled(true); // don't open trades etc.
        long now = System.currentTimeMillis();
        if (now - lastMark.getOrDefault(owner.getUniqueId(), 0L) < 300) return;
        lastMark.put(owner.getUniqueId(), now);

        noteTarget(owner, clicked, true);
        owner.sendActionBar(Component.text("NULL target: " + clicked.getName(), NamedTextColor.DARK_RED));
        owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.6f);
    }

    // flint and steel on a mob/player while NULL are out: set it on fire and send the army after it
    @EventHandler
    public void onBurnEntity(PlayerInteractEntityEvent e) {
        Player owner = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND || owner.isSneaking() || isNpc(owner)) return;
        if (owner.getInventory().getItemInMainHand().getType() != Material.FLINT_AND_STEEL) return;

        Army army = armies.get(owner.getUniqueId());
        if (army == null || !hasTotem(owner)) return;

        Entity clicked = e.getRightClicked();
        if (!canTarget(owner, clicked)) return;

        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - lastMark.getOrDefault(owner.getUniqueId(), 0L) < 300) return;
        lastMark.put(owner.getUniqueId(), now);

        clicked.setFireTicks(200);
        noteTarget(owner, clicked, true);
        clicked.getWorld().playSound(clicked.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1f);
        owner.sendActionBar(Component.text("NULL: burn " + clicked.getName(), NamedTextColor.GOLD));
    }

    // sneak + right-click a block with the totem = rally point, sneak + right-click the air = follow me again
    @EventHandler
    public void onRally(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Player owner = e.getPlayer();
        if (!owner.isSneaking() || isNpc(owner) || !isTotem(e.getItem())) return;

        long now = System.currentTimeMillis();
        if (now - lastRally.getOrDefault(owner.getUniqueId(), 0L) < 400) return;
        lastRally.put(owner.getUniqueId(), now);

        Army army = armies.get(owner.getUniqueId());
        if (army == null) {
            owner.sendActionBar(Component.text("Blow the Call horn first.", NamedTextColor.RED));
            return;
        }
        e.setCancelled(true);

        if (action == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Location spot = e.getClickedBlock().getLocation().add(0.5, 1, 0.5);
            army.rally = spot;
            spot.getWorld().spawnParticle(Particle.PORTAL, spot.clone().add(0, 0.6, 0), 50, 0.5, 0.6, 0.5, 0.3);
            spot.getWorld().playSound(spot, Sound.BLOCK_PORTAL_TRIGGER, 0.4f, 1.6f);
            owner.sendActionBar(Component.text("NULL rally point set.", NamedTextColor.DARK_GRAY));
        } else {
            army.rally = null;
            owner.sendActionBar(Component.text("NULL follow you again.", NamedTextColor.DARK_GRAY));
        }

        army.lastDest.clear();
        LivingEntity t = liveTarget(owner, army.targetId);
        for (NPC npc : army.npcs) {
            if (npc.isSpawned() && !army.flights.containsKey(npc)) steerOne(owner, army, npc, t);
        }
    }

    // flint and steel / TNT / wither skull + right-click = burn / blow up / bridge
    @EventHandler
    public void onOrder(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null) return;

        JobType type;
        Material m = item.getType();
        if (m == Material.FLINT_AND_STEEL) {
            type = JobType.BURN;
        } else if (m == Material.TNT) {
            type = JobType.BLAST;
        } else if (m == Material.WITHER_SKELETON_SKULL) {
            type = JobType.BRIDGE;
        } else {
            return;
        }

        Action action = e.getAction();
        boolean onBlock = action == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null;
        boolean inAir = action == Action.RIGHT_CLICK_AIR;
        if (!onBlock && !(inAir && type == JobType.BRIDGE)) return;

        Player owner = e.getPlayer();
        if (owner.isSneaking() || isNpc(owner)) return;      // sneaking = normal use of the item
        Army army = armies.get(owner.getUniqueId());
        if (army == null || !hasTotem(owner)) return;         // only while commanding NULL

        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - lastOrder.getOrDefault(owner.getUniqueId(), 0L) < 500) return;
        lastOrder.put(owner.getUniqueId(), now);

        giveOrder(owner, army, type, onBlock ? e.getClickedBlock() : null);
    }

    private void noteTarget(Player owner, Entity target, boolean manual) {
        UUID oid = owner.getUniqueId();
        long now = System.currentTimeMillis();

        if (!manual && now < markLock.getOrDefault(oid, 0L)) {
            Army a = armies.get(oid);
            LivingEntity current = a != null ? liveTarget(owner, a.targetId) : recentTarget(owner);
            if (current != null) return;          // a manual mark is still active
        }
        if (manual) markLock.put(oid, now + MARK_LOCK_MS);

        lastTarget.put(oid, target.getUniqueId());
        lastTargetTime.put(oid, now);
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

    // ---------- horn / command: summon once, again = vanish ----------
    @EventHandler
    public void onHorn(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.GOAT_HORN) return;

        Player owner = e.getPlayer();
        boolean callHorn = isCallHorn(item);

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[debug] " + owner.getName() + " goat horn click: action=" + e.getAction()
                    + " hand=" + e.getHand() + " instrument=" + instrumentKey(item)
                    + " callHorn=" + callHorn + " totem=" + hasTotem(owner));
        }

        boolean rightClick = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean anyHorn = getConfig().getBoolean("army.any-horn", false);
        if (!rightClick || !(callHorn || anyHorn)) return;

        toggle(owner, false);
    }

    private void toggle(Player owner, boolean notify) {
        UUID id = owner.getUniqueId();
        if (!hasTotem(owner)) {
            if (notify) owner.sendActionBar(Component.text("You need the Totem of NULL.", NamedTextColor.RED));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHorn.getOrDefault(id, 0L) < HORN_DEBOUNCE_MS) return;
        lastHorn.put(id, now);

        if (armies.containsKey(id)) {
            dismiss(id, true);
        } else {
            summon(owner);
        }
    }

    // ---------- gear ----------
    private ItemStack enchanted(Material type, Object... pairs) {
        ItemStack item = new ItemStack(type);
        ItemMeta m = item.getItemMeta();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.addEnchant((Enchantment) pairs[i], (Integer) pairs[i + 1], true);
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack weaponFor(Role role) {
        return switch (role) {
            case CAPTAIN -> enchanted(Material.NETHERITE_SWORD,
                    Enchantment.SHARPNESS, 6, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                    Enchantment.FIRE_ASPECT, 2, Enchantment.LOOTING, 3, Enchantment.KNOCKBACK, 2,
                    Enchantment.SWEEPING_EDGE, 3);
            case AXE -> enchanted(Material.NETHERITE_AXE,
                    Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3,
                    Enchantment.MENDING, 1, Enchantment.FIRE_ASPECT, 2);
            case ARCHER -> enchanted(Material.BOW,
                    Enchantment.POWER, 5, Enchantment.PUNCH, 2, Enchantment.FLAME, 1,
                    Enchantment.INFINITY, 1, Enchantment.UNBREAKING, 3);
            default -> enchanted(Material.NETHERITE_SWORD,
                    Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                    Enchantment.FIRE_ASPECT, 2, Enchantment.LOOTING, 3, Enchantment.KNOCKBACK, 2,
                    Enchantment.SWEEPING_EDGE, 3);
        };
    }

    private ItemStack maceItem() {
        return enchanted(Material.MACE,
                Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 3, Enchantment.FIRE_ASPECT, 2,
                Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
    }

    private ItemStack elytraItem() {
        return enchanted(Material.ELYTRA, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
    }

    private ItemStack chestplate() {
        return enchanted(Material.NETHERITE_CHESTPLATE,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3);
    }

    private void equipArmor(Equipment eq) {
        eq.set(Equipment.EquipmentSlot.HELMET, enchanted(Material.NETHERITE_HELMET,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, chestplate());
        eq.set(Equipment.EquipmentSlot.LEGGINGS, enchanted(Material.NETHERITE_LEGGINGS,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.SWIFT_SNEAK, 3));
        eq.set(Equipment.EquipmentSlot.BOOTS, enchanted(Material.NETHERITE_BOOTS,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.FEATHER_FALLING, 4, Enchantment.DEPTH_STRIDER, 3,
                Enchantment.SOUL_SPEED, 3));
    }

    // elytra-mace style = elytra + mace, normal style = netherite chestplate + sword
    private void equipStyle(NPC npc, boolean elytraMace) {
        if (!npc.isSpawned()) return;
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, elytraMace ? elytraItem() : chestplate());
        eq.set(Equipment.EquipmentSlot.HAND, elytraMace ? maceItem() : weaponFor(Role.SWORD));
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

    // ---------- portals ----------

    // true if the location is next to one of our temporary portals
    private boolean nearOurPortal(Location l) {
        if (l == null || l.getWorld() == null) return false;
        for (Portal p : activePortals) {
            if (p.center.getWorld().equals(l.getWorld()) && p.center.distanceSquared(l) <= 16) return true;
        }
        return false;
    }

    // nobody can be teleported through our portals, and they spawn no piglins
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (nearOurPortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (nearOurPortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler
    public void onPortalSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL && nearOurPortal(e.getLocation())) {
            e.setCancelled(true);
        }
    }

    private int findGround(World w, int x, int startY, int z) {
        for (int y = startY + 3; y >= startY - 4; y--) {
            Block below = w.getBlockAt(x, y - 1, z);
            Block at = w.getBlockAt(x, y, z);
            if (below.getType().isSolid() && !below.isLiquid() && at.isPassable() && !at.isLiquid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    // asks protection plugins whether this player may build here
    private boolean allowedToBuild(Player owner, Block b) {
        BlockPlaceEvent ev = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN),
                new ItemStack(Material.OBSIDIAN), owner, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);
        return !ev.isCancelled() && ev.canBuild();
    }

    // builds a 4x5 portal (obsidian frame, 2x3 portal inside) at a random spot near the owner
    private Portal openPortal(Player owner) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location o = owner.getLocation();
        World w = o.getWorld();

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = r.nextDouble(Math.PI * 2);
            double dist = r.nextDouble(PORTAL_MIN_DIST, PORTAL_MAX_DIST);
            int bx = o.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int bz = o.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            boolean axisX = r.nextBoolean();
            int dx = axisX ? 1 : 0;
            int dz = axisX ? 0 : 1;

            if (!w.isChunkLoaded(bx >> 4, bz >> 4) || !w.isChunkLoaded((bx + 3 * dx) >> 4, (bz + 3 * dz) >> 4)) continue;
            int by = findGround(w, bx, o.getBlockY(), bz);
            if (by == Integer.MIN_VALUE) continue;
            if (by < w.getMinHeight() + 1 || by + 5 >= w.getMaxHeight()) continue;

            Portal p = tryBuild(owner, w, bx, by, bz, axisX);
            if (p != null) return p;
        }
        return null;
    }

    private Portal tryBuild(Player owner, World w, int bx, int by, int bz, boolean axisX) {
        int dx = axisX ? 1 : 0;
        int dz = axisX ? 0 : 1;

        // everything the portal would replace must be empty, and the ground under it solid
        for (int wd = 0; wd < 4; wd++) {
            Block ground = w.getBlockAt(bx + dx * wd, by - 1, bz + dz * wd);
            if (!ground.getType().isSolid() || ground.isLiquid()) return null;
            for (int h = 0; h < 5; h++) {
                Block b = w.getBlockAt(bx + dx * wd, by + h, bz + dz * wd);
                if (!(b.getType().isAir() || (b.isPassable() && !b.isLiquid()))) return null;
            }
        }
        if (!allowedToBuild(owner, w.getBlockAt(bx, by, bz))) return null; // protection plugins can say no

        Portal p = new Portal();
        BlockData portalData = Material.NETHER_PORTAL.createBlockData();
        if (portalData instanceof Orientable orientable) {
            orientable.setAxis(axisX ? Axis.X : Axis.Z);
        }
        for (int wd = 0; wd < 4; wd++) {
            for (int h = 0; h < 5; h++) {
                Block b = w.getBlockAt(bx + dx * wd, by + h, bz + dz * wd);
                p.originals.add(b.getState());
                boolean frame = wd == 0 || wd == 3 || h == 0 || h == 4;
                if (frame) {
                    b.setType(Material.OBSIDIAN, false);
                } else {
                    b.setBlockData(portalData, false);
                }
            }
        }
        // the NULL steps out at the bottom middle of the portal
        p.center = new Location(w, bx + (axisX ? 2.0 : 0.5), by + 1, bz + (axisX ? 0.5 : 2.0));
        return p;
    }

    private void closePortal(Portal p) {
        if (p.closed) return;
        p.closed = true;
        activePortals.remove(p);
        for (BlockState s : p.originals) s.update(true, false); // put the old blocks back
        p.center.getWorld().playSound(p.center, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 0.8f);
    }

    // ---------- army ----------

    // distance at which a melee NULL stops walking in formation and charges the target
    private double engageDist(Army army) {
        return switch (army.formation) {
            case CHARGE -> 400;
            case SCATTER -> 12;
            default -> 9;
        };
    }

    // where NULL number <index> stands around a centre (the owner, the rally point, or the target)
    private Location slot(Army army, Location center, int index, boolean atTarget) {
        double x;
        double z;
        if (army.captain && index == 0) {
            x = 1.5;
            z = 1.5;                                  // the captain stays close to the centre
        } else {
            int i = army.captain ? index - 1 : index;
            int n = Math.max(1, army.captain ? army.size - 1 : army.size);
            double angle;
            double radius;
            if (army.formation == Formation.CHARGE) {
                angle = i * 2.399963;
                radius = 1.5 + 0.6 * Math.sqrt(i);    // tight pack
            } else if (army.formation == Formation.SCATTER) {
                angle = i * 2.399963;
                radius = 6 + 1.2 * Math.sqrt(i);      // wide and spread out
            } else {
                angle = 2 * Math.PI * i / n;
                radius = atTarget ? 6 : 4.5;          // even ring
            }
            x = Math.cos(angle) * radius;
            z = Math.sin(angle) * radius;
        }
        Location l = center.clone().add(x, 0, z);
        if (!l.getBlock().isPassable()) return center.clone(); // spot is inside a wall: use the centre
        return l;
    }

    private Role roleFor(Army army, int index) {
        if (index >= army.baseCount) return Role.MACE;          // the extra elytra-mace NULL
        if (army.captain && index == 0) return Role.CAPTAIN;
        if (!getConfig().getBoolean("army.mixed-weapons", true)) return Role.SWORD;
        return switch (index % 3) {
            case 1 -> Role.ARCHER;
            case 2 -> Role.AXE;
            default -> Role.SWORD;
        };
    }

    private void summon(Player owner) {
        Army army = new Army();
        final int base = Math.max(1, Math.min(60, getConfig().getInt("army.size", 15)));
        final int maces = Math.max(0, Math.min(20, getConfig().getInt("army.mace-count", 5)));
        final int total = base + maces;
        final long interval = Math.max(1L, getConfig().getLong("army.spawn-interval-ticks", 6L));
        army.baseCount = base;
        army.size = total;
        army.captain = getConfig().getBoolean("army.captain", true);
        army.formation = formations.getOrDefault(owner.getUniqueId(), configFormation());

        LivingEntity first = recentTarget(owner);
        army.targetId = first == null ? null : first.getUniqueId();
        armies.put(owner.getUniqueId(), army);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);

        // one entrance after another
        army.spawnTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!owner.isOnline() || owner.isDead() || army.npcs.size() + army.pending >= total) {
                army.spawnTask.cancel();
                return;
            }
            enter(owner, army, -1, true);
        }, 0L, interval);

        army.task = Bukkit.getScheduler().runTaskTimer(this, () -> tickArmy(owner, army), TICK, TICK);
        army.lookTask = Bukkit.getScheduler().runTaskTimer(this, () -> lookTick(owner, army), 2L, 2L);
        army.flightTask = Bukkit.getScheduler().runTaskTimer(this, () -> flightTick(owner, army), 1L, 1L);
        army.jobTask = Bukkit.getScheduler().runTaskTimer(this, () -> jobTick(owner, army), 2L, 2L);
    }

    // opens a portal, then a moment later the NULL steps out of it
    // forcedIndex >= 0 means "replace the NULL in that slot", -1 means "a new one"
    private void enter(Player owner, Army army, int forcedIndex, boolean initial) {
        if (initial) army.pending++;

        final Portal portal = getConfig().getBoolean("army.portals", true) ? openPortal(owner) : null;
        if (portal != null) {
            army.portals.add(portal);
            activePortals.add(portal);
            World w = portal.center.getWorld();
            w.playSound(portal.center, Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.4f);
            w.spawnParticle(Particle.PORTAL, portal.center.clone().add(0, 1, 0), 60, 0.6, 1.0, 0.6, 0.4);
        }

        long delay = portal != null ? PORTAL_OPEN_TICKS : 1L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (initial) army.pending--;
            if (army.dismissed || !owner.isOnline() || owner.isDead()) return;
            spawnOne(owner, army, portal == null ? null : portal.center, forcedIndex);
        }, delay);

        if (portal != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> closePortal(portal), delay + PORTAL_STAY_TICKS);
        }
    }

    private void spawnOne(Player owner, Army army, Location at, int forcedIndex) {
        int index = forcedIndex >= 0 ? forcedIndex : army.npcs.size();
        Role role = roleFor(army, index);

        String name = gibberishName();
        if (role == Role.CAPTAIN) name = "\u00a7c" + name;   // red name for the captain

        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(false); // can be killed

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HAND, weaponFor(role));
        if (getConfig().getBoolean("army.armor", true) || role == Role.MACE) equipArmor(eq);

        // looks at nearby players like a real player would
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        look.setRange(10);
        look.setRealisticLooking(true);

        applySkin(npc);
        npc.getNavigator().getDefaultParameters().range(100f).speedModifier(1.2f);

        Location center = army.rally != null ? army.rally : owner.getLocation();
        Location spawnAt = at != null ? at.clone() : slot(army, center, index, false);
        npc.spawn(spawnAt);

        if (npc.getEntity() instanceof LivingEntity le) {
            boolean cap = role == Role.CAPTAIN;
            if (getConfig().getBoolean("army.effects", true)) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, cap ? 2 : 1, false, false));
                le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, cap ? 1 : 0, false, false));
                if (cap) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
                }
            }
            if (cap) {
                AttributeInstance maxHealth = le.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(60);
                    le.setHealth(60);
                }
            }
            npcOwner.put(le.getUniqueId(), owner.getUniqueId());
            army.byEntity.put(le.getUniqueId(), npc);
        }

        if (forcedIndex >= 0 && forcedIndex < army.npcs.size()) {
            NPC old = army.npcs.get(forcedIndex);   // the killed one: swap it out
            army.roles.remove(old);
            army.busy.remove(old);
            old.destroy();
            army.npcs.set(forcedIndex, npc);
        } else {
            army.npcs.add(npc);
        }
        army.roles.put(npc, role);

        spawnAt.getWorld().playSound(spawnAt, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
        if (at != null) {
            spawnAt.getWorld().spawnParticle(Particle.PORTAL, spawnAt.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.3);
        }

        // start walking right away
        steerOne(owner, army, npc, liveTarget(owner, army.targetId));
    }

    // a NULL was killed: clean up and (if enabled) send a replacement through a new portal
    @EventHandler
    public void onNullDeath(EntityDeathEvent e) {
        UUID entityId = e.getEntity().getUniqueId();
        UUID ownerId = npcOwner.get(entityId);
        if (ownerId == null) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        npcOwner.remove(entityId);

        Army army = armies.get(ownerId);
        Player owner = Bukkit.getPlayer(ownerId);
        if (army == null || army.dismissed || owner == null) return;

        NPC dead = army.byEntity.remove(entityId);
        if (dead == null) return;
        int index = army.npcs.indexOf(dead);

        army.engaged.remove(dead);
        army.stuck.remove(dead);
        army.idle.remove(dead);
        army.climbUntil.remove(dead);
        army.lastPos.remove(dead);
        army.lastDest.remove(dead);
        army.focus.remove(dead);
        army.busyUntil.remove(dead);
        army.flights.remove(dead);
        army.maceReady.remove(dead);
        army.busy.remove(dead);

        if (index >= 0) queueReplacement(owner, army, index);
    }

    private void queueReplacement(Player owner, Army army, int index) {
        if (!getConfig().getBoolean("army.replace-killed", true)) return;
        int max = getConfig().getInt("army.max-replacements", 30);
        if (max > 0 && army.replacements >= max) return;
        army.replacements++;

        long delay = Math.max(20L, getConfig().getLong("army.replace-delay-ticks", 80L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (army.dismissed || !owner.isOnline() || owner.isDead()) return;
            enter(owner, army, index, false);
        }, delay);
    }

    // new target (or null = no target: follow the owner / hold the rally point)
    private void command(Player owner, Army army, LivingEntity target) {
        army.targetId = target == null ? null : target.getUniqueId();
        army.engaged.clear();
        army.lastDest.clear();
        for (NPC npc : army.npcs) {
            if (npc.isSpawned() && !army.flights.containsKey(npc)) steerOne(owner, army, npc, target);
        }
    }

    // runs every 0.25s
    private void tickArmy(Player owner, Army army) {
        if (!owner.isOnline() || owner.isDead()) {
            dismiss(owner.getUniqueId(), false);
            return;
        }
        army.ticks += (int) TICK;

        if (army.rally != null && !army.rally.getWorld().equals(owner.getWorld())) army.rally = null;
        if (army.rally != null && army.ticks % 20 == 0) {
            army.rally.getWorld().spawnParticle(Particle.PORTAL, army.rally.clone().add(0, 0.6, 0), 10, 0.4, 0.4, 0.4, 0.2);
        }

        LivingEntity target = liveTarget(owner, army.targetId);
        if (army.targetId != null && target == null) {
            command(owner, army, null);                 // target died / left
        }

        long now = System.currentTimeMillis();
        boolean breakOn = getConfig().getBoolean("army.break-blocks", true);

        for (NPC npc : army.npcs) {
            if (!npc.isSpawned()) continue;
            if (army.flights.containsKey(npc)) continue;   // in the air: flightTick drives it
            if (army.busy.contains(npc)) continue;         // carrying out an order: jobTick drives it

            Location here = npc.getEntity().getLocation();
            if (target != null && army.roles.get(npc) == Role.MACE
                    && maybeLaunch(owner, army, npc, here, target)) {
                continue;
            }

            // still stacking blocks up to a target above: keep going, no pause between blocks
            Long climbing = army.climbUntil.get(npc);
            if (target != null && climbing != null && now < climbing
                    && shouldClimb(here, target) && pillar(owner, army, npc, here, breakOn)) {
                continue;
            }

            steerOne(owner, army, npc, target);
            if (target != null) {
                checkStuck(owner, army, npc, here, target.getLocation(), target, 9);
            } else if (army.rally != null) {
                int idx = Math.max(0, army.npcs.indexOf(npc));
                checkStuck(owner, army, npc, here, slot(army, army.rally, idx, false), null, 4);
            } else {
                army.stuck.remove(npc);
                army.idle.remove(npc);
                army.lastPos.remove(npc);
            }
        }
    }

    // runs every 0.1s: keeps heads pointed at what the NULL is working on or fighting
    private void lookTick(Player owner, Army army) {
        if (army.dismissed) return;
        long now = System.currentTimeMillis();
        LivingEntity target = liveTarget(owner, army.targetId);

        for (NPC npc : army.npcs) {
            if (!npc.isSpawned() || army.flights.containsKey(npc) || army.busy.contains(npc)) continue;

            Location focus = army.focus.get(npc);
            Long until = army.busyUntil.get(npc);
            if (focus != null && until != null && now < until) {
                npc.faceLocation(focus);                          // looking at the block it is mining/placing
                continue;
            }
            if (target != null && army.engaged.contains(npc) && army.roles.get(npc) != Role.ARCHER) {
                Location here = npc.getEntity().getLocation();
                if (here.getWorld().equals(target.getWorld())
                        && here.distanceSquared(target.getLocation()) <= 64) {
                    npc.faceLocation(target.getEyeLocation());    // eyes on the enemy while fighting
                }
            }
        }
    }

    // ---------- orders: burn / blow up / bridge ----------

    private double distSq(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) return Double.MAX_VALUE;
        return a.distanceSquared(b);
    }

    // the free NULL closest to a spot
    private List<NPC> pickWorkers(Army army, Location where, int count) {
        List<NPC> pool = new ArrayList<>();
        for (NPC n : army.npcs) {
            if (n.isSpawned() && !army.busy.contains(n) && !army.flights.containsKey(n)) pool.add(n);
        }
        pool.sort(Comparator.comparingDouble(n -> distSq(n.getEntity().getLocation(), where)));
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private void giveOrder(Player owner, Army army, JobType type, Block clicked) {
        if (army.jobs.size() >= 3) {
            owner.sendActionBar(Component.text("NULL are busy.", NamedTextColor.RED));
            return;
        }

        if (type == JobType.BURN) {
            Location where = clicked.getLocation().add(0.5, 1.0, 0.5);
            List<NPC> workers = pickWorkers(army, where, Math.max(1, getConfig().getInt("army.burn-workers", 4)));
            if (workers.isEmpty()) {
                owner.sendActionBar(Component.text("No free NULL.", NamedTextColor.RED));
                return;
            }
            Job job = new Job(JobType.BURN, where);
            job.workers.addAll(workers);
            army.busy.addAll(workers);
            army.jobs.add(job);
            burnZones.add(new BurnZone(owner.getUniqueId(), where.clone(), System.currentTimeMillis() + 45_000));
            owner.sendActionBar(Component.text("NULL: burn it.", NamedTextColor.GOLD));
            return;
        }

        if (type == JobType.BLAST) {
            Block above = clicked.getRelative(BlockFace.UP);
            if (!allowedToBuild(owner, above)) {
                owner.sendActionBar(Component.text("You can't blow that up here.", NamedTextColor.RED));
                return;
            }
            Location where = clicked.getLocation().add(0.5, 1.0, 0.5);
            List<NPC> workers = pickWorkers(army, where, Math.max(1, getConfig().getInt("army.tnt-workers", 3)));
            if (workers.isEmpty()) {
                owner.sendActionBar(Component.text("No free NULL.", NamedTextColor.RED));
                return;
            }
            if (getConfig().getBoolean("army.consume-tnt", true) && owner.getGameMode() != GameMode.CREATIVE) {
                ItemStack inHand = owner.getInventory().getItemInMainHand();
                inHand.setAmount(inHand.getAmount() - 1);
                owner.getInventory().setItemInMainHand(inHand.getAmount() > 0 ? inHand : new ItemStack(Material.AIR));
            }
            Job job = new Job(JobType.BLAST, where);
            job.workers.addAll(workers);
            job.tntLeft = workers.size() * Math.max(1, getConfig().getInt("army.tnt-per-npc", 2));
            army.busy.addAll(workers);
            army.jobs.add(job);
            owner.sendActionBar(Component.text("NULL: blow it up.", NamedTextColor.DARK_RED));
            return;
        }

        // bridge
        List<Block> path = clicked != null ? bridgePathTo(owner, clicked) : bridgePathForward(owner);
        if (path == null || path.isEmpty()) {
            owner.sendActionBar(Component.text("NULL: nothing to bridge there.", NamedTextColor.RED));
            return;
        }
        Location head = owner.getLocation();
        List<NPC> workers = pickWorkers(army, head, Math.max(1, getConfig().getInt("army.bridge-workers", 3)));
        if (workers.isEmpty()) {
            owner.sendActionBar(Component.text("No free NULL.", NamedTextColor.RED));
            return;
        }
        Job job = new Job(JobType.BRIDGE, head.clone());
        job.path = path;
        job.working = true;
        job.workers.addAll(workers);
        army.busy.addAll(workers);
        army.jobs.add(job);
        owner.sendActionBar(Component.text("NULL: building a bridge.", NamedTextColor.GRAY));
    }

    // plus-shaped column of bridge blocks, 3 wide, so the army can walk over it
    private void addBridgeColumn(LinkedHashSet<Block> set, World w, int x, int y, int z) {
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offsets) {
            Block b = w.getBlockAt(x + o[0], y, z + o[1]);
            if (b.getType().isSolid()) continue;       // already something to walk on
            set.add(b);
        }
    }

    // bridge from where the owner stands to the block they clicked
    private List<Block> bridgePathTo(Player owner, Block dest) {
        World w = dest.getWorld();
        Location o = owner.getLocation();
        if (!o.getWorld().equals(w)) return null;

        int sx = o.getBlockX();
        int sy = o.getBlockY() - 1;                      // the block the owner stands on
        int sz = o.getBlockZ();
        int dx = dest.getX() - sx;
        int dz = dest.getZ() - sz;
        int dy = dest.getY() - sy;
        int len = Math.max(Math.abs(dx), Math.abs(dz));
        int max = getConfig().getInt("army.bridge-max-length", 40);
        if (len < 2 || len > max || Math.abs(dy) > len) return null;

        LinkedHashSet<Block> set = new LinkedHashSet<>();
        for (int i = 1; i < len; i++) {
            double t = (double) i / len;
            addBridgeColumn(set, w, (int) Math.round(sx + dx * t), (int) Math.round(sy + dy * t),
                    (int) Math.round(sz + dz * t));
        }
        return new ArrayList<>(set);
    }

    // bridge straight ahead until the next solid ground (or a default length)
    private List<Block> bridgePathForward(Player owner) {
        World w = owner.getWorld();
        Location o = owner.getLocation();
        BlockFace face = owner.getFacing();
        int sx = o.getBlockX();
        int sy = o.getBlockY() - 1;
        int sz = o.getBlockZ();
        int max = getConfig().getInt("army.bridge-max-length", 40);

        int end = -1;
        boolean gap = false;
        for (int i = 1; i <= max; i++) {
            Block g = w.getBlockAt(sx + face.getModX() * i, sy, sz + face.getModZ() * i);
            if (!g.getType().isSolid()) {
                gap = true;
            } else if (gap) {
                end = i;
                break;
            }
        }
        int len = end > 0 ? end - 1 : Math.min(max, 16);

        LinkedHashSet<Block> set = new LinkedHashSet<>();
        for (int i = 1; i <= len; i++) {
            addBridgeColumn(set, w, sx + face.getModX() * i, sy, sz + face.getModZ() * i);
        }
        return new ArrayList<>(set);
    }

    private Material bridgeMaterial() {
        Material m = Material.matchMaterial(getConfig().getString("army.bridge-block", "COBBLESTONE"));
        return (m == null || !m.isBlock() || m.isAir()) ? Material.COBBLESTONE : m;
    }

    // runs every 0.1s
    private void jobTick(Player owner, Army army) {
        if (army.dismissed || army.jobs.isEmpty()) return;

        for (Job job : new ArrayList<>(army.jobs)) {
            job.ticks += 2;
            job.workers.removeIf(n -> !n.isSpawned());
            if (job.workers.isEmpty() || job.ticks > JOB_MAX_TICKS) {
                finishJob(army, job);
                continue;
            }

            boolean done;
            switch (job.type) {
                case BURN -> done = burnTick(owner, army, job);
                case BLAST -> done = blastTick(army, job);
                default -> done = bridgeTick(owner, job);
            }
            if (done) finishJob(army, job);
        }
    }

    private void finishJob(Army army, Job job) {
        army.jobs.remove(job);
        army.busy.removeAll(job.workers);
        for (NPC n : job.workers) {
            army.lastDest.remove(n);
            army.lastPos.remove(n);
            army.stuck.remove(n);
            army.idle.remove(n);
        }
    }

    // walks the workers to the job site (true = everyone is there)
    private boolean approach(Army army, Job job, double reach) {
        boolean all = true;
        for (NPC npc : job.workers) {
            Location here = npc.getEntity().getLocation();
            Location spot = slot(army, job.where, Math.max(0, army.npcs.indexOf(npc)), true);
            if (!here.getWorld().equals(job.where.getWorld())) {
                npc.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
                all = false;
                continue;
            }
            if (here.distanceSquared(job.where) > reach * reach) {
                all = false;
                if (job.ticks > 200) {
                    npc.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);   // taking too long: pop over
                } else {
                    walkTo(army, npc, npc.getNavigator(), here, spot);
                }
            }
        }
        return all;
    }

    private void swingAt(NPC npc, Location point) {
        if (!npc.isSpawned()) return;
        npc.faceLocation(point);
        if (npc.getEntity() instanceof LivingEntity le) le.swingMainHand();
    }

    // ---- burn ----
    private boolean burnTick(Player owner, Army army, Job job) {
        if (!job.working) {
            if (approach(army, job, 9)) job.working = true;
            return false;
        }
        if (job.ticks % 6 != 0) return false;

        int maxFires = getConfig().getInt("army.burn-max-fires", 40);
        if (job.placed >= maxFires || job.misses >= 12) return true;

        for (NPC npc : job.workers) {
            Block spot = findFireSpot(job.where);
            if (spot == null) {
                job.misses++;
                continue;
            }
            BlockIgniteEvent ev = new BlockIgniteEvent(spot, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, owner);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) {
                job.misses++;
                continue;
            }
            spot.setType(Material.FIRE);
            job.placed++;
            job.misses = 0;
            spot.getWorld().playSound(spot.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.8f, 1f);
            swingAt(npc, spot.getLocation().add(0.5, 0.5, 0.5));
        }
        return false;
    }

    // an empty block next to something that burns (or, failing that, on the ground)
    private Block findFireSpot(Location where) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = where.getWorld();
        BlockFace[] faces = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};

        for (int i = 0; i < 24; i++) {
            Block b = w.getBlockAt(where.getBlockX() + r.nextInt(-4, 5),
                    where.getBlockY() + r.nextInt(-2, 4), where.getBlockZ() + r.nextInt(-4, 5));
            if (!b.getType().isAir()) continue;
            for (BlockFace f : faces) {
                if (b.getRelative(f).getType().isFlammable()) return b;
            }
        }
        for (int i = 0; i < 8; i++) {
            Block b = w.getBlockAt(where.getBlockX() + r.nextInt(-3, 4), where.getBlockY(),
                    where.getBlockZ() + r.nextInt(-3, 4));
            if (b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid()) return b;
        }
        return null;
    }

    // ---- blow up ----
    private boolean blastTick(Army army, Job job) {
        if (!job.working) {
            if (approach(army, job, 10)) job.working = true;
            return false;
        }
        if (job.tntLeft <= 0) return true;
        if (job.ticks % 6 != 0) return false;

        NPC npc = job.workers.get(job.placed % job.workers.size());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = job.where.clone().add(r.nextDouble(-0.8, 0.8), 0, r.nextDouble(-0.8, 0.8));
        float yield = (float) getConfig().getDouble("army.tnt-yield", 4.0);

        job.where.getWorld().spawn(at, TNTPrimed.class, t -> {
            t.setFuseTicks(60);
            t.setSource(npc.getEntity());
            t.setYield(yield);
        });
        job.where.getWorld().playSound(at, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
        swingAt(npc, at);

        job.placed++;
        job.tntLeft--;
        return false;
    }

    // ---- bridge ----
    private boolean bridgeTick(Player owner, Job job) {
        Material m = bridgeMaterial();
        Block head = null;
        boolean sounded = false;

        for (int k = 0; k < BRIDGE_BLOCKS_PER_STEP && job.next < job.path.size(); k++) {
            Block b = job.path.get(job.next++);
            if (!(b.getType().isAir() || b.isLiquid() || b.isPassable())) continue;
            if (!allowedToBuild(owner, b)) {
                job.misses++;
                continue;
            }
            b.setType(m);
            head = b;
            if (!sounded) {
                b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 1f);
                sounded = true;
            }
        }

        if (head != null) {
            int i = 0;
            for (NPC npc : job.workers) {
                // the builders stand on the end of the bridge as it grows
                npc.teleport(head.getLocation().add(0.5 + (i - 1) * 0.8, 1, 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (job.next < job.path.size()) {
                    swingAt(npc, job.path.get(job.next).getLocation().add(0.5, 1, 0.5));
                } else if (npc.getEntity() instanceof LivingEntity le) {
                    le.swingMainHand();
                }
                i++;
            }
        }
        return job.next >= job.path.size() || job.misses > 40;
    }

    // ---------- elytra-mace flight ----------

    // a far-away target: swap to elytra + mace and take off (true = it is now flying)
    private boolean maybeLaunch(Player owner, Army army, NPC npc, Location here, LivingEntity target) {
        if (!getConfig().getBoolean("army.mace-elytra", true)) return false;
        long now = System.currentTimeMillis();
        if (now < army.maceReady.getOrDefault(npc, 0L)) return false;
        if (!here.getWorld().equals(target.getWorld())) return false;

        double d = here.distance(target.getLocation());
        if (d < MACE_LAUNCH_MIN || d > MACE_LAUNCH_MAX) return false;

        // needs open sky above to take off
        for (int i = 1; i <= 6; i++) {
            if (!here.getBlock().getRelative(BlockFace.UP, i).isPassable()) {
                army.maceReady.put(npc, now + 3000);
                return false;
            }
        }

        npc.getNavigator().cancelNavigation();
        army.engaged.remove(npc);
        army.lastDest.remove(npc);
        army.stuck.remove(npc);
        army.idle.remove(npc);
        equipStyle(npc, true);

        Flight f = new Flight();
        f.apexY = here.getY();
        army.flights.put(npc, f);
        here.getWorld().playSound(here, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
        return true;
    }

    // runs every tick: moves every NULL that is in the air
    private void flightTick(Player owner, Army army) {
        if (army.dismissed || army.flights.isEmpty()) return;

        double diveHeight = getConfig().getDouble("army.mace-dive-height", 14.0);

        for (Map.Entry<NPC, Flight> entry : new ArrayList<>(army.flights.entrySet())) {
            NPC npc = entry.getKey();
            Flight f = entry.getValue();
            if (!npc.isSpawned()) {
                army.flights.remove(npc);
                continue;
            }

            LivingEntity target = liveTarget(owner, army.targetId);
            Entity ne = npc.getEntity();
            Location cur = ne.getLocation();
            f.ticks++;

            if (target == null || f.ticks > MACE_FLIGHT_MAX_TICKS) {
                endFlight(army, npc, null, false);
                continue;
            }

            Location tl = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            Vector move;

            if (f.phase == 0) {
                // climb, drifting toward the target
                if (cur.getY() >= tl.getY() + diveHeight || f.ticks > 25) f.phase = 1;
                Vector horiz = tl.toVector().subtract(cur.toVector());
                horiz.setY(0);
                if (horiz.lengthSquared() > 0.01) horiz.normalize().multiply(0.35);
                move = new Vector(horiz.getX(), 0.9, horiz.getZ());
            } else {
                Vector to = tl.toVector().subtract(cur.toVector());
                double dist = to.length();
                if (dist <= 1.8) {
                    smash(owner, army, npc, target, f);
                    continue;
                }
                double speed = Math.min(2.2, 0.9 + f.diveTicks * 0.12);
                f.diveTicks++;
                move = to.normalize().multiply(Math.min(speed, dist));
            }

            Location next = cur.clone().add(move);
            next.setDirection(move);

            // blocked by a wall or roof: crash-land without damage
            if (!next.getBlock().isPassable() || !next.clone().add(0, 1, 0).getBlock().isPassable()) {
                endFlight(army, npc, null, false);
                continue;
            }

            f.apexY = Math.max(f.apexY, next.getY());
            npc.teleport(next, PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (ne instanceof LivingEntity le) le.setGliding(true);
            ne.setFallDistance(0f);
            next.getWorld().spawnParticle(Particle.CLOUD, next, 2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    // the dive hit: mace smash damage grows with the height fallen
    private void smash(Player owner, Army army, NPC npc, LivingEntity target, Flight f) {
        Entity attacker = npc.getEntity();
        Location at = target.getLocation();

        double fall = Math.max(0, f.apexY - at.getY());
        double bonus = Math.min(fall, 3) * 4
                + Math.min(Math.max(fall - 3, 0), 5) * 2
                + Math.max(fall - 8, 0);
        double density = 0.5 * 5 * fall;                       // Density V
        double max = getConfig().getDouble("army.mace-max-damage", 40.0);
        double dmg = Math.min(max, 6 + bonus + density);

        target.damage(dmg, attacker);
        target.setVelocity(target.getVelocity().add(new Vector(0, 0.7, 0)));

        for (Entity near : at.getWorld().getNearbyEntities(at, 3.5, 3.5, 3.5)) {
            if (near.equals(target) || !canTarget(owner, near)) continue;
            ((LivingEntity) near).damage(dmg * 0.35, attacker);
            near.setVelocity(near.getVelocity().add(new Vector(0, 0.5, 0)));
        }

        World w = at.getWorld();
        w.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 1f);
        w.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.3, 0), 40, 1.0, 0.2, 1.0, 0.1);
        w.spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0.3);

        endFlight(army, npc, at, true);
    }

    private Location groundBelow(Location from) {
        Location l = from.clone();
        for (int i = 0; i < 12; i++) {
            if (l.getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) return l;
            l.subtract(0, 1, 0);
        }
        return from.clone();
    }

    // like groundBelow, but null if there is nothing solid to stand on within 12 blocks
    private Location solidGroundBelow(Location from) {
        Location l = from.clone();
        for (int i = 0; i < 12; i++) {
            if (l.getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) return l;
            l.subtract(0, 1, 0);
        }
        return null;
    }

    // landed (or crashed): back on the ground, switch back to the normal style
    private void endFlight(Army army, NPC npc, Location landAt, boolean smashed) {
        army.flights.remove(npc);
        if (!npc.isSpawned()) return;

        Entity ne = npc.getEntity();
        if (ne instanceof LivingEntity le) le.setGliding(false);
        ne.setFallDistance(0f);

        if (landAt != null) {
            Location land = groundBelow(landAt).add(1, 0, 0);
            if (!land.getBlock().isPassable()) land.subtract(1, 0, 0);
            npc.teleport(land, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        long cooldownMs = smashed
                ? (long) (getConfig().getDouble("army.mace-cooldown-seconds", 10.0) * 1000)
                : 4000L;
        army.maceReady.put(npc, System.currentTimeMillis() + cooldownMs);
        army.lastDest.remove(npc);
        army.engaged.remove(npc);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!army.dismissed && npc.isSpawned() && !army.flights.containsKey(npc)) equipStyle(npc, false);
        }, 8L);
    }

    // ---------- walking, fighting, building ----------

    // decides where one NPC should be heading
    private void steerOne(Player owner, Army army, NPC npc, LivingEntity target) {
        if (!npc.isSpawned() || army.busy.contains(npc)) return;
        Navigator nav = npc.getNavigator();
        Location here = npc.getEntity().getLocation();
        int index = Math.max(0, army.npcs.indexOf(npc));
        Role role = army.roles.getOrDefault(npc, Role.SWORD);

        if (target != null) {
            Location tl = target.getLocation();
            if (!here.getWorld().equals(tl.getWorld())) return;   // checkStuck() teleports it over
            double d = here.distance(tl);

            if (role == Role.ARCHER) {
                steerArcher(army, npc, nav, here, tl, d, target, index);
                return;
            }

            double engage = engageDist(army);
            boolean engaged = army.engaged.contains(npc);
            if (engaged && d > engage + 4) {
                army.engaged.remove(npc);
                army.lastDest.remove(npc);
                engaged = false;
            }
            if (!engaged && d <= engage) {
                army.engaged.add(npc);
                nav.setTarget(target, true);              // close enough: charge and attack
                return;
            }
            if (engaged) {
                if (!nav.isNavigating()) nav.setTarget(target, true);
                return;
            }
            walkTo(army, npc, nav, here, slot(army, tl, index, true)); // far away: walk in formation
            return;
        }

        // no target: hold the rally point, or follow the owner
        Location center = army.rally != null ? army.rally : owner.getLocation();
        if (!here.getWorld().equals(center.getWorld())
                || (army.rally == null && here.distanceSquared(center) > 40 * 40)) {
            npc.teleport(slot(army, center, index, false), PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
            return;
        }
        walkTo(army, npc, nav, here, slot(army, center, index, false));
    }

    // archers stop at range and shoot instead of running into melee
    private void steerArcher(Army army, NPC npc, Navigator nav, Location here, Location tl, double d,
                             LivingEntity target, int index) {
        if (npc.getEntity() instanceof LivingEntity me && d <= ARCHER_RANGE && me.hasLineOfSight(target)) {
            if (nav.isNavigating()) nav.cancelNavigation();
            army.lastDest.remove(npc);
            npc.faceLocation(target.getEyeLocation());
            shoot(army, npc, me, target);
            return;
        }
        if (d <= ARCHER_RANGE + 10) {
            walkTo(army, npc, nav, here, tl);                              // get a clear shot
        } else {
            walkTo(army, npc, nav, here, slot(army, tl, index, true));     // far away: walk in formation
        }
    }

    private void shoot(Army army, NPC npc, LivingEntity me, LivingEntity target) {
        long now = System.currentTimeMillis();
        if (now - army.lastShot.getOrDefault(npc, 0L) < ARCHER_COOLDOWN_MS) return;
        army.lastShot.put(npc, now);

        Location eye = me.getEyeLocation();
        Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.5) return;
        dir.normalize();
        dir.setY(dir.getY() + dist * 0.012);          // small arc compensation

        me.swingMainHand();
        Arrow arrow = me.getWorld().spawnArrow(eye, dir, 3.0f, 1.0f, Arrow.class);
        arrow.setShooter(me);
        arrow.setCritical(true);
        arrow.setDamage(ARROW_DAMAGE);
        arrow.setFireTicks(100);                      // flame
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        me.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
    }

    private void walkTo(Army army, NPC npc, Navigator nav, Location here, Location dest) {
        if (here.getWorld().equals(dest.getWorld()) && here.distanceSquared(dest) <= 4) return; // already there
        Location prev = army.lastDest.get(npc);
        boolean destMoved = prev == null || !prev.getWorld().equals(dest.getWorld())
                || prev.distanceSquared(dest) > 2.25;
        if (destMoved || !nav.isNavigating()) {
            army.lastDest.put(npc, dest.clone());
            nav.setTarget(dest);
        }
    }

    // should this NULL stack blocks upward to reach the target? only if it is standing, nearly overhead, and not too high
    private boolean shouldClimb(Location here, LivingEntity target) {
        if (!here.getWorld().equals(target.getWorld())) return false;
        Location t = target.getLocation();
        double dy = t.getY() - here.getY();
        double max = getConfig().getDouble("army.pillar-max-height", 30.0);
        if (dy < 1.5 || dy > max) return false;
        if (!target.isOnGround()) return false;            // a flying target can't be reached by stacking blocks
        double dx = t.getX() - here.getX();
        double dz = t.getZ() - here.getZ();
        return dx * dx + dz * dz <= 64;                    // only when it is nearly overhead
    }

    // is this NPC stuck on its way to <to>? if so, start digging / bridging / pillaring
    private void checkStuck(Player owner, Army army, NPC npc, Location here, Location to,
                            LivingEntity target, double nearSq) {
        if (!here.getWorld().equals(to.getWorld())) {
            Location c = army.rally != null ? army.rally : owner.getLocation();
            npc.teleport(slot(army, c, Math.max(0, army.npcs.indexOf(npc)), false),
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
            return;
        }

        Location last = army.lastPos.put(npc, here.clone());

        // an archer standing still and shooting is not stuck
        if (target != null && army.roles.get(npc) == Role.ARCHER && npc.getEntity() instanceof LivingEntity me
                && here.distanceSquared(to) <= ARCHER_RANGE * ARCHER_RANGE && me.hasLineOfSight(target)) {
            army.stuck.put(npc, 0);
            army.idle.put(npc, 0);
            return;
        }
        if (here.distanceSquared(to) <= nearSq) {     // close enough: fighting or arrived
            army.stuck.put(npc, 0);
            army.idle.put(npc, 0);
            return;
        }
        boolean moved = last != null && last.getWorld().equals(here.getWorld())
                && last.distanceSquared(here) > 0.04;
        if (moved) {
            army.stuck.put(npc, 0);
            army.idle.put(npc, 0);
            return;
        }
        int stuckFor = army.stuck.merge(npc, 1, Integer::sum);
        if (stuckFor < 2) return;                     // no movement for about half a second

        if (clearPath(owner, army, npc, here, to, target)) {   // dug, bridged or stacked something
            army.idle.put(npc, 0);
            return;
        }

        // nothing it could do about being stuck: after a few seconds, pop it next to the target
        int idleChecks = army.idle.merge(npc, 1, Integer::sum);
        double secs = getConfig().getDouble("army.stuck-teleport-seconds", 6.0);
        if (secs > 0 && idleChecks * TICK >= secs * 20) {
            rescue(army, npc, to);
            army.idle.put(npc, 0);
            army.stuck.put(npc, 0);
        }
    }

    // teleports a hopelessly stuck NULL next to where it was heading (if there is ground there)
    private void rescue(Army army, NPC npc, Location to) {
        Location ground = solidGroundBelow(to);
        if (ground == null) return;
        Location spot = ground.clone().add(1.5, 0, 0);
        if (!spot.getBlock().isPassable() || !spot.clone().add(0, 1, 0).getBlock().isPassable()) spot = ground;

        Location from = npc.getEntity().getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
        npc.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
        spot.getWorld().spawnParticle(Particle.PORTAL, spot.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
        army.lastDest.remove(npc);
        army.engaged.remove(npc);
    }

    // blocked NPC: dig a tunnel, bridge a gap, stack blocks up or dig down. true = it did something
    private boolean clearPath(Player owner, Army army, NPC npc, Location here, Location to, LivingEntity target) {
        boolean breakOn = getConfig().getBoolean("army.break-blocks", true);
        boolean placeOn = getConfig().getBoolean("army.place-blocks", true);

        double dx = to.getX() - here.getX();
        double dz = to.getZ() - here.getZ();
        double dy = to.getY() - here.getY();
        boolean xFirst = Math.abs(dx) > Math.abs(dz);
        BlockFace primary = xFirst ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                                   : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        BlockFace secondary = xFirst ? (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH)
                                     : (dx > 0 ? BlockFace.EAST : BlockFace.WEST);
        boolean useSecondary = Math.min(Math.abs(dx), Math.abs(dz)) >= 1.5;

        Block feet = here.getBlock();
        boolean canClimb = target != null
                ? shouldClimb(here, target)
                : (dy >= 1.5 && dy <= getConfig().getDouble("army.pillar-max-height", 30.0));

        // 1) wall ahead: dig a two-block-deep tunnel; if that side can't be dug, try the other direction
        BlockFace[] tries = useSecondary ? new BlockFace[]{primary, secondary} : new BlockFace[]{primary};
        boolean walled = false;
        for (BlockFace face : tries) {
            Block front = feet.getRelative(face);
            Block frontHead = front.getRelative(BlockFace.UP);
            Block front2 = front.getRelative(face);
            Block front2Head = front2.getRelative(BlockFace.UP);
            boolean wall = !front.isPassable() || !frontHead.isPassable()
                    || !front2.isPassable() || !front2Head.isPassable();
            if (!wall) continue;
            walled = true;
            if (breakOn && dig(owner, army, npc, front, frontHead, front2, front2Head)) return true;
        }
        if (walled) {
            // could not dig through: go up and over if the target is above
            return canClimb && placeOn && pillar(owner, army, npc, here, breakOn);
        }

        // 2) gap ahead: bridge two blocks at once
        Block front = feet.getRelative(primary);
        Block front2 = front.getRelative(primary);
        Block groundFront = front.getRelative(BlockFace.DOWN);
        if (placeOn && dy > -2 && !groundFront.getType().isSolid()) {
            boolean did = placeBlock(army, groundFront);
            Block groundFront2 = front2.getRelative(BlockFace.DOWN);
            if (!groundFront2.getType().isSolid()) did |= placeBlock(army, groundFront2);
            if (did) showWork(army, npc, groundFront);
            return did;
        }

        // 3) target is above and reachable by stacking blocks
        if (canClimb && placeOn) {
            return pillar(owner, army, npc, here, breakOn);
        }

        // 4) target is far below: dig down
        if (dy <= -2.5 && breakOn) {
            Block below = feet.getRelative(BlockFace.DOWN);
            if (breakBlock(owner, below, true)) {
                showWork(army, npc, below);
                return true;
            }
        }
        return false;
    }

    private boolean dig(Player owner, Army army, NPC npc, Block front, Block frontHead, Block front2, Block front2Head) {
        boolean did = false;
        boolean makeSound = true;
        for (Block b : new Block[]{frontHead, front, front2Head, front2}) {
            if (b.isPassable()) continue;
            if (breakBlock(owner, b, makeSound)) {
                did = true;
                makeSound = false;
            }
        }
        if (did) showWork(army, npc, front);
        return did;
    }

    // arm swing + eyes on the block it is working on
    private void showWork(Army army, NPC npc, Block focus) {
        Location f = focus.getLocation().add(0.5, 0.5, 0.5);
        army.focus.put(npc, f);
        army.busyUntil.put(npc, System.currentTimeMillis() + 700);
        if (npc.isSpawned()) {
            npc.faceLocation(f);
            if (npc.getEntity() instanceof LivingEntity le) le.swingMainHand();
        }
    }

    // places a block under the NULL and lifts it one block up (true = it went up)
    private boolean pillar(Player owner, Army army, NPC npc, Location here, boolean breakOn) {
        Block feet = here.getBlock();
        Block above2 = feet.getRelative(BlockFace.UP, 2);
        if (!above2.isPassable()) {
            if (!breakOn || !breakBlock(owner, above2, true)) return false;
        }
        if (!placeBlock(army, feet)) return false;

        npc.teleport(here.clone().add(0, 1, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        army.climbUntil.put(npc, System.currentTimeMillis() + 700);   // keep going on the next check
        showWork(army, npc, feet);
        return true;
    }

    private boolean breakBlock(Player owner, Block b, boolean makeSound) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid() || t.getHardness() < 0) return false;
        if (t == Material.SPAWNER || t == Material.BEACON) return false;
        if (b.getState(false) instanceof Container) return false;

        BlockBreakEvent ev = new BlockBreakEvent(b, owner); // lets protection plugins say no
        ev.setDropItems(false);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;

        if (makeSound) {
            Sound sound = b.getBlockData().getSoundGroup().getBreakSound();
            b.getWorld().playSound(b.getLocation(), sound, 1f, 0.8f);
        }
        b.setType(Material.AIR);
        return true;
    }

    private boolean placeBlock(Army army, Block b) {
        int cap = getConfig().getInt("army.max-placed-blocks", 1500);
        if (army.placed.size() >= cap) return false;

        Material cur = b.getType();
        boolean soft = b.isPassable() && !cur.isAir() && cur.getHardness() <= 0.2f; // grass, flowers, snow layers
        if (!(cur.isAir() || b.isLiquid() || soft)) return false;

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
        army.dismissed = true;
        if (army.task != null) army.task.cancel();
        if (army.spawnTask != null) army.spawnTask.cancel();
        if (army.lookTask != null) army.lookTask.cancel();
        if (army.flightTask != null) army.flightTask.cancel();
        if (army.jobTask != null) army.jobTask.cancel();

        Location where = null;
        for (NPC npc : army.npcs) {
            if (where == null && npc.isSpawned()) where = npc.getEntity().getLocation();
            npc.destroy();
        }
        npcOwner.values().removeIf(v -> v.equals(ownerId));

        // close any portals that are still open (puts the old blocks back)
        for (Portal p : army.portals) closePortal(p);

        // remove blocks the army placed while chasing (bridges you ordered stay)
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
