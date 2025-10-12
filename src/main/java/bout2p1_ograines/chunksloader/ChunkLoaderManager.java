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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChunkLoaderManager {
    private static final String STORAGE_FILE = "chunkloaders.yml";

    private final ChunksLoaderPlugin plugin;
    private final File storageFile;

    private final Map<UUID, Map<ChunkLoaderLocation, ChunkLoaderState>> loadersByWorld = new HashMap<>();
    private final List<ChunkLoaderListener> listeners = new ArrayList<>();
    private final PlayerEmulationController playerEmulationController;

    public ChunkLoaderManager(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), STORAGE_FILE);
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        this.playerEmulationController = new PlayerEmulationController(plugin);
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
            Map<ChunkLoaderLocation, ChunkLoaderState> set = new HashMap<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Integer x = mapValue(map, "x");
                    Integer y = mapValue(map, "y");
                    Integer z = mapValue(map, "z");
                    Boolean active = mapBoolean(map, "active");
                    Boolean emulatePlayer = mapBoolean(map, "player");
                    String playerName = mapString(map, "playerName");
                    if (x != null && y != null && z != null) {
                        ChunkLoaderLocation location = new ChunkLoaderLocation(uuid, x, y, z);
                        boolean isActive = active == null || active;
                        boolean emulate = emulatePlayer != null && emulatePlayer;
                        if (emulate && !playerEmulationController.isPlayerCommandAvailable()) {
                            plugin.getLogger().warning("Simulated players are not supported on this server. Disabling player emulation for loader at " + x + ", " + y + ", " + z + ".");
                            emulate = false;
                        }
                        if (emulate && (playerName == null || playerName.isBlank())) {
                            playerName = generateSimulatedPlayerName(location);
                        }
                        ChunkLoaderState state = new ChunkLoaderState(isActive, emulate, playerName);
                        set.put(location, state);
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
        for (Map.Entry<UUID, Map<ChunkLoaderLocation, ChunkLoaderState>> entry : loadersByWorld.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> loaderEntry : entry.getValue().entrySet()) {
                ChunkLoaderLocation location = loaderEntry.getKey();
                ChunkLoaderState state = loaderEntry.getValue();
                Map<String, Object> map = new HashMap<>();
                map.put("x", location.x());
                map.put("y", location.y());
                map.put("z", location.z());
                map.put("active", state != null && state.isActive());
                if (state != null && state.isPlayerEmulationEnabled()) {
                    map.put("player", true);
                }
                if (state != null && state.getSimulatedPlayerName() != null) {
                    map.put("playerName", state.getSimulatedPlayerName());
                }
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

    private String mapString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    public boolean isChunkLoaderBlock(Block block) {
        UUID worldId = block.getWorld().getUID();
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(worldId);
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

        Map<ChunkLoaderLocation, ChunkLoaderState> states = loadersByWorld.get(worldId);
        if (states == null) {
            return true;
        }
        for (ChunkLoaderLocation loader : states.keySet()) {
            if (overlaps(chunkX, chunkZ, loader, radius)) {
                return false;
            }
        }
        return true;
    }

    public void addLoader(Location location) {
        UUID worldId = location.getWorld().getUID();
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.computeIfAbsent(worldId, k -> new HashMap<>());
        ChunkLoaderLocation loaderLocation = new ChunkLoaderLocation(worldId, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        loaders.put(loaderLocation, new ChunkLoaderState(true, false, null));
        save();
        applyForcedChunks(location.getWorld());
        notifyListeners(location.getWorld());
    }

    public boolean removeLoader(Block block) {
        World world = block.getWorld();
        UUID worldId = world.getUID();
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return false;
        }
        ChunkLoaderLocation location = new ChunkLoaderLocation(worldId, block.getX(), block.getY(), block.getZ());
        ChunkLoaderState removed = loaders.remove(location);
        if (removed != null) {
            playerEmulationController.disable(location, removed);
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
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return Set.of();
        }
        Set<ChunkLoaderLocation> active = new HashSet<>();
        for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : loaders.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isActive()) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    public Set<ChunkLoaderLocation> getAllLoaders() {
        Set<ChunkLoaderLocation> all = new HashSet<>();
        for (Map<ChunkLoaderLocation, ChunkLoaderState> loaders : loadersByWorld.values()) {
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
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(world.getUID());
        if (loaders == null) {
            playerEmulationController.clearWorld(world.getUID());
            return;
        }
        for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : loaders.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isActive()) {
                forceChunkArea(world, entry.getKey(), radius);
            }
        }
        playerEmulationController.syncWorld(world, loaders);
    }

    public void clearAllForcedChunks() {
        for (World world : Bukkit.getWorlds()) {
            clearForcedChunks(world);
        }
        playerEmulationController.clearAll();
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
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(world.getUID());
        if (loaders == null) {
            return inactive;
        }
        for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : loaders.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isActive()) {
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

    public Map<ChunkLoaderLocation, ChunkLoaderState> getLoaderStates(UUID worldId) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(worldId);
        if (loaders == null) {
            return Map.of();
        }
        Map<ChunkLoaderLocation, ChunkLoaderState> copy = new HashMap<>();
        for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : loaders.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? null : new ChunkLoaderState(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    public boolean isLoaderActive(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return false;
        }
        ChunkLoaderState state = loaders.get(location);
        return state != null && state.isActive();
    }

    public boolean isLoaderActive(Block block) {
        ChunkLoaderLocation location = new ChunkLoaderLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        return isLoaderActive(location);
    }

    public void setLoaderActive(ChunkLoaderLocation location, boolean active) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return;
        }
        ChunkLoaderState state = loaders.get(location);
        if (state == null || state.isActive() == active) {
            return;
        }
        state.setActive(active);
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
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null || !loaders.containsKey(location)) {
            return false;
        }
        boolean currentlyActive = isLoaderActive(location);
        setLoaderActive(location, !currentlyActive);
        return !currentlyActive;
    }

    public ChunkLoaderState getLoaderState(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return null;
        }
        ChunkLoaderState state = loaders.get(location);
        if (state == null) {
            return null;
        }
        return new ChunkLoaderState(state);
    }

    public boolean hasLoader(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        return loaders != null && loaders.containsKey(location);
    }

    public boolean isPlayerEmulationEnabled(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return false;
        }
        ChunkLoaderState state = loaders.get(location);
        return state != null && state.isPlayerEmulationEnabled();
    }

    public boolean setPlayerEmulation(ChunkLoaderLocation location, boolean emulate) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null) {
            return false;
        }
        ChunkLoaderState state = loaders.get(location);
        if (state == null) {
            return false;
        }
        if (state.isPlayerEmulationEnabled() == emulate) {
            return true;
        }
        if (emulate && !playerEmulationController.isPlayerCommandAvailable()) {
            return false;
        }
        if (emulate && (state.getSimulatedPlayerName() == null || state.getSimulatedPlayerName().isBlank())) {
            state.setSimulatedPlayerName(generateSimulatedPlayerName(location));
        }
        state.setPlayerEmulationEnabled(emulate);
        save();
        World world = Bukkit.getWorld(location.worldId());
        if (world != null) {
            applyForcedChunks(world);
            notifyListeners(world);
        } else {
            applyForcedChunks();
            notifyListeners(null);
        }
        return true;
    }

    public boolean togglePlayerEmulation(ChunkLoaderLocation location) {
        Map<ChunkLoaderLocation, ChunkLoaderState> loaders = loadersByWorld.get(location.worldId());
        if (loaders == null || !loaders.containsKey(location)) {
            return false;
        }
        ChunkLoaderState state = loaders.get(location);
        boolean target = state == null || !state.isPlayerEmulationEnabled();
        if (!setPlayerEmulation(location, target)) {
            return false;
        }
        return target;
    }

    public boolean canEmulatePlayers() {
        return playerEmulationController.isPlayerCommandAvailable();
    }

    public void clearAllPlayerEmulators() {
        playerEmulationController.clearAll();
    }

    private String generateSimulatedPlayerName(ChunkLoaderLocation location) {
        long hash = 1469598103934665603L;
        hash = mixHash(hash, location.worldId().getMostSignificantBits());
        hash = mixHash(hash, location.worldId().getLeastSignificantBits());
        hash = mixHash(hash, location.x());
        hash = mixHash(hash, location.y());
        hash = mixHash(hash, location.z());
        String base = Long.toUnsignedString(hash, 36).toUpperCase(Locale.ROOT);
        String name = "CL" + base;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        while (name.length() < 3) {
            name = name + "0";
        }
        return name;
    }

    private long mixHash(long current, long value) {
        current ^= value;
        current *= 1099511628211L;
        return current;
    }

}
