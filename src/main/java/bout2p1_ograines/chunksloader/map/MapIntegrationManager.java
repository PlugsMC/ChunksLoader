package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderListener;
import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;
import org.bukkit.World;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

public final class MapIntegrationManager implements ChunkLoaderListener {

    private final ChunksLoaderPlugin plugin;

    private DynmapIntegration dynmapIntegration;
    private BlueMapBridge blueMapBridge;

    public MapIntegrationManager(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        dynmapIntegration = DynmapIntegration.create(plugin)
            .map(integration -> {
                plugin.getLogger().info("Dynmap integration enabled.");
                return integration;
            })
            .orElse(null);

        blueMapBridge = createBlueMapBridge();
        if (blueMapBridge != null) {
            blueMapBridge.initialize();
        }
    }

    public void updateAll() {
        List<LoaderData> data = plugin.getLoaderData();
        if (dynmapIntegration != null) {
            dynmapIntegration.update(data);
        }
        if (blueMapBridge != null) {
            blueMapBridge.update(data);
        }
    }

    @Override
    public void onLoadersChanged(World world) {
        updateAll();
    }

    public void shutdown() {
        Optional.ofNullable(dynmapIntegration).ifPresent(MapIntegration::shutdown);
        Optional.ofNullable(blueMapBridge).ifPresent(BlueMapBridge::shutdown);
        dynmapIntegration = null;
        blueMapBridge = null;
    }

    private BlueMapBridge createBlueMapBridge() {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        try {
            Class.forName("de.bluecolored.bluemap.api.BlueMapAPI", false, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            plugin.getLogger().log(Level.FINE, "BlueMap not detected; skipping integration.");
            return null;
        }

        try {
            Class<?> bridgeClass = Class.forName(
                "bout2p1_ograines.chunksloader.map.BlueMapIntegrationHandler",
                false,
                classLoader
            );
            Constructor<?> constructor = bridgeClass.getDeclaredConstructor(ChunksLoaderPlugin.class);
            Object instance = constructor.newInstance(plugin);
            return (BlueMapBridge) instance;
        } catch (ReflectiveOperationException | ClassCastException | NoClassDefFoundError exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to initialize BlueMap integration", exception);
            return null;
        }
    }
}
