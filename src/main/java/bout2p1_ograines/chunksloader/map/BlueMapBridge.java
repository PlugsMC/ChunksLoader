package bout2p1_ograines.chunksloader.map;

import java.util.Collection;

interface BlueMapBridge {

    void initialize();

    void update(Collection<LoaderData> loaders);

    void shutdown();
}
