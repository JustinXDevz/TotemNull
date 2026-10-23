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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    // ---- tweak these ----
    private static final int ARMY_SIZE = 15;
    private static final long TARGET_WINDOW_MS = 30_000; // how long after you hit someone they count as "your target"

    private static class Army {
        final List<NPC> npcs = new ArrayList<>();
        UUID currentTarget;   // null = following owner
        BukkitTask task;
    }

    private NamespacedKey totemKey;
    private NPCRegistry registry; // anonymous: NPCs are never saved to Citizens' data

    private final Map<UUID, Army> armies = new HashMap<>();
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final Map<UUID, Long> lastHitTime = new HashMap<>();

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
        meta.lore(List.of(Component.text("Hit a player, then blow the Call horn.", NamedTextColor.GRAY)
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

    // ---------- remember who the owner hit ----------
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player owner) || !(e.getEntity() instanceof Player victim)) return;
        if (owner.equals(victim) || CitizensAPI.getNPCRegistry().isNPC(victim) || registry.isNPC(victim)) return;
        if (!hasTotem(owner)) return;

        lastTarget.put(owner.getUniqueId(), victim.getUniqueId());
        lastHitTime.put(owner.getUniqueId(), System.currentTimeMillis());

        // army already out? send it after the new victim
        Army army = armies.get(owner.getUniqueId());
        if (army != null) command(owner, army, victim);
    }

    // ---------- horn: blow once = summon, blow again = vanish ----------
    @EventHandler
    public void onHorn(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCallHorn(e.getItem())) return;

        Player owner = e.getPlayer();
        if (!hasTotem(owner)) return;

        if (armies.containsKey(owner.getUniqueId())) {
            dismiss(owner.getUniqueId(), true);
            return;
        }

        Player target = validTarget(owner);
        if (target == null) {
            owner.sendActionBar(Component.text("Hit a player first.", NamedTextColor.RED));
            return;
        }
        summon(owner, target);
    }

    private Player validTarget(Player owner) {
        UUID id = lastTarget.get(owner.getUniqueId());
        if (id == null) return null;
        boolean fresh = System.currentTimeMillis() - lastHitTime.getOrDefault(owner.getUniqueId(), 0L) <= TARGET_WINDOW_MS;
        Player t = Bukkit.getPlayer(id);
        if (!fresh || t == null || t.isDead() || !t.getWorld().equals(owner.getWorld())) return null;
        return t;
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

    private void summon(Player owner, Player target) {
        Army army = new Army();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int i = 0; i < ARMY_SIZE; i++) {
            NPC npc = registry.createNPC(EntityType.PLAYER, gibberishName());
            npc.setProtected(false); // can be killed
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, nullSword());
            applySkin(npc);
            npc.getNavigator().getDefaultParameters().range(100f).speedModifier(1.2f);
            npc.spawn(owner.getLocation().add(r.nextDouble(-4, 4), 0, r.nextDouble(-4, 4)));
            army.npcs.add(npc);
        }
        armies.put(owner.getUniqueId(), army);
        command(owner, army, target);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);

        // every 0.5s: keep the army on target, follow owner when target is gone, clean up
        army.task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 10;
                if (!owner.isOnline() || owner.isDead()) {
                    dismiss(owner.getUniqueId(), false);
                    return;
                }
                Player t = army.currentTarget == null ? null : Bukkit.getPlayer(army.currentTarget);
                boolean targetOk = t != null && !t.isDead() && t.getWorld().equals(owner.getWorld());
                if (!targetOk && army.currentTarget != null) {
                    command(owner, army, null);          // target died/left: follow owner
                } else if (ticks % 100 == 0) {
                    command(owner, army, targetOk ? t : null); // re-apply now and then
                }
            }
        }, 10L, 10L);
    }

    // target == null -> follow the owner, otherwise attack target
    private void command(Player owner, Army army, Player target) {
        army.currentTarget = target == null ? null : target.getUniqueId();
        for (NPC npc : army.npcs) {
            if (!npc.isSpawned()) continue;
            if (target == null) npc.getNavigator().setTarget(owner, false);
            else npc.getNavigator().setTarget(target, true);
        }
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
