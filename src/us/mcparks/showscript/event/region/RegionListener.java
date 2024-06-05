package us.mcparks.showscript.event.region;

import com.google.common.collect.Sets;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import us.mcparks.showscript.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.*;
import java.util.stream.Collectors;

public class RegionListener implements Listener {
    public static Map<Player, Set<ProtectedRegion>> regionMap;
    private WorldGuardPlugin worldGuard;

    // Players in this set won't have their regions updated as long as they're passengers in a vehicle
    private Set<Player> disabledPassengers;

    public RegionListener(WorldGuardPlugin wgp) {
        regionMap = new HashMap<>();
        disabledPassengers = new HashSet<>();
        worldGuard = wgp;
        Bukkit.getPluginManager().registerEvents(this, Main.getPlugin(Main.class));
    }

    public Collection<Player> getPlayersInRegion(String regionId) {
        return regionMap.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(region -> region.getId().equals(regionId)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public Collection<String> getRegionsForPlayer(Player player) {
        return regionMap.get(player).stream().map(ProtectedRegion::getId).collect(Collectors.toList());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        updateRegions(e.getPlayer(), MovementType.MOVE, e.getTo());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        updateRegions(e.getPlayer(), MovementType.TELEPORT, e.getTo());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        updateRegions(e.getPlayer(), MovementType.SPAWN, e.getPlayer().getLocation());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        updateRegions(e.getPlayer(), MovementType.SPAWN, e.getRespawnLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        regionMap.get(e.getPlayer()).forEach(region -> Bukkit.getServer().getPluginManager()
                .callEvent(new PlayerLeaveRegionEvent(e.getPlayer(), region.getId())));
        regionMap.remove(e.getPlayer());
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent e) {
        e.getVehicle().getPassengers().stream().filter(en -> en instanceof Player).forEach(en -> {
            Player player = (Player) en;
            updateRegions(player, disabledPassengers.contains(player) ? MovementType.VEHICLE_NO_AUDIO : MovementType.VEHICLE, e.getTo());
        });
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player) {
            if (disabledPassengers.remove((Player) e.getExited())) {
                updateRegions((Player) e.getExited(), MovementType.MOVE, e.getExited().getLocation());
            }
        }
    }

    private synchronized void updateRegions(final Player player, final MovementType movementType,
                                            Location to) {
        Set<ProtectedRegion> oldRegions;

        if (regionMap.get(player) == null) {
            oldRegions = new HashSet<>();
        } else {
            oldRegions = new HashSet<>(regionMap.get(player));
        }

        RegionManager rm = worldGuard.getRegionManager(to.getWorld());

        if (rm != null) {
            Set<ProtectedRegion> applicableRegions = rm.getApplicableRegions(to).getRegions();
            // System.out.println("------------");
            // for (ProtectedRegion region : oldRegions) {
            // System.out.println("old: " + region.getId());
            // }
            // for (ProtectedRegion region : applicableRegions) {
            // System.out.println("new: " + region.getId());
            // }
            for (ProtectedRegion region : Sets.difference(applicableRegions, oldRegions)) {
                Bukkit.getServer().getPluginManager()
                        .callEvent(new PlayerEnterRegionEvent(player, region.getId()));
            }

            for (ProtectedRegion region : Sets.difference(oldRegions, applicableRegions)) {
                Bukkit.getServer().getPluginManager()
                        .callEvent(new PlayerLeaveRegionEvent(player, region.getId()));
            }

            regionMap.put(player, applicableRegions);
        }


    }

    /**
     * As long as `player` is in a vehicle, do not update their regions when their vehicle moves.
     *
     * @param player The player for whom to disable regions. Updating will resume when they exit their vehicle
     */
    public void disableRegionsForPassenger(Player player) {
        disabledPassengers.add(player);
    }
}
