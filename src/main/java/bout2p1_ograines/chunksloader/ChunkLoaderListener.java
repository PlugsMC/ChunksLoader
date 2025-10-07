package bout2p1_ograines.chunksloader;

import org.bukkit.World;

/**
 * Listener notified whenever chunk loader placements change.
 */
public interface ChunkLoaderListener {
    /**
     * Triggered when the chunk loader set changes.
     *
     * @param world the world that changed, or {@code null} when every world should be refreshed
     */
    void onLoadersChanged(World world);
}
