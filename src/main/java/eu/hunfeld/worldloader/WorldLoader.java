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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Level;

public final class WorldLoader extends JavaPlugin {

    private PluginSettings settings;
    private Messages messages;
    private WorldManager worldManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("language.yml", false);
        settings = PluginSettings.load(getConfig(), getLogger());
        messages = new Messages(getConfig(), loadLanguage());

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
                    worldManager.startIdleTask();
                    getLogger().info("WorldLoader " + getPluginMeta().getVersion()
                            + " is ready. World directory: " + worldManager.root());
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
    }

    @NotNull
    public PluginSettings settings() {
        return settings;
    }

    @NotNull
    public Messages messages() {
        return messages;
    }

    public boolean reloadPluginSettings() {
        final String previousRoot = settings.worldRoot();
        final String previousStorageFile = settings.storageFile();
        reloadConfig();
        settings = PluginSettings.load(getConfig(), getLogger());
        messages = new Messages(getConfig(), loadLanguage());
        worldManager.updateSettings(settings);
        return previousRoot.equals(settings.worldRoot()) && previousStorageFile.equals(settings.storageFile());
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
