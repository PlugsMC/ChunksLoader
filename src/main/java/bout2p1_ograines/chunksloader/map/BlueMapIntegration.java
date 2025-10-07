package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderLocation;
import bout2p1_ograines.chunksloader.ChunkLoaderManager;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BlueMapIntegration implements MapIntegration {
    private static final String MARKER_SET_ID = "chunksloader";
    private static final String SPAWN_MARKER_ID_PREFIX = "spawn_";
    private static final String LOADER_MARKER_PREFIX = "loader_";
    private static final String LOADER_AREA_MARKER_SUFFIX = "_area";

    private final ChunksLoaderPlugin plugin;
    private final ChunkLoaderManager manager;

    private final Map<String, Object> markerSets = new HashMap<>();

    private Object api;
    private Consumer<Object> enableListener;
    private Consumer<Object> disableListener;

    private Method apiOnEnableMethod;
    private Method apiOnDisableMethod;
    private Method apiUnregisterListenerMethod;
    private Method apiGetInstanceMethod;
    private Method apiGetMapsMethod;
    private Method apiGetWorldMethod;

    private Method worldGetMapsMethod;
    private Method mapGetMarkerSetsMethod;
    private Method mapGetIdMethod;

    private Constructor<?> markerSetConstructor;
    private Method markerSetSetLabelMethod;
    private Method markerSetSetToggleableMethod;
    private Method markerSetGetMarkersMethod;
    private Method markerSetSetDirtyMethod;

    private Constructor<?> poiMarkerConstructor;
    private Constructor<?> vector3dConstructor;
    private Method poiMarkerSetLabelMethod;

    private Method shapeCreateRectMethod;
    private Constructor<?> shapeMarkerConstructor;
    private Method shapeMarkerSetLabelMethod;
    private Method shapeMarkerSetFillColorMethod;
    private Method shapeMarkerSetLineColorMethod;
    private Method shapeMarkerSetDepthTestMethod;

    private Constructor<?> colorConstructor;

    public BlueMapIntegration(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
    }

    @Override
    public boolean initialize() {
        if (!setupReflection()) {
            plugin.getLogger().info("BlueMap API introuvable, l'intégration est désactivée.");
            return false;
        }

        enableListener = apiInstance -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(apiInstance));
        disableListener = apiInstance -> Bukkit.getScheduler().runTask(plugin, this::handleDisable);

        try {
            apiOnEnableMethod.invoke(null, enableListener);
            apiOnDisableMethod.invoke(null, disableListener);
            Optional<?> optionalApi = (Optional<?>) apiGetInstanceMethod.invoke(null);
            optionalApi.ifPresent(existing -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(existing)));
            return true;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'initialiser l'intégration BlueMap", exception);
            return false;
        }
    }

    @Override
    public void shutdown() {
        if (enableListener != null) {
            try {
                apiUnregisterListenerMethod.invoke(null, enableListener);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                plugin.getLogger().log(Level.WARNING, "Impossible de désinscrire le listener BlueMap", exception);
            }
        }
        if (disableListener != null) {
            try {
                apiUnregisterListenerMethod.invoke(null, disableListener);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                plugin.getLogger().log(Level.WARNING, "Impossible de désinscrire le listener BlueMap", exception);
            }
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

    private void handleEnable(Object apiInstance) {
        this.api = apiInstance;
        refreshMarkerSets();
        updateMarkers();
    }

    private void handleDisable() {
        if (api != null) {
            try {
                Collection<?> maps = (Collection<?>) apiGetMapsMethod.invoke(api);
                for (Object map : maps) {
                    Map<String, Object> markerSetMap = getMarkerSetMap(map);
                    markerSetMap.remove(MARKER_SET_ID);
                }
            } catch (IllegalAccessException | InvocationTargetException exception) {
                plugin.getLogger().log(Level.WARNING, "Impossible de nettoyer les marqueurs BlueMap", exception);
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
        try {
            Collection<?> maps = (Collection<?>) apiGetMapsMethod.invoke(api);
            for (Object map : maps) {
                Map<String, Object> markerSetMap = getMarkerSetMap(map);
                Object set = markerSetMap.get(MARKER_SET_ID);
                if (set == null) {
                    set = createMarkerSet();
                    markerSetMap.put(MARKER_SET_ID, set);
                }
                String mapId = (String) mapGetIdMethod.invoke(map);
                markerSets.put(mapId, set);
            }
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException exception) {
            plugin.getLogger().log(Level.WARNING, "Impossible de rafraîchir les marqueurs BlueMap", exception);
        }
    }

    private void updateMarkers() {
        if (api == null) {
            return;
        }

        refreshMarkerSets();
        try {
            for (Object set : markerSets.values()) {
                getMarkers(set).clear();
                markMarkerSetDirty(set);
            }

            for (World world : Bukkit.getWorlds()) {
                Optional<?> optionalWorld = (Optional<?>) apiGetWorldMethod.invoke(api, world);
                if (optionalWorld.isEmpty()) {
                    continue;
                }
                Object blueWorld = optionalWorld.get();
                Collection<?> maps = (Collection<?>) worldGetMapsMethod.invoke(blueWorld);
                for (Object map : maps) {
                    Map<String, Object> markerSetMap = getMarkerSetMap(map);
                    Object set = markerSetMap.get(MARKER_SET_ID);
                    if (set == null) {
                        set = createMarkerSet();
                        markerSetMap.put(MARKER_SET_ID, set);
                    }
                    String mapId = (String) mapGetIdMethod.invoke(map);
                    markerSets.put(mapId, set);
                    addSpawnMarker(world, set);
                    addLoaderMarkers(world, set);
                    markMarkerSetDirty(set);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException exception) {
            plugin.getLogger().log(Level.WARNING, "Impossible de mettre à jour les marqueurs BlueMap", exception);
        }
    }

    private void addLoaderMarkers(World world, Object markerSet) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        Map<String, Object> markers = getMarkers(markerSet);
        for (ChunkLoaderLocation loader : manager.getLoaders(world.getUID())) {
            if (!manager.isLoaderActive(loader)) {
                continue;
            }
            String id = LOADER_MARKER_PREFIX + loader.worldId() + "_" + loader.x() + "_" + loader.y() + "_" + loader.z();
            Object position = vector3dConstructor.newInstance(loader.x() + 0.5, loader.y() + 0.5, loader.z() + 0.5);
            Object poiMarker = poiMarkerConstructor.newInstance(id, position);
            String label = "Chunk Loader (" + world.getName() + ")";
            poiMarkerSetLabelMethod.invoke(poiMarker, label);
            markers.put(id, poiMarker);

            addLoaderAreaMarker(world, loader, markers, id, label);
        }
    }

    private void addSpawnMarker(World world, Object markerSet) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        UUID worldId = world.getUID();
        String markerId = SPAWN_MARKER_ID_PREFIX + worldId;
        int radius = plugin.getLoaderRadius();
        int spawnChunkX = world.getSpawnLocation().getChunk().getX();
        int spawnChunkZ = world.getSpawnLocation().getChunk().getZ();
        double minX = (spawnChunkX - radius) * 16.0;
        double maxX = (spawnChunkX + radius + 1) * 16.0;
        double minZ = (spawnChunkZ - radius) * 16.0;
        double maxZ = (spawnChunkZ + radius + 1) * 16.0;

        Object shape = shapeCreateRectMethod.invoke(null, minX, minZ, maxX, maxZ);
        float y = (float) world.getSpawnLocation().getY();
        Object marker = shapeMarkerConstructor.newInstance(markerId, shape, y);
        shapeMarkerSetLabelMethod.invoke(marker, "Zone de spawn");
        Object fillColor = colorConstructor.newInstance(255, 85, 85, 0.35f);
        Object lineColor = colorConstructor.newInstance(255, 85, 85, 1.0f);
        shapeMarkerSetFillColorMethod.invoke(marker, fillColor);
        shapeMarkerSetLineColorMethod.invoke(marker, lineColor);
        shapeMarkerSetDepthTestMethod.invoke(marker, false);
        getMarkers(markerSet).put(markerId, marker);
    }

    private void addLoaderAreaMarker(World world,
                                     ChunkLoaderLocation loader,
                                     Map<String, Object> markers,
                                     String baseMarkerId,
                                     String label)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        int mapRadius = plugin.getMapRadius();
        if (mapRadius <= 0) {
            return;
        }

        int chunkX = Math.floorDiv(loader.x(), 16);
        int chunkZ = Math.floorDiv(loader.z(), 16);
        double minX = (chunkX - mapRadius) * 16.0;
        double maxX = (chunkX + mapRadius + 1) * 16.0;
        double minZ = (chunkZ - mapRadius) * 16.0;
        double maxZ = (chunkZ + mapRadius + 1) * 16.0;

        Object shape = shapeCreateRectMethod.invoke(null, minX, minZ, maxX, maxZ);
        float y = (float) (loader.y() + 1);
        Object areaMarker = shapeMarkerConstructor.newInstance(baseMarkerId + LOADER_AREA_MARKER_SUFFIX, shape, y);
        String areaLabel = label + " - Zone de " + (mapRadius * 2 + 1) + "x" + (mapRadius * 2 + 1) + " chunks";
        shapeMarkerSetLabelMethod.invoke(areaMarker, areaLabel);
        Object fillColor = colorConstructor.newInstance(85, 255, 85, 0.2f);
        Object lineColor = colorConstructor.newInstance(85, 255, 85, 0.9f);
        shapeMarkerSetFillColorMethod.invoke(areaMarker, fillColor);
        shapeMarkerSetLineColorMethod.invoke(areaMarker, lineColor);
        shapeMarkerSetDepthTestMethod.invoke(areaMarker, false);
        markers.put(baseMarkerId + LOADER_AREA_MARKER_SUFFIX, areaMarker);
    }

    private boolean setupReflection() {
        try {
            Class<?> apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            Class<?> worldClass = Class.forName("de.bluecolored.bluemap.api.BlueMapWorld");
            Class<?> mapClass = Class.forName("de.bluecolored.bluemap.api.BlueMapMap");
            Class<?> markerSetClass = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
            Class<?> poiMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.POIMarker");
            Class<?> shapeClass = Class.forName("de.bluecolored.bluemap.api.math.Shape");
            Class<?> shapeMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.ShapeMarker");
            Class<?> colorClass = Class.forName("de.bluecolored.bluemap.api.math.Color");

            Class<?> vectorClass;
            try {
                vectorClass = Class.forName("com.flowpowered.math.vector.Vector3d");
            } catch (ClassNotFoundException ignored) {
                vectorClass = Class.forName("de.bluecolored.bluemap.api.math.Vector3d");
            }

            apiOnEnableMethod = apiClass.getMethod("onEnable", Consumer.class);
            apiOnDisableMethod = apiClass.getMethod("onDisable", Consumer.class);
            apiUnregisterListenerMethod = apiClass.getMethod("unregisterListener", Consumer.class);
            apiGetInstanceMethod = apiClass.getMethod("getInstance");
            apiGetMapsMethod = apiClass.getMethod("getMaps");
            apiGetWorldMethod = apiClass.getMethod("getWorld", Object.class);

            worldGetMapsMethod = worldClass.getMethod("getMaps");
            mapGetMarkerSetsMethod = mapClass.getMethod("getMarkerSets");
            mapGetIdMethod = mapClass.getMethod("getId");

            markerSetConstructor = markerSetClass.getConstructor(String.class);
            markerSetSetLabelMethod = markerSetClass.getMethod("setLabel", String.class);
            markerSetSetToggleableMethod = markerSetClass.getMethod("setToggleable", boolean.class);
            markerSetGetMarkersMethod = markerSetClass.getMethod("getMarkers");
            markerSetSetDirtyMethod = resolveMarkerSetDirtyMethod(markerSetClass);

            vector3dConstructor = vectorClass.getConstructor(double.class, double.class, double.class);
            poiMarkerConstructor = poiMarkerClass.getConstructor(String.class, vectorClass);
            poiMarkerSetLabelMethod = poiMarkerClass.getMethod("setLabel", String.class);

            shapeCreateRectMethod = shapeClass.getMethod("createRect", double.class, double.class, double.class, double.class);
            shapeMarkerConstructor = shapeMarkerClass.getConstructor(String.class, shapeClass, float.class);
            shapeMarkerSetLabelMethod = shapeMarkerClass.getMethod("setLabel", String.class);
            shapeMarkerSetFillColorMethod = shapeMarkerClass.getMethod("setFillColor", colorClass);
            shapeMarkerSetLineColorMethod = shapeMarkerClass.getMethod("setLineColor", colorClass);
            shapeMarkerSetDepthTestMethod = shapeMarkerClass.getMethod("setDepthTestEnabled", boolean.class);

            colorConstructor = colorClass.getConstructor(int.class, int.class, int.class, float.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            plugin.getLogger().log(Level.FINE, "BlueMap API manquante", exception);
            return false;
        }
    }

    private Map<String, Object> getMarkerSetMap(Object map)
            throws IllegalAccessException, InvocationTargetException {
        return (Map<String, Object>) mapGetMarkerSetsMethod.invoke(map);
    }

    private Map<String, Object> getMarkers(Object markerSet)
            throws IllegalAccessException, InvocationTargetException {
        return (Map<String, Object>) markerSetGetMarkersMethod.invoke(markerSet);
    }

    private Object createMarkerSet()
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object markerSet = markerSetConstructor.newInstance(MARKER_SET_ID);
        markerSetSetLabelMethod.invoke(markerSet, "Chunk Loaders");
        markerSetSetToggleableMethod.invoke(markerSet, true);
        return markerSet;
    }

    private Method resolveMarkerSetDirtyMethod(Class<?> markerSetClass) {
        try {
            return markerSetClass.getMethod("setDirty");
        } catch (NoSuchMethodException ignored) {
            try {
                return markerSetClass.getMethod("setDirty", boolean.class);
            } catch (NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
    }

    private void markMarkerSetDirty(Object markerSet) {
        if (markerSetSetDirtyMethod == null || markerSet == null) {
            return;
        }
        try {
            if (markerSetSetDirtyMethod.getParameterCount() == 0) {
                markerSetSetDirtyMethod.invoke(markerSet);
            } else {
                markerSetSetDirtyMethod.invoke(markerSet, true);
            }
        } catch (IllegalAccessException | InvocationTargetException exception) {
            plugin.getLogger().log(Level.FINER, "Impossible de marquer le calque BlueMap comme modifié", exception);
        }
    }
}
