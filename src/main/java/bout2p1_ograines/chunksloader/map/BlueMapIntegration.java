package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class BlueMapIntegration implements MapIntegration {

    private static final String MARKER_SET_ID = "chunkloader";
    private static final Color ACTIVE_LINE = new Color(46, 204, 113, 1.0f);
    private static final Color ACTIVE_FILL = new Color(46, 204, 113, 0.35f);
    private static final Color INACTIVE_LINE = new Color(231, 76, 60, 1.0f);
    private static final Color INACTIVE_FILL = new Color(231, 76, 60, 0.25f);

    private final ChunksLoaderPlugin plugin;
    private final BlueMapAPI api;
    private final Map<String, Optional<BlueMapWorld>> worldCache = new ConcurrentHashMap<>();

    public BlueMapIntegration(ChunksLoaderPlugin plugin, BlueMapAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    @Override
    public void update(Collection<LoaderData> loaders) {
        try {
            Map<String, List<LoaderData>> grouped = loaders.stream()
                .collect(Collectors.groupingBy(LoaderData::worldName));

            Map<String, List<LoaderData>> byWorldId = new HashMap<>();
            for (Map.Entry<String, List<LoaderData>> entry : grouped.entrySet()) {
                resolveWorld(entry.getKey()).ifPresent(world ->
                    byWorldId.computeIfAbsent(world.getId(), ignored -> new ArrayList<>()).addAll(entry.getValue())
                );
            }

            for (BlueMapMap map : api.getMaps()) {
                List<LoaderData> data = byWorldId.getOrDefault(map.getWorld().getId(), Collections.emptyList());
                if (data.isEmpty()) {
                    map.getMarkerSets().remove(MARKER_SET_ID);
                    continue;
                }
                MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, key -> new MarkerSet("Chunk Loaders"));
                markerSet.setLabel("Chunk Loaders");
                markerSet.setToggleable(true);
                markerSet.setDefaultHidden(false);
                markerSet.setSorting(50);
                markerSet.getMarkers().clear();

                for (LoaderData loader : data) {
                    Shape shape = Shape.createRect(loader.minX(), loader.minZ(), loader.maxX(), loader.maxZ());
                    ShapeMarker marker = new ShapeMarker(loader.plainDisplayName(), shape, loader.blockY());
                    marker.setLabel(loader.plainDisplayName());
                    marker.setDetail(buildDetail(loader));
                    marker.setDepthTestEnabled(false);
                    marker.setLineWidth(2);
                    if (loader.active()) {
                        marker.setLineColor(ACTIVE_LINE);
                        marker.setFillColor(ACTIVE_FILL);
                    } else {
                        marker.setLineColor(INACTIVE_LINE);
                        marker.setFillColor(INACTIVE_FILL);
                    }
                    marker.centerPosition();
                    markerSet.put(loader.id(), marker);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Impossible de mettre à jour les marqueurs BlueMap", exception);
        }
    }

    private Optional<BlueMapWorld> resolveWorld(String worldName) {
        return worldCache.computeIfAbsent(worldName, name -> {
            World bukkitWorld = Bukkit.getWorld(name);
            if (bukkitWorld != null) {
                Optional<BlueMapWorld> resolved = api.getWorld(bukkitWorld);
                if (resolved.isPresent()) {
                    return resolved;
                }
            }
            Optional<BlueMapWorld> direct = api.getWorld(name);
            if (direct.isPresent()) {
                return direct;
            }
            String namespaced = "minecraft:" + name.toLowerCase(Locale.ROOT);
            return api.getWorld(namespaced);
        });
    }

    private String buildDetail(LoaderData loader) {
        return new StringBuilder()
            .append("<strong>").append(escape(loader.plainDisplayName())).append("</strong><br/>")
            .append("Propriétaire : ").append(escape(loader.ownerLabel())).append("<br/>")
            .append("Rayon : ").append(loader.radius()).append(" chunk(s)<br/>")
            .append("Chunks : ").append(loader.chunkCount()).append("<br/>")
            .append("État : ").append(loader.statusLabel()).append("<br/>")
            .append("Position : ").append(loader.blockX()).append(", ")
            .append(loader.blockY()).append(", ").append(loader.blockZ()).append("<br/>")
            .append("Activité : ").append(escape(loader.formatDuration(Locale.FRANCE)))
            .toString();
    }

    private String escape(String input) {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public boolean isFor(BlueMapAPI other) {
        return api == other;
    }

    @Override
    public void shutdown() {
        try {
            api.getMaps().forEach(map -> map.getMarkerSets().remove(MARKER_SET_ID));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Impossible de nettoyer les marqueurs BlueMap", exception);
        }
        worldCache.clear();
    }
}
