package eu.hunfeld.worldloader.listener;

import eu.hunfeld.worldloader.world.WorldManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

public final class WorldUsageListener implements Listener {

    private final WorldManager manager;

    public WorldUsageListener(@NotNull final WorldManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        manager.playerEnteredWorld(event.getPlayer().getWorld());
        manager.playerLeftWorld(event.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        manager.playerLeftWorld(event.getPlayer().getWorld());
    }
}
