package eu.hunfeld.worldloader.config;

import eu.hunfeld.worldloader.model.WorldTypeMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public record PluginSettings(
        String worldRoot,
        String storageFile,
        boolean autoUnloadEnabled,
        long idleMillis,
        long checkIntervalTicks,
        boolean saveBeforeUnload,
        boolean generateStructures,
        boolean airPlatformEnabled,
        int airPlatformY,
        int airPlatformRadius,
        String airPlatformMaterial,
        int randomTickSpeed,
        boolean fireSpread,
        boolean mobSpawning,
        boolean daylightCycle,
        boolean weatherCycle,
        boolean safeSpawn,
        boolean centerOnBlock,
        boolean requireLevelDat,
        WorldTypeMode importType,
        long confirmationMillis,
        boolean evacuateBeforeDelete,
        int worldsPerPage,
        Set<String> blockedDimensions,
        Set<String> blockedWorlds
) {

    private static final long TICKS_PER_SECOND = 20L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final int MAX_AIR_PLATFORM_RADIUS = 16;
    private static final int MAX_WORLDS_PER_PAGE = 50;

    @NotNull
    public static PluginSettings load(@NotNull final FileConfiguration config, @NotNull final Logger logger) {
        final int idleMinutes = positive(config, logger, "auto-unload.idle-minutes", 5);
        final int checkSeconds = positive(config, logger, "auto-unload.check-interval-seconds", 30);
        final int confirmationSeconds = positive(config, logger, "delete.confirmation-seconds", 30);
        final int pageSize = Math.min(positive(config, logger, "list.worlds-per-page", 8), MAX_WORLDS_PER_PAGE);
        final String configuredImportType = config.getString("import.default-world-type", "normal");
        final WorldTypeMode importType = WorldTypeMode.parse(configuredImportType).orElseGet(() -> {
            logger.warning("Invalid import.default-world-type '" + configuredImportType + "'; using normal.");
            return WorldTypeMode.NORMAL;
        });
        final int randomTickSpeed = config.getInt("creation.gamerules.random-tick-speed", 0);
        if (randomTickSpeed < 0) {
            logger.warning("creation.gamerules.random-tick-speed must not be negative; using 0.");
        }
        final int configuredRadius = config.getInt("creation.air-spawn-platform.radius", 2);
        final int platformRadius = Math.clamp(configuredRadius, 0, MAX_AIR_PLATFORM_RADIUS);
        if (platformRadius != configuredRadius) {
            logger.warning("creation.air-spawn-platform.radius must be between 0 and "
                    + MAX_AIR_PLATFORM_RADIUS + "; using " + platformRadius + '.');
        }
        final String configuredMaterial = config.getString("creation.air-spawn-platform.material", "GLASS");
        final Material material = Material.matchMaterial(configuredMaterial);
        final String platformMaterial;
        if (material == null || !material.isBlock() || material.isAir()) {
            logger.warning("Invalid creation.air-spawn-platform.material '" + configuredMaterial + "'; using GLASS.");
            platformMaterial = Material.GLASS.name();
        } else {
            platformMaterial = material.name();
        }

        return new PluginSettings(
                config.getString("storage.world-root", "world/dimensions"),
                config.getString("storage.file", "storage.json"),
                config.getBoolean("auto-unload.enabled", true),
                idleMinutes * 60L * MILLIS_PER_SECOND,
                checkSeconds * TICKS_PER_SECOND,
                config.getBoolean("auto-unload.save-before-unload", true),
                config.getBoolean("creation.generate-structures", true),
                config.getBoolean("creation.air-spawn-platform.enabled", true),
                config.getInt("creation.air-spawn-platform.y", 64),
                platformRadius,
                platformMaterial,
                Math.max(0, randomTickSpeed),
                config.getBoolean("creation.gamerules.fire-spread", false),
                config.getBoolean("creation.gamerules.mob-spawning", false),
                config.getBoolean("creation.gamerules.daylight-cycle", false),
                config.getBoolean("creation.gamerules.weather-cycle", false),
                config.getBoolean("teleport.use-safe-spawn", true),
                config.getBoolean("teleport.center-on-block", true),
                config.getBoolean("import.require-level-dat", true),
                importType,
                confirmationSeconds * MILLIS_PER_SECOND,
                config.getBoolean("delete.teleport-players-to-primary-world", true),
                pageSize,
                lowerCaseSet(config.getStringList("blacklist.dimensions")),
                lowerCaseSet(config.getStringList("blacklist.worlds"))
        );
    }

    public boolean isBlocked(@NotNull final String dimension, @Nullable final String world) {
        final String lowerDimension = dimension.toLowerCase(Locale.ROOT);
        if (blockedDimensions.contains(lowerDimension)) {
            return true;
        }
        if (world == null) {
            return false;
        }
        final String lowerWorld = world.toLowerCase(Locale.ROOT);
        return blockedWorlds.contains(lowerWorld)
                || blockedWorlds.contains(lowerDimension + ':' + lowerWorld);
    }

    private static int positive(final FileConfiguration config, final Logger logger,
                                final String path, final int fallback) {
        final int value = config.getInt(path, fallback);
        if (value > 0) {
            return value;
        }
        logger.warning(path + " must be greater than zero; using " + fallback + '.');
        return fallback;
    }

    private static Set<String> lowerCaseSet(final List<String> values) {
        return values.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
