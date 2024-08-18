package us.mcparks.showscript;

import cloud.commandframework.annotations.Argument;
import cloud.commandframework.annotations.CommandMethod;
import cloud.commandframework.annotations.CommandPermission;
import cloud.commandframework.annotations.Flag;
import cloud.commandframework.annotations.suggestions.Suggestions;
import cloud.commandframework.context.CommandContext;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import us.mcparks.showscript.event.region.PlayerEnterRegionEvent;
import us.mcparks.showscript.event.region.PlayerLeaveRegionEvent;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import us.mcparks.showscript.showscript.framework.actions.BasicShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowActionType;
import us.mcparks.showscript.showscript.groovy.GroovyShowAction;
import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;
import us.mcparks.showscript.util.DebugLogger;
import us.mcparks.showscript.util.OneTimeUseListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import us.mcparks.showscript.event.show.ShowStopEvent;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.TimecodeShowConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;


public class RegionShowListener implements Listener {
    Main main;

    // map of region->shows
    Multimap<String, RegionShowInstance> regionMap;

    // map of schema filepath -> region (used for reloading when the region has changed)
    Multimap<String, String> schemaRegionMap;

    // regions -> ignored schemas, for messaging to users when `/regionshows --ignored` is run
    Multimap<String, RegionShowInstance> ignoredSchemas;

    // Store a map of schema filepaths to their hash, so we can detect if a duplicate schema has been loaded
    Map<HashCode, String> schemaHashes = new HashMap<>();

    public RegionShowListener(Main main) {
        this.main = main;
        main.getServer().getPluginManager().registerEvents(this, main);
        regionMap = MultimapBuilder.hashKeys().hashSetValues().build();
        schemaRegionMap = MultimapBuilder.hashKeys().hashSetValues().build();
        ignoredSchemas = MultimapBuilder.hashKeys().hashSetValues().build();
        loadRegionShows();
    }

    private void loadRegionShows() {
        System.out.println("Loading region shows...");
        loadRegionShows(Main.getPlugin(Main.class).fs.toPath());
    }
    
    private void loadRegionShows(Path path) {
        loadRegionShows(path, null);
    }

