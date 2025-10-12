package bout2p1_ograines.chunksloader;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Handles spawning and removing simulated players using the vanilla {@code /player}
 * command introduced in Minecraft 1.21.
 */
public class PlayerEmulationController {
    private final ChunksLoaderPlugin plugin;
    private final Map<ChunkLoaderLocation, String> activePlayers = new HashMap<>();
    private final boolean playerCommandAvailable;

    public PlayerEmulationController(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.playerCommandAvailable = detectPlayerCommand();
    }

    private boolean detectPlayerCommand() {
        try {
            Object commandMap = Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
            Object command = commandMap.getClass().getMethod("getCommand", String.class).invoke(commandMap, "player");
            return command != null;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Unable to detect support for the /player command: " + exception.getMessage());
            return false;
        }
    }

    public boolean isPlayerCommandAvailable() {
        return playerCommandAvailable;
    }

    public void syncWorld(World world, Map<ChunkLoaderLocation, ChunkLoaderState> states) {
        if (!playerCommandAvailable) {
            clearWorld(world.getUID());
            return;
        }
        Set<ChunkLoaderLocation> desired = new HashSet<>();
        for (Map.Entry<ChunkLoaderLocation, ChunkLoaderState> entry : states.entrySet()) {
            ChunkLoaderLocation location = entry.getKey();
            ChunkLoaderState state = entry.getValue();
            if (!Objects.equals(location.worldId(), world.getUID())) {
                continue;
            }
            if (state.isActive() && state.isPlayerEmulationEnabled()) {
                desired.add(location);
                ensureSpawned(location, state);
            } else {
                disable(location, state);
            }
        }
        for (Map.Entry<ChunkLoaderLocation, String> entry : new HashMap<>(activePlayers).entrySet()) {
            ChunkLoaderLocation location = entry.getKey();
            if (!Objects.equals(location.worldId(), world.getUID())) {
                continue;
            }
            if (!desired.contains(location)) {
                killPlayer(entry.getValue());
                activePlayers.remove(location);
            }
        }
    }

    public void ensureSpawned(ChunkLoaderLocation location, ChunkLoaderState state) {
        if (!playerCommandAvailable) {
            return;
        }
        String name = state.getSimulatedPlayerName();
        if (name == null || name.isBlank()) {
            return;
        }
        if (activePlayers.containsKey(location)) {
            return;
        }
        Location spawnLocation = resolveLocation(location);
        if (spawnLocation == null) {
            return;
        }
        killPlayer(name);
        if (spawnPlayer(name, spawnLocation)) {
            activePlayers.put(location, name);
        }
    }

    public void disable(ChunkLoaderLocation location, ChunkLoaderState state) {
        String tracked = activePlayers.remove(location);
        String name = tracked != null ? tracked : state.getSimulatedPlayerName();
        if (name != null && !name.isBlank()) {
            killPlayer(name);
        }
    }

    public void clearAll() {
        for (String name : new HashSet<>(activePlayers.values())) {
            killPlayer(name);
        }
        activePlayers.clear();
    }

    public void clearWorld(UUID worldId) {
        for (Map.Entry<ChunkLoaderLocation, String> entry : new HashMap<>(activePlayers).entrySet()) {
            if (Objects.equals(entry.getKey().worldId(), worldId)) {
                killPlayer(entry.getValue());
                activePlayers.remove(entry.getKey());
            }
        }
    }

    private Location resolveLocation(ChunkLoaderLocation location) {
        World world = Bukkit.getWorld(location.worldId());
        if (world == null) {
            return null;
        }
        return new Location(world, location.x() + 0.5, location.y(), location.z() + 0.5, 0.0f, 0.0f);
    }

    private boolean spawnPlayer(String name, Location location) {
        String dimension = switch (location.getWorld().getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            case NORMAL -> "minecraft:overworld";
            default -> location.getWorld().getKey().toString();
        };
        String command = String.format(Locale.ROOT,
            "player %s spawn at %.2f %.2f %.2f in %s", name,
            location.getX(), location.getY(), location.getZ(), dimension);
        CommandSender console = Bukkit.getConsoleSender();
        boolean success = Bukkit.dispatchCommand(console, command);
        if (!success) {
            plugin.getLogger().warning("Failed to spawn simulated player '" + name + "' using command: " + command);
        }
        return success;
    }

    private void killPlayer(String name) {
        if (!playerCommandAvailable || name == null || name.isBlank()) {
            return;
        }
        CommandSender console = Bukkit.getConsoleSender();
        if (!Bukkit.dispatchCommand(console, "player " + name + " kill")) {
            plugin.getLogger().warning("Failed to remove simulated player '" + name + "'.");
        }
    }
}
