package bout2p1_ograines.chunksloader;

/**
 * Represents the state of a chunk loader, including whether it is active and whether
 * it should emulate a player.
 */
public final class ChunkLoaderState {
    private boolean active;
    private boolean playerEmulationEnabled;
    private String simulatedPlayerName;

    public ChunkLoaderState(boolean active, boolean playerEmulationEnabled, String simulatedPlayerName) {
        this.active = active;
        this.playerEmulationEnabled = playerEmulationEnabled;
        this.simulatedPlayerName = simulatedPlayerName;
    }

    public ChunkLoaderState(ChunkLoaderState other) {
        this(other.active, other.playerEmulationEnabled, other.simulatedPlayerName);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isPlayerEmulationEnabled() {
        return playerEmulationEnabled;
    }

    public void setPlayerEmulationEnabled(boolean playerEmulationEnabled) {
        this.playerEmulationEnabled = playerEmulationEnabled;
    }

    public String getSimulatedPlayerName() {
        return simulatedPlayerName;
    }

    public void setSimulatedPlayerName(String simulatedPlayerName) {
        this.simulatedPlayerName = simulatedPlayerName;
    }
}
