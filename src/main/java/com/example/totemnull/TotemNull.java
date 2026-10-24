package com.example.totemnull;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
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
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class TotemNull extends JavaPlugin implements Listener, CommandExecutor {

    private static final long TARGET_WINDOW_MS = 30_000; // a hit/attack counts as "target" for this long before summoning
    private static final long HORN_DEBOUNCE_MS = 700;
    private static final long MARK_LOCK_MS = 10_000;     // a manual mark beats automatic targeting for this long
    private static final double ARCHER_RANGE = 16;
    private static final long ARCHER_COOLDOWN_MS = 1_500;
    private static final double ARROW_DAMAGE = 4.0;
    private static final long PORTAL_OPEN_TICKS = 15;    // portal opens this long before the NULL steps out
    private static final long PORTAL_STAY_TICKS = 45;    // ...and stays open this long after
    private static final double PORTAL_MIN_DIST = 6;
    private static final double PORTAL_MAX_DIST = 14;

    private enum Formation { RING, CHARGE, SCATTER }

    private enum Role { CAPTAIN, SWORD, AXE, ARCHER }

    // a temporary nether portal used as an entrance
    private static class Portal {
        final List<BlockState> originals = new ArrayList<>();
        Location center;
        boolean closed = false;
    }

    private static class Army {
        final List<NPC> npcs = new ArrayList<>();
        final Map<NPC, Role> roles = new HashMap<>();
        final Map<NPC, Location> lastPos = new HashMap<>();
        final Map<NPC, Location> lastDest = new HashMap<>();
        final Map<NPC, Integer> stuck = new HashMap<>();
        final Map<NPC, Long> lastShot = new HashMap<>();
        final Set<NPC> engaged = new HashSet<>();
        final List<Block> placed = new ArrayList<>();
        final List<Portal> portals = new ArrayList<>();
        Material placedType = Material.COBBLESTONE;
        Formation formation = Formation.RING;
        boolean captain = true;
        boolean dismissed = false;
        int size = 15;
        int pending = 0;    // NULL whose portal is open but who has not stepped out yet
        UUID targetId;      // null = following owner
        BukkitTask task;
        BukkitTask spawnTask;
        int ticks = 0;
    }

    private NamespacedKey totemKey;
    private NPCRegistry registry; // anonymous: NPCs are never saved to Citizens' data

    private final Map<UUID, Army> armies = new HashMap<>();
    private final Set<Portal> activePortals = new HashSet<>();
    private final Map<UUID, UUID> npcOwner = new HashMap<>();      // NULL entity -> who commands it
    private final Map<UUID, Formation> formations = new HashMap<>();
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, Long> lastTargetTime = new HashMap<>();
    private final Map<UUID, Long> markLock = new HashMap<>();
    private final Map<UUID, Long> lastMark = new HashMap<>();
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

        // friendly fire: NULL never hurt their own commander or each other
        UUID armyOwner = npcOwner.get(attacker.getUniqueId());
        if (armyOwner != null) {
            if (victim.getUniqueId().equals(armyOwner) || armyOwner.equals(npcOwner.get(victim.getUniqueId()))) {
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

    private void equipArmor(Equipment eq) {
        eq.set(Equipment.EquipmentSlot.HELMET, enchanted(Material.NETHERITE_HELMET,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, enchanted(Material.NETHERITE_CHESTPLATE,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, enchanted(Material.NETHERITE_LEGGINGS,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.SWIFT_SNEAK, 3));
        eq.set(Equipment.EquipmentSlot.BOOTS, enchanted(Material.NETHERITE_BOOTS,
                Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.THORNS, 3, Enchantment.FEATHER_FALLING, 4, Enchantment.DEPTH_STRIDER, 3,
                Enchantment.SOUL_SPEED, 3));
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

    // where NULL number <index> stands around a centre (the owner, or the target)
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
        final int size = Math.max(1, Math.min(60, getConfig().getInt("army.size", 15)));
        final long interval = Math.max(1L, getConfig().getLong("army.spawn-interval-ticks", 6L));
        army.size = size;
        army.captain = getConfig().getBoolean("army.captain", true);
        army.formation = formations.getOrDefault(owner.getUniqueId(), configFormation());

        LivingEntity first = recentTarget(owner);
        army.targetId = first == null ? null : first.getUniqueId();
        armies.put(owner.getUniqueId(), army);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);

        // one entrance after another
        army.spawnTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!owner.isOnline() || owner.isDead() || army.npcs.size() + army.pending >= size) {
                army.spawnTask.cancel();
                return;
            }
            scheduleEntrance(owner, army);
        }, 0L, interval);

        army.task = Bukkit.getScheduler().runTaskTimer(this, () -> tickArmy(owner, army), 10L, 10L);
    }

    // opens a portal, then a moment later the NULL steps out of it
    private void scheduleEntrance(Player owner, Army army) {
        army.pending++;

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
            army.pending--;
            if (army.dismissed || !owner.isOnline() || owner.isDead()) return;
            spawnOne(owner, army, portal == null ? null : portal.center);
        }, delay);

        if (portal != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> closePortal(portal), delay + PORTAL_STAY_TICKS);
        }
    }

    private void spawnOne(Player owner, Army army, Location at) {
        int index = army.npcs.size();
        Role role = roleFor(army, index);

        String name = gibberishName();
        if (role == Role.CAPTAIN) name = "\u00a7c" + name;   // red name for the captain

        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(false); // can be killed

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HAND, weaponFor(role));
        if (getConfig().getBoolean("army.armor", true)) equipArmor(eq);

        applySkin(npc);
        npc.getNavigator().getDefaultParameters().range(100f).speedModifier(1.2f);

        Location spawnAt = at != null ? at.clone() : slot(army, owner.getLocation(), index, false);
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
        }

        army.npcs.add(npc);
        army.roles.put(npc, role);
        spawnAt.getWorld().playSound(spawnAt, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
        if (at != null) {
            spawnAt.getWorld().spawnParticle(Particle.PORTAL, spawnAt.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.3);
        }

        // start walking right away
        steerOne(owner, army, npc, liveTarget(owner, army.targetId));
    }

    // new target (or null = follow the owner)
    private void command(Player owner, Army army, LivingEntity target) {
        army.targetId = target == null ? null : target.getUniqueId();
        army.engaged.clear();
        army.lastDest.clear();
        for (NPC npc : army.npcs) {
            if (npc.isSpawned()) steerOne(owner, army, npc, target);
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
        }

        for (NPC npc : army.npcs) {
            if (!npc.isSpawned()) continue;
            steerOne(owner, army, npc, target);
            if (target != null) {
                pursue(owner, army, npc, npc.getEntity().getLocation(), target);
            } else {
                army.stuck.remove(npc);
                army.lastPos.remove(npc);
            }
        }
    }

    // decides where one NPC should be heading
    private void steerOne(Player owner, Army army, NPC npc, LivingEntity target) {
        if (!npc.isSpawned()) return;
        Navigator nav = npc.getNavigator();
        Location here = npc.getEntity().getLocation();
        int index = Math.max(0, army.npcs.indexOf(npc));
        Role role = army.roles.getOrDefault(npc, Role.SWORD);

        if (target != null) {
            Location tl = target.getLocation();
            if (!here.getWorld().equals(tl.getWorld())) return;   // pursue() teleports it over
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

        // no target: follow the owner
        Location o = owner.getLocation();
        if (!here.getWorld().equals(o.getWorld()) || here.distanceSquared(o) > 40 * 40) {
            npc.teleport(slot(army, o, index, false), PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
            return;
        }
        walkTo(army, npc, nav, here, slot(army, o, index, false));
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

    private void pursue(Player owner, Army army, NPC npc, Location here, LivingEntity target) {
        Location to = target.getLocation();
        if (!here.getWorld().equals(to.getWorld())) {
            npc.teleport(slot(army, owner.getLocation(), Math.max(0, army.npcs.indexOf(npc)), false),
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
            return;
        }

        Location last = army.lastPos.put(npc, here.clone());

        // an archer standing still and shooting is not stuck
        if (army.roles.get(npc) == Role.ARCHER && npc.getEntity() instanceof LivingEntity me
                && here.distanceSquared(to) <= ARCHER_RANGE * ARCHER_RANGE && me.hasLineOfSight(target)) {
            army.stuck.put(npc, 0);
            return;
        }
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
        army.dismissed = true;
        if (army.task != null) army.task.cancel();
        if (army.spawnTask != null) army.spawnTask.cancel();

        Location where = null;
        for (NPC npc : army.npcs) {
            if (where == null && npc.isSpawned()) where = npc.getEntity().getLocation();
            npc.destroy();
        }
        npcOwner.values().removeIf(v -> v.equals(ownerId));

        // close any portals that are still open (puts the old blocks back)
        for (Portal p : army.portals) closePortal(p);

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
