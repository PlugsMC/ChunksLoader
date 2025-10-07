package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderLocation;
import bout2p1_ograines.chunksloader.ChunkLoaderManager;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

@SuppressWarnings({"rawtypes", "unchecked"})
public class BlueMapIntegration implements MapIntegration {

    private static final String MARKER_SET_ID = "chunksloader";
    private static final String SPAWN_MARKER_ID_PREFIX = "spawn_";
    private static final String LOADER_MARKER_PREFIX = "loader_";
    private static final String LOADER_AREA_SUFFIX = "_area";

    private final ChunksLoaderPlugin plugin;
    private final ChunkLoaderManager manager;

    private final Map<String, Object> markerSets = new HashMap<>();

    private BlueMapReflection reflection;
    private Object apiInstance;
    private Consumer<Object> enableListener;
    private Consumer<Object> disableListener;

    public BlueMapIntegration(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getManager();
    }

    @Override
    public boolean initialize() {
        reflection = BlueMapReflection.create(plugin);
        if (reflection == null) {
            return false;
        }

        enableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(api));
        disableListener = api -> Bukkit.getScheduler().runTask(plugin, this::handleDisable);

        try {
            reflection.registerEnableListener(enableListener);
            reflection.registerDisableListener(disableListener);
            reflection.getCurrentInstance().ifPresent(api -> Bukkit.getScheduler().runTask(plugin, () -> handleEnable(api)));
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'initialiser l'intégration BlueMap", throwable);
            unregisterListeners();
            reflection = null;
            return false;
        }

