package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderLocation;
import bout2p1_ograines.chunksloader.ChunkLoaderManager;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class DynmapIntegration implements MapIntegration {
    private static final String MARKER_SET_ID = "chunksloader";
    private static final String SPAWN_MARKER_ID_PREFIX = "spawn_";
    private static final String LOADER_MARKER_PREFIX = "loader_";

    private final ChunksLoaderPlugin plugin;
    private final ChunkLoaderManager manager;

    private Object markerAPI;
    private Object markerSet;
    private Object markerIcon;
    private boolean active;

    private Class<?> markerApiClass;
    private Class<?> markerSetClass;
    private Class<?> areaMarkerClass;
    private Class<?> markerIconClass;
    private Method deleteMethod;
    private Method createMarkerMethod;
    private Method createAreaMarkerMethod;
    private Method setFillStyleMethod;
    private Method setLineStyleMethod;

    public DynmapIntegration(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
    }

    @Override
    public boolean initialize() {
        Plugin dynmapPlugin = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmapPlugin == null) {
            return false;
        }

        try {
            Class<?> dynmapApiClass = Class.forName("org.dynmap.DynmapAPI");
            if (!dynmapApiClass.isInstance(dynmapPlugin)) {
                plugin.getLogger().warning("Plugin dynmap détecté mais API incompatible.");
                return false;
            }

            markerApiClass = Class.forName("org.dynmap.markers.MarkerAPI");
            markerSetClass = Class.forName("org.dynmap.markers.MarkerSet");
            areaMarkerClass = Class.forName("org.dynmap.markers.AreaMarker");
            markerIconClass = Class.forName("org.dynmap.markers.MarkerIcon");
            deleteMethod = Class.forName("org.dynmap.markers.GenericMarker").getMethod("deleteMarker");
            createMarkerMethod = markerSetClass.getMethod("createMarker", String.class, String.class, String.class,
                    double.class, double.class, double.class, markerIconClass, boolean.class);
            createAreaMarkerMethod = markerSetClass.getMethod("createAreaMarker", String.class, String.class, boolean.class,
                    String.class, double[].class, double[].class, boolean.class);
            setFillStyleMethod = areaMarkerClass.getMethod("setFillStyle", double.class, int.class);
            setLineStyleMethod = areaMarkerClass.getMethod("setLineStyle", int.class, double.class, int.class);

            markerAPI = dynmapApiClass.getMethod("getMarkerAPI").invoke(dynmapPlugin);
            if (markerAPI == null) {
                plugin.getLogger().warning("Impossible d'obtenir l'API des marqueurs Dynmap.");
                return false;
            }

            markerSet = markerApiClass.getMethod("getMarkerSet", String.class).invoke(markerAPI, MARKER_SET_ID);
            if (markerSet == null) {
                markerSet = markerApiClass
                        .getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class)
                        .invoke(markerAPI, MARKER_SET_ID, "Chunk Loaders", null, true);
            } else {
                markerSetClass.getMethod("setMarkerSetLabel", String.class).invoke(markerSet, "Chunk Loaders");
            }

            if (markerSet == null) {
                plugin.getLogger().warning("Impossible de créer le calque Dynmap pour les chunk loaders.");
                return false;
            }

            markerIcon = markerApiClass.getMethod("getMarkerIcon", String.class).invoke(markerAPI, "default");
            if (markerIcon == null) {
                Set<?> icons = (Set<?>) markerApiClass.getMethod("getMarkerIcons").invoke(markerAPI);
                if (icons != null && !icons.isEmpty()) {
                    markerIcon = icons.iterator().next();
                }
            }

            active = true;
            return true;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Échec de l'initialisation Dynmap : " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void shutdown() {
        if (!active) {
            return;
        }
        Runnable task = () -> {
            try {
                clearMarkers();
                if (markerSet != null) {
                    markerSetClass.getMethod("deleteMarkerSet").invoke(markerSet);
                }
            } catch (ReflectiveOperationException exception) {
                plugin.getLogger().warning("Impossible de nettoyer Dynmap : " + exception.getMessage());
            } finally {
                active = false;
                markerAPI = null;
                markerSet = null;
                markerIcon = null;
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void onLoadersChanged(World world) {
        if (!active) {
            return;
        }
        Runnable updateTask = this::updateMarkers;
        if (Bukkit.isPrimaryThread()) {
            updateTask.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, updateTask);
        }
    }

    private void updateMarkers() {
        if (!active || markerSet == null) {
            return;
        }

        try {
            clearMarkers();
            for (World world : Bukkit.getWorlds()) {
                addSpawnMarker(world);
                for (ChunkLoaderLocation loader : manager.getLoaders(world.getUID())) {
                    addLoaderMarker(world, loader);
                }
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Erreur lors de la mise à jour Dynmap : " + exception.getMessage());
        }
    }

    private void clearMarkers() throws ReflectiveOperationException {
        if (markerSet == null) {
            return;
        }
        Set<?> markers = new HashSet<>((Set<?>) markerSetClass.getMethod("getMarkers").invoke(markerSet));
        for (Object marker : markers) {
            deleteMethod.invoke(marker);
        }
        Set<?> areas = new HashSet<>((Set<?>) markerSetClass.getMethod("getAreaMarkers").invoke(markerSet));
        for (Object area : areas) {
            deleteMethod.invoke(area);
        }
    }

    private void addLoaderMarker(World world, ChunkLoaderLocation loader) throws ReflectiveOperationException {
        if (markerSet == null) {
            return;
        }
        if (!manager.isLoaderActive(loader)) {
            return;
        }
        String markerId = LOADER_MARKER_PREFIX + loader.worldId() + "_" + loader.x() + "_" + loader.y() + "_" + loader.z();
        String label = "Chunk Loader (" + world.getName() + ")";
        createMarkerMethod.invoke(markerSet, markerId, label, world.getName(), loader.x() + 0.5, loader.y() + 0.5,
                loader.z() + 0.5, markerIcon, false);
    }

    private void addSpawnMarker(World world) throws ReflectiveOperationException {
        if (markerSet == null) {
            return;
        }
        int radius = plugin.getLoaderRadius();
        int spawnChunkX = world.getSpawnLocation().getChunk().getX();
        int spawnChunkZ = world.getSpawnLocation().getChunk().getZ();
        double minX = (spawnChunkX - radius) * 16.0;
        double maxX = (spawnChunkX + radius + 1) * 16.0;
        double minZ = (spawnChunkZ - radius) * 16.0;
        double maxZ = (spawnChunkZ + radius + 1) * 16.0;
        double[] xCorners = new double[] {minX, maxX, maxX, minX};
        double[] zCorners = new double[] {minZ, minZ, maxZ, maxZ};
        String markerId = SPAWN_MARKER_ID_PREFIX + world.getUID();
        Object area = createAreaMarkerMethod.invoke(markerSet, markerId, "Zone de spawn", false, world.getName(),
                xCorners, zCorners, false);
        if (area != null) {
            setFillStyleMethod.invoke(area, 0.35D, 0xAA0000);
            setLineStyleMethod.invoke(area, 3, 0.8D, 0xFF0000);
        }
    }
}
