package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderLocation;
import bout2p1_ograines.chunksloader.ChunkLoaderManager;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;

import com.flowpowered.math.vector.Vector3d;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class BlueMapIntegration implements MapIntegration {
    private static final String MARKER_SET_ID = "chunksloader";
    private static final String SPAWN_MARKER_ID_PREFIX = "spawn_";
    private static final String LOADER_MARKER_PREFIX = "loader_";

    private final ChunksLoaderPlugin plugin;
    private final ChunkLoaderManager manager;

    private final Map<String, MarkerSet> markerSets = new HashMap<>();
    private BlueMapAPI api;
    private Consumer<BlueMapAPI> enableListener;
    private Consumer<BlueMapAPI> disableListener;

    public BlueMapIntegration(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
    }

    @Override
    public boolean initialize() {
        enableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(api));
        disableListener = api -> Bukkit.getScheduler().runTask(plugin, this::handleDisable);

        BlueMapAPI.onEnable(enableListener);
        BlueMapAPI.onDisable(disableListener);
        BlueMapAPI.getInstance().ifPresent(existing -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(existing)));
        return true;
    }

    @Override
    public void shutdown() {
        if (enableListener != null) {
            BlueMapAPI.unregisterListener(enableListener);
        }
        if (disableListener != null) {
            BlueMapAPI.unregisterListener(disableListener);
        }

        Runnable task = this::handleDisable;
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void onLoadersChanged(World world) {
        if (api == null) {
            return;
        }
        Runnable task = this::updateMarkers;
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void handleEnable(BlueMapAPI api) {
        this.api = api;
        refreshMarkerSets();
        updateMarkers();
    }

    private void handleDisable() {
        if (api != null) {
            for (BlueMapMap map : api.getMaps()) {
                map.getMarkerSets().remove(MARKER_SET_ID);
            }
            api = null;
        }
        markerSets.clear();
    }

    private void refreshMarkerSets() {
        if (api == null) {
            return;
        }
        markerSets.clear();
        Collection<BlueMapMap> maps = api.getMaps();
        for (BlueMapMap map : maps) {
            MarkerSet set = map.getMarkerSets().get(MARKER_SET_ID);
            if (set == null) {
                set = new MarkerSet(MARKER_SET_ID);
                set.setLabel("Chunk Loaders");
                set.setToggleable(true);
                map.getMarkerSets().put(MARKER_SET_ID, set);
            }
            markerSets.put(map.getId(), set);
        }
    }

    private void updateMarkers() {
        if (api == null) {
            return;
        }

        refreshMarkerSets();
        for (MarkerSet set : markerSets.values()) {
            set.getMarkers().clear();
        }

        for (World world : Bukkit.getWorlds()) {
            Optional<BlueMapWorld> optionalWorld = api.getWorld(world);
            if (optionalWorld.isEmpty()) {
                continue;
            }
            BlueMapWorld blueWorld = optionalWorld.get();
            Collection<BlueMapMap> maps = blueWorld.getMaps();
            for (BlueMapMap map : maps) {
                MarkerSet set = map.getMarkerSets().get(MARKER_SET_ID);
                if (set == null) {
                    set = new MarkerSet(MARKER_SET_ID);
                    set.setLabel("Chunk Loaders");
                    set.setToggleable(true);
                    map.getMarkerSets().put(MARKER_SET_ID, set);
                }
                markerSets.put(map.getId(), set);
                addSpawnMarker(world, set);
                addLoaderMarkers(world, set);
            }
        }
    }

    private void addLoaderMarkers(World world, MarkerSet set) {
        for (ChunkLoaderLocation loader : manager.getLoaders(world.getUID())) {
            String id = LOADER_MARKER_PREFIX + loader.worldId() + "_" + loader.x() + "_" + loader.y() + "_" + loader.z();
            POIMarker marker = new POIMarker(id, new Vector3d(loader.x() + 0.5, loader.y() + 0.5, loader.z() + 0.5));
            marker.setLabel("Chunk Loader (" + world.getName() + ")");
            set.getMarkers().put(id, marker);
        }
    }

    private void addSpawnMarker(World world, MarkerSet set) {
        UUID worldId = world.getUID();
        String markerId = SPAWN_MARKER_ID_PREFIX + worldId;
        int radius = plugin.getLoaderRadius();
        int spawnChunkX = world.getSpawnLocation().getChunk().getX();
        int spawnChunkZ = world.getSpawnLocation().getChunk().getZ();
        double minX = (spawnChunkX - radius) * 16.0;
        double maxX = (spawnChunkX + radius + 1) * 16.0;
        double minZ = (spawnChunkZ - radius) * 16.0;
        double maxZ = (spawnChunkZ + radius + 1) * 16.0;
        Shape shape = Shape.createRect(minX, minZ, maxX, maxZ);
        ShapeMarker marker = new ShapeMarker(markerId, shape, (float) world.getSpawnLocation().getY());
        marker.setLabel("Zone de spawn");
        marker.setFillColor(new Color(255, 0, 0, 0.35f));
        marker.setLineColor(new Color(255, 0, 0, 1.0f));
        marker.setDepthTestEnabled(false);
        set.getMarkers().put(markerId, marker);
    }
}
