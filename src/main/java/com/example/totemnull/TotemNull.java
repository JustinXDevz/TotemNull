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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

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
    private static final double ENGAGE_DIST = 9;     // closer than this to the target: charge and attack
    private static final double DISENGAGE_DIST = 13; // farther than this: go back to formation walking

    private static class Army {
        final List<NPC> npcs = new ArrayList<>();
        final Map<NPC, Location> lastPos = new HashMap<>();
        final Map<NPC, Location> lastDest = new HashMap<>();
        final Map<NPC, Integer> stuck = new HashMap<>();
        final Set<NPC> engaged = new HashSet<>();
        final List<Block> placed = new ArrayList<>();
        Material placedType = Material.COBBLESTONE;
        UUID targetId;      // null = following owner
        BukkitTask task;
        BukkitTask spawnTask;
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
        getCommand("nullcall").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(armies.keySet())) dismiss(id, false);
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

    private ItemStack nullSword() {
        return enchanted(Material.NETHERITE_SWORD,
                Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                Enchantment.FIRE_ASPECT, 2, Enchantment.LOOTING, 3, Enchantment.KNOCKBACK, 2,
                Enchantment.SWEEPING_EDGE, 3);
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

    // ---------- army ----------

    // spread-out spot number <index> around a centre (sunflower pattern, ~2 blocks apart)
    private Location slot(Location center, int index) {
        double angle = index * 2.399963;
        double radius = 2.5 + 0.9 * Math.sqrt(index);
        Location l = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        if (!l.getBlock().isPassable()) return center.clone(); // spot is inside a wall: use the centre
        return l;
    }

    private void summon(Player owner) {
        Army army = new Army();
        final int size = Math.max(1, Math.min(60, getConfig().getInt("army.size", 15)));
        final long interval = Math.max(1L, getConfig().getLong("army.spawn-interval-ticks", 6L));

        LivingEntity first = recentTarget(owner);
        army.targetId = first == null ? null : first.getUniqueId();
        armies.put(owner.getUniqueId(), army);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);

        // they appear one by one
        army.spawnTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!owner.isOnline() || owner.isDead() || army.npcs.size() >= size) {
                army.spawnTask.cancel();
                return;
            }
            spawnOne(owner, army);
        }, 0L, interval);

        army.task = Bukkit.getScheduler().runTaskTimer(this, () -> tickArmy(owner, army), 10L, 10L);
    }

    private void spawnOne(Player owner, Army army) {
        int index = army.npcs.size();
        NPC npc = registry.createNPC(EntityType.PLAYER, gibberishName());
        npc.setProtected(false); // can be killed

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HAND, nullSword());
        if (getConfig().getBoolean("army.armor", true)) equipArmor(eq);

        applySkin(npc);
        npc.getNavigator().getDefaultParameters().range(100f).speedModifier(1.2f);

        Location at = slot(owner.getLocation(), index);
        npc.spawn(at);

        if (getConfig().getBoolean("army.effects", true) && npc.getEntity() instanceof LivingEntity le) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 1, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        army.npcs.add(npc);
        at.getWorld().playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
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
        Navigator nav = npc.getNavigator();
        Location here = npc.getEntity().getLocation();
        int index = Math.max(0, army.npcs.indexOf(npc));

        if (target != null) {
            Location tl = target.getLocation();
            if (!here.getWorld().equals(tl.getWorld())) return;   // pursue() teleports it over
            double d = here.distance(tl);
            boolean engaged = army.engaged.contains(npc);

            if (engaged && d > DISENGAGE_DIST) {
                army.engaged.remove(npc);
                army.lastDest.remove(npc);
                engaged = false;
            }
            if (!engaged && d <= ENGAGE_DIST) {
                army.engaged.add(npc);
                nav.setTarget(target, true);              // close enough: charge and attack
                return;
            }
            if (engaged) {
                if (!nav.isNavigating()) nav.setTarget(target, true);
                return;
            }
            walkTo(army, npc, nav, here, slot(tl, index)); // far away: walk in formation
            return;
        }

        // no target: follow the owner, each NPC in its own spot
        Location o = owner.getLocation();
        if (!here.getWorld().equals(o.getWorld()) || here.distanceSquared(o) > 40 * 40) {
            npc.teleport(slot(o, index), PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
            return;
        }
        walkTo(army, npc, nav, here, slot(o, index));
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
            npc.teleport(slot(owner.getLocation(), Math.max(0, army.npcs.indexOf(npc))),
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            army.lastDest.remove(npc);
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
        if (army.spawnTask != null) army.spawnTask.cancel();

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
