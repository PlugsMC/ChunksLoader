package bout2p1_ograines.chunksloader;

import bout2p1_ograines.chunksloader.map.LoaderData;
import bout2p1_ograines.chunksloader.map.MapIntegrationManager;
import bout2p1_ograines.chunksloader.ChunkLoaderState;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.World;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunksLoaderPlugin extends JavaPlugin implements Listener {
    private static final String CONFIG_RADIUS = "loader-radius";
    private static final String CONFIG_MAP_RADIUS = "map-radius";
    private static final String MENU_TITLE = ChatColor.DARK_GREEN + "Chunk Loader";
    private static final int MENU_SIZE = 9;
    private static final int TOGGLE_SLOT = 4;
    private static final int PLAYER_SLOT = 6;
    private static final int CLOSE_SLOT = 8;

    private NamespacedKey itemKey;
    private ChunkLoaderManager manager;
    private int loaderRadius;
    private int mapRadius;
    private MapIntegrationManager mapIntegrationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();

        this.itemKey = new NamespacedKey(this, "chunk_loader_item");
        this.manager = new ChunkLoaderManager(this);
        this.mapIntegrationManager = new MapIntegrationManager(this);
        manager.addListener(mapIntegrationManager);
        mapIntegrationManager.initialize();
        manager.load();
        mapIntegrationManager.updateAll();

        Bukkit.getPluginManager().registerEvents(this, this);

        var pluginCommand = getCommand("chunksloader");
        if (pluginCommand == null) {
            getLogger().severe("The /chunksloader command is missing from plugin.yml");
            return;
        }

        pluginCommand.setExecutor(this);
        pluginCommand.setTabCompleter((sender, command, alias, args) -> {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                completions.add("give");
                completions.add("map");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            }
            return completions;
        });
    }

    @Override
    public void onDisable() {
        if (mapIntegrationManager != null) {
            manager.removeListener(mapIntegrationManager);
            mapIntegrationManager.shutdown();
            mapIntegrationManager = null;
        }
        manager.clearAllPlayerEmulators();
        manager.clearAllForcedChunks();
        manager.save();
    }

    public ChunkLoaderManager getManager() {
        return manager;
    }

    public int getLoaderRadius() {
        return loaderRadius;
    }

    public int getMapRadius() {
        return mapRadius;
    }

    public List<LoaderData> getLoaderData() {
        List<LoaderData> loaders = new ArrayList<>();
        int radius = getLoaderRadius();
        int diameter = (radius * 2) + 1;
        int chunkCount = diameter * diameter;
        for (World world : Bukkit.getWorlds()) {
            Map<ChunkLoaderLocation, ChunkLoaderState> states = manager.getLoaderStates(world.getUID());
            for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : states.entrySet()) {
                ChunkLoaderLocation location = entry.getKey();
                boolean active = entry.getValue() != null && entry.getValue().isActive();
                int chunkX = Math.floorDiv(location.x(), 16);
                int chunkZ = Math.floorDiv(location.z(), 16);
                String id = world.getUID() + ":" + location.x() + ":" + location.y() + ":" + location.z();
                String plainName = "Chunk Loader";
                loaders.add(new LoaderData(
                    id,
                    world.getName(),
                    location.x(),
                    location.y(),
                    location.z(),
                    chunkX,
                    chunkZ,
                    radius,
                    chunkCount,
                    active,
                    plainName,
                    plainName,
                    null,
                    0L
                ));
            }
        }
        return loaders;
    }

    private void reloadConfigValues() {
        FileConfiguration configuration = getConfig();
        configuration.addDefault(CONFIG_RADIUS, 1);
        configuration.addDefault(CONFIG_MAP_RADIUS, 5);
        configuration.options().copyDefaults(true);
        saveConfig();
        loaderRadius = Math.max(0, configuration.getInt(CONFIG_RADIUS, 1));
        mapRadius = Math.max(1, configuration.getInt(CONFIG_MAP_RADIUS, 5));
    }

    public ItemStack createChunkLoaderItem() {
        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Chunk Loader");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Keeps nearby chunks loaded");
            lore.add(ChatColor.GRAY + "Area " + (loaderRadius * 2 + 1) + "x" + (loaderRadius * 2 + 1));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(itemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isChunkLoaderItem(ItemStack item) {
        if (item == null || item.getType() != Material.BEACON) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "/" + label + " give [player]" + ChatColor.GRAY + " or " + ChatColor.RED + "/" + label + " map");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("chunksloader.give")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission.");
                return true;
            }

            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
            } else {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "You must specify a player.");
                    return true;
                }
                target = player;
            }

            ItemStack item = createChunkLoaderItem();
            target.getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "Gave a chunk loader to " + target.getName() + ".");
            return true;
        }

        if (args[0].equalsIgnoreCase("map")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command is only available to players.");
                return true;
            }
            showMap(player);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown sub-command.");
        return true;
    }

    private void showMap(Player player) {
        int radius = mapRadius;
        ChunkLoaderManager manager = getManager();
        Set<ChunkCoordinate> loaded = manager.getLoadedChunkArea(player.getWorld());
        Set<ChunkCoordinate> inactive = manager.getInactiveChunkArea(player.getWorld());
        int centerChunkX = player.getLocation().getChunk().getX();
        int centerChunkZ = player.getLocation().getChunk().getZ();
        StringBuilder builder = new StringBuilder();
        builder.append(ChatColor.YELLOW).append("Loaded chunk map (" + (radius * 2 + 1) + "x" + (radius * 2 + 1) + "):");
        player.sendMessage(builder.toString());

        for (int dz = radius; dz >= -radius; dz--) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                boolean spawn = manager.isInSpawnArea(player.getWorld(), chunkX, chunkZ);
                if (spawn) {
                    row.append(ChatColor.RED).append('■');
                    continue;
                }
                boolean loaderChunk = loaded.contains(new ChunkCoordinate(chunkX, chunkZ));
                if (loaderChunk) {
                    row.append(ChatColor.GREEN).append('■');
                    continue;
                }
                if (inactive.contains(new ChunkCoordinate(chunkX, chunkZ))) {
                    row.append(ChatColor.GOLD).append('■');
                } else {
                    row.append(ChatColor.DARK_GRAY).append('■');
                }
            }
            player.sendMessage(row.toString());
        }

        player.sendMessage(ChatColor.GREEN + "■" + ChatColor.GRAY + " = Chunk loader" + ChatColor.GOLD + "  ■" + ChatColor.GRAY + " = Disabled chunk loader" + ChatColor.RED + "  ■" + ChatColor.GRAY + " = Spawn" + ChatColor.DARK_GRAY + "  ■" + ChatColor.GRAY + " = Inactive");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isChunkLoaderItem(item)) {
            return;
        }

        if (!manager.canPlaceLoader(event.getBlockPlaced().getLocation(), loaderRadius)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot place a chunk loader in an area that is already loaded.");
            return;
        }

        manager.addLoader(event.getBlockPlaced().getLocation());
        event.getPlayer().sendMessage(ChatColor.GREEN + "Chunk loader enabled.");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!manager.isChunkLoaderBlock(block)) {
            return;
        }

        event.setDropItems(false);
        if (manager.removeLoader(block)) {
            block.getWorld().dropItemNaturally(block.getLocation(), createChunkLoaderItem());
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Chunk loader disabled.");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (!manager.isChunkLoaderBlock(clicked)) {
            return;
        }
        event.setCancelled(true);
        openChunkLoaderMenu(event.getPlayer(), clicked);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof ChunkLoaderMenuHolder menuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == TOGGLE_SLOT) {
            handleToggle(menuHolder.getLocation(), inventory, player);
        } else if (event.getRawSlot() == PLAYER_SLOT) {
            handlePlayerEmulationToggle(menuHolder.getLocation(), inventory, player);
        } else if (event.getRawSlot() == CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ChunkLoaderMenuHolder menuHolder) {
            menuHolder.clear();
        }
    }

    private void handleToggle(ChunkLoaderLocation location, Inventory inventory, Player player) {
        ChunkLoaderState state = manager.getLoaderState(location);
        if (state == null) {
            player.sendMessage(ChatColor.RED + "This chunk loader no longer exists.");
            player.closeInventory();
            return;
        }
        boolean active = manager.toggleLoader(location);
        if (active) {
            player.sendMessage(ChatColor.GREEN + "Chunk loader enabled.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Chunk loader disabled.");
        }
        fillChunkLoaderMenu(inventory, location);
    }

    private void handlePlayerEmulationToggle(ChunkLoaderLocation location, Inventory inventory, Player player) {
        if (!manager.hasLoader(location)) {
            player.sendMessage(ChatColor.RED + "This chunk loader no longer exists.");
            player.closeInventory();
            return;
        }
        if (!manager.canEmulatePlayers()) {
            player.sendMessage(ChatColor.RED + "This server version does not support simulated players.");
            return;
        }
        boolean desired = manager.togglePlayerEmulation(location);
        boolean current = manager.isPlayerEmulationEnabled(location);
        if (desired != current) {
            player.sendMessage(ChatColor.RED + "Unable to change player emulation for this chunk loader.");
        } else if (current) {
            player.sendMessage(ChatColor.GREEN + "Player emulation enabled.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Player emulation disabled.");
        }
        fillChunkLoaderMenu(inventory, location);
    }

    private void openChunkLoaderMenu(Player player, Block block) {
        ChunkLoaderLocation location = new ChunkLoaderLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        ChunkLoaderMenuHolder holder = new ChunkLoaderMenuHolder(location);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, MENU_TITLE);
        holder.setInventory(inventory);
        fillChunkLoaderMenu(inventory, location);
        player.openInventory(inventory);
    }

    private void fillChunkLoaderMenu(Inventory inventory, ChunkLoaderLocation location) {
        ItemStack filler = createFillerItem();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot == TOGGLE_SLOT || slot == PLAYER_SLOT || slot == CLOSE_SLOT) {
                continue;
            }
            inventory.setItem(slot, filler.clone());
        }
        ChunkLoaderState state = manager.getLoaderState(location);
        boolean active = state != null && state.isActive();
        boolean emulate = state != null && state.isPlayerEmulationEnabled();
        inventory.setItem(TOGGLE_SLOT, createToggleItem(active));
        inventory.setItem(PLAYER_SLOT, createPlayerEmulationItem(emulate, manager.canEmulatePlayers(), active));
        inventory.setItem(CLOSE_SLOT, createCloseItem());
    }

    private ItemStack createToggleItem(boolean active) {
        Material material = active ? Material.LIME_DYE : Material.ORANGE_DYE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (active) {
                meta.setDisplayName(ChatColor.GREEN + "Chunk loader active");
                meta.setLore(List.of(ChatColor.GRAY + "Click to disable the chunk loader."));
            } else {
                meta.setDisplayName(ChatColor.GOLD + "Chunk loader disabled");
                meta.setLore(List.of(ChatColor.GRAY + "Click to reactivate the chunk loader."));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPlayerEmulationItem(boolean enabled, boolean available, boolean loaderActive) {
        Material material;
        ChatColor color;
        List<String> lore = new ArrayList<>();
        if (!available) {
            material = Material.GRAY_DYE;
            color = ChatColor.DARK_GRAY;
            lore.add(ChatColor.GRAY + "Simulated players require Minecraft 1.21 or newer.");
        } else if (enabled) {
            material = Material.PLAYER_HEAD;
            color = ChatColor.GREEN;
            lore.add(ChatColor.GRAY + "Chunks behave as if a player is standing here.");
            if (!loaderActive) {
                lore.add(ChatColor.DARK_GRAY + "Activate the loader to spawn the player emulator.");
            }
            lore.add(ChatColor.YELLOW + "Click to disable player emulation.");
        } else {
            material = Material.SKELETON_SKULL;
            color = ChatColor.GOLD;
            lore.add(ChatColor.GRAY + "Enable to spawn a simulated player at this loader.");
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (!available) {
                meta.setDisplayName(color + "Player emulation unavailable");
            } else if (enabled) {
                meta.setDisplayName(color + "Player emulation active");
            } else {
                meta.setDisplayName(color + "Enable player emulation");
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Close");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFillerItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private static class ChunkLoaderMenuHolder implements InventoryHolder {
        private final ChunkLoaderLocation location;
        private Inventory inventory;

        private ChunkLoaderMenuHolder(ChunkLoaderLocation location) {
            this.location = location;
        }

        public ChunkLoaderLocation getLocation() {
            return location;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private void clear() {
            this.inventory = null;
        }
    }
}
