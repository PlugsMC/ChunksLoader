package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunksLoaderPlugin;
import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Bukkit;

import java.util.Collection;
import java.util.function.Consumer;

final class BlueMapIntegrationHandler implements BlueMapBridge {

    private final ChunksLoaderPlugin plugin;
    private final Consumer<BlueMapAPI> enableListener;
    private final Consumer<BlueMapAPI> disableListener;

    private BlueMapIntegration blueMapIntegration;

    BlueMapIntegrationHandler(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.enableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> registerBlueMap(api));
        this.disableListener = api -> Bukkit.getScheduler().runTask(plugin, () -> unregisterBlueMap(api));
    }

    @Override
    public void initialize() {
        BlueMapAPI.onEnable(enableListener);
        BlueMapAPI.onDisable(disableListener);
        BlueMapAPI.getInstance().ifPresent(api -> Bukkit.getScheduler().runTask(plugin, () -> registerBlueMap(api)));
    }

    @Override
    public void update(Collection<LoaderData> loaders) {
        if (blueMapIntegration != null) {
            blueMapIntegration.update(loaders);
        }
    }

    @Override
    public void shutdown() {
        BlueMapAPI.unregisterListener(enableListener);
        BlueMapAPI.unregisterListener(disableListener);
        if (blueMapIntegration != null) {
            blueMapIntegration.shutdown();
            blueMapIntegration = null;
        }
    }

    private void registerBlueMap(BlueMapAPI api) {
        if (blueMapIntegration != null && blueMapIntegration.isFor(api)) {
            return;
        }
        blueMapIntegration = new BlueMapIntegration(plugin, api);
        plugin.getLogger().info("BlueMap integration enabled.");
        blueMapIntegration.update(plugin.getLoaderData());
    }

    private void unregisterBlueMap(BlueMapAPI api) {
        if (blueMapIntegration != null && blueMapIntegration.isFor(api)) {
            blueMapIntegration.shutdown();
            blueMapIntegration = null;
            plugin.getLogger().info("BlueMap integration disabled.");
        }
    }
}
