package bout2p1_ograines.chunksloader;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles spawning and removing simulated players by reflectively bridging into
 * the server internals. This avoids relying on the {@code /player} command which
 * is not available on all forks, while still emulating a real player entity so
 * farms and other mechanics behave correctly.
 */
public class PlayerEmulationController {
    private final ChunksLoaderPlugin plugin;
    private final ReflectionBridge bridge;
    private final Map<ChunkLoaderLocation, SimulatedPlayer> activePlayers = new HashMap<>();

    public PlayerEmulationController(ChunksLoaderPlugin plugin) {
        this.plugin = plugin;
        this.bridge = ReflectionBridge.create(plugin);
    }

    public boolean isSupported() {
        return bridge != null;
    }

    public void syncWorld(World world, Map<ChunkLoaderLocation, ChunkLoaderState> states) {
        if (!isSupported()) {
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
        for (Map.Entry<ChunkLoaderLocation, SimulatedPlayer> entry : new HashMap<>(activePlayers).entrySet()) {
            ChunkLoaderLocation location = entry.getKey();
            if (!Objects.equals(location.worldId(), world.getUID())) {
                continue;
            }
            if (!desired.contains(location)) {
                removeSimulatedPlayer(entry.getValue());
                activePlayers.remove(location);
            }
        }
    }

    public void ensureSpawned(ChunkLoaderLocation location, ChunkLoaderState state) {
        if (!isSupported()) {
            return;
        }
        if (activePlayers.containsKey(location)) {
            return;
        }
        Location spawnLocation = resolveLocation(location);
        if (spawnLocation == null) {
            return;
        }
        String name = state.getSimulatedPlayerName();
        if (name == null || name.isBlank()) {
            name = generateDefaultName(location);
            state.setSimulatedPlayerName(name);
        }
        removeExistingByName(name);
        SimulatedPlayer player = bridge.spawn(spawnLocation, name);
        if (player != null) {
            activePlayers.put(location, player);
        }
    }

    public void disable(ChunkLoaderLocation location, ChunkLoaderState state) {
        SimulatedPlayer player = activePlayers.remove(location);
        if (player != null) {
            removeSimulatedPlayer(player);
        } else if (state != null && state.getSimulatedPlayerName() != null) {
            removeExistingByName(state.getSimulatedPlayerName());
        }
    }

    public void clearAll() {
        for (SimulatedPlayer player : new HashSet<>(activePlayers.values())) {
            removeSimulatedPlayer(player);
        }
        activePlayers.clear();
    }

    public void clearWorld(UUID worldId) {
        for (Map.Entry<ChunkLoaderLocation, SimulatedPlayer> entry : new HashMap<>(activePlayers).entrySet()) {
            if (Objects.equals(entry.getKey().worldId(), worldId)) {
                removeSimulatedPlayer(entry.getValue());
                activePlayers.remove(entry.getKey());
            }
        }
    }

    private void removeExistingByName(String name) {
        if (name == null || name.isBlank() || !isSupported()) {
            return;
        }
        for (Map.Entry<ChunkLoaderLocation, SimulatedPlayer> entry : new HashMap<>(activePlayers).entrySet()) {
            SimulatedPlayer player = entry.getValue();
            if (player.name.equalsIgnoreCase(name)) {
                removeSimulatedPlayer(player);
                activePlayers.remove(entry.getKey());
            }
        }
        Player existing = Bukkit.getPlayerExact(name);
        if (existing != null) {
            existing.remove();
        }
    }

    private void removeSimulatedPlayer(SimulatedPlayer player) {
        try {
            bridge.remove(player);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove simulated player '" + player.name + "'", exception);
        }
    }

    private Location resolveLocation(ChunkLoaderLocation location) {
        World world = Bukkit.getWorld(location.worldId());
        if (world == null) {
            return null;
        }
        return new Location(world, location.x() + 0.5, location.y(), location.z() + 0.5, 0.0f, 0.0f);
    }

    private String generateDefaultName(ChunkLoaderLocation location) {
        long hash = 1469598103934665603L;
        hash = mixHash(hash, location.worldId().getMostSignificantBits());
        hash = mixHash(hash, location.worldId().getLeastSignificantBits());
        hash = mixHash(hash, location.x());
        hash = mixHash(hash, location.y());
        hash = mixHash(hash, location.z());
        String base = Long.toUnsignedString(hash, 36).toUpperCase(Locale.ROOT);
        String name = "CL" + base;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        while (name.length() < 3) {
            name = name + "0";
        }
        return name;
    }

    private long mixHash(long current, long value) {
        current ^= value;
        current *= 1099511628211L;
        return current;
    }

    private record SimulatedPlayer(UUID uuid, String name, Object handle, Player bukkit) {
    }

    private static final class ReflectionBridge {
        private final ChunksLoaderPlugin plugin;
        private final Object minecraftServer;
        private final Constructor<?> serverPlayerConstructor;
        private final Object playerList;
        private final Method playerListRemove;
        private final Method serverLevelAddPlayer;
        private final Method getBukkitEntity;
        private final Method moveToMethod;
        private final Method teleportMethod;
        private final Method setYawMethod;
        private final Method setPitchMethod;
        private final Method setPosRawMethod;
        private final Method discardMethod;
        private final Method removeMethod;
        private final Object removalReason;
        private final Constructor<?> gameProfileConstructor;
        private final Object defaultClientInformation;
        private final Class<?> clientInformationClass;
        private final Class<?> serverPlayerClass;
        private final Class<?> serverLevelClass;
        private final Method getClientInformationMethod;
        private final Field clientInformationField;
        private final Constructor<?> serverGamePacketListenerConstructor;
        private final Field connectionField;
        private final Method connectionSetterMethod;
        private final Class<?> connectionClass;
        private final Class<?> packetListenerClass;
        private final Method connectionSetListenerMethod;

        private ReflectionBridge(ChunksLoaderPlugin plugin,
                                 Object minecraftServer,
                                 Constructor<?> serverPlayerConstructor,
                                 Object playerList,
                                 Method playerListRemove,
                                 Method serverLevelAddPlayer,
                                 Method getBukkitEntity,
                                 Method moveToMethod,
                                 Method teleportMethod,
                                 Method setYawMethod,
                                 Method setPitchMethod,
                                 Method setPosRawMethod,
                                 Method discardMethod,
                                 Method removeMethod,
                                 Object removalReason,
                                 Constructor<?> gameProfileConstructor,
                                 Object defaultClientInformation,
                                 Class<?> clientInformationClass,
                                 Class<?> serverPlayerClass,
                                 Class<?> serverLevelClass,
                                 Method getClientInformationMethod,
                                 Field clientInformationField,
                                 Constructor<?> serverGamePacketListenerConstructor,
                                 Field connectionField,
                                 Method connectionSetterMethod,
                                 Class<?> connectionClass,
                                 Class<?> packetListenerClass,
                                 Method connectionSetListenerMethod) {
            this.plugin = plugin;
            this.minecraftServer = minecraftServer;
            this.serverPlayerConstructor = serverPlayerConstructor;
            this.playerList = playerList;
            this.playerListRemove = playerListRemove;
            this.serverLevelAddPlayer = serverLevelAddPlayer;
            this.getBukkitEntity = getBukkitEntity;
            this.moveToMethod = moveToMethod;
            this.teleportMethod = teleportMethod;
            this.setYawMethod = setYawMethod;
            this.setPitchMethod = setPitchMethod;
            this.setPosRawMethod = setPosRawMethod;
            this.discardMethod = discardMethod;
            this.removeMethod = removeMethod;
            this.removalReason = removalReason;
            this.gameProfileConstructor = gameProfileConstructor;
            this.defaultClientInformation = defaultClientInformation;
            this.clientInformationClass = clientInformationClass;
            this.serverPlayerClass = serverPlayerClass;
            this.serverLevelClass = serverLevelClass;
            this.getClientInformationMethod = getClientInformationMethod;
            this.clientInformationField = clientInformationField;
            this.serverGamePacketListenerConstructor = serverGamePacketListenerConstructor;
            this.connectionField = connectionField;
            this.connectionSetterMethod = connectionSetterMethod;
            this.connectionClass = connectionClass;
            this.packetListenerClass = packetListenerClass;
            this.connectionSetListenerMethod = connectionSetListenerMethod;
        }

        static ReflectionBridge create(ChunksLoaderPlugin plugin) {
            try {
                Object craftServer = Bukkit.getServer();
                Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);

                Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
                Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
                Constructor<?> gameProfileConstructor = gameProfileClass.getConstructor(UUID.class, String.class);

                Constructor<?> serverPlayerConstructor = selectServerPlayerConstructor(serverPlayerClass);
                if (serverPlayerConstructor == null) {
                    plugin.getLogger().warning("Unable to locate a usable ServerPlayer constructor; player emulation disabled.");
                    return null;
                }

                Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
                Class<?> clientInformationClass = tryClass("net.minecraft.server.level.ClientInformation");
                if (clientInformationClass == null) {
                    clientInformationClass = tryClass("net.minecraft.network.protocol.login.ClientInformation");
                }
                Object defaultClientInformation = createDefaultClientInformation(clientInformationClass);
                Method getClientInformationMethod = findZeroArgMethod(serverPlayerClass, "clientInformation", "getClientInformation");
                Field clientInformationField = null;
                if (getClientInformationMethod == null && clientInformationClass != null) {
                    clientInformationField = findField(serverPlayerClass, clientInformationClass, "clientInformation");
                }

                Method getBukkitEntity = serverPlayerClass.getMethod("getBukkitEntity");
                Method moveToMethod = findPositionMethod(serverPlayerClass, "moveTo");
                Method teleportMethod = findPositionMethod(serverPlayerClass, "teleportTo");
                Method setYawMethod = findMethod(serverPlayerClass, "setYRot", float.class);
                Method setPitchMethod = findMethod(serverPlayerClass, "setXRot", float.class);
                Method setPosRawMethod = findPositionMethod(serverPlayerClass, "setPosRaw");
                if (setPosRawMethod == null) {
                    setPosRawMethod = findPositionMethod(serverPlayerClass, "setPos");
                }
                if (setPosRawMethod == null) {
                    setPosRawMethod = findPositionMethod(serverPlayerClass, "absMoveTo");
                }

                Method discardMethod = null;
                try {
                    discardMethod = serverPlayerClass.getMethod("discard");
                } catch (NoSuchMethodException ignored) {
                }
                Method removeMethod = null;
                Object removalReason = null;
                if (discardMethod == null) {
                    Class<?> removalReasonClass = tryClass("net.minecraft.world.entity.Entity$RemovalReason");
                    if (removalReasonClass != null) {
                        try {
                            removeMethod = serverPlayerClass.getMethod("remove", removalReasonClass);
                            removalReason = findEnumConstant(removalReasonClass, "DISCARDED");
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                }

                Object playerList = minecraftServer.getClass().getMethod("getPlayerList").invoke(minecraftServer);
                Method playerListRemove = findSingleParamMethod(playerList.getClass(), serverPlayerClass, "remove", "removePlayer");

                Method serverLevelAddPlayer = findSingleParamMethod(serverLevelClass, serverPlayerClass, "addNewPlayer", "addPlayer", "addFreshEntity");
                if (serverLevelAddPlayer == null) {
                    plugin.getLogger().warning("Unable to locate a ServerLevel method to add simulated players; player emulation disabled.");
                    return null;
                }

                Class<?> serverGamePacketListenerClass = tryClass("net.minecraft.server.network.ServerGamePacketListenerImpl");
                Class<?> connectionClass = tryClass("net.minecraft.network.Connection");
                Class<?> packetListenerClass = tryClass("net.minecraft.network.PacketListener");
                Constructor<?> serverGamePacketListenerConstructor = null;
                Field connectionField = null;
                Method connectionSetterMethod = null;
                Method connectionSetListenerMethod = null;
                if (connectionClass != null && packetListenerClass != null) {
                    connectionSetListenerMethod = findMethod(connectionClass, "setListener", packetListenerClass);
                }
                if (serverGamePacketListenerClass != null && connectionClass != null) {
                    serverGamePacketListenerConstructor = selectServerGamePacketListenerConstructor(
                        serverGamePacketListenerClass,
                        minecraftServer.getClass(),
                        connectionClass,
                        serverPlayerClass
                    );
                    connectionField = findField(serverPlayerClass, serverGamePacketListenerClass, "connection");
                }
                if (connectionField == null && serverGamePacketListenerClass != null) {
                    connectionSetterMethod = findSingleParamMethod(
                        serverPlayerClass,
                        serverGamePacketListenerClass,
                        "setConnection",
                        "connection",
                        "setListener",
                        "setupConnection"
                    );
                }
                if (connectionField == null && connectionSetterMethod == null && packetListenerClass != null) {
                    connectionSetterMethod = findSingleParamMethod(
                        serverPlayerClass,
                        packetListenerClass,
                        "setConnection",
                        "connection",
                        "setListener",
                        "setupConnection"
                    );
                }
                if (connectionField == null && connectionSetterMethod == null) {
                    connectionField = findField(serverPlayerClass, null, "connection");
                }
                if (serverGamePacketListenerConstructor == null || (connectionField == null && connectionSetterMethod == null) || connectionClass == null) {
                    List<String> reasons = new ArrayList<>();
                    if (serverGamePacketListenerClass == null) {
                        reasons.add("missing net.minecraft.server.network.ServerGamePacketListenerImpl");
                    } else if (serverGamePacketListenerConstructor == null) {
                        reasons.add("unable to locate a usable ServerGamePacketListenerImpl constructor");
                    }
                    if (connectionClass == null) {
                        reasons.add("missing net.minecraft.network.Connection class");
                    }
                    if (packetListenerClass == null) {
                        reasons.add("missing net.minecraft.network.PacketListener class");
                    }
                    if (connectionField == null && connectionSetterMethod == null) {
                        reasons.add("no connection field or setter on ServerPlayer");
                    }
                    String suffix = reasons.isEmpty() ? "" : " Details: " + String.join("; ", reasons);
                    plugin.getLogger().warning("Unable to initialise simulated network connection; player emulation disabled." + suffix);
                    return null;
                }

                return new ReflectionBridge(
                    plugin,
                    minecraftServer,
                    serverPlayerConstructor,
                    playerList,
                    playerListRemove,
                    serverLevelAddPlayer,
                    getBukkitEntity,
                    moveToMethod,
                    teleportMethod,
                    setYawMethod,
                    setPitchMethod,
                    setPosRawMethod,
                    discardMethod,
                    removeMethod,
                    removalReason,
                    gameProfileConstructor,
                    defaultClientInformation,
                    clientInformationClass,
                    serverPlayerClass,
                    serverLevelClass,
                    getClientInformationMethod,
                    clientInformationField,
                    serverGamePacketListenerConstructor,
                    connectionField,
                    connectionSetterMethod,
                    connectionClass,
                    packetListenerClass,
                    connectionSetListenerMethod
                );
            } catch (ReflectiveOperationException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to initialise player emulation bridge", exception);
                return null;
            }
        }

        private static Constructor<?> selectServerPlayerConstructor(Class<?> serverPlayerClass) {
            Constructor<?>[] constructors = serverPlayerClass.getConstructors();
            Constructor<?> chosen = null;
            int score = -1;
            for (Constructor<?> constructor : constructors) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length < 3) {
                    continue;
                }
                if (!params[0].getName().equals("net.minecraft.server.MinecraftServer")) {
                    continue;
                }
                if (!params[1].getName().equals("net.minecraft.server.level.ServerLevel")) {
                    continue;
                }
                if (!params[2].getName().equals("com.mojang.authlib.GameProfile")) {
                    continue;
                }
                int constructorScore = params.length;
                if (constructorScore > score) {
                    constructor.setAccessible(true);
                    chosen = constructor;
                    score = constructorScore;
                }
            }
            return chosen;
        }

        private static Constructor<?> selectServerGamePacketListenerConstructor(Class<?> listenerClass,
                                                                                Class<?> minecraftServerClass,
                                                                                Class<?> connectionClass,
                                                                                Class<?> serverPlayerClass) {
            Constructor<?> chosen = null;
            int score = -1;
            for (Constructor<?> constructor : listenerClass.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                boolean hasServer = false;
                boolean hasConnection = false;
                boolean hasPlayer = false;
                for (Class<?> param : params) {
                    if (!hasServer && minecraftServerClass.isAssignableFrom(param)) {
                        hasServer = true;
                    }
                    if (!hasConnection && param.isAssignableFrom(connectionClass)) {
                        hasConnection = true;
                    }
                    if (!hasPlayer && param.isAssignableFrom(serverPlayerClass)) {
                        hasPlayer = true;
                    }
                }
                if (!(hasServer && hasConnection && hasPlayer)) {
                    continue;
                }
                int constructorScore = params.length;
                if (constructorScore > score) {
                    constructor.setAccessible(true);
                    chosen = constructor;
                    score = constructorScore;
                }
            }
            if (chosen != null) {
                return chosen;
            }
            Constructor<?>[] constructors = listenerClass.getConstructors();
            Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> ctor) -> ctor.getParameterCount()).reversed());
            for (Constructor<?> constructor : constructors) {
                Class<?>[] params = constructor.getParameterTypes();
                boolean hasServer = false;
                boolean hasConnection = false;
                boolean hasPlayer = false;
                for (Class<?> param : params) {
                    if (!hasServer && minecraftServerClass.isAssignableFrom(param)) {
                        hasServer = true;
                    }
                    if (!hasConnection && param.isAssignableFrom(connectionClass)) {
                        hasConnection = true;
                    }
                    if (!hasPlayer && param.isAssignableFrom(serverPlayerClass)) {
                        hasPlayer = true;
                    }
                }
                if (hasServer && hasPlayer) {
                    constructor.setAccessible(true);
                    return constructor;
                }
            }
            return null;
        }

        private static Class<?> tryClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException exception) {
                return null;
            }
        }

        private static Object createDefaultClientInformation(Class<?> clientInformationClass) {
            if (clientInformationClass == null) {
                return null;
            }
            try {
                Object viaFactory = invokeStaticFactory(clientInformationClass, "createDefault", "defaultOptions", "defaultConfig", "defaultSettings", "defaultInstance");
                if (viaFactory != null) {
                    return viaFactory;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Method candidate = findZeroArgFactoryMethod(clientInformationClass);
                if (candidate != null) {
                    Object result = candidate.invoke(null);
                    if (result != null) {
                        return result;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Constructor<?> ctor = clientInformationClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException ignored) {
            }
            if (clientInformationClass.isRecord()) {
                try {
                    Object record = instantiateRecord(clientInformationClass);
                    if (record != null) {
                        return record;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            try {
                Object constructed = instantiateWithConstructors(clientInformationClass);
                if (constructed != null) {
                    return constructed;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return null;
        }

        private static Method findZeroArgFactoryMethod(Class<?> clientInformationClass) {
            for (Method method : clientInformationClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!clientInformationClass.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                if (method.getParameterCount() != 0) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("default") && !name.contains("create") && !name.contains("factory")) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
            return null;
        }

        private static Object instantiateRecord(Class<?> recordClass) throws ReflectiveOperationException {
            RecordComponent[] components = recordClass.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                values[i] = defaultValueForType(component.getType(), component.getGenericType(), component.getName());
            }
            Constructor<?> canonical = recordClass.getDeclaredConstructor(parameterTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(values);
        }

        private static Object defaultValueForType(Class<?> type, java.lang.reflect.Type genericType, String name) throws ReflectiveOperationException {
            if (type.isPrimitive()) {
                if (type == boolean.class) {
                    return false;
                } else if (type == byte.class) {
                    return (byte) 0;
                } else if (type == short.class) {
                    return (short) 0;
                } else if (type == int.class) {
                    if (name.toLowerCase(Locale.ROOT).contains("distance")) {
                        return 10;
                    }
                    return 0;
                } else if (type == long.class) {
                    return 0L;
                } else if (type == float.class) {
                    return 0.0f;
                } else if (type == double.class) {
                    return 0.0d;
                } else if (type == char.class) {
                    return (char) 0;
                }
            } else if (String.class.isAssignableFrom(type)) {
                return "en_us";
            } else if (Enum.class.isAssignableFrom(type)) {
                Object[] constants = type.getEnumConstants();
                if (constants != null && constants.length > 0) {
                    return constants[0];
                }
                return null;
            } else if (EnumSet.class.isAssignableFrom(type)) {
                Class<?> elementType = extractEnumType(genericType);
                if (elementType != null && Enum.class.isAssignableFrom(elementType)) {
                    return EnumSet.noneOf((Class<? extends Enum>) elementType);
                }
                return EnumSet.noneOf(DummyEnum.class);
            } else if (Optional.class.isAssignableFrom(type)) {
                return Optional.empty();
            } else if (type == OptionalInt.class) {
                return OptionalInt.empty();
            } else if (type == OptionalLong.class) {
                return OptionalLong.empty();
            } else if (type == OptionalDouble.class) {
                return OptionalDouble.empty();
            } else if (Collection.class.isAssignableFrom(type)) {
                if (Set.class.isAssignableFrom(type)) {
                    return Collections.emptySet();
                } else if (List.class.isAssignableFrom(type)) {
                    return Collections.emptyList();
                }
                return Collections.emptyList();
            } else if (Map.class.isAssignableFrom(type)) {
                return Collections.emptyMap();
            } else if (UUID.class.isAssignableFrom(type)) {
                return new UUID(0L, 0L);
            } else if (Locale.class.isAssignableFrom(type)) {
                return Locale.US;
            }
            Object constant = extractStaticConstant(type, "DEFAULT", "EMPTY", "INSTANCE", "ZERO", "NONE");
            if (constant != null) {
                return constant;
            }
            Object viaFactory = invokeStaticFactory(type, "createDefault", "createEmpty", "empty", "of");
            if (viaFactory != null) {
                return viaFactory;
            }
            try {
                Constructor<?> ctor = type.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException ignored) {
                // Fall through
            }
            return null;
        }

        private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }

        private static Method findPositionMethod(Class<?> type, String name) {
            Method bestMatch = null;
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 3) {
                    continue;
                }
                if (!(isCoordinateType(params[0]) && isCoordinateType(params[1]) && isCoordinateType(params[2]))) {
                    continue;
                }
                method.setAccessible(true);
                if (bestMatch == null || params.length > bestMatch.getParameterCount()) {
                    bestMatch = method;
                }
            }
            return bestMatch;
        }

        private static boolean isCoordinateType(Class<?> type) {
            return type == double.class || type == Double.class;
        }

        private static Method findZeroArgMethod(Class<?> type, String... candidates) {
            for (String candidate : candidates) {
                try {
                    Method method = type.getMethod(candidate);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    Method method = type.getDeclaredMethod(candidate);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }

        private static Field findField(Class<?> type, Class<?> expectedType, String... candidates) {
            for (String candidate : candidates) {
                try {
                    Field field = type.getDeclaredField(candidate);
                    if (expectedType == null || expectedType.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (expectedType == null || expectedType.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            return null;
        }

        private static Method findSingleParamMethod(Class<?> type, Class<?> argument, String... preferredNames) {
            for (String preferred : preferredNames) {
                try {
                    Method method = type.getMethod(preferred, argument);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(argument)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }

        private static Object findEnumConstant(Class<?> enumClass, String name) {
            if (!enumClass.isEnum()) {
                return null;
            }
            Object[] constants = enumClass.getEnumConstants();
            for (Object constant : constants) {
                if (constant.toString().equalsIgnoreCase(name)) {
                    return constant;
                }
            }
            return constants.length > 0 ? constants[0] : null;
        }

        SimulatedPlayer spawn(Location location, String name) {
            try {
                Object craftWorld = location.getWorld();
                Method getHandle = craftWorld.getClass().getMethod("getHandle");
                Object serverLevel = getHandle.invoke(craftWorld);
                UUID uuid = generateProfileUuid(name);
                Object profile = gameProfileConstructor.newInstance(uuid, name);
                Object[] args = buildConstructorArguments(serverLevel, profile);
                Object serverPlayer = serverPlayerConstructor.newInstance(args);
                if (!attachSimulatedConnection(serverPlayer)) {
                    plugin.getLogger().warning("Unable to attach simulated connection for player '" + name + "'");
                    return null;
                }
                positionPlayer(serverPlayer, location);
                serverLevelAddPlayer.invoke(serverLevel, serverPlayer);

                Player bukkitPlayer = null;
                Object bukkitEntity = getBukkitEntity.invoke(serverPlayer);
                if (bukkitEntity instanceof Player player) {
                    bukkitPlayer = player;
                    configureBukkitPlayer(player);
                }

                return new SimulatedPlayer(uuid, name, serverPlayer, bukkitPlayer);
            } catch (ReflectiveOperationException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to spawn simulated player '" + name + "'", exception);
                return null;
            }
        }

        private boolean attachSimulatedConnection(Object serverPlayer) throws ReflectiveOperationException {
            Object connection = createConnection();
            if (connection == null) {
                return false;
            }
            Object listener = createPacketListener(serverPlayer, connection);
            if (listener == null) {
                return false;
            }
            if (connectionSetListenerMethod != null && packetListenerClass != null && packetListenerClass.isInstance(listener)) {
                connectionSetListenerMethod.invoke(connection, listener);
            }
            if (connectionField != null) {
                connectionField.set(serverPlayer, listener);
            } else if (connectionSetterMethod != null) {
                connectionSetterMethod.invoke(serverPlayer, listener);
            }
            return true;
        }

        private Object createConnection() throws ReflectiveOperationException {
            Constructor<?>[] constructors = connectionClass.getDeclaredConstructors();
            Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> ctor) -> ctor.getParameterCount()));
            for (Constructor<?> constructor : constructors) {
                Object instance = invokeWithDefaultArguments(null, constructor);
                if (instance != null) {
                    return instance;
                }
            }
            return instantiateWithConstructors(connectionClass);
        }

        private Object createPacketListener(Object serverPlayer, Object connection) throws ReflectiveOperationException {
            Constructor<?> constructor = serverGamePacketListenerConstructor;
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            java.lang.reflect.Type[] genericTypes = constructor.getGenericParameterTypes();
            Parameter[] parameters = constructor.getParameters();
            Object[] values = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (parameterType.isInstance(minecraftServer)) {
                    values[i] = minecraftServer;
                } else if (parameterType.isAssignableFrom(connectionClass)) {
                    values[i] = connection;
                } else if (parameterType.isAssignableFrom(serverPlayerClass)) {
                    values[i] = serverPlayer;
                } else {
                    String name = parameters.length > i ? parameters[i].getName() : "";
                    values[i] = defaultValueForType(parameterType, genericTypes[i], name);
                    if (values[i] == null) {
                        values[i] = instantiateWithConstructors(parameterType);
                    }
                    if (values[i] == null && parameterType.isPrimitive()) {
                        return null;
                    }
                }
            }
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        }

        void remove(SimulatedPlayer player) throws InvocationTargetException, IllegalAccessException {
            if (player.bukkit != null && player.bukkit.isOnline()) {
                player.bukkit.remove();
            }
            if (playerListRemove != null) {
                playerListRemove.invoke(playerList, player.handle);
            }
            if (discardMethod != null) {
                discardMethod.invoke(player.handle);
            } else if (removeMethod != null) {
                removeMethod.invoke(player.handle, removalReason);
            }
        }

        private UUID generateProfileUuid(String name) {
            return UUID.nameUUIDFromBytes(("chunksloader:" + name).getBytes(StandardCharsets.UTF_8));
        }

        private Object[] buildConstructorArguments(Object serverLevel, Object profile) {
            Class<?>[] params = serverPlayerConstructor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> param = params[i];
                if (param.getName().equals("net.minecraft.server.MinecraftServer")) {
                    args[i] = minecraftServer;
                } else if (serverLevelClass.isAssignableFrom(param)) {
                    args[i] = serverLevel;
                } else if (param.getName().equals("com.mojang.authlib.GameProfile")) {
                    args[i] = profile;
                } else if (clientInformationClass != null && param.isAssignableFrom(clientInformationClass)) {
                    args[i] = resolveClientInformation();
                } else if (param.isPrimitive()) {
                    args[i] = primitiveDefault(param);
                } else {
                    args[i] = null;
                }
            }
            return args;
        }

        private Object primitiveDefault(Class<?> type) {
            if (type == boolean.class) {
                return false;
            } else if (type == byte.class) {
                return (byte) 0;
            } else if (type == short.class) {
                return (short) 0;
            } else if (type == int.class) {
                return 0;
            } else if (type == long.class) {
                return 0L;
            } else if (type == float.class) {
                return 0.0f;
            } else if (type == double.class) {
                return 0.0d;
            } else if (type == char.class) {
                return (char) 0;
            }
            return null;
        }

        private void positionPlayer(Object serverPlayer, Location location) throws InvocationTargetException, IllegalAccessException {
            boolean positioned = false;
            boolean handledOrientation = false;
            PositioningResult moveResult = invokePositioningMethod(serverPlayer, moveToMethod, location, false);
            if (moveResult.positioned()) {
                positioned = true;
                handledOrientation = moveResult.handledOrientation();
            }
            if (!positioned) {
                PositioningResult teleportResult = invokePositioningMethod(serverPlayer, teleportMethod, location, true);
                if (teleportResult.positioned()) {
                    positioned = true;
                    handledOrientation = teleportResult.handledOrientation();
                }
            }
            if (!positioned) {
                PositioningResult rawResult = invokePositioningMethod(serverPlayer, setPosRawMethod, location, false);
                if (rawResult.positioned()) {
                    positioned = true;
                    handledOrientation = handledOrientation || rawResult.handledOrientation();
                }
            }
            if (!handledOrientation && setYawMethod != null) {
                setYawMethod.invoke(serverPlayer, location.getYaw());
            }
            if (!handledOrientation && setPitchMethod != null) {
                setPitchMethod.invoke(serverPlayer, location.getPitch());
            }
        }

        private PositioningResult invokePositioningMethod(Object serverPlayer, Method method, Location location, boolean ignoreConnectionNullPointer) throws InvocationTargetException, IllegalAccessException {
            if (method == null) {
                return PositioningResult.NOT_POSITIONED;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            arguments[0] = location.getX();
            arguments[1] = location.getY();
            arguments[2] = location.getZ();
            int orientationIndex = 0;
            for (int i = 3; i < parameterTypes.length; i++) {
                Class<?> type = parameterTypes[i];
                if (type == float.class || type == Float.class) {
                    float value = orientationIndex == 0 ? location.getYaw() : orientationIndex == 1 ? location.getPitch() : 0.0f;
                    arguments[i] = value;
                    orientationIndex++;
                } else if (type == double.class || type == Double.class) {
                    double value = orientationIndex == 0 ? location.getYaw() : orientationIndex == 1 ? location.getPitch() : 0.0d;
                    arguments[i] = value;
                    orientationIndex++;
                } else if (type == boolean.class || type == Boolean.class) {
                    arguments[i] = Boolean.FALSE;
                } else if (type == int.class || type == Integer.class) {
                    arguments[i] = 0;
                } else if (type == long.class || type == Long.class) {
                    arguments[i] = 0L;
                } else if (type == short.class || type == Short.class) {
                    arguments[i] = (short) 0;
                } else if (type == byte.class || type == Byte.class) {
                    arguments[i] = (byte) 0;
                } else if (type == char.class || type == Character.class) {
                    arguments[i] = (char) 0;
                } else {
                    arguments[i] = null;
                }
            }
            try {
                method.invoke(serverPlayer, arguments);
                return new PositioningResult(true, orientationIndex >= 2);
            } catch (InvocationTargetException exception) {
                if (ignoreConnectionNullPointer && isConnectionNullPointer(exception.getCause())) {
                    plugin.getLogger().log(Level.FINEST, "Ignored connection-less teleport failure for simulated player positioning", exception.getCause());
                    return PositioningResult.NOT_POSITIONED;
                }
                throw exception;
            }
        }

        private boolean isConnectionNullPointer(Throwable throwable) {
            if (!(throwable instanceof NullPointerException)) {
                return false;
            }
            String message = throwable.getMessage();
            if (message != null && message.contains("this.connection")) {
                return true;
            }
            for (StackTraceElement element : throwable.getStackTrace()) {
                if ("net.minecraft.server.level.ServerPlayer".equals(element.getClassName()) && "teleportTo".equals(element.getMethodName())) {
                    return true;
                }
            }
            return false;
        }

        private record PositioningResult(boolean positioned, boolean handledOrientation) {
            private static final PositioningResult NOT_POSITIONED = new PositioningResult(false, false);
        }

        private void configureBukkitPlayer(Player player) {
            player.setGameMode(GameMode.SPECTATOR);
            player.setInvisible(true);
            player.setInvulnerable(true);
            player.setCollidable(false);
            player.setSleepingIgnored(true);
            player.setGravity(false);
        }

        private Object resolveClientInformation() {
            if (clientInformationClass == null) {
                return null;
            }
            if (defaultClientInformation != null) {
                return defaultClientInformation;
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                try {
                    Object info = extractClientInformation(online);
                    if (info != null) {
                        return info;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return null;
        }

        private Object extractClientInformation(Player player) throws ReflectiveOperationException {
            if (clientInformationClass == null) {
                return null;
            }
            Object craftPlayer = player;
            Method getHandle = craftPlayer.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(craftPlayer);
            if (getClientInformationMethod != null) {
                Object value = getClientInformationMethod.invoke(handle);
                if (value != null) {
                    return value;
                }
            }
            if (clientInformationField != null) {
                Object value = clientInformationField.get(handle);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static Object invokeStaticFactory(Class<?> type, String... names) throws ReflectiveOperationException {
            for (String name : names) {
                try {
                    for (Method method : type.getDeclaredMethods()) {
                        if (!method.getName().equals(name)) {
                            continue;
                        }
                        if (!Modifier.isStatic(method.getModifiers())) {
                            continue;
                        }
                        if (!type.isAssignableFrom(method.getReturnType())) {
                            continue;
                        }
                        Object value = invokeWithDefaultArguments(null, method);
                        if (value != null) {
                            return value;
                        }
                    }
                } catch (SecurityException ignored) {
                }
            }
            return null;
        }

        private static Object instantiateWithConstructors(Class<?> type) throws ReflectiveOperationException {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> ctor) -> ctor.getParameterCount()));
            for (Constructor<?> constructor : constructors) {
                Object value = invokeWithDefaultArguments(null, constructor);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static Object invokeWithDefaultArguments(Object target, Executable executable) throws ReflectiveOperationException {
            Class<?>[] parameterTypes = executable.getParameterTypes();
            if (parameterTypes.length == 0) {
                if (executable instanceof Method method) {
                    method.setAccessible(true);
                    return method.invoke(target);
                } else if (executable instanceof Constructor<?> constructor) {
                    constructor.setAccessible(true);
                    return constructor.newInstance();
                }
            }
            Object[] values = new Object[parameterTypes.length];
            Parameter[] parameters = executable.getParameters();
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                String parameterName = parameters.length > i ? parameters[i].getName() : "";
                Object value = defaultValueForType(parameterType, parameters.length > i ? parameters[i].getParameterizedType() : parameterType, parameterName);
                if (value == null && parameterType.isPrimitive()) {
                    return null;
                }
                values[i] = value;
            }
            executable.setAccessible(true);
            if (executable instanceof Method method) {
                return method.invoke(target, values);
            }
            Constructor<?> constructor = (Constructor<?>) executable;
            return constructor.newInstance(values);
        }

        private static Object extractStaticConstant(Class<?> type, String... names) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value != null) {
                            return value;
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return null;
        }

        private static Class<?> extractEnumType(Type genericType) {
            if (genericType instanceof ParameterizedType parameterizedType) {
                Type[] arguments = parameterizedType.getActualTypeArguments();
                if (arguments.length == 1 && arguments[0] instanceof Class<?> cls) {
                    return cls;
                }
            }
            return null;
        }

        private enum DummyEnum {
        }
    }
}
