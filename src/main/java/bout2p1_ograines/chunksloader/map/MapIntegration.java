package bout2p1_ograines.chunksloader.map;

import bout2p1_ograines.chunksloader.ChunkLoaderListener;

public interface MapIntegration extends ChunkLoaderListener {
    /**
     * Attempt to initialise the integration.
     *
     * @return {@code true} if the integration is active
     */
    boolean initialize();

    /**
     * Shut down and clean all registered resources.
     */
    void shutdown();
}
