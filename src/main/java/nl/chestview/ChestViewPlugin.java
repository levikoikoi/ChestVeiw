package nl.chestview;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class ChestViewPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {

    private static final Set<Material> CONTAINERS =
            EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    /** De naam die op het item komt te staan. */
    private static final String CLOSED_NAME = "Closed chest by admin";

    private NamespacedKey closedKey;

    @Override
    public void onEnable() {
        closedKey = new NamespacedKey(this, "closed");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ChestView ingeschakeld.");
    }

    // ---------- Helpers ----------

    /** Alle blokken van een container (2 bij een dubbele kist). */
    private List<Block> blocksOf(Block block) {
        List<Block> result = new ArrayList<>();
        if (block.getState() instanceof org.bukkit.block.Chest chest
                && chest.getInventory() instanceof DoubleChestInventory dci) {
            result.add(dci.getLeftSide().getLocation().getBlock());
            result.add(dci.getRightSide().getLocation().getBlock());
        } else {
            result.add(block);
        }
        return result;
    }

    private boolean isClosedBlock(Block block) {
        if (!(block.getState() instanceof TileState state)) return false;
        return state.getPersistentDataContainer().has(closedKey, PersistentDataType.BYTE);
    }

    /** Een kist is gesloten als een van de helften gesloten is. */
    private boolean isClosed(Block block) {
        if (!CONTAINERS.contains(block.getType())) return false;
        for (Block b : blocksOf(block)) {
            if (isClosedBlock(b)) return true;
        }
        return false;
    }

    private boolean isClosed(Inventory inv) {
        if (inv.getLocation() == null) return false;
        return isClosed(inv.getLocation().getBlock());
    }

    private boolean isClosedItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(closedKey, PersistentDataType.BYTE);
    }

    private void markBlockClosed(Block block) {
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(closedKey, PersistentDataType.BYTE, (byte) 1);
            state.update();
        }
    }

    /** Alleen operators / mensen met deze permissie mogen een gesloten kist aanpassen. */
    private boolean canBypass(Player p) {
        return p.hasPermission("chestview.bypass");
    }

    // ---------- Command: /close chest ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Alleen voor spelers.");
            return true;
        }
        if (!p.hasPermission("chestview.close")) {
            p.sendMessage(Component.text("Je hebt hier geen rechten voor.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("chest")) {
            p.sendMessage(Component.text("Gebruik: /close chest (met een kist in je hand)", NamedTextColor.RED));
            return true;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!CONTAINERS.contains(hand.getType())) {
            p.sendMessage(Component.text("Je moet een kist (of vat) in je hand houden.", NamedTextColor.RED));
            return true;
        }
        if (isClosedItem(hand)) {
            p.sendMessage(Component.text("Deze kist is al gesloten.", NamedTextColor.YELLOW));
            return true;
        }

        ItemMeta meta = hand.getItemMeta();
        meta.displayName(Component.text(CLOSED_NAME, NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(closedKey, PersistentDataType.BYTE, (byte) 1);
        hand.setItemMeta(meta);

        p.sendMessage(Component.text("De kist is nu \"" + CLOSED_NAME + "\". Als je hem plaatst, "
                + "kunnen anderen alleen kijken.", NamedTextColor.GREEN));
        return true;
    }

    // ---------- Events ----------

    /** Geplaatste kist met het gesloten-item wordt een gesloten kist. */
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!CONTAINERS.contains(e.getBlockPlaced().getType())) return;
        if (isClosedItem(e.getItemInHand())) {
            markBlockClosed(e.getBlockPlaced());
        }
    }

    /** Alleen operators kunnen een gesloten kist slopen. */
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isClosed(b)) return;
        if (!canBypass(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("Deze kist is gesloten, je kunt hem niet slopen.",
                    NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!isClosed(e.getInventory()) || canBypass(p)) return;
        p.sendActionBar(Component.text("Gesloten kist: je kunt alleen kijken", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isClosed(e.getView().getTopInventory()) && !canBypass(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isClosed(e.getView().getTopInventory()) && !canBypass(p)) {
            e.setCancelled(true);
        }
    }

    /** Trechters mogen niets uit of in een gesloten kist verplaatsen. */
    @EventHandler
    public void onHopper(InventoryMoveItemEvent e) {
        if (isClosed(e.getSource()) || isClosed(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    /** Gesloten kisten gaan niet kapot door explosies. */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isClosed);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isClosed);
    }
}
