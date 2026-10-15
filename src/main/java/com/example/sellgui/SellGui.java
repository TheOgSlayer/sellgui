package com.example.sellgui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

public final class SellGui extends JavaPlugin implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int SIZE = 54;
    private static final int ITEM_SLOTS = 45; // slots 0-44 hold the items to sell
    private static final int SELL_SLOT = 49;  // sell button in the bottom row

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
    private Economy economy;

    private static final class SellHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveResource("prices.yml", false);
        loadPrices();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("sell")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        // Closing sells whatever is inside, so nobody loses items on shutdown
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) {
                p.closeInventory();
            }
        }
    }

    private void loadPrices() {
        prices.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "prices.yml"));
        for (String key : yml.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) {
                getLogger().warning("prices.yml: unknown material '" + key + "'");
                continue;
            }
            double v = yml.getDouble(key);
            if (v > 0) prices.put(m, v);
        }
        getLogger().info("Loaded " + prices.size() + " item prices.");
    }

    private @Nullable Economy eco() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    // ------------------------------------------------------------------ command + GUI

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        open(p);
        return true;
    }

    private void open(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, MM.deserialize("<dark_green>Sell Items"));
        holder.inv = inv;

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = ITEM_SLOTS; i < SIZE; i++) inv.setItem(i, filler);

        inv.setItem(SELL_SLOT, named(Material.LIME_DYE,
                MM.deserialize("<!italic><green><bold>SELL"),
                MM.deserialize("<!italic><gray>Closing this menu also sells your items")));

        p.openInventory(inv);
    }

    private ItemStack named(Material m, Component name, @Nullable Component lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null) meta.lore(List.of(lore));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ selling

    private void sell(Player p, Inventory inv) {
        // Take everything out of the item slots
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            items.add(it);
            inv.setItem(i, null);
        }
        if (items.isEmpty()) return;

        Economy eco = eco();
        List<ItemStack> giveBack = new ArrayList<>();
        double total = 0;

        if (eco == null) {
            giveBack.addAll(items);
            p.sendMessage(MM.deserialize("<red>Selling is unavailable right now."));
        } else {
            List<ItemStack> sold = new ArrayList<>();
            for (ItemStack it : items) {
                Double price = prices.get(it.getType());
                if (price == null) {
                    giveBack.add(it);
                } else {
                    total += price * it.getAmount();
                    sold.add(it);
                }
            }
            total = Math.round(total * 100.0) / 100.0;

            if (total > 0) {
                EconomyResponse r = eco.depositPlayer(p, total);
                if (r.transactionSuccess()) {
                    p.sendMessage(MM.deserialize("<white>+<green>$<white>" + format.format(total)));
                } else {
                    getLogger().warning("Could not pay " + p.getName() + ": " + r.errorMessage);
                    giveBack.addAll(sold); // nothing was paid, so return the items
                }
            }
        }

        // Return anything that wasn't sold
        for (ItemStack it : giveBack) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getClickedInventory() == top && e.getSlot() >= ITEM_SLOTS) {
            e.setCancelled(true);
            if (e.getSlot() == SELL_SLOT) {
                sell(p, top);
                Bukkit.getScheduler().runTask(this, p::closeInventory);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SellHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= ITEM_SLOTS && raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof SellHolder && e.getPlayer() instanceof Player p) {
            sell(p, e.getInventory());
        }
    }
}
