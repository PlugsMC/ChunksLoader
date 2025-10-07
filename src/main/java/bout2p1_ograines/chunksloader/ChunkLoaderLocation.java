package bout2p1_ograines.chunksloader;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

public final class ChunkLoaderLocation {
    private final UUID worldId;
    private final int x;
    private final int y;
    private final int z;

    public ChunkLoaderLocation(UUID worldId, int x, int y, int z) {
        this.worldId = worldId;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID worldId() {
        return worldId;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Location toLocation(org.bukkit.World world) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkLoaderLocation that = (ChunkLoaderLocation) o;
        return x == that.x && y == that.y && z == that.z && Objects.equals(worldId, that.worldId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldId, x, y, z);
    }
}
