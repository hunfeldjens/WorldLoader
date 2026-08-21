package eu.hunfeld.worldloader.world;

import eu.hunfeld.worldloader.WorldLoader;
import eu.hunfeld.worldloader.config.PluginSettings;
import eu.hunfeld.worldloader.model.ManagedWorld;
import eu.hunfeld.worldloader.model.WorldTypeMode;
import eu.hunfeld.worldloader.storage.WorldStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class WorldManager {

    public enum Status {
        SUCCESS,
        ALREADY_EXISTS,
        ALREADY_LOADED,
        MISSING,
        IN_USE,
        BLOCKED,
        FAILED,
        EMPTY
    }

    public record Result(@NotNull Status status, @NotNull String actualName,
                         @Nullable ManagedWorld world, int count) {
        static Result of(@NotNull final Status status, @NotNull final String actualName) {
            return new Result(status, actualName, null, 0);
        }

        static Result of(@NotNull final Status status, @NotNull final ManagedWorld world) {
            return new Result(status, world.id(), world, 0);
        }

        @NotNull
        public ManagedWorld requireWorld() {
            if (world == null) {
                throw new IllegalStateException("Result does not contain a managed world");
            }
            return world;
        }
    }

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final long NEXT_TICK = 1L;
    private static final int ENABLED_FIRE_SPREAD_RADIUS = 128;

    private final WorldLoader plugin;
    private final WorldStorage storage;
    private final Path worldContainer;
    private final Path root;
    private final Map<String, String> dimensions = new ConcurrentHashMap<>();
    private final Map<String, ManagedWorld> worlds = new ConcurrentHashMap<>();
    private final Map<String, ManagedWorld> worldsByBukkitName = new ConcurrentHashMap<>();
    private final Map<String, String> diskDimensions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> diskWorlds = new ConcurrentHashMap<>();
    private final Map<String, Long> lastEmptySince = new HashMap<>();
    private final Set<String> loadedByPlugin = new HashSet<>();
    private final Set<String> deleting = ConcurrentHashMap.newKeySet();

    private volatile PluginSettings settings;
    private volatile boolean ready;
    private BukkitTask idleTask;

    public WorldManager(@NotNull final WorldLoader plugin, @NotNull final WorldStorage storage,
                        @NotNull final PluginSettings settings, @NotNull final Path worldContainer,
                        @NotNull final Path root) {
        this.plugin = plugin;
        this.storage = storage;
        this.settings = settings;
        this.worldContainer = worldContainer;
        this.root = root;
    }

    @NotNull
    public CompletableFuture<Void> initialize() {
        return storage.load().thenCombine(storage.scan(root), (snapshot, disk) -> {
            for (final String dimension : snapshot.dimensions()) {
                if (validName(dimension)) {
                    dimensions.putIfAbsent(lower(dimension), dimension);
                }
            }
            for (final ManagedWorld world : snapshot.worlds()) {
                if (!validName(world.dimension()) || !validName(world.name())) {
                    plugin.getLogger().warning("Ignored invalid storage entry: " + world.id());
                    continue;
                }
                dimensions.putIfAbsent(lower(world.dimension()), world.dimension());
                worlds.putIfAbsent(world.lookupKey(), world);
                worldsByBukkitName.putIfAbsent(internalWorldName(world), world);
            }
            diskDimensions.putAll(disk.dimensions());
            disk.worlds().forEach((dimension, names) -> diskWorlds.put(dimension, new ConcurrentHashMap<>(names)));
            ready = true;
            return null;
        });
    }

    public boolean isInitializing() {
        return !ready;
    }

    @NotNull
    public Path root() {
        return root;
    }

    public void updateSettings(@NotNull final PluginSettings settings) {
        this.settings = settings;
        restartIdleTask();
    }

    public void startIdleTask() {
        restartIdleTask();
    }

    public void stop() {
        if (idleTask != null) {
            idleTask.cancel();
            idleTask = null;
        }
        storage.close();
    }

    public boolean validName(@NotNull final String name) {
        return VALID_NAME.matcher(name).matches();
    }

    @NotNull
    public Optional<ManagedWorld> findWorld(@NotNull final String id) {
        final String[] parts = splitId(id);
        return parts == null ? Optional.empty() : Optional.ofNullable(worlds.get(ManagedWorld.lookupKey(parts[0], parts[1])));
    }

    @Nullable
    public String[] splitId(@NotNull final String id) {
        final int separator = id.indexOf(':');
        if (separator <= 0 || separator != id.lastIndexOf(':') || separator == id.length() - 1) {
            return null;
        }
        final String dimension = id.substring(0, separator);
        final String world = id.substring(separator + 1);
        return validName(dimension) && validName(world) ? new String[]{dimension, world} : null;
    }

    public boolean isBlocked(@NotNull final String dimension, @Nullable final String world) {
        return settings.isBlocked(dimension, world);
    }

    @NotNull
    public Optional<String> blockedTargetInDimension(@NotNull final String dimension) {
        if (settings.isBlocked(dimension, null)) {
            return Optional.of(dimension);
        }
        final Map<String, String> diskNames = diskWorlds.get(lower(dimension));
        if (diskNames != null) {
            for (final String world : diskNames.values()) {
                if (settings.isBlocked(dimension, world)) {
                    return Optional.of(dimension + ':' + world);
                }
            }
        }
        return worlds.values().stream()
                .filter(world -> world.dimension().equalsIgnoreCase(dimension))
                .filter(world -> settings.isBlocked(world.dimension(), world.name()))
                .map(ManagedWorld::id)
                .findFirst();
    }

    @NotNull
    public CompletableFuture<Result> createDimension(@NotNull final String requestedName) {
        final String lookup = lower(requestedName);
        synchronized (dimensions) {
            final String collision = dimensionCollision(lookup);
            if (collision != null) {
                return CompletableFuture.completedFuture(Result.of(Status.ALREADY_EXISTS, collision));
            }
            dimensions.put(lookup, requestedName);
            diskDimensions.put(lookup, requestedName);
            diskWorlds.put(lookup, new ConcurrentHashMap<>());
        }
        return storage.createDirectory(root.resolve(requestedName)).handle((ignored, throwable) -> {
            if (throwable != null) {
                dimensions.remove(lookup, requestedName);
                diskDimensions.remove(lookup, requestedName);
                diskWorlds.remove(lookup);
                storage.logFailure(throwable);
                return Result.of(Status.FAILED, requestedName);
            }
            saveSnapshot();
            return Result.of(Status.SUCCESS, requestedName);
        });
    }

    @NotNull
    public Result createWorld(@NotNull final String requestedDimension, @NotNull final String requestedWorld,
                              @NotNull final WorldTypeMode type) {
        requirePrimaryThread();
        final String dimension = dimensions.get(lower(requestedDimension));
        if (dimension == null) {
            return Result.of(Status.MISSING, requestedDimension);
        }
        final String worldLookup = lower(requestedWorld);
        final String existing = worldCollision(dimension, worldLookup);
        if (existing != null) {
            return Result.of(Status.ALREADY_EXISTS, dimension + ':' + existing);
        }

        final ManagedWorld managed = new ManagedWorld(dimension, requestedWorld, type);
        final World world = createBukkitWorld(managed, true);
        if (world == null) {
            return Result.of(Status.FAILED, managed);
        }
        applyCreationDefaults(world, type);
        worlds.put(managed.lookupKey(), managed);
        worldsByBukkitName.put(internalWorldName(managed), managed);
        diskWorlds.computeIfAbsent(lower(dimension), ignored -> new ConcurrentHashMap<>())
                .put(worldLookup, requestedWorld);
        loadedByPlugin.add(managed.lookupKey());
        markUsage(world);
        saveSnapshot();
        return Result.of(Status.SUCCESS, managed);
    }

    @NotNull
    public Result loadWorld(@NotNull final ManagedWorld managed) {
        requirePrimaryThread();
        if (deleting.contains(managed.lookupKey())) {
            return Result.of(Status.MISSING, managed);
        }
        final World existing = getBukkitWorld(managed);
        if (existing != null) {
            loadedByPlugin.add(managed.lookupKey());
            markUsage(existing);
            return Result.of(Status.ALREADY_LOADED, managed);
        }
        final World world = createBukkitWorld(managed, false);
        if (world == null) {
            return Result.of(Status.FAILED, managed);
        }
        loadedByPlugin.add(managed.lookupKey());
        markUsage(world);
        return Result.of(Status.SUCCESS, managed);
    }

    @NotNull
    public Result unloadWorld(@NotNull final ManagedWorld managed, final boolean automatic) {
        requirePrimaryThread();
        final World world = getBukkitWorld(managed);
        if (world == null) {
            loadedByPlugin.remove(managed.lookupKey());
            lastEmptySince.remove(managed.lookupKey());
            return Result.of(Status.SUCCESS, managed);
        }
        if (!world.getPlayers().isEmpty()) {
            return Result.of(Status.IN_USE, managed);
        }
        final boolean save = !automatic || settings.saveBeforeUnload();
        if (!Bukkit.unloadWorld(world, save)) {
            return Result.of(Status.FAILED, managed);
        }
        loadedByPlugin.remove(managed.lookupKey());
        lastEmptySince.remove(managed.lookupKey());
        return Result.of(Status.SUCCESS, managed);
    }

    @NotNull
    public Result unloadDimension(@NotNull final String requestedDimension) {
        requirePrimaryThread();
        final String dimension = dimensions.get(lower(requestedDimension));
        if (dimension == null) {
            return Result.of(Status.MISSING, requestedDimension);
        }
        final List<ManagedWorld> contained = worlds.values().stream()
                .filter(world -> world.dimension().equalsIgnoreCase(dimension)).toList();
        for (final ManagedWorld managed : contained) {
            final World world = getBukkitWorld(managed);
            if (world != null && !world.getPlayers().isEmpty()) {
                return new Result(Status.IN_USE, dimension, null, 0);
            }
        }
        int unloaded = 0;
        for (final ManagedWorld managed : contained) {
            if (getBukkitWorld(managed) != null) {
                final Result result = unloadWorld(managed, false);
                if (result.status() != Status.SUCCESS) {
                    return new Result(Status.FAILED, dimension, null, unloaded);
                }
                unloaded++;
            }
        }
        return new Result(Status.SUCCESS, dimension, null, unloaded);
    }

    @NotNull
    public CompletableFuture<Optional<Location>> teleportLocation(@NotNull final ManagedWorld managed) {
        requirePrimaryThread();
        final World world = getBukkitWorld(managed);
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Location spawn = world.getSpawnLocation();
        if (!settings.safeSpawn() || managed.type() == WorldTypeMode.AIR) {
            return CompletableFuture.completedFuture(Optional.of(center(spawn)));
        }
        return world.getChunkAtAsync(spawn).thenApply(ignored -> {
            final int y = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1;
            return Optional.of(center(new Location(world, spawn.getX(), y, spawn.getZ(), spawn.getYaw(), spawn.getPitch())));
        });
    }

    private Location center(final Location location) {
        if (settings.centerOnBlock()) {
            location.setX(location.getBlockX() + 0.5D);
            location.setZ(location.getBlockZ() + 0.5D);
        }
        return location;
    }

    public void playerLeftWorld(@NotNull final World world) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> markUsage(world), NEXT_TICK);
    }

    public void playerEnteredWorld(@NotNull final World world) {
        final ManagedWorld managed = byBukkitWorld(world);
        if (managed != null) {
            lastEmptySince.remove(managed.lookupKey());
        }
    }

    public boolean isLoaded(@NotNull final ManagedWorld world) {
        return getBukkitWorld(world) != null;
    }

    @NotNull
    public List<String> visibleDimensions() {
        return dimensions.values().stream()
                .filter(dimension -> !settings.isBlocked(dimension, null))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @NotNull
    public List<ManagedWorld> visibleWorlds(@NotNull final String dimension) {
        return worlds.values().stream()
                .filter(world -> world.dimension().equalsIgnoreCase(dimension))
                .filter(world -> !settings.isBlocked(world.dimension(), world.name()))
                .sorted(Comparator.comparing(ManagedWorld::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @NotNull
    public List<String> visibleWorldIds() {
        return worlds.values().stream()
                .filter(world -> !settings.isBlocked(world.dimension(), world.name()))
                .map(ManagedWorld::id)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @NotNull
    public List<String> visibleDiskDimensions() {
        return diskDimensions.values().stream()
                .filter(dimension -> !settings.isBlocked(dimension, null))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @NotNull
    public CompletableFuture<Result> importDimension(@NotNull final String requestedDimension,
                                                     @NotNull final WorldTypeMode type) {
        final String lookup = lower(requestedDimension);
        return storage.scan(root).thenCompose(disk -> {
            diskDimensions.putAll(disk.dimensions());
            disk.worlds().forEach((dimension, names) ->
                    diskWorlds.put(dimension, new ConcurrentHashMap<>(names)));
            if (!disk.dimensions().containsKey(lookup)) {
                diskDimensions.remove(lookup);
                diskWorlds.remove(lookup);
            }
            final String actualDimension = diskDimensions.get(lookup);
            if (actualDimension == null) {
                return CompletableFuture.completedFuture(Result.of(Status.MISSING, requestedDimension));
            }
            return storage.scanWorldNames(root.resolve(actualDimension), settings.requireLevelDat()).handle((names, throwable) -> {
                if (throwable != null) {
                    storage.logFailure(throwable);
                    return Result.of(Status.FAILED, actualDimension);
                }
                int imported = 0;
                synchronized (dimensions) {
                    for (final String name : names) {
                        if (!validName(name) || settings.isBlocked(actualDimension, name)) {
                            continue;
                        }
                        final ManagedWorld managed = new ManagedWorld(actualDimension, name, type);
                        if (worlds.putIfAbsent(managed.lookupKey(), managed) == null) {
                            worldsByBukkitName.put(internalWorldName(managed), managed);
                            imported++;
                        }
                    }
                    if (imported > 0) {
                        dimensions.putIfAbsent(lookup, actualDimension);
                    }
                }
                if (imported > 0) {
                    saveSnapshot();
                    return new Result(Status.SUCCESS, actualDimension, null, imported);
                }
                return new Result(Status.EMPTY, actualDimension, null, 0);
            });
        }).exceptionally(throwable -> {
            storage.logFailure(throwable);
            return Result.of(Status.FAILED, requestedDimension);
        });
    }
    @NotNull
    public CompletableFuture<Result> deleteWorld(@NotNull final ManagedWorld managed) {
        requirePrimaryThread();
        if (!deleting.add(managed.lookupKey())) {
            return CompletableFuture.completedFuture(Result.of(Status.FAILED, managed));
        }
        if (!evacuateAndUnload(List.of(managed))) {
            deleting.remove(managed.lookupKey());
            return CompletableFuture.completedFuture(Result.of(Status.IN_USE, managed));
        }
        return storage.deleteDirectory(root, worldPath(managed)).handle((ignored, throwable) -> {
            deleting.remove(managed.lookupKey());
            if (throwable != null) {
                storage.logFailure(throwable);
                return Result.of(Status.FAILED, managed);
            }
            worlds.remove(managed.lookupKey(), managed);
            worldsByBukkitName.remove(internalWorldName(managed), managed);
            final Map<String, String> names = diskWorlds.get(lower(managed.dimension()));
            if (names != null) {
                names.remove(lower(managed.name()));
            }
            saveSnapshot();
            return Result.of(Status.SUCCESS, managed);
        });
    }

    @NotNull
    public CompletableFuture<Result> deleteDimension(@NotNull final String requestedDimension) {
        requirePrimaryThread();
        final String dimension = dimensions.get(lower(requestedDimension));
        if (dimension == null) {
            return CompletableFuture.completedFuture(Result.of(Status.MISSING, requestedDimension));
        }
        final List<ManagedWorld> contained = worlds.values().stream()
                .filter(world -> world.dimension().equalsIgnoreCase(dimension)).toList();
        final List<String> keys = contained.stream().map(ManagedWorld::lookupKey).toList();
        if (keys.stream().anyMatch(deleting::contains)) {
            return CompletableFuture.completedFuture(Result.of(Status.FAILED, dimension));
        }
        deleting.addAll(keys);
        return storage.scan(root).thenCompose(disk -> {
            final String dimensionKey = lower(dimension);
            final Map<String, String> scannedWorlds = disk.worlds().get(dimensionKey);
            if (scannedWorlds == null) {
                diskWorlds.remove(dimensionKey);
            } else {
                diskWorlds.put(dimensionKey, new ConcurrentHashMap<>(scannedWorlds));
            }
            final Optional<String> blocked = blockedTargetInDimension(dimension);
            if (blocked.isPresent()) {
                removeDeleting(keys);
                return CompletableFuture.completedFuture(Result.of(Status.BLOCKED, blocked.get()));
            }
            return onMain(() -> evacuateAndUnload(contained)
                    ? Result.of(Status.SUCCESS, dimension)
                    : Result.of(Status.IN_USE, dimension));
        }).thenCompose(preparation -> {
            if (preparation.status() != Status.SUCCESS) {
                removeDeleting(keys);
                return CompletableFuture.completedFuture(preparation);
            }
            return storage.deleteDirectory(root, root.resolve(dimension)).handle((ignored, throwable) -> {
                removeDeleting(keys);
                if (throwable != null) {
                    storage.logFailure(throwable);
                    return Result.of(Status.FAILED, dimension);
                }
                contained.forEach(world -> {
                    worlds.remove(world.lookupKey(), world);
                    worldsByBukkitName.remove(internalWorldName(world), world);
                });
                dimensions.remove(lower(dimension), dimension);
                diskDimensions.remove(lower(dimension));
                diskWorlds.remove(lower(dimension));
                saveSnapshot();
                return new Result(Status.SUCCESS, dimension, null, contained.size());
            });
        }).exceptionally(throwable -> {
            removeDeleting(keys);
            storage.logFailure(throwable);
            return Result.of(Status.FAILED, dimension);
        });
    }

    @NotNull
    public Optional<String> actualDimension(@NotNull final String requested) {
        return Optional.ofNullable(dimensions.get(lower(requested)));
    }

    private World createBukkitWorld(final ManagedWorld managed, final boolean newWorld) {
        try {
            final WorldCreator creator = new WorldCreator(internalWorldName(managed))
                    .environment(World.Environment.NORMAL)
                    .generateStructures(settings.generateStructures());
            switch (managed.type()) {
                case AIR -> creator.generator(VoidChunkGenerator.INSTANCE).generateStructures(false);
                case FLAT -> creator.type(WorldType.FLAT);
                case NORMAL -> creator.type(WorldType.NORMAL);
            }
            final World world = creator.createWorld();
            if (world != null && newWorld) {
                world.setAutoSave(true);
            }
            return world;
        } catch (final RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "World could not be loaded: " + managed.id(), exception);
            return null;
        }
    }

    private void applyCreationDefaults(final World world, final WorldTypeMode type) {
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, settings.randomTickSpeed());
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,
                settings.fireSpread() ? ENABLED_FIRE_SPREAD_RADIUS : 0);
        world.setGameRule(GameRules.SPAWN_MOBS, settings.mobSpawning());
        world.setGameRule(GameRules.ADVANCE_TIME, settings.daylightCycle());
        world.setGameRule(GameRules.ADVANCE_WEATHER, settings.weatherCycle());
        world.setStorm(false);
        world.setThundering(false);

        if (type != WorldTypeMode.AIR || !settings.airPlatformEnabled()) {
            return;
        }
        Material material = Material.matchMaterial(settings.airPlatformMaterial());
        if (material == null || !material.isBlock() || material.isAir()) {
            material = Material.GLASS;
        }
        final int y = Math.clamp(settings.airPlatformY(), world.getMinHeight() + 1, world.getMaxHeight() - 2);
        final int radius = settings.airPlatformRadius();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                world.getBlockAt(x, y, z).setType(material, false);
            }
        }
        world.setSpawnLocation(0, y + 1, 0);
    }

    private boolean evacuateAndUnload(final Collection<ManagedWorld> managedWorlds) {
        final World primary = Bukkit.getWorlds().stream()
                .filter(world -> byBukkitWorld(world) == null)
                .findFirst()
                .orElse(null);
        for (final ManagedWorld managed : managedWorlds) {
            final World world = getBukkitWorld(managed);
            if (world == null) {
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                if (!settings.evacuateBeforeDelete() || primary == null || primary.equals(world)) {
                    return false;
                }
                final Location fallback = primary.getSpawnLocation();
                for (final Player player : List.copyOf(world.getPlayers())) {
                    player.teleport(fallback);
                }
            }
            if (!Bukkit.unloadWorld(world, true)) {
                return false;
            }
            loadedByPlugin.remove(managed.lookupKey());
            lastEmptySince.remove(managed.lookupKey());
        }
        return true;
    }

    private void restartIdleTask() {
        if (idleTask != null) {
            idleTask.cancel();
            idleTask = null;
        }
        if (ready && settings.autoUnloadEnabled()) {
            idleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::unloadIdleWorlds,
                    settings.checkIntervalTicks(), settings.checkIntervalTicks());
        }
    }

    private void unloadIdleWorlds() {
        requirePrimaryThread();
        final long now = System.currentTimeMillis();
        for (final String key : List.copyOf(loadedByPlugin)) {
            final ManagedWorld managed = worlds.get(key);
            if (managed == null || deleting.contains(key)
                    || settings.isBlocked(managed.dimension(), managed.name())) {
                continue;
            }
            final World world = getBukkitWorld(managed);
            if (world == null) {
                loadedByPlugin.remove(key);
                lastEmptySince.remove(key);
                continue;
            }
            if (!world.getPlayers().isEmpty()) {
                lastEmptySince.remove(key);
                continue;
            }
            final long emptySince = lastEmptySince.computeIfAbsent(key, ignored -> now);
            if (now - emptySince >= settings.idleMillis()) {
                final Result result = unloadWorld(managed, true);
                if (result.status() == Status.SUCCESS) {
                    plugin.getLogger().info("Automatically unloaded idle world: " + managed.id());
                }
            }
        }
    }

    private void markUsage(final World world) {
        final ManagedWorld managed = byBukkitWorld(world);
        if (managed == null || !loadedByPlugin.contains(managed.lookupKey())) {
            return;
        }
        if (world.getPlayers().isEmpty()) {
            lastEmptySince.putIfAbsent(managed.lookupKey(), System.currentTimeMillis());
        } else {
            lastEmptySince.remove(managed.lookupKey());
        }
    }

    private ManagedWorld byBukkitWorld(final World world) {
        return worldsByBukkitName.get(world.getName());
    }

    private World getBukkitWorld(final ManagedWorld managed) {
        return Bukkit.getWorld(internalWorldName(managed));
    }

    private Path worldPath(final ManagedWorld managed) {
        return root.resolve(managed.dimension()).resolve(managed.name()).normalize();
    }

    private String internalWorldName(final ManagedWorld managed) {
        return worldContainer.relativize(worldPath(managed)).toString().replace(File.separatorChar, '/');
    }

    private String dimensionCollision(final String lookup) {
        final String registered = dimensions.get(lookup);
        return registered != null ? registered : diskDimensions.get(lookup);
    }

    private String worldCollision(final String dimension, final String lookup) {
        final ManagedWorld registered = worlds.get(ManagedWorld.lookupKey(dimension, lookup));
        if (registered != null) {
            return registered.name();
        }
        final Map<String, String> names = diskWorlds.get(lower(dimension));
        return names == null ? null : names.get(lookup);
    }

    private void saveSnapshot() {
        storage.save(snapshot()).exceptionally(throwable -> {
            storage.logFailure(throwable);
            return null;
        });
    }

    private CompletableFuture<Result> onMain(final Supplier<Result> action) {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(action.get());
        }
        final CompletableFuture<Result> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            future.complete(Result.of(Status.FAILED, "plugin-disabled"));
            return future;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (final RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private WorldStorage.Snapshot snapshot() {
        return new WorldStorage.Snapshot(Set.copyOf(dimensions.values()), List.copyOf(worlds.values()));
    }

    private void removeDeleting(final Collection<String> keys) {
        keys.forEach(deleting::remove);
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit world operation attempted outside the main thread");
        }
    }

    private static String lower(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
