package bout2p1_ograines.chunksloader;

import bout2p1_ograines.chunksloader.map.BlueMapIntegration;
import bout2p1_ograines.chunksloader.map.DynmapIntegration;
import bout2p1_ograines.chunksloader.map.MapIntegration;

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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ChunksLoaderPlugin extends JavaPlugin implements Listener {
    private static final String CONFIG_RADIUS = "loader-radius";
    private static final String CONFIG_MAP_RADIUS = "map-radius";

    private NamespacedKey itemKey;
    private ChunkLoaderManager manager;
    private int loaderRadius;
    private int mapRadius;
    private final List<MapIntegration> mapIntegrations = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();

        this.itemKey = new NamespacedKey(this, "chunk_loader_item");
        this.manager = new ChunkLoaderManager(this);
        manager.load();

        setupMapIntegrations();

        Bukkit.getPluginManager().registerEvents(this, this);

        var pluginCommand = getCommand("chunksloader");
        if (pluginCommand == null) {
            getLogger().severe("La commande /chunksloader est introuvable dans plugin.yml");
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
        for (MapIntegration integration : mapIntegrations) {
            try {
                manager.removeListener(integration);
                integration.shutdown();
            } catch (Exception exception) {
                getLogger().warning("Erreur lors de l'arrêt d'une intégration carte : " + exception.getMessage());
            }
        }
        mapIntegrations.clear();
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

    private void reloadConfigValues() {
        FileConfiguration configuration = getConfig();
        configuration.addDefault(CONFIG_RADIUS, 1);
        configuration.addDefault(CONFIG_MAP_RADIUS, 5);
        configuration.options().copyDefaults(true);
        saveConfig();
        loaderRadius = Math.max(0, configuration.getInt(CONFIG_RADIUS, 1));
        mapRadius = Math.max(1, configuration.getInt(CONFIG_MAP_RADIUS, 5));
    }

    private void setupMapIntegrations() {
        mapIntegrations.clear();

        DynmapIntegration dynmapIntegration = new DynmapIntegration(this);
        if (dynmapIntegration.initialize()) {
            mapIntegrations.add(dynmapIntegration);
            manager.addListener(dynmapIntegration);
            dynmapIntegration.onLoadersChanged(null);
            getLogger().info("Intégration Dynmap activée.");
        }

        BlueMapIntegration blueMapIntegration = new BlueMapIntegration(this);
        if (blueMapIntegration.initialize()) {
            mapIntegrations.add(blueMapIntegration);
            manager.addListener(blueMapIntegration);
            blueMapIntegration.onLoadersChanged(null);
            getLogger().info("Intégration BlueMap activée.");
        }
    }

    public ItemStack createChunkLoaderItem() {
        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Chunk Loader");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Charge les chunks voisins");
            lore.add(ChatColor.GRAY + "Zone " + (loaderRadius * 2 + 1) + "x" + (loaderRadius * 2 + 1));
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
            sender.sendMessage(ChatColor.RED + "/" + label + " give [player]" + ChatColor.GRAY + " ou " + ChatColor.RED + "/" + label + " map");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("chunksloader.give")) {
                sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
                return true;
            }

            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Joueur introuvable.");
                    return true;
                }
            } else {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Vous devez préciser un joueur.");
                    return true;
                }
                target = player;
            }

            ItemStack item = createChunkLoaderItem();
            target.getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "Chunk loader donné à " + target.getName() + ".");
            return true;
        }

        if (args[0].equalsIgnoreCase("map")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Cette commande est réservée aux joueurs.");
                return true;
            }
            showMap(player);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Sous-commande inconnue.");
        return true;
    }

    private void showMap(Player player) {
        int radius = mapRadius;
        ChunkLoaderManager manager = getManager();
        Set<ChunkCoordinate> loaded = manager.getLoadedChunkArea(player.getWorld());
        int centerChunkX = player.getLocation().getChunk().getX();
        int centerChunkZ = player.getLocation().getChunk().getZ();
        StringBuilder builder = new StringBuilder();
        builder.append(ChatColor.YELLOW).append("Carte des chunks chargés (" + (radius * 2 + 1) + "x" + (radius * 2 + 1) + "):");
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
                } else {
                    row.append(ChatColor.DARK_GRAY).append('■');
                }
            }
            player.sendMessage(row.toString());
        }

        player.sendMessage(ChatColor.GREEN + "■" + ChatColor.GRAY + " = Chunk loader" + ChatColor.RED + "  ■" + ChatColor.GRAY + " = Spawn" + ChatColor.DARK_GRAY + "  ■" + ChatColor.GRAY + " = Inactif");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isChunkLoaderItem(item)) {
            return;
        }

        if (!manager.canPlaceLoader(event.getBlockPlaced().getLocation(), loaderRadius)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Impossible de placer un chunk loader dans une zone déjà chargée.");
            return;
        }

        manager.addLoader(event.getBlockPlaced().getLocation());
        event.getPlayer().sendMessage(ChatColor.GREEN + "Chunk loader activé.");
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
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Chunk loader désactivé.");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (!manager.isChunkLoaderBlock(event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
    }
}
