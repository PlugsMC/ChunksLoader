package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderLocation;
import bout2p1_ograines.chunksloader.ChunkLoaderManager;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public class BlueMapIntegration implements MapIntegration {

    private static final String MARKER_SET_ID = "chunksloader";
    private static final String MARKER_SET_LABEL = "Chunk Loaders";
    private static final String SPAWN_MARKER_ID_PREFIX = "spawn_";
    private static final String LOADER_MARKER_PREFIX = "loader_";
    private static final String LOADER_AREA_SUFFIX = "_area";

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

        try {
            BlueMapAPI.onEnable(enableListener);
            BlueMapAPI.onDisable(disableListener);
            BlueMapAPI.getInstance().ifPresent(instance ->
                    Bukkit.getScheduler().runTask(plugin, () -> handleEnable(instance)));
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'initialiser l'intégration BlueMap", throwable);
            unregisterListeners();
            return false;
        }
    }

    @Override
    public void shutdown() {
        unregisterListeners();
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

        Runnable task = () -> {
            if (api == null) {
                return;
            }
            if (world != null) {
                refreshMarkers(List.of(world), false);
            } else {
                refreshMarkers(Bukkit.getWorlds(), true);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void handleEnable(BlueMapAPI apiInstance) {
        this.api = apiInstance;
        markerSets.clear();
        refreshMarkers(Bukkit.getWorlds(), true);
    }

    private void handleDisable() {
        if (api == null) {
            markerSets.clear();
            return;
        }

        try {
            for (BlueMapMap map : api.getMaps()) {
                map.getMarkerSets().remove(MARKER_SET_ID);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible de nettoyer les marqueurs BlueMap", throwable);
        } finally {
            markerSets.clear();
            api = null;
        }
    }

    private void unregisterListeners() {
        if (enableListener != null) {
            try {
                BlueMapAPI.unregisterListener(enableListener);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.FINE, "Impossible de désinscrire le listener BlueMap (enable)", throwable);
            }
            enableListener = null;
        }

        if (disableListener != null) {
            try {
                BlueMapAPI.unregisterListener(disableListener);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.FINE, "Impossible de désinscrire le listener BlueMap (disable)", throwable);
            }
            disableListener = null;
        }
    }

    private void refreshMarkers(Collection<World> worlds, boolean cleanMissingMaps) {
        if (api == null) {
            return;
        }

        Set<String> seenMaps = cleanMissingMaps ? new HashSet<>() : null;

        for (World world : worlds) {
            if (world == null) {
                continue;
            }

            api.getWorld(world).ifPresent(blueWorld -> updateWorldMarkers(world, blueWorld, seenMaps));
        }

        if (cleanMissingMaps && seenMaps != null) {
            markerSets.entrySet().removeIf(entry -> {
                if (seenMaps.contains(entry.getKey())) {
                    return false;
                }

                try {
                    api.getMap(entry.getKey()).ifPresent(map -> map.getMarkerSets().remove(MARKER_SET_ID));
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.FINER,
                            "Impossible de retirer le calque BlueMap obsolète " + entry.getKey(),
                            throwable);
                }
                return true;
            });
        }
    }

    private void updateWorldMarkers(World bukkitWorld, BlueMapWorld blueWorld, Set<String> seenMaps) {
        for (BlueMapMap map : blueWorld.getMaps()) {
            MarkerSet markerSet = ensureMarkerSet(map);
            if (markerSet == null) {
                continue;
            }

            if (seenMaps != null) {
                seenMaps.add(map.getId());
            }

            Map<String, Marker> markers = markerSet.getMarkers();
            markers.clear();
            addSpawnMarker(bukkitWorld, markers);
            addLoaderMarkers(bukkitWorld, markers);
        }
    }

    private MarkerSet ensureMarkerSet(BlueMapMap map) {
        MarkerSet markerSet = map.getMarkerSets().get(MARKER_SET_ID);
        if (markerSet == null) {
            markerSet = new MarkerSet(MARKER_SET_LABEL);
            markerSet.setToggleable(true);
            markerSet.setDefaultHidden(false);
            map.getMarkerSets().put(MARKER_SET_ID, markerSet);
        } else {
            markerSet.setLabel(MARKER_SET_LABEL);
            markerSet.setToggleable(true);
            markerSet.setDefaultHidden(false);
        }

        markerSets.put(map.getId(), markerSet);
        return markerSet;
    }

    private void addLoaderMarkers(World world, Map<String, Marker> markers) {
        for (ChunkLoaderLocation loader : manager.getLoaders(world.getUID())) {
            if (!manager.isLoaderActive(loader)) {
                continue;
            }

            String label = "Chunk Loader (" + world.getName() + ")";
            String markerId = LOADER_MARKER_PREFIX + loader.worldId() + "_" + loader.x() + "_" + loader.y() + "_" + loader.z();

            POIMarker poiMarker = new POIMarker(label, new Vector3d(loader.x() + 0.5, loader.y() + 0.5, loader.z() + 0.5));
            poiMarker.setDetail(buildLoaderDetail(world, loader));
            markers.put(markerId, poiMarker);

            addLoaderAreaMarker(world, loader, markers, markerId, label);
        }
    }

    private void addLoaderAreaMarker(World world,
                                     ChunkLoaderLocation loader,
                                     Map<String, Marker> markers,
                                     String baseId,
                                     String label) {
        int radius = plugin.getMapRadius();
        if (radius <= 0) {
            return;
        }

        int chunkX = Math.floorDiv(loader.x(), 16);
        int chunkZ = Math.floorDiv(loader.z(), 16);

        double minX = (chunkX - radius) * 16.0;
        double maxX = (chunkX + radius + 1) * 16.0;
        double minZ = (chunkZ - radius) * 16.0;
        double maxZ = (chunkZ + radius + 1) * 16.0;

        Shape shape = Shape.createRect(minX, minZ, maxX, maxZ);
        float y = (float) (loader.y() + 1);
        int size = radius * 2 + 1;
        ShapeMarker marker = new ShapeMarker(label + " - Zone de " + size + "x" + size + " chunks", shape, y);
        marker.setFillColor(new Color(85, 255, 85, 0.2f));
        marker.setLineColor(new Color(85, 255, 85, 0.9f));
        marker.setDepthTestEnabled(false);
        marker.setDetail(buildLoaderAreaDetail(world, loader, radius));
        markers.put(baseId + LOADER_AREA_SUFFIX, marker);
    }

    private void addSpawnMarker(World world, Map<String, Marker> markers) {
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
        float y = (float) world.getSpawnLocation().getY();
        ShapeMarker marker = new ShapeMarker("Zone de spawn", shape, y);
        marker.setFillColor(new Color(255, 85, 85, 0.35f));
        marker.setLineColor(new Color(255, 85, 85, 1.0f));
        marker.setDepthTestEnabled(false);
        marker.setDetail(buildSpawnDetail(world, radius));

        markers.put(markerId, marker);
    }

    private String buildLoaderDetail(World world, ChunkLoaderLocation loader) {
        int chunkX = Math.floorDiv(loader.x(), 16);
        int chunkZ = Math.floorDiv(loader.z(), 16);
        return new StringBuilder()
                .append("<strong>Chunk Loader</strong><br/>")
                .append("Monde : ").append(escape(world.getName())).append("<br/>")
                .append("Chunk : ").append(chunkX).append(", ").append(chunkZ).append("<br/>")
                .append("Position : ").append(loader.x()).append(", ")
                .append(loader.y()).append(", ").append(loader.z()).append("<br/>")
                .append("Rayon actif : ").append(plugin.getLoaderRadius()).append(" chunk(s)")
                .toString();
    }

    private String buildLoaderAreaDetail(World world, ChunkLoaderLocation loader, int radius) {
        int chunkX = Math.floorDiv(loader.x(), 16);
        int chunkZ = Math.floorDiv(loader.z(), 16);
        return new StringBuilder()
                .append("<strong>Zone de chargement</strong><br/>")
                .append("Monde : ").append(escape(world.getName())).append("<br/>")
                .append("Chunk : ").append(chunkX).append(", ").append(chunkZ).append("<br/>")
                .append("Rayon affiché : ").append(radius).append(" chunk(s)")
                .toString();
    }

    private String buildSpawnDetail(World world, int radius) {
        int chunkX = world.getSpawnLocation().getChunk().getX();
        int chunkZ = world.getSpawnLocation().getChunk().getZ();
        return new StringBuilder()
                .append("<strong>Zone de spawn</strong><br/>")
                .append("Monde : ").append(escape(world.getName())).append("<br/>")
                .append("Chunk : ").append(chunkX).append(", ").append(chunkZ).append("<br/>")
                .append("Rayon protégé : ").append(radius).append(" chunk(s)")
                .toString();
    }

    private String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