        return true;
    }

    @Override
    public void shutdown() {
        if (reflection == null) {
            return;
        }

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
        if (apiInstance == null || reflection == null) {
            return;
        }

        Runnable task = this::updateMarkers;
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void unregisterListeners() {
        if (reflection == null) {
            return;
        }

        if (enableListener != null) {
            try {
                reflection.unregisterListener(enableListener);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Impossible de désinscrire le listener BlueMap", throwable);
            }
        }

        if (disableListener != null) {
            try {
                reflection.unregisterListener(disableListener);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Impossible de désinscrire le listener BlueMap", throwable);
            }
        }
    }

    private void handleEnable(Object apiInstance) {
        if (reflection == null) {
            return;
        }

        this.apiInstance = apiInstance;
        rebuildMarkerSets();
        updateMarkers();
    }

    private void handleDisable() {
        if (reflection == null || apiInstance == null) {
            markerSets.clear();
            apiInstance = null;
            return;
        }

        try {
            for (Object map : reflection.getMaps(apiInstance)) {
                reflection.removeMarkerSet(map, MARKER_SET_ID);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible de nettoyer les marqueurs BlueMap", throwable);
        } finally {
            markerSets.clear();
            apiInstance = null;
        }
    }

    private void rebuildMarkerSets() {
        if (apiInstance == null || reflection == null) {
            return;
        }

        markerSets.clear();

        try {
            for (Object map : reflection.getMaps(apiInstance)) {
                String mapId = reflection.getMapId(map);
                Object markerSet = reflection.ensureMarkerSet(map, MARKER_SET_ID, "Chunk Loaders");
                markerSets.put(mapId, markerSet);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible de préparer les calques BlueMap", throwable);
        }
    }

    private void updateMarkers() {
        if (apiInstance == null || reflection == null) {
            return;
        }

        Set<String> seenMaps = new HashSet<>();

        try {
            for (World world : Bukkit.getWorlds()) {
                Optional<?> optionalWorld = reflection.getWorld(apiInstance, world);
                if (optionalWorld.isEmpty()) {
                    continue;
                }

                Object blueWorld = optionalWorld.get();
                for (Object map : reflection.getWorldMaps(blueWorld)) {
                    String mapId = reflection.getMapId(map);
                    Object markerSet = markerSets.computeIfAbsent(mapId, id -> {
                        try {
                            return reflection.ensureMarkerSet(map, MARKER_SET_ID, "Chunk Loaders");
                        } catch (Throwable throwable) {
                            plugin.getLogger().log(Level.WARNING, "Impossible de créer le calque BlueMap " + id, throwable);
                            return null;
                        }
                    });

                    if (markerSet == null) {
                        continue;
                    }

                    seenMaps.add(mapId);
                    reflection.clearMarkerSet(markerSet);
                    addSpawnMarker(world, markerSet);
                    addLoaderMarkers(world, markerSet);
                    reflection.markDirty(markerSet);
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Impossible de mettre à jour les marqueurs BlueMap", throwable);
        }

        // Clean up marker sets that are no longer present on any map.
        markerSets.entrySet().removeIf(entry -> {
            if (seenMaps.contains(entry.getKey())) {
                return false;
            }

            try {
                reflection.removeMarkerSetById(entry.getKey(), MARKER_SET_ID, apiInstance);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.FINER, "Impossible de retirer le calque BlueMap obsolète " + entry.getKey(), throwable);
            }
            return true;
        });
    }

    private void addLoaderMarkers(World world, Object markerSet) throws Throwable {
        Map<String, Object> markers = reflection.getMarkers(markerSet);

        for (ChunkLoaderLocation loader : manager.getLoaders(world.getUID())) {
            if (!manager.isLoaderActive(loader)) {
                continue;
            }

            String markerId = LOADER_MARKER_PREFIX + loader.worldId() + "_" + loader.x() + "_" + loader.y() + "_" + loader.z();
            Object position = reflection.createVector(loader.x() + 0.5, loader.y() + 0.5, loader.z() + 0.5);
            Object poi = reflection.createPoiMarker(markerId, position, "Chunk Loader (" + world.getName() + ")");
            markers.put(markerId, poi);

            addLoaderAreaMarker(world, loader, markers, markerId, "Chunk Loader (" + world.getName() + ")");
        }
    }

    private void addLoaderAreaMarker(World world,
                                     ChunkLoaderLocation loader,
                                     Map<String, Object> markers,
                                     String baseId,
                                     String label) throws Throwable {
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

        Object shape = reflection.createRectangle(minX, minZ, maxX, maxZ);
        float y = (float) (loader.y() + 1);
        Object marker = reflection.createShapeMarker(baseId + LOADER_AREA_SUFFIX, shape, y);
        reflection.configureShapeMarker(marker,
                label + " - Zone de " + (radius * 2 + 1) + "x" + (radius * 2 + 1) + " chunks",
                reflection.createColor(85, 255, 85, 0.2f),
                reflection.createColor(85, 255, 85, 0.9f));
        markers.put(baseId + LOADER_AREA_SUFFIX, marker);
    }

    private void addSpawnMarker(World world, Object markerSet) throws Throwable {
        UUID worldId = world.getUID();
        String markerId = SPAWN_MARKER_ID_PREFIX + worldId;

        int radius = plugin.getLoaderRadius();
        int spawnChunkX = world.getSpawnLocation().getChunk().getX();
        int spawnChunkZ = world.getSpawnLocation().getChunk().getZ();

        double minX = (spawnChunkX - radius) * 16.0;
        double maxX = (spawnChunkX + radius + 1) * 16.0;
        double minZ = (spawnChunkZ - radius) * 16.0;
        double maxZ = (spawnChunkZ + radius + 1) * 16.0;

        Object shape = reflection.createRectangle(minX, minZ, maxX, maxZ);
        float y = (float) world.getSpawnLocation().getY();
        Object marker = reflection.createShapeMarker(markerId, shape, y);
        reflection.configureShapeMarker(marker,
                "Zone de spawn",
                reflection.createColor(255, 85, 85, 0.35f),
                reflection.createColor(255, 85, 85, 1.0f));

        reflection.getMarkers(markerSet).put(markerId, marker);
    }

    private static final class BlueMapReflection {
        private final ChunksLoaderPlugin plugin;

        private final MethodHandle apiOnEnable;
        private final MethodHandle apiOnDisable;
        private final MethodHandle apiUnregister;
        private final MethodHandle apiGetInstance;
        private final MethodHandle apiGetMaps;
        private final MethodHandle apiGetWorld;
        private final MethodHandle worldGetMaps;
        private final MethodHandle mapGetMarkerSets;
        private final MethodHandle mapGetId;

        private final Constructor<?> markerSetConstructor;
        private final MethodHandle markerSetSetLabel;
        private final MethodHandle markerSetSetToggleable;
        private final MethodHandle markerSetSetDefaultHidden;
        private final MethodHandle markerSetSetHidden;
        private final MethodHandle markerSetGetMarkers;
        private final MethodHandle markerSetSetDirty;

        private final Constructor<?> vectorConstructor;
        private final Constructor<?> poiConstructor;
        private final MethodHandle poiSetLabel;

        private final MethodHandle shapeCreateRect;
        private final Constructor<?> shapeMarkerConstructor;
        private final MethodHandle shapeMarkerSetLabel;
        private final MethodHandle shapeMarkerSetFillColor;
        private final MethodHandle shapeMarkerSetLineColor;
        private final MethodHandle shapeMarkerSetDepthTest;

        private final Constructor<?> colorConstructor;

        private final MethodHandle mapRemoveMarkerSet;

        private final Class<?> apiClass;

        private BlueMapReflection(ChunksLoaderPlugin plugin,
                                   MethodHandle apiOnEnable,
                                   MethodHandle apiOnDisable,
                                   MethodHandle apiUnregister,
                                   MethodHandle apiGetInstance,
                                   MethodHandle apiGetMaps,
                                   MethodHandle apiGetWorld,
                                   MethodHandle worldGetMaps,
                                   MethodHandle mapGetMarkerSets,
                                   MethodHandle mapGetId,
                                   Constructor<?> markerSetConstructor,
                                   MethodHandle markerSetSetLabel,
                                   MethodHandle markerSetSetToggleable,
                                   MethodHandle markerSetSetDefaultHidden,
                                   MethodHandle markerSetSetHidden,
                                   MethodHandle markerSetGetMarkers,
                                   MethodHandle markerSetSetDirty,
                                   Constructor<?> vectorConstructor,
                                   Constructor<?> poiConstructor,
                                   MethodHandle poiSetLabel,
                                   MethodHandle shapeCreateRect,
                                   Constructor<?> shapeMarkerConstructor,
                                   MethodHandle shapeMarkerSetLabel,
                                   MethodHandle shapeMarkerSetFillColor,
                                   MethodHandle shapeMarkerSetLineColor,
                                   MethodHandle shapeMarkerSetDepthTest,
                                   Constructor<?> colorConstructor,
                                   MethodHandle mapRemoveMarkerSet,
                                   Class<?> apiClass) {
            this.plugin = plugin;
            this.apiOnEnable = apiOnEnable;
            this.apiOnDisable = apiOnDisable;
            this.apiUnregister = apiUnregister;
            this.apiGetInstance = apiGetInstance;
            this.apiGetMaps = apiGetMaps;
            this.apiGetWorld = apiGetWorld;
            this.worldGetMaps = worldGetMaps;
            this.mapGetMarkerSets = mapGetMarkerSets;
            this.mapGetId = mapGetId;
            this.markerSetConstructor = markerSetConstructor;
            this.markerSetSetLabel = markerSetSetLabel;
            this.markerSetSetToggleable = markerSetSetToggleable;
            this.markerSetSetDefaultHidden = markerSetSetDefaultHidden;
            this.markerSetSetHidden = markerSetSetHidden;
            this.markerSetGetMarkers = markerSetGetMarkers;
            this.markerSetSetDirty = markerSetSetDirty;
            this.vectorConstructor = vectorConstructor;
            this.poiConstructor = poiConstructor;
            this.poiSetLabel = poiSetLabel;
            this.shapeCreateRect = shapeCreateRect;
            this.shapeMarkerConstructor = shapeMarkerConstructor;
            this.shapeMarkerSetLabel = shapeMarkerSetLabel;
            this.shapeMarkerSetFillColor = shapeMarkerSetFillColor;
            this.shapeMarkerSetLineColor = shapeMarkerSetLineColor;
            this.shapeMarkerSetDepthTest = shapeMarkerSetDepthTest;
            this.colorConstructor = colorConstructor;
            this.mapRemoveMarkerSet = mapRemoveMarkerSet;
            this.apiClass = apiClass;
        }

        static BlueMapReflection create(ChunksLoaderPlugin plugin) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();

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

                MethodHandle apiOnEnable = lookup.findStatic(apiClass, "onEnable", MethodType.methodType(void.class, Consumer.class));
                MethodHandle apiOnDisable = lookup.findStatic(apiClass, "onDisable", MethodType.methodType(void.class, Consumer.class));
                MethodHandle apiUnregister = lookup.findStatic(apiClass, "unregisterListener", MethodType.methodType(void.class, Consumer.class));
                MethodHandle apiGetInstance = lookup.findStatic(apiClass, "getInstance", MethodType.methodType(Optional.class));
                MethodHandle apiGetMaps = lookup.findVirtual(apiClass, "getMaps", MethodType.methodType(Collection.class));
                MethodHandle apiGetWorld = lookup.findVirtual(apiClass, "getWorld", MethodType.methodType(Optional.class, Object.class));
                MethodHandle worldGetMaps = lookup.findVirtual(worldClass, "getMaps", MethodType.methodType(Collection.class));
                MethodHandle mapGetMarkerSets = lookup.findVirtual(mapClass, "getMarkerSets", MethodType.methodType(Map.class));
                MethodHandle mapGetId = lookup.findVirtual(mapClass, "getId", MethodType.methodType(String.class));

                Constructor<?> markerSetConstructor = markerSetClass.getConstructor(String.class);
                MethodHandle markerSetSetLabel = lookup.findVirtual(markerSetClass, "setLabel", MethodType.methodType(void.class, String.class));
                MethodHandle markerSetSetToggleable = lookup.findVirtual(markerSetClass, "setToggleable", MethodType.methodType(void.class, boolean.class));

                MethodHandle markerSetSetDefaultHidden;
                try {
                    markerSetSetDefaultHidden = lookup.findVirtual(markerSetClass, "setDefaultHidden", MethodType.methodType(void.class, boolean.class));
                } catch (NoSuchMethodException exception) {
                    markerSetSetDefaultHidden = null;
                }

                MethodHandle markerSetSetHidden;
                try {
                    markerSetSetHidden = lookup.findVirtual(markerSetClass, "setHidden", MethodType.methodType(void.class, boolean.class));
                } catch (NoSuchMethodException exception) {
                    markerSetSetHidden = null;
                }

                MethodHandle markerSetGetMarkers = lookup.findVirtual(markerSetClass, "getMarkers", MethodType.methodType(Map.class));

                MethodHandle markerSetSetDirty;
                try {
                    markerSetSetDirty = lookup.findVirtual(markerSetClass, "setDirty", MethodType.methodType(void.class));
                } catch (NoSuchMethodException exception) {
                    markerSetSetDirty = lookup.findVirtual(markerSetClass, "setDirty", MethodType.methodType(void.class, boolean.class));
                }

                Constructor<?> vectorConstructor = vectorClass.getConstructor(double.class, double.class, double.class);
                Constructor<?> poiConstructor = poiMarkerClass.getConstructor(String.class, vectorClass);
                MethodHandle poiSetLabel = lookup.findVirtual(poiMarkerClass, "setLabel", MethodType.methodType(void.class, String.class));

                MethodHandle shapeCreateRect = lookup.findStatic(shapeClass, "createRect", MethodType.methodType(shapeClass, double.class, double.class, double.class, double.class));
                Constructor<?> shapeMarkerConstructor = shapeMarkerClass.getConstructor(String.class, shapeClass, float.class);
                MethodHandle shapeMarkerSetLabel = lookup.findVirtual(shapeMarkerClass, "setLabel", MethodType.methodType(void.class, String.class));
                MethodHandle shapeMarkerSetFillColor = lookup.findVirtual(shapeMarkerClass, "setFillColor", MethodType.methodType(void.class, colorClass));
                MethodHandle shapeMarkerSetLineColor = lookup.findVirtual(shapeMarkerClass, "setLineColor", MethodType.methodType(void.class, colorClass));
                MethodHandle shapeMarkerSetDepthTest = lookup.findVirtual(shapeMarkerClass, "setDepthTestEnabled", MethodType.methodType(void.class, boolean.class));

                Constructor<?> colorConstructor = colorClass.getConstructor(int.class, int.class, int.class, float.class);

                MethodHandle mapRemoveMarkerSet = lookup.findVirtual(mapClass, "removeMarkerSet", MethodType.methodType(void.class, String.class));

                return new BlueMapReflection(plugin,
                        apiOnEnable,
                        apiOnDisable,
                        apiUnregister,
                        apiGetInstance,
                        apiGetMaps,
                        apiGetWorld,
                        worldGetMaps,
                        mapGetMarkerSets,
                        mapGetId,
                        markerSetConstructor,
                        markerSetSetLabel,
                        markerSetSetToggleable,
                        markerSetSetDefaultHidden,
                        markerSetSetHidden,
                        markerSetGetMarkers,
                        markerSetSetDirty,
                        vectorConstructor,
                        poiConstructor,
                        poiSetLabel,
                        shapeCreateRect,
                        shapeMarkerConstructor,
                        shapeMarkerSetLabel,
                        shapeMarkerSetFillColor,
                        shapeMarkerSetLineColor,
                        shapeMarkerSetDepthTest,
                        colorConstructor,
                        mapRemoveMarkerSet,
                        apiClass);
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
                plugin.getLogger().log(Level.INFO, "BlueMap API introuvable, l'intégration est désactivée.");
                plugin.getLogger().log(Level.FINE, "Détails de l'échec BlueMap", exception);
                return null;
            }
        }

        void registerEnableListener(Consumer<Object> listener) throws Throwable {
            apiOnEnable.invokeWithArguments(listener);
        }

        void registerDisableListener(Consumer<Object> listener) throws Throwable {
            apiOnDisable.invokeWithArguments(listener);
        }

        void unregisterListener(Consumer<Object> listener) throws Throwable {
            apiUnregister.invokeWithArguments(listener);
        }

        Optional<Object> getCurrentInstance() throws Throwable {
            return (Optional<Object>) apiGetInstance.invokeWithArguments();
        }

        Collection<?> getMaps(Object apiInstance) throws Throwable {
            return (Collection<?>) apiGetMaps.invoke(apiInstance);
        }

        Optional<?> getWorld(Object apiInstance, World world) throws Throwable {
            return (Optional<?>) apiGetWorld.invoke(apiInstance, world);
        }

        Collection<?> getWorldMaps(Object blueWorld) throws Throwable {
            return (Collection<?>) worldGetMaps.invoke(blueWorld);
        }

        Map<String, Object> getMarkerSetMap(Object map) throws Throwable {
            return (Map<String, Object>) mapGetMarkerSets.invoke(map);
        }

        String getMapId(Object map) throws Throwable {
            return (String) mapGetId.invoke(map);
        }

        Object ensureMarkerSet(Object map, String markerSetId, String label) throws Throwable {
            Map<String, Object> sets = getMarkerSetMap(map);
            Object markerSet = sets.get(markerSetId);
            if (markerSet == null) {
                markerSet = markerSetConstructor.newInstance(markerSetId);
                markerSetSetLabel.invoke(markerSet, label);
                markerSetSetToggleable.invoke(markerSet, true);
                if (markerSetSetDefaultHidden != null) {
                    markerSetSetDefaultHidden.invoke(markerSet, false);
                }
                if (markerSetSetHidden != null) {
                    markerSetSetHidden.invoke(markerSet, false);
                }
                sets.put(markerSetId, markerSet);
            } else {
                markerSetSetLabel.invoke(markerSet, label);
                markerSetSetToggleable.invoke(markerSet, true);
                if (markerSetSetHidden != null) {
                    markerSetSetHidden.invoke(markerSet, false);
                }
            }
            return markerSet;
        }

        void clearMarkerSet(Object markerSet) throws Throwable {
            Map<String, Object> markers = getMarkers(markerSet);
            markers.clear();
        }

        Map<String, Object> getMarkers(Object markerSet) throws Throwable {
            return (Map<String, Object>) markerSetGetMarkers.invoke(markerSet);
        }

        void markDirty(Object markerSet) {
            if (markerSetSetDirty == null) {
                return;
            }
            try {
                if (markerSetSetDirty.type().parameterCount() == 1) {
                    markerSetSetDirty.invoke(markerSet, true);
                } else {
                    markerSetSetDirty.invoke(markerSet);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.FINER, "Impossible de marquer le calque BlueMap", throwable);
            }
        }

        Object createVector(double x, double y, double z) throws InstantiationException, IllegalAccessException, InvocationTargetException {
            return vectorConstructor.newInstance(x, y, z);
        }

        Object createPoiMarker(String id, Object position, String label) throws Throwable {
            Object marker = poiConstructor.newInstance(id, position);
            poiSetLabel.invoke(marker, label);
            return marker;
        }

        Object createRectangle(double minX, double minZ, double maxX, double maxZ) throws Throwable {
            return shapeCreateRect.invokeWithArguments(minX, minZ, maxX, maxZ);
        }

        Object createShapeMarker(String id, Object shape, float y) throws InstantiationException, IllegalAccessException, InvocationTargetException {
            return shapeMarkerConstructor.newInstance(id, shape, y);
        }

        void configureShapeMarker(Object marker, String label, Object fill, Object line) throws Throwable {
            shapeMarkerSetLabel.invoke(marker, label);
            shapeMarkerSetFillColor.invoke(marker, fill);
            shapeMarkerSetLineColor.invoke(marker, line);
            shapeMarkerSetDepthTest.invoke(marker, false);
        }

        Object createColor(int r, int g, int b, float alpha) throws InstantiationException, IllegalAccessException, InvocationTargetException {
            return colorConstructor.newInstance(r, g, b, alpha);
        }

        void removeMarkerSet(Object map, String markerSetId) throws Throwable {
            mapRemoveMarkerSet.invoke(map, markerSetId);
        }

        void removeMarkerSetById(String mapId, String markerSetId, Object apiInstance) throws Throwable {
            for (Object map : getMaps(apiInstance)) {
                String id = getMapId(map);
                if (!mapId.equals(id)) {
                    continue;
                }
                removeMarkerSet(map, markerSetId);
                break;
            }
        }
    }
}

