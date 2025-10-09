package bout2p1_ograines.chunksloader.map;

import java.util.Collection;

public interface MapIntegration {

    void update(Collection<LoaderData> loaders);

    void shutdown();
}
