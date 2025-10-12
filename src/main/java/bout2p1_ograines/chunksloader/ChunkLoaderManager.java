package bout2p1_ograines.chunksloader;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChunkLoaderManager {
    private static final String STORAGE_FILE = "chunkloaders.yml";

    private final ChunksLoaderPlugin plugin;
    private final File storageFile;

    private final Map<UUID, Map<ChunkLoaderLocation, Boolean>> loadersByWorld = new HashMap<>();
    private final List<ChunkLoaderListener> listeners = new ArrayList<>();

    public ChunkLoaderManager(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), STORAGE_FILE);
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
    }

    public void addListener(ChunkLoaderListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChunkLoaderListener listener) {
        listeners.remove(listener);
    }

    public void load() {
        loadersByWorld.clear();
        if (!storageFile.exists()) {
            return;
        }

        FileConfiguration configuration = YamlConfiguration.loadConfiguration(storageFile);
        for (String worldId : configuration.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(worldId);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid world identifier '" + worldId + "' in " + STORAGE_FILE);
                continue;
            }

            List<?> list = configuration.getList(worldId);
            if (list == null) {
                continue;
            }
            Map<ChunkLoaderLocation, Boolean> set = new HashMap<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Integer x = mapValue(map, "x");
                    Integer y = mapValue(map, "y");
                    Integer z = mapValue(map, "z");
                    Boolean active = mapBoolean(map, "active");
                    if (x != null && y != null && z != null) {
                        ChunkLoaderLocation location = new ChunkLoaderLocation(uuid, x, y, z);
                        set.put(location, active == null || active);
                    } else {
                        plugin.getLogger().warning("Ignoring invalid chunk loader entry for world '" + worldId + "' in " + STORAGE_FILE);
                    }
                }
            }
            loadersByWorld.put(uuid, set);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            applyForcedChunks();
            notifyListeners(null);
        });
    }

    public void save() {
        FileConfiguration configuration = new YamlConfiguration();
        for (Map.Entry<UUID, Map<ChunkLoaderLocation, Boolean>> entry : loadersByWorld.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<ChunkLoaderLocation, Boolean> loaderEntry : entry.getValue().entrySet()) {
                ChunkLoaderLocation location = loaderEntry.getKey();
                Map<String, Object> map = new HashMap<>();
                map.put("x", location.x());
                map.put("y", location.y());
                map.put("z", location.z());
                map.put("active", loaderEntry.getValue() != null && loaderEntry.getValue());
                list.add(map);
            }
            configuration.set(entry.getKey().toString(), list);
        }

        try {
            configuration.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Unable to save chunk loaders: " + exception.getMessage());
        }
    }

    private Integer mapValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Boolean mapBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return null;
    }

    public boolean isChunkLoaderBlock(Block block) {
        UUID worldId = block.getWorld().getUID();
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return false;
        }
        ChunkLoaderLocation location = new ChunkLoaderLocation(worldId, block.getX(), block.getY(), block.getZ());
        return loaders.containsKey(location);
    }

    public boolean canPlaceLoader(Location location, int radius) {
        int chunkX = Math.floorDiv(location.getBlockX(), 16);
        int chunkZ = Math.floorDiv(location.getBlockZ(), 16);
        UUID worldId = location.getWorld().getUID();

        if (overlapsSpawnArea(location.getWorld(), chunkX, chunkZ, radius)) {
            return false;
        }

        for (ChunkLoaderLocation loader : getLoaderStates(worldId).keySet()) {
            if (overlaps(chunkX, chunkZ, loader, radius)) {
                return false;
            }
        }
        return true;
    }

    public void addLoader(Location location) {
        UUID worldId = location.getWorld().getUID();
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.computeIfAbsent(worldId, k -> new HashMap<>());
        ChunkLoaderLocation loaderLocation = new ChunkLoaderLocation(worldId, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        loaders.put(loaderLocation, true);
        save();
        applyForcedChunks(location.getWorld());
        notifyListeners(location.getWorld());
    }

    public boolean removeLoader(Block block) {
        World world = block.getWorld();
        UUID worldId = world.getUID();
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return false;
        }
        ChunkLoaderLocation location = new ChunkLoaderLocation(worldId, block.getX(), block.getY(), block.getZ());
        Boolean removed = loaders.remove(location);
        if (removed != null) {
            if (loaders.isEmpty()) {
                loadersByWorld.remove(worldId);
            }
            save();
            applyForcedChunks(world);
            notifyListeners(world);
            return true;
        }
        return false;
    }

    public Set<ChunkLoaderLocation> getLoaders(UUID worldId) {
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return Set.of();
        }
        Set<ChunkLoaderLocation> active = new HashSet<>();
        for (Map.Entry<ChunkLoaderLocation, Boolean> entry : loaders.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    public Set<ChunkLoaderLocation> getAllLoaders() {
        Set<ChunkLoaderLocation> all = new HashSet<>();
        for (Map<ChunkLoaderLocation, Boolean> loaders : loadersByWorld.values()) {
            all.addAll(loaders.keySet());
        }
        return all;
    }

    public void applyForcedChunks() {
        for (World world : Bukkit.getWorlds()) {
            applyForcedChunks(world);
        }
    }

    private void notifyListeners(World world) {
        for (ChunkLoaderListener listener : new ArrayList<>(listeners)) {
            try {
                listener.onLoadersChanged(world);
            } catch (Exception exception) {
                plugin.getLogger().warning("Map integration listener failed: " + exception.getMessage());
            }
        }
    }

    public void applyForcedChunks(World world) {
        clearForcedChunks(world);
        int radius = plugin.getLoaderRadius();
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(world.getUID());
        if (loaders == null) {
            return;
        }
        for (Map.Entry<ChunkLoaderLocation, Boolean> entry : loaders.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                forceChunkArea(world, entry.getKey(), radius);
            }
        }
    }

    public void clearAllForcedChunks() {
        for (World world : Bukkit.getWorlds()) {
            clearForcedChunks(world);
        }
    }

    private void forceChunkArea(World world, ChunkLoaderLocation loader, int radius) {
        int centerX = Math.floorDiv(loader.x(), 16);
        int centerZ = Math.floorDiv(loader.z(), 16);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.setChunkForceLoaded(centerX + dx, centerZ + dz, true);
            }
        }
    }

    private void clearForcedChunks(World world) {
        Set<Chunk> forced = Set.copyOf(world.getForceLoadedChunks());
        for (Chunk chunk : forced) {
            world.setChunkForceLoaded(chunk.getX(), chunk.getZ(), false);
        }
    }

    private boolean overlaps(int chunkX, int chunkZ, ChunkLoaderLocation loader, int radius) {
        World world = Bukkit.getWorld(loader.worldId());
        if (world == null) {
            return false;
        }
        int existingX = Math.floorDiv(loader.x(), 16);
        int existingZ = Math.floorDiv(loader.z(), 16);
        return Math.abs(existingX - chunkX) <= radius * 2 && Math.abs(existingZ - chunkZ) <= radius * 2;
    }

    private boolean overlapsSpawnArea(World world, int chunkX, int chunkZ, int radius) {
        Chunk spawnChunk = world.getChunkAt(world.getSpawnLocation());
        int spawnX = spawnChunk.getX();
        int spawnZ = spawnChunk.getZ();
        return Math.abs(spawnX - chunkX) <= radius && Math.abs(spawnZ - chunkZ) <= radius;
    }

    public boolean isInSpawnArea(World world, int chunkX, int chunkZ) {
        int radius = plugin.getLoaderRadius();
        return overlapsSpawnArea(world, chunkX, chunkZ, radius);
    }

    public Set<ChunkCoordinate> getLoadedChunkArea(World world) {
        Set<ChunkCoordinate> loaded = new HashSet<>();
        int radius = plugin.getLoaderRadius();
        for (ChunkLoaderLocation loader : getLoaders(world.getUID())) {
            int centerX = Math.floorDiv(loader.x(), 16);
            int centerZ = Math.floorDiv(loader.z(), 16);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    loaded.add(new ChunkCoordinate(centerX + dx, centerZ + dz));
                }
            }
        }
        return loaded;
    }

    public Set<ChunkCoordinate> getInactiveChunkArea(World world) {
        Set<ChunkCoordinate> inactive = new HashSet<>();
        int radius = plugin.getLoaderRadius();
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(world.getUID());
        if (loaders == null) {
            return inactive;
        }
        for (Map.Entry<ChunkLoaderLocation, Boolean> entry : loaders.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            ChunkLoaderLocation loader = entry.getKey();
            int centerX = Math.floorDiv(loader.x(), 16);
            int centerZ = Math.floorDiv(loader.z(), 16);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    inactive.add(new ChunkCoordinate(centerX + dx, centerZ + dz));
                }
            }
        }
        return inactive;
    }

    public Map<ChunkLoaderLocation, Boolean> getLoaderStates(UUID worldId) {
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return Map.of();
        }
        return Map.copyOf(loaders);
    }

    public boolean isLoaderActive(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return false;
        }
        return Boolean.TRUE.equals(loaders.get(location));
    }

    public boolean isLoaderActive(Block block) {
        ChunkLoaderLocation location = new ChunkLoaderLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        return isLoaderActive(location);
    }

    public void setLoaderActive(ChunkLoaderLocation location, boolean active) {
        Map<ChunkLoaderLocation, Boolean> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null || !loaders.containsKey(location)) {
            return;
        }
        if (Boolean.TRUE.equals(loaders.get(location)) == active) {
            return;
        }
        loaders.put(location, active);
        save();
        World world = Bukkit.getWorld(location.worldId());
        if (world != null) {
            applyForcedChunks(world);
            notifyListeners(world);
        } else {
            applyForcedChunks();
            notifyListeners(null);
        }
    }

    public boolean toggleLoader(ChunkLoaderLocation location) {
        if (!getLoaderStates(location.worldId()).containsKey(location)) {
            return false;
        }
        boolean currentlyActive = isLoaderActive(location);
        setLoaderActive(location, !currentlyActive);
        return !currentlyActive;
    }

}
