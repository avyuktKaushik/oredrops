package dev.dropevent;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DropEventPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int DEFAULT_MINUTES = 30;
    private static final int DEFAULT_MULTIPLIER = 4;

    private boolean active = false;
    private int multiplier = DEFAULT_MULTIPLIER;
    private long endMillis;
    private long totalMillis;
    private BukkitTask task;
    private BossBar bar;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("dropevent");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        stopEvent(false);
    }

    // ---------------------------------------------------------------- event control

    private void startEvent(int minutes, int mult) {
        active = true;
        multiplier = mult;
        totalMillis = minutes * 60_000L;
        endMillis = System.currentTimeMillis() + totalMillis;

        bar = BossBar.bossBar(Component.text(""), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        updateBar();
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);

        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (System.currentTimeMillis() >= endMillis) {
                stopEvent(true);
            } else {
                updateBar();
            }
        }, 20L, 20L);

        Bukkit.broadcast(Component.text("⛏ DROP EVENT STARTED! Ore and mob drops are x" + mult
                + " for " + minutes + " minutes!", NamedTextColor.GOLD));
    }

    private void stopEvent(boolean announce) {
        if (!active) return;
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
        if (announce) {
            Bukkit.broadcast(Component.text("⛏ The drop event has ended. Drops are back to normal.",
                    NamedTextColor.RED));
        }
    }

    private long remainingMillis() {
        return Math.max(0, endMillis - System.currentTimeMillis());
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void updateBar() {
        if (bar == null) return;
        long rem = remainingMillis();
        bar.name(Component.text("Drop Event x" + multiplier + " — " + formatTime(rem) + " left",
                NamedTextColor.GOLD));
        bar.progress(Math.max(0f, Math.min(1f, (float) rem / totalMillis)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (active && bar != null) e.getPlayer().showBossBar(bar);
    }

    // ---------------------------------------------------------------- drop handling

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    /** Ore blocks: multiply everything the ore dropped. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent e) {
        if (!active || !isOre(e.getBlockState().getType())) return;

        for (Item item : new ArrayList<>(e.getItems())) {
            ItemStack original = item.getItemStack();
            int extra = original.getAmount() * (multiplier - 1);
            int max = original.getMaxStackSize();
            Location loc = item.getLocation();
            while (extra > 0) {
                int n = Math.min(extra, max);
                ItemStack copy = original.clone();
                copy.setAmount(n);
                item.getWorld().dropItemNaturally(loc, copy);
                extra -= n;
            }
        }
    }

    /** Mobs (not players / armor stands): multiply the drop list. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent e) {
        if (!active || !(e.getEntity() instanceof Mob)) return;

        List<ItemStack> drops = e.getDrops();
        List<ItemStack> extras = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir()) continue;
            int extra = stack.getAmount() * (multiplier - 1);
            int max = stack.getMaxStackSize();
            while (extra > 0) {
                int n = Math.min(extra, max);
                ItemStack copy = stack.clone();
                copy.setAmount(n);
                extras.add(copy);
                extra -= n;
            }
        }
        drops.addAll(extras);
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /" + label + " <start [minutes] [multiplier]|stop|status>",
                    NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (active) {
                    sender.sendMessage(Component.text("An event is already running. Use /" + label + " stop first.",
                            NamedTextColor.RED));
                    return true;
                }
                int minutes = DEFAULT_MINUTES;
                int mult = DEFAULT_MULTIPLIER;
                try {
                    if (args.length > 1) minutes = Integer.parseInt(args[1]);
                    if (args.length > 2) mult = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Minutes and multiplier must be whole numbers.",
                            NamedTextColor.RED));
                    return true;
                }
                if (minutes < 1 || mult < 2 || mult > 100) {
                    sender.sendMessage(Component.text("Minutes must be >= 1 and multiplier between 2 and 100.",
                            NamedTextColor.RED));
                    return true;
                }
                startEvent(minutes, mult);
            }
            case "stop" -> {
                if (!active) {
                    sender.sendMessage(Component.text("No event is running.", NamedTextColor.RED));
                } else {
                    stopEvent(true);
                }
            }
            case "status" -> {
                if (active) {
                    sender.sendMessage(Component.text("Event active: x" + multiplier + ", "
                            + formatTime(remainingMillis()) + " remaining.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("No event is running.", NamedTextColor.GRAY));
                }
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "status").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("30");
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) return List.of("4");
        return List.of();
    }
}
