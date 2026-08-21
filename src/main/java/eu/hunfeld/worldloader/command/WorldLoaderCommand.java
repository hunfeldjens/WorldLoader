package eu.hunfeld.worldloader.command;

import eu.hunfeld.worldloader.WorldLoader;
import eu.hunfeld.worldloader.model.ManagedWorld;
import eu.hunfeld.worldloader.model.WorldTypeMode;
import eu.hunfeld.worldloader.world.WorldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WorldLoaderCommand implements CommandExecutor, TabCompleter {

    private enum DeleteKind {
        WORLD,
        DIMENSION
    }

    private record PendingDelete(String owner, DeleteKind kind, String target, long expiresAt) {
    }

    private static final List<String> ROOT_ARGUMENTS = List.of(
            "create", "load", "unload", "tp", "list", "import", "delete", "reload", "help"
    );
    private static final List<String> WORLD_TYPES = List.of("air", "flat", "normal");

    private final WorldLoader plugin;
    private final WorldManager manager;
    private final Map<String, PendingDelete> confirmations = new HashMap<>();

    public WorldLoaderCommand(@NotNull final WorldLoader plugin, @NotNull final WorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command,
                             @NotNull final String label, final String @NotNull [] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("enable") || args[0].equalsIgnoreCase("disable"))) {
            handleOperationalState(sender, args);
            return true;
        }
        if (!plugin.isOperational()) {
            plugin.messages().send(sender, "plugin-inactive");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.messages().sendHelp(sender);
            return true;
        }
        if (manager.isInitializing()) {
            plugin.messages().send(sender, "initializing");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> handleCreate(sender, args);
            case "load" -> handleLoad(sender, args);
            case "unload" -> handleUnload(sender, args);
            case "tp", "teleport" -> handleTeleport(sender, args);
            case "list" -> handleList(sender, args);
            case "import" -> handleImport(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "confirm" -> handleConfirm(sender, args);
            case "reload" -> handleReload(sender);
            default -> plugin.messages().sendHelp(sender);
        }
        return true;
    }

    private void handleOperationalState(final CommandSender sender, final String[] args) {
        if (rejectMissingPermission(sender, "worldloader.*")) {
            return;
        }
        if (args.length != 1) {
            plugin.messages().send(sender, "plugin-state-usage");
            return;
        }
        final boolean enable = args[0].equalsIgnoreCase("enable");
        if (plugin.isOperational() == enable) {
            plugin.messages().send(sender, enable ? "plugin-already-enabled" : "plugin-already-disabled");
            return;
        }
        int unloadedWorlds = 0;
        if (!enable) {
            if (manager.isInitializing()) {
                plugin.messages().send(sender, "initializing");
                return;
            }
            final WorldManager.Result unloadResult = manager.unloadAllLoadedWorlds();
            if (unloadResult.status() != WorldManager.Status.SUCCESS) {
                plugin.messages().send(sender, "plugin-disable-failed");
                return;
            }
            unloadedWorlds = unloadResult.count();
            confirmations.clear();
        }
        final int unloadedCount = unloadedWorlds;
        plugin.changeOperationalState(enable).thenAccept(saved -> {
            if (!saved) {
                plugin.messages().send(sender, "plugin-state-save-failed");
            } else if (enable) {
                plugin.messages().send(sender, "plugin-enabled");
            } else {
                plugin.messages().send(sender, "plugin-disabled",
                        plugin.messages().text("count", unloadedCount));
            }
        });
    }

    private void handleCreate(final CommandSender sender, final String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("dimension")) {
            if (rejectMissingPermission(sender, "worldloader.create.dimension")) {
                return;
            }
            final String dimension = args[2];
            if (rejectInvalidName(sender, dimension) || rejectBlocked(sender, dimension, null)) {
                return;
            }
            manager.createDimension(dimension).thenAccept(result -> onMain(() -> {
                if (result.status() == WorldManager.Status.SUCCESS) {
                    plugin.messages().send(sender, "dimension-created", plugin.messages().text("dimension", dimension));
                } else if (result.status() == WorldManager.Status.ALREADY_EXISTS) {
                    plugin.messages().send(sender, "dimension-exists", plugin.messages().text("dimension", result.actualName()));
                } else {
                    plugin.messages().send(sender, "storage-error");
                }
            }));
            return;
        }
        if (args.length != 3) {
            plugin.messages().sendHelp(sender);
            return;
        }
        if (rejectMissingPermission(sender, "worldloader.create.world")) {
            return;
        }
        final String[] id = manager.splitId(args[1]);
        if (id == null) {
            plugin.messages().send(sender, "invalid-id");
            return;
        }
        if (rejectBlocked(sender, id[0], id[1])) {
            return;
        }
        final Optional<WorldTypeMode> type = WorldTypeMode.parse(args[2]);
        if (type.isEmpty()) {
            plugin.messages().send(sender, "invalid-type");
            return;
        }
        final WorldManager.Result result = manager.createWorld(id[0], id[1], type.get());
        switch (result.status()) {
            case SUCCESS -> {
                final ManagedWorld created = result.requireWorld();
                plugin.messages().send(sender, "world-created",
                        plugin.messages().text("world", created.id()),
                        plugin.messages().text("type", created.type().configName()));
            }
            case ALREADY_EXISTS -> plugin.messages().send(sender, "world-exists",
                    plugin.messages().text("world", result.actualName()));
            case MISSING -> plugin.messages().send(sender, "dimension-missing",
                    plugin.messages().text("dimension", id[0]));
            default -> plugin.messages().send(sender, "world-load-failed",
                    plugin.messages().text("world", args[1]));
        }
    }

    private void handleLoad(final CommandSender sender, final String[] args) {
        if (rejectMissingPermission(sender, "worldloader.load.world") || rejectInvalidWorldArgumentCount(sender, args, 3)) {
            return;
        }
        final ManagedWorld world = registeredWorld(sender, args[1]);
        if (world == null || rejectBlocked(sender, world.dimension(), world.name())) {
            return;
        }
        if (args.length == 3) {
            final Optional<WorldTypeMode> requestedType = WorldTypeMode.parse(args[2]);
            if (requestedType.isEmpty()) {
                plugin.messages().send(sender, "invalid-type");
                return;
            }
            if (requestedType.get() != world.type()) {
                plugin.messages().send(sender, "type-mismatch",
                        plugin.messages().text("expected", world.type().configName()),
                        plugin.messages().text("actual", requestedType.get().configName()));
                return;
            }
        }
        final WorldManager.Result result = manager.loadWorld(world);
        final String message = switch (result.status()) {
            case SUCCESS -> "world-loaded";
            case ALREADY_LOADED -> "world-already-loaded";
            default -> "world-load-failed";
        };
        plugin.messages().send(sender, message, plugin.messages().text("world", world.id()));
    }

    private void handleUnload(final CommandSender sender, final String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("dimension")) {
            if (rejectMissingPermission(sender, "worldloader.unload.dimension")) {
                return;
            }
            final Optional<String> dimension = manager.actualDimension(args[2]);
            if (dimension.isEmpty()) {
                plugin.messages().send(sender, "dimension-missing", plugin.messages().text("dimension", args[2]));
                return;
            }
            if (rejectBlockedTree(sender, dimension.get())) {
                return;
            }
            final WorldManager.Result result = manager.unloadDimension(dimension.get());
            if (result.status() == WorldManager.Status.SUCCESS) {
                plugin.messages().send(sender, "dimension-unloaded",
                        plugin.messages().text("dimension", result.actualName()),
                        plugin.messages().text("count", result.count()));
            } else if (result.status() == WorldManager.Status.IN_USE) {
                plugin.messages().send(sender, "dimension-in-use", plugin.messages().text("dimension", result.actualName()));
            } else {
                plugin.messages().send(sender, "world-unload-failed", plugin.messages().text("world", result.actualName()));
            }
            return;
        }
        if (rejectMissingPermission(sender, "worldloader.unload.world") || rejectInvalidWorldArgumentCount(sender, args, 2)) {
            return;
        }
        final ManagedWorld world = registeredWorld(sender, args[1]);
        if (world == null || rejectBlocked(sender, world.dimension(), world.name())) {
            return;
        }
        final WorldManager.Result result = manager.unloadWorld(world, false);
        final String message = switch (result.status()) {
            case SUCCESS -> "world-unloaded";
            case IN_USE -> "world-in-use";
            default -> "world-unload-failed";
        };
        plugin.messages().send(sender, message, plugin.messages().text("world", world.id()));
    }

    private void handleTeleport(final CommandSender sender, final String[] args) {
        if (rejectMissingAnyPermission(sender, "worldloader.teleport", "worldloader.teleprot")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (rejectInvalidWorldArgumentCount(sender, args, 2)) {
            return;
        }
        final ManagedWorld world = registeredWorld(sender, args[1]);
        if (world == null || rejectBlocked(sender, world.dimension(), world.name())) {
            return;
        }
        final WorldManager.Result result = manager.loadWorld(world);
        if (result.status() != WorldManager.Status.SUCCESS && result.status() != WorldManager.Status.ALREADY_LOADED) {
            plugin.messages().send(sender, "world-load-failed", plugin.messages().text("world", world.id()));
            return;
        }
        manager.teleportLocation(world)
                .thenCompose(destination -> destination
                        .map(player::teleportAsync)
                        .orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(false)))
                .thenAccept(success -> onMain(() -> {
                    if (success) {
                        plugin.messages().send(player, "teleported", plugin.messages().text("world", world.id()));
                    } else {
                        plugin.messages().send(player, "world-load-failed", plugin.messages().text("world", world.id()));
                    }
                }));
    }

    private void handleImport(final CommandSender sender, final String[] args) {
        if (rejectMissingPermission(sender, "worldloader.import")) {
            return;
        }
        if (args.length < 3 || args.length > 4 || !args[1].equalsIgnoreCase("dimension")) {
            plugin.messages().sendHelp(sender);
            return;
        }
        final String dimension = args[2];
        if (rejectInvalidName(sender, dimension) || rejectBlocked(sender, dimension, null)) {
            return;
        }
        final WorldTypeMode type;
        if (args.length == 4) {
            final Optional<WorldTypeMode> parsed = WorldTypeMode.parse(args[3]);
            if (parsed.isEmpty()) {
                plugin.messages().send(sender, "invalid-type");
                return;
            }
            type = parsed.get();
        } else {
            type = plugin.settings().importType();
        }
        manager.importDimension(dimension, type).thenAccept(result -> onMain(() -> {
            switch (result.status()) {
                case SUCCESS -> plugin.messages().send(sender, "import-complete",
                        plugin.messages().text("dimension", result.actualName()),
                        plugin.messages().text("count", result.count()));
                case MISSING -> plugin.messages().send(sender, "import-missing",
                        plugin.messages().text("dimension", dimension));
                case EMPTY -> plugin.messages().send(sender, "import-empty",
                        plugin.messages().text("dimension", result.actualName()));
                default -> plugin.messages().send(sender, "storage-error");
            }
        }));
    }

    private void handleDelete(final CommandSender sender, final String[] args) {
        if (args.length == 3 && isDimensionKeyword(args[1])) {
            if (rejectMissingAnyPermission(sender, "worldloader.delete.dimension", "worldloader.delete.dimesnion")) {
                return;
            }
            final Optional<String> dimension = manager.actualDimension(args[2]);
            if (dimension.isEmpty()) {
                plugin.messages().send(sender, "dimension-missing", plugin.messages().text("dimension", args[2]));
                return;
            }
            if (rejectBlockedTree(sender, dimension.get())) {
                return;
            }
            requestConfirmation(sender, DeleteKind.DIMENSION, dimension.get());
            return;
        }
        if (args.length != 2 || manager.splitId(args[1]) == null) {
            plugin.messages().send(sender, "invalid-id");
            return;
        }
        if (rejectMissingPermission(sender, "worldloader.delete.world")) {
            return;
        }
        final ManagedWorld world = registeredWorld(sender, args[1]);
        if (world == null || rejectBlocked(sender, world.dimension(), world.name())) {
            return;
        }
        requestConfirmation(sender, DeleteKind.WORLD, world.id());
    }

    private void requestConfirmation(final CommandSender sender, final DeleteKind kind, final String target) {
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt() < System.currentTimeMillis());
        final String token = UUID.randomUUID().toString();
        confirmations.put(token, new PendingDelete(owner(sender), kind, target,
                System.currentTimeMillis() + plugin.settings().confirmationMillis()));
        final Component button = plugin.messages().component("delete-confirm-button")
                .clickEvent(ClickEvent.runCommand("/wl confirm " + token))
                .hoverEvent(HoverEvent.showText(plugin.messages().component("delete-confirm-hover")));
        if (kind == DeleteKind.WORLD) {
            plugin.messages().send(sender, "delete-world-question",
                    plugin.messages().text("world", target), Placeholder.component("confirm", button));
        } else {
            plugin.messages().send(sender, "delete-dimension-question",
                    plugin.messages().text("dimension", target), Placeholder.component("confirm", button));
        }
    }

    private void handleConfirm(final CommandSender sender, final String[] args) {
        if (args.length != 2) {
            plugin.messages().send(sender, "confirmation-expired");
            return;
        }
        final PendingDelete pending = confirmations.remove(args[1]);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis() || !pending.owner().equals(owner(sender))) {
            plugin.messages().send(sender, "confirmation-expired");
            return;
        }
        final boolean allowed = pending.kind() == DeleteKind.WORLD
                ? sender.hasPermission("worldloader.delete.world")
                : sender.hasPermission("worldloader.delete.dimension") || sender.hasPermission("worldloader.delete.dimesnion");
        if (!allowed) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (pending.kind() == DeleteKind.WORLD) {
            final ManagedWorld world = manager.findWorld(pending.target()).orElse(null);
            if (world == null || manager.isBlocked(world.dimension(), world.name())) {
                plugin.messages().send(sender, "confirmation-expired");
                return;
            }
            manager.deleteWorld(world).thenAccept(result -> onMain(() -> sendDeleteResult(sender, result, true)));
        } else {
            final Optional<String> dimension = manager.actualDimension(pending.target());
            if (dimension.isEmpty() || manager.blockedTargetInDimension(dimension.get()).isPresent()) {
                plugin.messages().send(sender, "confirmation-expired");
                return;
            }
            manager.deleteDimension(dimension.get()).thenAccept(result -> onMain(() -> sendDeleteResult(sender, result, false)));
        }
    }

    private void sendDeleteResult(final CommandSender sender, final WorldManager.Result result, final boolean world) {
        if (result.status() == WorldManager.Status.SUCCESS) {
            if (world) {
                plugin.messages().send(sender, "world-deleted",
                        plugin.messages().text("world", result.actualName()));
            } else {
                plugin.messages().send(sender, "dimension-deleted",
                        plugin.messages().text("dimension", result.actualName()));
            }
        } else if (result.status() == WorldManager.Status.IN_USE) {
            if (world) {
                plugin.messages().send(sender, "world-in-use",
                        plugin.messages().text("world", result.actualName()));
            } else {
                plugin.messages().send(sender, "dimension-in-use",
                        plugin.messages().text("dimension", result.actualName()));
            }
        } else if (result.status() == WorldManager.Status.BLOCKED) {
            plugin.messages().send(sender, "blacklisted", plugin.messages().text("name", result.actualName()));
        } else {
            plugin.messages().send(sender, "delete-failed");
        }
    }

    private void handleList(final CommandSender sender, final String[] args) {
        if (rejectMissingPermission(sender, "worldloader.list")) {
            return;
        }
        final int requestedPage;
        try {
            requestedPage = args.length >= 2 ? Math.max(1, Integer.parseInt(args[1])) : 1;
        } catch (final NumberFormatException exception) {
            plugin.messages().sendHelp(sender);
            return;
        }
        final List<String> dimensions = manager.visibleDimensions();
        final List<ManagedWorld> allWorlds = new ArrayList<>();
        for (final String dimension : dimensions) {
            allWorlds.addAll(manager.visibleWorlds(dimension));
        }
        if (dimensions.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        final int pageSize = plugin.settings().worldsPerPage();
        final int pages = Math.max(1, (allWorlds.size() + pageSize - 1) / pageSize);
        final int page = Math.min(requestedPage, pages);
        plugin.messages().sendRaw(sender, "list-header", plugin.messages().text("page", page), plugin.messages().text("pages", pages));
        if (page == 1) {
            for (final String dimension : dimensions) {
                if (manager.visibleWorlds(dimension).isEmpty()) {
                    plugin.messages().sendRaw(sender, "list-dimension",
                            plugin.messages().text("dimension", dimension), plugin.messages().text("count", 0));
                }
            }
        }
        final List<ManagedWorld> visible = allWorlds.subList((page - 1) * pageSize, Math.min(page * pageSize, allWorlds.size()));
        String previousDimension = null;
        for (final ManagedWorld world : visible) {
            if (!world.dimension().equals(previousDimension)) {
                previousDimension = world.dimension();
                plugin.messages().sendRaw(sender, "list-dimension",
                        plugin.messages().text("dimension", world.dimension()),
                        plugin.messages().text("count", manager.visibleWorlds(world.dimension()).size()));
            }
            final Component teleport = plugin.messages().component("list-click")
                    .clickEvent(ClickEvent.runCommand("/wl tp " + world.id()))
                    .hoverEvent(HoverEvent.showText(plugin.messages().component("list-teleport-hover",
                            plugin.messages().text("world", world.id()))));
            plugin.messages().sendRaw(sender, manager.isLoaded(world) ? "list-world-loaded" : "list-world-unloaded",
                    plugin.messages().text("world", world.name()),
                    plugin.messages().text("type", world.type().configName()),
                    Placeholder.component("click", teleport));
        }
        plugin.messages().sendRaw(sender, "list-footer");
    }

    private void handleReload(final CommandSender sender) {
        if (rejectMissingPermission(sender, "worldloader.reload")) {
            return;
        }
        final boolean storageUnchanged = plugin.reloadPluginSettings();
        plugin.messages().send(sender, storageUnchanged ? "reload-complete" : "reload-restart-required");
    }

    private ManagedWorld registeredWorld(final CommandSender sender, final String id) {
        final String[] parts = manager.splitId(id);
        if (parts == null) {
            plugin.messages().send(sender, "invalid-id");
            return null;
        }
        return manager.findWorld(id).orElseGet(() -> {
            plugin.messages().send(sender, "world-missing", plugin.messages().text("world", id));
            return null;
        });
    }

    private boolean rejectInvalidName(final CommandSender sender, final String name) {
        if (manager.validName(name)) {
            return false;
        }
        plugin.messages().send(sender, "invalid-name", plugin.messages().text("name", name));
        return true;
    }

    private boolean rejectBlocked(final CommandSender sender, final String dimension, final String world) {
        if (!manager.isBlocked(dimension, world)) {
            return false;
        }
        plugin.messages().send(sender, "blacklisted",
                plugin.messages().text("name", world == null ? dimension : dimension + ':' + world));
        return true;
    }

    private boolean rejectBlockedTree(final CommandSender sender, final String dimension) {
        final Optional<String> blocked = manager.blockedTargetInDimension(dimension);
        if (blocked.isEmpty()) {
            return false;
        }
        plugin.messages().send(sender, "blacklisted", plugin.messages().text("name", blocked.get()));
        return true;
    }

    private boolean rejectMissingPermission(final CommandSender sender, final String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        plugin.messages().send(sender, "no-permission");
        return true;
    }

    private boolean rejectMissingAnyPermission(final CommandSender sender, final String... permissions) {
        for (final String permission : permissions) {
            if (sender.hasPermission(permission)) {
                return false;
            }
        }
        plugin.messages().send(sender, "no-permission");
        return true;
    }

    private boolean rejectInvalidWorldArgumentCount(final CommandSender sender, final String[] args, final int max) {
        if (args.length >= 2 && args.length <= max) {
            return false;
        }
        plugin.messages().sendHelp(sender);
        return true;
    }

    private void onMain(final Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static String owner(final CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console:" + sender.getName();
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command,
                                                @NotNull final String alias, final String @NotNull [] args) {
        if (args.length == 1) {
            final List<String> roots = new ArrayList<>();
            if (plugin.isOperational()) {
                roots.addAll(ROOT_ARGUMENTS);
            }
            if (sender.hasPermission("worldloader.*")) {
                roots.add(plugin.isOperational() ? "disable" : "enable");
            }
            return matching(args[0], roots);
        }
        if (!plugin.isOperational()) {
            return List.of();
        }
        if (manager.isInitializing()) {
            return List.of();
        }
        final String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (root) {
                case "create" -> {
                    final List<String> values = new ArrayList<>();
                    values.add("dimension");
                    manager.visibleDimensions().forEach(dimension -> values.add(dimension + ':'));
                    yield matching(args[1], values);
                }
                case "load", "tp", "teleport" -> matching(args[1], manager.visibleWorldIds());
                case "unload" -> {
                    final List<String> values = new ArrayList<>(manager.visibleWorldIds());
                    values.add("dimension");
                    yield matching(args[1], values);
                }
                case "delete" -> {
                    final List<String> values = new ArrayList<>(manager.visibleWorldIds());
                    values.add("universum");
                    yield matching(args[1], values);
                }
                case "import" -> matching(args[1], List.of("dimension"));
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (root.equals("create") && !args[1].equalsIgnoreCase("dimension")) {
                return matching(args[2], WORLD_TYPES);
            }
            if (root.equals("load")) {
                return matching(args[2], WORLD_TYPES);
            }
            if (root.equals("unload") && args[1].equalsIgnoreCase("dimension")) {
                return matching(args[2], manager.visibleDimensions());
            }
            if (root.equals("import") && args[1].equalsIgnoreCase("dimension")) {
                return matching(args[2], manager.visibleDiskDimensions());
            }
            if (root.equals("delete") && isDimensionKeyword(args[1])) {
                return matching(args[2], manager.visibleDimensions());
            }
        }
        if (args.length == 4 && root.equals("import") && args[1].equalsIgnoreCase("dimension")) {
            return matching(args[3], WORLD_TYPES);
        }
        return List.of();
    }

    private static List<String> matching(final String input, final List<String> choices) {
        final String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static boolean isDimensionKeyword(final String input) {
        return input.equalsIgnoreCase("universum")
                || input.equalsIgnoreCase("universe")
                || input.equalsIgnoreCase("dimension");
    }
}