    private void loadRegionShows(Path path, CommandSender sender) {
        if (sender != null) sender.sendMessage("Loading region shows in " + path.toString().substring(Main.getPlugin(Main.class).fs.toPath().toString().length()));
        try {
            Files.walk(path)
            .filter(Files::isRegularFile)
            .filter(file -> file.toString().endsWith("_regionshowschema.yml"))
            .forEach(file -> {
                loadRegionShow(file.toFile(), sender);
            });
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private boolean loadRegionShow(String regionShowName) {
        return loadRegionShow(regionShowName, null);
    }

    private boolean loadRegionShow(String regionShowName, CommandSender sender) {
        return loadRegionShow(new File(Main.getPlugin(Main.class).fs, regionShowName + ".yml"), null);
    }

    private boolean loadRegionShow(File f, CommandSender sender) {
        if (!f.exists()) {
            return false;
        }

        try {
            List<String> regions = new ArrayList<>();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);

            String configAsString = yaml.saveToString();
            HashCode hash = Hashing.sha256().hashBytes(configAsString.getBytes());
            if (schemaHashes.containsKey(hash)) {
                if (sender != null) {
                    sender.sendMessage("Error loading region show " + f.getName() + ": A schema with identical " +
                            "contents has already been loaded: " + schemaHashes.get(hash));
                }
                return false;
            }

            if (yaml.isList("region")) {
                regions.addAll(yaml.getStringList("region"));
            } else if (yaml.isString("region")) {
                regions.add(yaml.getString("region"));
            } else {
                throw new IllegalArgumentException("No region[s] specified");
            }
            ShowArgs placeholderArgs = new ShowArgs(new Object[]{regions.get(0)});

            for (String region : regions) {
                if (!region.toLowerCase().equals(region)) {
                    throw new IllegalArgumentException("Region names are always lowercase, but you entered " + region);
                }
            }

            TimecodeShowConfig setupShow = main.timecodeExecutor.getShowConfig(yaml.isConfigurationSection("setup") ? yaml.getString("setup.name") : yaml.getString("setup"), placeholderArgs);
            Long setupDelay = yaml.isConfigurationSection("setup") ? (yaml.contains("setup.delay") ? yaml.getLong("setup.delay") : 0) : 0;
            TimecodeShowConfig cleanupShow = main.timecodeExecutor.getShowConfig(yaml.getString("cleanup"), placeholderArgs);
            
            List<TimecodeShowConfig> loopShows = new ArrayList<>();
            List<Long> loopShowDelays = new ArrayList<>();
            
            if (yaml.isString("loop")) {
                loopShows.add(main.timecodeExecutor.getShowConfig(yaml.getString("loop"), placeholderArgs));
                loopShowDelays.add(0L);
            } else if (yaml.isConfigurationSection("loop")) {
                loopShows.add(main.timecodeExecutor.getShowConfig(yaml.getString("loop.name"), placeholderArgs));
                loopShowDelays.add(yaml.contains("loop.delay") ? yaml.getLong("loop.delay") : 0);
            } else {
                for (Map<?,?> loop : yaml.getMapList("loop")) {
                    loopShows.add(
                        main.timecodeExecutor.getShowConfig((String) loop.get("name"), placeholderArgs));
                    loopShowDelays.add(loop.containsKey("delay") ? ((Number) loop.get("delay")).longValue() : 0);   
                }
            }

            String filepath = f.getAbsolutePath();
            boolean isIgnored = yaml.contains("ignore") && yaml.getBoolean("ignore") == true;

            if (schemaRegionMap.containsKey(filepath)) {
                schemaRegionMap.removeAll(filepath).forEach(oldRegion -> {
                    regionMap.get(oldRegion).removeIf((rsi) -> {
                        if(rsi.cfg.filePath.equals(filepath)) {
                            System.out.println("stopping " + rsi.cfg.name + " because it was replaced");
                            rsi.stop(true);
                            return true;
                        } else {
                            return false;
                        }
                    });
                });
            }

            if (regions.size() == 1) {
                loadRegionShow(filepath, regions.get(0), setupShow, loopShows, cleanupShow, setupDelay, loopShowDelays, isIgnored, false);
                if (!isIgnored && sender != null) sender.sendMessage("Loaded " + f.getName() + " in region " + regions.get(0));
            } else {
                String NOT_SHOWSCRIPT_3_ERROR = "%s uses ShowScript 2. ShowScript 3 must be used for region show schemas with multiple regions (your show should take one argument, which will be the region name as a String)";
                String INVALID_ARGS_ERROR = "%s takes %s arguments, but region show schemas with multiple regions must take 1 argument (the region name as a String)";
                if (!(setupShow instanceof GroovyShowConfig)) {
                    throw new IllegalArgumentException(String.format(NOT_SHOWSCRIPT_3_ERROR, "Setup show"));
                } else {
                    if (((GroovyShowConfig) setupShow).getRequiredArgumentCount() != 1) {
                        throw new IllegalArgumentException(String.format(INVALID_ARGS_ERROR, "Setup show", ((GroovyShowConfig) setupShow).getRequiredArgumentCount()));
                    }
                }
                if (!(cleanupShow instanceof GroovyShowConfig)) {
                    throw new IllegalArgumentException(String.format(NOT_SHOWSCRIPT_3_ERROR, "Cleanup show"));
                } else {
                    if (((GroovyShowConfig) cleanupShow).getRequiredArgumentCount() != 1) {
                        throw new IllegalArgumentException(String.format(INVALID_ARGS_ERROR, "Cleanup show", ((GroovyShowConfig) cleanupShow).getRequiredArgumentCount()));
                    }
                }
                for (TimecodeShowConfig show : loopShows) {
                    if (!(show instanceof GroovyShowConfig)) {
                        throw new IllegalArgumentException(String.format(NOT_SHOWSCRIPT_3_ERROR, "Loop show " + show.getShowName()));
                    } else {
                        if (((GroovyShowConfig) show).getRequiredArgumentCount() != 1) {
                            throw new IllegalArgumentException(String.format(INVALID_ARGS_ERROR, "Loop show " + show.getShowName(), ((GroovyShowConfig) show).getRequiredArgumentCount()));
                        }
                    }
                }

                for (String region : regions) {
                    if (!region.toLowerCase().equals(region)) {
                        throw new IllegalArgumentException("Region names are always lowercase, but you entered " + region);
                    }
                    loadRegionShow(filepath, region, setupShow, loopShows, cleanupShow, setupDelay, loopShowDelays, isIgnored, true);

                    if (!isIgnored && sender != null) sender.sendMessage("Loaded " + f.getName() + " in region " + region);
                }
            }



            if (isIgnored) {
                if (sender != null) sender.sendMessage("Ignoring region show schema " + f.getName() + "(if it was previously running, it has been unloaded)");
                return true;
            }



            return true;
        } catch (Exception ex) {
            if (sender != null) {
                sender.sendMessage("Error loading region show " + f.getName() + ": " + ex.getMessage());
            }
            ex.printStackTrace();
            return false;
        }
    }

    private void loadRegionShow(String filepath, String region, TimecodeShowConfig setupShow, List<TimecodeShowConfig> loopShows, TimecodeShowConfig cleanupShow, long setupDelay, List<Long> loopDelays, boolean isIgnored, boolean isMultiRegion) throws Exception {
        String name = isMultiRegion ? (filepath + ":multiregion:" + region) : filepath;
        RegionShowInstance regionShowInstance = new RegionShowInstance(
                new RegionShowConfig(
                        filepath,
                        name,
                        region,
                        setupShow,
                        loopShows,
                        cleanupShow,
                        setupDelay,
                        loopDelays,
                        isMultiRegion
                )
        );




        if (isIgnored) {
            ignoredSchemas.put(region, regionShowInstance);
            return;
        }
        ignoredSchemas.remove(region, regionShowInstance);
        regionMap.put(region, regionShowInstance);
        schemaRegionMap.put(filepath, region);
    }


    public void unloadRegionShows() {
        for (String key : regionMap.keySet()) {
            for (RegionShowInstance instance : regionMap.get(key)) {
                instance.stop(true);
                regionMap.remove(key, instance);
                schemaRegionMap.remove(instance.cfg.name, key);
            }
        }
        schemaRegionMap.clear();
        regionMap.clear();
    }

    public void reloadRegionShows() {
        unloadRegionShows();
        loadRegionShows();
    }

    @CommandMethod("loadregionshows <path>")
    @CommandPermission("castmember")
    public void loadRegionShowsCommand(CommandSender sender, @Argument(value="path", suggestions = "showDirectoryNames") String path) {
        loadRegionShows(new File(Main.getPlugin(Main.class).fs, path).toPath(), sender);
    }

    @CommandMethod("reloadregionshows")
    @CommandPermission("castmember")
    public void reloadRegionShowsCommand(CommandSender sender) {
        reloadRegionShows();
        sender.sendMessage("Reloaded region shows");
    }

    @CommandMethod("regionshows")
    @CommandPermission("castmember")
    public void listRegionShowsCommand(CommandSender sender,
                                   @Flag(value="region", suggestions = "regions") String region,
                                   @Flag(value="path", suggestions = "showDirectoryNames") String path,
                                   @Flag(value="ignored") boolean ignored,
                                   @Flag(value="status", suggestions = "statuses") String status) {
        StringBuilder message = new StringBuilder(ChatColor.GREEN + "Region Shows (")
                .append(ignored ? "Ignored" : "Active")
                .append(region != null ? ", in region " + region : "")
                .append(status != null ? ", with status " + status : "")
                .append(path != null ? ", in directory " + path : "")
                .append("):")
                .append("\n");

        Multimap<String, RegionShowInstance> instanceMap = ignored ? ignoredSchemas : regionMap;
        if (region != null) {
            message.append(printRegionShowsInRegion(instanceMap, region, path, status));
        } else {
            for (String r : instanceMap.keySet()) {
                message.append(printRegionShowsInRegion(instanceMap, r, path, status));
            }
        }

        sender.sendMessage(message.toString());

    }

    private String printRegionShowsInRegion(Multimap<String, RegionShowInstance> instanceMap, String region, String path, String status) {
        boolean anyMatch = false;
        StringBuilder message = new StringBuilder()
                .append(ChatColor.YELLOW)
                .append(region)
                .append(": \n  ");
        for (RegionShowInstance rsi : instanceMap.get(region)) {
            if (path != null && !Shows.getShowNameFromAbsolutePath(rsi.cfg.name).startsWith(path)) continue;
            if (status != null && !rsi.status.name().equalsIgnoreCase(status)) continue;

            anyMatch = true;
            message
                    .append(ChatColor.AQUA)
                    .append(Shows.getShowNameFromAbsolutePath(rsi.cfg.name))
                    .append(" - ")
                    .append(rsi.status.equals(RegionShowInstance.Status.SETUP) ? ChatColor.GOLD :
                            rsi.status.equals(RegionShowInstance.Status.LOOP) ? ChatColor.GREEN :
                            rsi.status.equals(RegionShowInstance.Status.CLEANUP) ? ChatColor.LIGHT_PURPLE :
                                    ChatColor.RED)
                    .append(rsi.status.name())
                    .append("\n  ");
        }
        // remove the trailing two spaces
        message.delete(message.length() - 2, message.length());
        return anyMatch ? message.toString() : "";
    }

    @Suggestions("regions")
    public List<String> regions(CommandContext<CommandSender> sender, String input) {
        return regionMap.keySet().stream().filter(r -> r.startsWith(input)).collect(Collectors.toList());
    }

    @Suggestions("statuses")
    public List<String> statuses(CommandContext<CommandSender> sender, String input) {
        return Arrays.stream(RegionShowInstance.Status.values()).map(Enum::name).filter(s -> s.startsWith(input.toUpperCase())).collect(Collectors.toList());
    }

    @EventHandler
    public void onPlayerEnterRegion(PlayerEnterRegionEvent e) {
        if (regionMap.containsKey(e.getRegionName())) {
            regionMap.get(e.getRegionName()).forEach(RegionShowInstance::onPlayerEnterRegion);
        }
    }

    @EventHandler
    public void onPlayerLeaveRegion(PlayerLeaveRegionEvent e) {
        if (regionMap.containsKey(e.getRegionName())) {
            regionMap.get(e.getRegionName()).forEach(RegionShowInstance::onPlayerLeaveRegion);
        }
    }

    class RegionShowConfig {
        final String name;
        final String region;
        final String filePath;
        final TimecodeShow setupShow, cleanupShow;
        final List<TimecodeShow> loopShows;
        final boolean isMultiRegion;

        public RegionShowConfig(String filePath, String name, String region, TimecodeShowConfig setupShow, List<TimecodeShowConfig> loopShows, TimecodeShowConfig cleanupShow, Long setupDelay, List<Long> loopDelays, boolean isMultiRegion) throws Exception {
            assert setupShow != null;
            assert cleanupShow != null;

            this.name = name;
            this.region = region;
            this.isMultiRegion = isMultiRegion;
            this.filePath = filePath;

            if (isMultiRegion) {
                setupShow = ((GroovyShowConfig) setupShow).cloneWithArgs(new ShowArgs(new Object[]{region}));
                loopShows = loopShows.stream().map(show -> ((GroovyShowConfig) show).cloneWithArgs(new ShowArgs(new Object[]{region}))).collect(Collectors.toList());
                cleanupShow = ((GroovyShowConfig) cleanupShow).cloneWithArgs(new ShowArgs(new Object[]{region}));

                setupShow.setShowName(setupShow.getShowName() + ":region:" + region);
                cleanupShow.setShowName(cleanupShow.getShowName() + ":region:" + region);
                for (TimecodeShowConfig loopShow : loopShows) {
                    loopShow.setShowName(loopShow.getShowName() + ":region:" + region);
                }
            } else {
                setupShow = setupShow.clone();
                loopShows = loopShows.stream().map(show -> show.clone()).collect(Collectors.toList());
                cleanupShow = cleanupShow.clone();
            }


            if (setupDelay > 0) {
                addSetupDelay(setupShow, setupDelay);
            }
            int cleanupShowDuration = cleanupShow.getDuration();
            for (int i = 0; i < loopShows.size(); i++) {
                TimecodeShowConfig show = loopShows.get(i);

                // if the show calls itself, throw an error
                for (ShowAction action : show.getMap().values()) {
                    if (action.getType().equals(ShowActionType.CMD)) {
                        if (action.getStringProp("cmd").startsWith("show start " + show.getShowName())) {
                            throw new Exception("Loop show " + show.getShowName() + " calls itself");
                        }
                    } else if (action.getType().equals(ShowActionType.SHOW)) {
                        if (action.getStringProp("name").equals(show.getShowName())) {
                            throw new Exception("Loop show " + show.getShowName() + " calls itself");
                        }
                    }
                }

                if (!show.getActionTicks().isEmpty()) {
                    long delay = loopDelays.get(i);
                    addLoopAction(show, delay);
                    show.setMaxRecursionDepth(Long.MAX_VALUE-1);
                }
                
                // add stop loop shows to the end of the cleanup show
                addLoopCleanupAction(cleanupShow, show, cleanupShowDuration);
            }            
            this.setupShow = setupShow.createShow(0, new ShowArgs(new Object[]{region}));
            this.loopShows = loopShows.stream().map(show -> show.createShow(0, new ShowArgs(new Object[]{region}))).collect(Collectors.toList());
            this.cleanupShow = cleanupShow.createShow(0, new ShowArgs(new Object[]{region}));
        }

        @Override
        public boolean equals(Object o) {
            // return true if the names are the same
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RegionShowConfig that = (RegionShowConfig) o;
            return Objects.equals(this.name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        private void addSetupDelay(TimecodeShowConfig setupShow, long delay) {
            if (delay < 0) {
                throw new IllegalArgumentException("Delay must be greater than or equal to 0");
            }
            Map<String, String> showActionMap = new HashMap<>();
            showActionMap.put("item", "cmd");
            showActionMap.put("cmd", "");
            setupShow.addShowAction((int) (setupShow.getDuration()+delay), new BasicShowAction(ShowActionType.CMD, showActionMap));
        }

        private void addLoopAction(TimecodeShowConfig loopShow, long delay) {
            if (delay < 0) {
                throw new IllegalArgumentException("Delay must be greater than or equal to 0");
            }

            if (loopShow instanceof GroovyShowConfig) {
                loopShow.addShowAction((int)(loopShow.getDuration()-1 + delay), new GroovyShowAction(ShowActionType.SELF, new HashMap<>()));
            } else {
                Map<String, String> showActionMap = new HashMap<>();
                showActionMap.put("item", "cmd");
                showActionMap.put("cmd", "show start " + loopShow.getShowName());
                int loopDuration = loopShow.getDuration();
                loopShow.addShowAction((int)(loopDuration + delay), new BasicShowAction(ShowActionType.CMD, showActionMap));
            }
        }

        private void addLoopCleanupAction(TimecodeShowConfig cleanupShow, TimecodeShowConfig loopShow, int cleanupShowDuration) {
            Map<String, String> killActionMap = new HashMap<>();
            killActionMap.put("item", "cmd");
            killActionMap.put("cmd", "show stop " + loopShow.getShowName());
            cleanupShow.addShowAction(cleanupShowDuration + 1, new BasicShowAction(ShowActionType.CMD, killActionMap));
        }
    }

    static class RegionShowInstance {
        RegionShowConfig cfg;
        Status status=Status.IDLE;
        long regionPlayerCount;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RegionShowInstance that = (RegionShowInstance) o;
            return Objects.equals(cfg, that.cfg);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cfg);
        }

        enum Status {
            SETUP, LOOP, CLEANUP, IDLE
        }

        public RegionShowInstance(RegionShowConfig cfg) {
            this.cfg = cfg;
        }

        public void onPlayerEnterRegion() {
            incrementPlayerCount();
            DebugLogger.log("entered " + cfg.region + " New Count: " + regionPlayerCount);

            if (regionPlayerCount == 1) {
                start();
            }

        }

        public void onPlayerLeaveRegion() {
            decrementPlayerCount();
            DebugLogger.log("left " + cfg.region + " New Count: " + regionPlayerCount);

            if (regionPlayerCount < 1) {
                stop(false);
            }
        }

        public void start() {
            DebugLogger.log("Starting " + cfg.region);

            if (status == Status.IDLE) {
                DebugLogger.log(cfg.region + " (is idle)");

                // clean up any loop shows that happen to be running
                stopLoopShows();
                
                status = Status.SETUP;
                startSetupShow();

                OneTimeUseListener.<ShowStopEvent>createOneTimeUseListener(ShowStopEvent.class, event -> event.getShow().getName().equals(cfg.setupShow.getShowName()), (event) -> this.setupComplete(), EventPriority.LOWEST);
            } else if (status == Status.CLEANUP) {
                DebugLogger.log(cfg.region + " (is cleanup)");

                OneTimeUseListener.<ShowStopEvent>createOneTimeUseListener(ShowStopEvent.class, event -> event.getShow().getName().equals(cfg.cleanupShow.getShowName()), (event) -> this.start());
            }
        }

        public void stop(boolean force) {
            DebugLogger.log("Stopping " + cfg.region);

            if (status == Status.LOOP) {
                DebugLogger.log(cfg.region + " (is loop)");
                
                stopLoopShows();
                
                status = Status.CLEANUP;
                startCleanupShow();
                OneTimeUseListener.<ShowStopEvent>createOneTimeUseListener(ShowStopEvent.class, event -> event.getShow().getName().equals(cfg.cleanupShow.getShowName()), (event) -> this.cleanupComplete(), EventPriority.LOWEST);
            } else if (status == Status.SETUP) {
                DebugLogger.log(cfg.region + " (is setup)");

                OneTimeUseListener.<ShowStopEvent>createOneTimeUseListener(ShowStopEvent.class, event -> event.getShow().getName().equals(cfg.setupShow.getShowName()), (event) -> this.stop(false));

                if (force) {
                    Main.getPlugin(Main.class).cancelShowByName(cfg.setupShow.getShowName());
                }
            
            }
        }


        private void stopLoopShows() {
            for (TimecodeShow show : cfg.loopShows) {
                if (!show.isEmpty()) {
                    Main.getPlugin(Main.class).cancelShowByName(show.getShowName());
                }
            }
        }

        private void setupComplete() {
            DebugLogger.log(cfg.region + " Setup done.");

            if (status == Status.SETUP) {
                DebugLogger.log("Starting to loop " + cfg.region);

                status = Status.LOOP;

                startLoopShows();
                
            }
        }

        private void cleanupComplete() {
            DebugLogger.log(cfg.region + " Cleanup done.");

            if (status == Status.CLEANUP) {
                DebugLogger.log(cfg.region + " Going idle");

                status = Status.IDLE;
            }
        }

        private long decrementPlayerCount() {
            regionPlayerCount = Long.max(0, regionPlayerCount - 1);
            return regionPlayerCount;
        }

        private long incrementPlayerCount() {
            return  regionPlayerCount++;
        }

        private void startSetupShow() {
            try {
                Main.getPlugin(Main.class).timecodeExecutor.startShow(cfg.setupShow.clone(), Bukkit.getConsoleSender(), false, 0, false);
            } catch (Exception ex) {
                Bukkit.getLogger().log(Level.WARNING, "Error starting setup show for " + cfg.name, ex);
            }
        }

        private void startCleanupShow() {
            try {
                Main.getPlugin(Main.class).timecodeExecutor.startShow(cfg.cleanupShow.clone(), Bukkit.getConsoleSender(), false, 0, false);
            } catch (Exception ex) {
                Bukkit.getLogger().log(Level.WARNING, "Error starting cleanup show for " + cfg.name, ex);
            }
        }

        private void startLoopShows() {
            for (TimecodeShow show : cfg.loopShows) {
                try {
                    Main.getPlugin(Main.class).timecodeExecutor.startShow(show.clone(), Bukkit.getConsoleSender(), false, 0, false);
                } catch (Exception ex) {
                    Bukkit.getLogger().log(Level.WARNING, "Error starting loop show " + show.getShowName() + " for " + cfg.name, ex);
                }
            }
        }


    }
    


}
