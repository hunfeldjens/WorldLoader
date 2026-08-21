package eu.hunfeld.worldloader.storage;

import eu.hunfeld.worldloader.model.ManagedWorld;
import eu.hunfeld.worldloader.model.WorldTypeMode;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class WorldStorage {

    public record Snapshot(Set<String> dimensions, List<ManagedWorld> worlds) {
    }

    public record DiskSnapshot(Map<String, String> dimensions, Map<String, Map<String, String>> worlds) {
    }

    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Logger logger;
    private final ExecutorService executor;

    public WorldStorage(@NotNull final Path file, @NotNull final Logger logger) {
        this.file = file;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "WorldLoader-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    @NotNull
    public CompletableFuture<Snapshot> load() {
        return CompletableFuture.supplyAsync(() -> {
            if (Files.notExists(file)) {
                return new Snapshot(Set.of(), List.of());
            }
            final YamlConfiguration json = new YamlConfiguration();
            try {
                json.load(file.toFile());
            } catch (final IOException | InvalidConfigurationException exception) {
                throw new StorageException("storage.json could not be read", exception);
            }

            final Set<String> dimensions = new HashSet<>();
            final List<ManagedWorld> worlds = new ArrayList<>();
            for (final Map<?, ?> dimensionEntry : json.getMapList("dimensions")) {
                final Object rawName = dimensionEntry.get("name");
                if (!(rawName instanceof String dimension) || dimension.isBlank()) {
                    continue;
                }
                dimensions.add(dimension);
                final Object rawWorlds = dimensionEntry.get("worlds");
                if (!(rawWorlds instanceof List<?> worldEntries)) {
                    continue;
                }
                for (final Object entry : worldEntries) {
                    if (!(entry instanceof Map<?, ?> worldEntry)) {
                        continue;
                    }
                    final Object rawWorldName = worldEntry.get("name");
                    final Object rawType = worldEntry.get("type");
                    if (!(rawWorldName instanceof String worldName) || !(rawType instanceof String typeName)) {
                        continue;
                    }
                    WorldTypeMode.parse(typeName)
                            .map(type -> new ManagedWorld(dimension, worldName, type))
                            .ifPresent(worlds::add);
                }
            }
            return new Snapshot(Set.copyOf(dimensions), List.copyOf(worlds));
        }, executor);
    }

    @NotNull
    public CompletableFuture<DiskSnapshot> scan(@NotNull final Path root) {
        return CompletableFuture.supplyAsync(() -> {
            final Map<String, String> dimensions = new HashMap<>();
            final Map<String, Map<String, String>> worlds = new HashMap<>();
            try {
                if (Files.isSymbolicLink(root)) {
                    throw new StorageException("Symbolic world-root links are not supported: " + root);
                }
                Files.createDirectories(root);
                try (Stream<Path> dimensionPaths = Files.list(root)) {
                    dimensionPaths.forEach(dimensionPath -> {
                        if (Files.isSymbolicLink(dimensionPath)) {
                            throw new StorageException("Symbolic dimension links are not supported: " + dimensionPath);
                        }
                        if (!Files.isDirectory(dimensionPath, LinkOption.NOFOLLOW_LINKS)) {
                            return;
                        }
                        final String dimension = dimensionPath.getFileName().toString();
                        final String dimensionKey = dimension.toLowerCase(java.util.Locale.ROOT);
                        final String previousDimension = dimensions.putIfAbsent(dimensionKey, dimension);
                        if (previousDimension != null && !previousDimension.equals(dimension)) {
                            throw new StorageException("Case-insensitive dimension collision: "
                                    + previousDimension + " / " + dimension);
                        }
                        final Map<String, String> dimensionWorlds = new HashMap<>();
                        try (Stream<Path> worldPaths = Files.list(dimensionPath)) {
                            worldPaths.forEach(worldPath -> {
                                if (Files.isSymbolicLink(worldPath)) {
                                    throw new StorageException("Symbolic world links are not supported: " + worldPath);
                                }
                                if (!Files.isDirectory(worldPath, LinkOption.NOFOLLOW_LINKS)) {
                                    return;
                                }
                                final String world = worldPath.getFileName().toString();
                                final String worldKey = world.toLowerCase(java.util.Locale.ROOT);
                                final String previousWorld = dimensionWorlds.putIfAbsent(worldKey, world);
                                if (previousWorld != null && !previousWorld.equals(world)) {
                                    throw new StorageException("Case-insensitive world collision in " + dimension
                                            + ": " + previousWorld + " / " + world);
                                }
                            });
                        } catch (final IOException exception) {
                            throw new StorageException("Dimension directory could not be read: " + dimensionPath, exception);
                        }
                        worlds.put(dimension.toLowerCase(java.util.Locale.ROOT), Map.copyOf(dimensionWorlds));
                    });
                }
            } catch (final IOException exception) {
                throw new StorageException("World directory could not be read: " + root, exception);
            }
            return new DiskSnapshot(Map.copyOf(dimensions), Map.copyOf(worlds));
        }, executor);
    }

    @NotNull
    public CompletableFuture<Void> createDirectory(@NotNull final Path directory) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (Files.isSymbolicLink(directory)) {
                    throw new StorageException("Symbolic directory links are not supported: " + directory);
                }
                Files.createDirectories(directory);
            } catch (final IOException exception) {
                throw new StorageException("Directory could not be created: " + directory, exception);
            }
        }, executor);
    }

    @NotNull
    public CompletableFuture<List<String>> scanWorldNames(@NotNull final Path dimension, final boolean requireLevelDat) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(dimension)) {
                return paths
                        .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !requireLevelDat || Files.isRegularFile(path.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            } catch (final IOException exception) {
                throw new StorageException("Dimension could not be imported: " + dimension, exception);
            }
        }, executor);
    }

    @NotNull
    public CompletableFuture<Void> save(@NotNull final Snapshot snapshot) {
        return CompletableFuture.runAsync(() -> write(snapshot), executor);
    }

    @NotNull
    public CompletableFuture<Void> deleteDirectory(@NotNull final Path root, @NotNull final Path target) {
        return CompletableFuture.runAsync(() -> {
            final Path normalizedRoot = root.toAbsolutePath().normalize();
            final Path normalizedTarget = target.toAbsolutePath().normalize();
            if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
                throw new StorageException("Unsafe deletion target rejected: " + target);
            }
            if (Files.notExists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(normalizedTarget)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (final IOException exception) {
                throw new StorageException("Directory could not be deleted: " + target, exception);
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
    }

    public void logFailure(@NotNull final Throwable throwable) {
        final Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        logger.log(Level.SEVERE, cause.getMessage(), cause);
    }

    private void write(final Snapshot snapshot) {
        final String json = toJson(snapshot);
        final Path parent = file.getParent();
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            throw new StorageException("storage.json could not be saved", exception);
        }
    }

    private static String toJson(final Snapshot snapshot) {
        final Map<String, List<ManagedWorld>> byDimension = new HashMap<>();
        snapshot.dimensions().forEach(dimension -> byDimension.put(dimension, new ArrayList<>()));
        for (final ManagedWorld world : snapshot.worlds()) {
            byDimension.computeIfAbsent(world.dimension(), ignored -> new ArrayList<>()).add(world);
        }

        final List<String> dimensions = byDimension.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        final StringBuilder json = new StringBuilder(256).append("{\n  \"schemaVersion\": ")
                .append(SCHEMA_VERSION).append(",\n  \"dimensions\": [");
        for (int dimensionIndex = 0; dimensionIndex < dimensions.size(); dimensionIndex++) {
            final String dimension = dimensions.get(dimensionIndex);
            if (dimensionIndex > 0) {
                json.append(',');
            }
            json.append("\n    {\"name\": \"").append(escape(dimension)).append("\", \"worlds\": [");
            final List<ManagedWorld> worlds = byDimension.get(dimension).stream()
                    .sorted(Comparator.comparing(ManagedWorld::name, String.CASE_INSENSITIVE_ORDER)).toList();
            for (int worldIndex = 0; worldIndex < worlds.size(); worldIndex++) {
                final ManagedWorld world = worlds.get(worldIndex);
                if (worldIndex > 0) {
                    json.append(',');
                }
                json.append("{\"name\": \"").append(escape(world.name()))
                        .append("\", \"type\": \"").append(world.type().configName()).append("\"}");
            }
            json.append("]}");
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static String escape(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    static final class StorageException extends RuntimeException {
        StorageException(final String message) {
            super(message);
        }

        StorageException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
