package eu.hunfeld.worldloader;

import eu.hunfeld.worldloader.command.WorldLoaderCommand;
import eu.hunfeld.worldloader.config.PluginSettings;
import eu.hunfeld.worldloader.listener.WorldUsageListener;
import eu.hunfeld.worldloader.message.Messages;
import eu.hunfeld.worldloader.storage.WorldStorage;
import eu.hunfeld.worldloader.world.WorldManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class WorldLoader extends JavaPlugin {

    private final Object configurationWriteLock = new Object();
    private final ExecutorService configurationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "WorldLoader-Config");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Void> pendingConfigurationWrite = CompletableFuture.completedFuture(null);

    private PluginSettings settings;
    private Messages messages;
    private WorldManager worldManager;
    private volatile boolean operational;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("language.yml", false);
        settings = PluginSettings.load(getConfig(), getLogger());
        messages = new Messages(getConfig(), loadLanguage());
        operational = getConfig().getBoolean("plugin-state.enabled", true);

        final Path worldContainer = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        final Path root = worldContainer.resolve(settings.worldRoot()).normalize();
        if (root.equals(worldContainer) || !root.startsWith(worldContainer)) {
            getLogger().severe("storage.world-root must be a child of the server world container: " + worldContainer);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        final Path dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
        final Path storageFile = dataDirectory.resolve(settings.storageFile()).normalize();
        if (!storageFile.getParent().equals(dataDirectory)) {
            getLogger().severe("storage.file must not contain a directory path.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        final WorldStorage storage = new WorldStorage(storageFile, getLogger());
        worldManager = new WorldManager(this, storage, settings, root);

        final PluginCommand command = getCommand("worldloader");
        if (command == null) {
            getLogger().severe("The worldloader command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        final WorldLoaderCommand executor = new WorldLoaderCommand(this, worldManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new WorldUsageListener(worldManager), this);

        worldManager.initialize().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                getLogger().log(Level.SEVERE, "WorldLoader could not be initialized", throwable);
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                }
                return;
            }
            if (!isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (isEnabled()) {
                    if (operational) {
                        worldManager.startIdleTask();
                    }
                    getLogger().info("WorldLoader " + getPluginMeta().getVersion()
                            + " is ready and " + (operational ? "enabled" : "disabled")
                            + ". World directory: " + worldManager.root());
                }
            });
        });
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        if (worldManager != null) {
            worldManager.stop();
        }
        configurationExecutor.shutdown();
        try {
            if (!configurationExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                configurationExecutor.shutdownNow();
            }
        } catch (final InterruptedException exception) {
            configurationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @NotNull
    public PluginSettings settings() {
        return settings;
    }

    @NotNull
    public Messages messages() {
        return messages;
    }

    public boolean isOperational() {
        return operational;
    }

    @NotNull
    public CompletableFuture<Boolean> changeOperationalState(final boolean enabled) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("WorldLoader state change attempted outside the main thread");
        }
        final boolean previous = operational;
        applyOperationalState(enabled);
        getConfig().set("plugin-state.enabled", enabled);
        final String serializedConfiguration = getConfig().saveToString();
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        queueConfigurationWrite(serializedConfiguration).whenComplete((ignored, throwable) -> {
            if (!isEnabled()) {
                result.complete(throwable == null);
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (throwable != null) {
                    getLogger().log(Level.SEVERE, "The WorldLoader state could not be saved to config.yml", throwable);
                    getConfig().set("plugin-state.enabled", previous);
                    applyOperationalState(previous);
                    result.complete(false);
                    return;
                }
                result.complete(true);
            });
        });
        return result;
    }

    public boolean reloadPluginSettings() {
        final String previousRoot = settings.worldRoot();
        final String previousStorageFile = settings.storageFile();
        reloadConfig();
        settings = PluginSettings.load(getConfig(), getLogger());
        messages = new Messages(getConfig(), loadLanguage());
        worldManager.updateSettings(settings);
        getConfig().set("plugin-state.enabled", operational);
        if (operational) {
            worldManager.startIdleTask();
        } else {
            worldManager.stopIdleTask();
        }
        return previousRoot.equals(settings.worldRoot()) && previousStorageFile.equals(settings.storageFile());
    }

    private void applyOperationalState(final boolean enabled) {
        operational = enabled;
        if (worldManager == null) {
            return;
        }
        if (enabled) {
            worldManager.startIdleTask();
        } else {
            worldManager.stopIdleTask();
        }
    }

    @NotNull
    private CompletableFuture<Void> queueConfigurationWrite(@NotNull final String content) {
        synchronized (configurationWriteLock) {
            pendingConfigurationWrite = pendingConfigurationWrite.exceptionally(ignored -> null)
                    .thenRunAsync(() -> writeConfiguration(content), configurationExecutor);
            return pendingConfigurationWrite;
        }
    }

    private void writeConfiguration(@NotNull final String content) {
        final Path file = getDataFolder().toPath().resolve("config.yml");
        final Path temporary = file.resolveSibling("config.yml.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException exception) {
            throw new CompletionException(exception);
        }
    }

    @NotNull
    private YamlConfiguration loadLanguage() {
        final YamlConfiguration language = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "language.yml"));
        final InputStream resource = getResource("language.yml");
        if (resource == null) {
            getLogger().severe("Bundled language.yml is missing from the plugin JAR.");
            return language;
        }
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            language.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (final RuntimeException | java.io.IOException exception) {
            getLogger().log(Level.WARNING, "Bundled language defaults could not be loaded", exception);
        }
        return language;
    }
}
