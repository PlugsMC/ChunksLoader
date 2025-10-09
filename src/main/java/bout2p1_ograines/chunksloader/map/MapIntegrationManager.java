package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderListener;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;
import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class MapIntegrationManager implements ChunkLoaderListener {

    private final ChunksLoaderPlugin plugin;
    private final Consumer<BlueMapAPI> enableListener;
    private final Consumer<BlueMapAPI> disableListener;

    private DynmapIntegration dynmapIntegration;
    private BlueMapIntegration blueMapIntegration;

    public MapIntegrationManager(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.enableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> registerBlueMap(api));
        this.disableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> unregisterBlueMap(api));
    }

    public void initialize() {
        dynmapIntegration = DynmapIntegration.create(plugin)
            .map(integration -> {
                plugin.getLogger().info("Dynmap integration enabled.");
                return integration;
            })
            .orElse(null);

        BlueMapAPI.onEnable(enableListener);
        BlueMapAPI.onDisable(disableListener);
        BlueMapAPI.getInstance().ifPresent(api -> Bukkit.getScheduler().runTask(plugin, () -> registerBlueMap(api)));
    }

    public void updateAll() {
        List<LoaderData> data = plugin.getLoaderData();
        if (dynmapIntegration != null) {
            dynmapIntegration.update(data);
        }
        if (blueMapIntegration != null) {
            blueMapIntegration.update(data);
        }
    }

    @Override
    public void onLoadersChanged(World world) {
        updateAll();
    }

    public void shutdown() {
        BlueMapAPI.unregisterListener(enableListener);
        BlueMapAPI.unregisterListener(disableListener);
        Optional.ofNullable(dynmapIntegration).ifPresent(MapIntegration::shutdown);
        Optional.ofNullable(blueMapIntegration).ifPresent(MapIntegration::shutdown);
        dynmapIntegration = null;
        blueMapIntegration = null;
    }

    private void registerBlueMap(BlueMapAPI api) {
        if (blueMapIntegration != null && blueMapIntegration.isFor(api)) {
            return;
        }
        blueMapIntegration = new BlueMapIntegration(plugin, api);
        plugin.getLogger().info("BlueMap integration enabled.");
        updateAll();
    }

    private void unregisterBlueMap(BlueMapAPI api) {
        if (blueMapIntegration != null && blueMapIntegration.isFor(api)) {
            blueMapIntegration.shutdown();
            blueMapIntegration = null;
            plugin.getLogger().info("BlueMap integration disabled.");
        }
    }
}
