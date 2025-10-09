package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

public final class DynmapIntegration implements MapIntegration {

    private static final String MARKER_SET_ID = "chunkloader";
    private static final int ACTIVE_COLOR = 0x2ECC71;
    private static final int INACTIVE_COLOR = 0xE74C3C;

    private final ChunksLoaderPlugin plugin;
    private final Object markerSet;

    private final Method findAreaMarker;
    private final Method createAreaMarker;
    private final Method getAreaMarkers;
    private final Method deleteMarker;
    private final Method getMarkerId;
    private final Method setLabel;
    private final Method setDescription;
    private final Method setCornerLocations;
    private final Method setRangeY;
    private final Method setLineStyle;
    private final Method setFillStyle;
    private final Method setMarkerSetLabel;
    private final Method setHideByDefault;
    private final Method setLayerPriority;

    private DynmapIntegration(ChunksLoaderPlugin plugin, Object markerSet,
                              Method findAreaMarker, Method createAreaMarker, Method getAreaMarkers,
                              Method deleteMarker, Method getMarkerId, Method setLabel,
                              Method setDescription, Method setCornerLocations, Method setRangeY,
                              Method setLineStyle, Method setFillStyle, Method setMarkerSetLabel,
                              Method setHideByDefault, Method setLayerPriority) {
        this.plugin = plugin;
        this.markerSet = markerSet;
        this.findAreaMarker = findAreaMarker;
        this.createAreaMarker = createAreaMarker;
        this.getAreaMarkers = getAreaMarkers;
        this.deleteMarker = deleteMarker;
        this.getMarkerId = getMarkerId;
        this.setLabel = setLabel;
        this.setDescription = setDescription;
        this.setCornerLocations = setCornerLocations;
        this.setRangeY = setRangeY;
        this.setLineStyle = setLineStyle;
        this.setFillStyle = setFillStyle;
        this.setMarkerSetLabel = setMarkerSetLabel;
        this.setHideByDefault = setHideByDefault;
        this.setLayerPriority = setLayerPriority;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void update(Collection<LoaderData> loaders) {
        try {
            setMarkerSetLabel.invoke(markerSet, "Chunk Loaders");
            setHideByDefault.invoke(markerSet, Boolean.FALSE);
            setLayerPriority.invoke(markerSet, 10);

            Set<String> expected = new HashSet<>();
            for (LoaderData loader : loaders) {
                String id = loader.id();
                expected.add(id);
                Object area = findAreaMarker.invoke(markerSet, id);
                double[] x = rectangleX(loader);
                double[] z = rectangleZ(loader);
                if (area == null) {
                    area = createAreaMarker.invoke(markerSet, id, loader.plainDisplayName(), false, loader.worldName(), x, z, true);
                    if (area == null) {
                        continue;
                    }
                } else {
                    setCornerLocations.invoke(area, x, z);
                }

                setLabel.invoke(area, loader.plainDisplayName());
                setDescription.invoke(area, buildDescription(loader));
                setRangeY.invoke(area, (double) loader.blockY() + 1.0d, (double) loader.blockY());
                int color = loader.active() ? ACTIVE_COLOR : INACTIVE_COLOR;
                double fill = loader.active() ? 0.35d : 0.2d;
                setLineStyle.invoke(area, 2, 1.0d, color);
                setFillStyle.invoke(area, fill, color);
            }

            Set<Object> existing = (Set<Object>) getAreaMarkers.invoke(markerSet);
            for (Object marker : existing) {
                String id = (String) getMarkerId.invoke(marker);
                if (!expected.contains(id)) {
                    deleteMarker.invoke(marker);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to update Dynmap markers", exception);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void shutdown() {
        try {
            Set<Object> existing = (Set<Object>) getAreaMarkers.invoke(markerSet);
            for (Object marker : existing) {
                deleteMarker.invoke(marker);
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to clear Dynmap markers", exception);
        }
    }

    private String buildDescription(LoaderData loader) {
        return new StringBuilder()
            .append("<strong>").append(html(loader.plainDisplayName())).append("</strong><br/>")
            .append("Owner: ").append(html(loader.ownerLabel())).append("<br/>")
            .append("Radius: ").append(loader.radius()).append(" chunk(s)<br/>")
            .append("Chunks: ").append(loader.chunkCount()).append("<br/>")
            .append("Status: ").append(loader.statusLabel()).append("<br/>")
            .append("Position: ").append(loader.blockX()).append(", ")
            .append(loader.blockY()).append(", ").append(loader.blockZ())
            .toString();
    }

    private String html(String input) {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private double[] rectangleX(LoaderData loader) {
        return new double[]{loader.minX(), loader.maxX(), loader.maxX(), loader.minX()};
    }

    private double[] rectangleZ(LoaderData loader) {
        return new double[]{loader.minZ(), loader.minZ(), loader.maxZ(), loader.maxZ()};
    }

    public static Optional<DynmapIntegration> create(ChunksLoaderPlugin plugin) {
        try {
            Class<?> dynmapApiClass = Class.forName("org.dynmap.DynmapAPI");
            Object dynmapPlugin = plugin.getServer().getPluginManager().getPlugin("dynmap");
            if (dynmapPlugin == null || !dynmapApiClass.isInstance(dynmapPlugin)) {
                return Optional.empty();
            }

            Method getMarkerAPI = dynmapApiClass.getMethod("getMarkerAPI");
            Object markerApi = getMarkerAPI.invoke(dynmapPlugin);
            if (markerApi == null) {
                return Optional.empty();
            }

            Class<?> markerApiClass = Class.forName("org.dynmap.markers.MarkerAPI");
            Class<?> markerSetClass = Class.forName("org.dynmap.markers.MarkerSet");
            Class<?> areaMarkerClass = Class.forName("org.dynmap.markers.AreaMarker");
            Class<?> genericMarkerClass = Class.forName("org.dynmap.markers.GenericMarker");

            Method getMarkerSet = markerApiClass.getMethod("getMarkerSet", String.class);
            Method createMarkerSet = markerApiClass.getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class);
            Object markerSet = getMarkerSet.invoke(markerApi, MARKER_SET_ID);
            if (markerSet == null) {
                markerSet = createMarkerSet.invoke(markerApi, MARKER_SET_ID, "Chunk Loaders", null, true);
            }
            if (markerSet == null) {
                return Optional.empty();
            }

            Method findAreaMarker = markerSetClass.getMethod("findAreaMarker", String.class);
            Method createAreaMarker = markerSetClass.getMethod("createAreaMarker", String.class, String.class, boolean.class, String.class, double[].class, double[].class, boolean.class);
            Method getAreaMarkers = markerSetClass.getMethod("getAreaMarkers");
            Method deleteMarker = genericMarkerClass.getMethod("deleteMarker");
            Method getMarkerId = genericMarkerClass.getMethod("getMarkerID");
            Method setLabel = genericMarkerClass.getMethod("setLabel", String.class);
            Method setDescription = areaMarkerClass.getMethod("setDescription", String.class);
            Method setCornerLocations = areaMarkerClass.getMethod("setCornerLocations", double[].class, double[].class);
            Method setRangeY = areaMarkerClass.getMethod("setRangeY", double.class, double.class);
            Method setLineStyle = areaMarkerClass.getMethod("setLineStyle", int.class, double.class, int.class);
            Method setFillStyle = areaMarkerClass.getMethod("setFillStyle", double.class, int.class);
            Method setMarkerSetLabel = markerSetClass.getMethod("setMarkerSetLabel", String.class);
            Method setHideByDefault = markerSetClass.getMethod("setHideByDefault", boolean.class);
            Method setLayerPriority = markerSetClass.getMethod("setLayerPriority", int.class);

            return Optional.of(new DynmapIntegration(
                plugin,
                markerSet,
                findAreaMarker,
                createAreaMarker,
                getAreaMarkers,
                deleteMarker,
                getMarkerId,
                setLabel,
                setDescription,
                setCornerLocations,
                setRangeY,
                setLineStyle,
                setFillStyle,
                setMarkerSetLabel,
                setHideByDefault,
                setLayerPriority
            ));
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to initialise Dynmap integration", exception);
            return Optional.empty();
        }
    }
}
