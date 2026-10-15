package io.tanmay.hgauto;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Automation layer for HungerGameSSS on Paper 1.21.11.
 * HungerGameSSS remains responsible for the actual game lifecycle, dungeons,
 * loot, Cornucopia construction, countdown, deathmatch and arena reset.
 *
 * Future multi-match support: replace the single activeMatch field with a
 * Map<String, MatchSession> and route queues by mode/arena.
 */
public final class HGAuto extends JavaPlugin implements Listener, TabExecutor {
    private enum Mode {
        SOLO("solo", 1), DUO("duo", 2), SQUAD("squad", 4);
        final String id;
        final int teamSize;
        Mode(String id, int teamSize) { this.id = id; this.teamSize = teamSize; }
        static Mode parse(String raw) {
            if (raw == null) return null;
            for (Mode m : values()) if (m.id.equalsIgnoreCase(raw)) return m;
            return null;
        }
        int minPlayers(HGAuto plugin) {
            return Math.max(2, plugin.getConfig().getInt("modes." + id + ".min-players", switch (this) {
                case SOLO -> 2; case DUO -> 4; case SQUAD -> 8;
            }));
        }
    }

    private enum State { PREPARING, WAITING, RUNNING, CLEANING }

    private static final class MatchSession {
        final String arenaName;
        final String worldName;
        final Mode mode;
        final long seed;
        State state = State.PREPARING;
        final Set<UUID> queuedPlayers = new HashSet<>();
        long startedAtMillis;
        long emptySinceMillis;
        boolean startCountdownScheduled;
        MatchSession(String arenaName, String worldName, Mode mode, long seed) {
            this.arenaName = arenaName;
            this.worldName = worldName;
            this.mode = mode;
            this.seed = seed;
        }
    }

    private static final class TeamGroup {
        final String name;
        final UUID owner;
        final Set<UUID> members = new HashSet<>();
        final Set<UUID> pendingInvites = new HashSet<>();
        boolean open;
        TeamGroup(String name, UUID owner) {
            this.name = name;
            this.owner = owner;
            members.add(owner);
        }
    }

    // Intentionally one active match for v1. MatchSession is isolated for a future map-based manager.
    private MatchSession activeMatch;
    private final Map<UUID, Mode> playerModes = new HashMap<>();
    private final LinkedHashMap<UUID, Mode> nextQueue = new LinkedHashMap<>();
    private final Map<String, TeamGroup> teamsByName = new HashMap<>();
    private final Map<UUID, TeamGroup> teamByMember = new HashMap<>();
    private BukkitTask pollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("br").setExecutor(this);
        getCommand("br").setTabCompleter(this);
        getCommand("solo").setExecutor(this);
        getCommand("duo").setExecutor(this);
        getCommand("squad").setExecutor(this);
        restoreSessionIfPresent();
        pollTask = Bukkit.getScheduler().runTaskTimer(this, this::pollMatch, 100L, 100L);
        if (!hungerGamesPresent()) {
            getLogger().severe("HungerGameSSS wasn't found/enabled. Install HungerGameSSS before using HGAuto.");
        } else {
            getLogger().info("HGAuto enabled for Paper 1.21.11. One active match at a time.");
        }
    }

    @Override
    public void onDisable() {
        if (pollTask != null) pollTask.cancel();
        saveSession();
    }

    private boolean hungerGamesPresent() {
        var pm = Bukkit.getPluginManager();
        Plugin a = pm.getPlugin("HungerGameSSS");
        Plugin b = pm.getPlugin("HungerGamesSSS");
        return (a != null && a.isEnabled()) || (b != null && b.isEnabled());
    }

    private String prefix() { return getConfig().getString("messages.prefix", "§8[§6HGAuto§8] §r"); }
    private void tell(CommandSender sender, String message) { sender.sendMessage(prefix() + message); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("solo") || commandName.equals("duo") || commandName.equals("squad")) {
            if (!(sender instanceof Player player)) { tell(sender, "These commands can only be used in-game."); return true; }
            joinGame(player, Mode.parse(commandName));
            return true;
        }
        if (args.length == 0) {
            tell(sender, "NPC command: /br play <solo|duo|squad>. Also: /br join [solo|duo|squad], /br leave, /br status");
            tell(sender, "Admin: /br setlobby, /br start, /br cancel, /br reload");
            tell(sender, "Teams use HungerGameSSS's native /team commands (team size is capped by the selected mode).");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "play" -> {
                if (!(sender instanceof Player player)) { tell(sender, "Players only."); return true; }
                if (args.length < 2) { tell(sender, "Usage: /br play <solo|duo|squad>"); return true; }
                Mode mode = Mode.parse(args[1]);
                if (mode == null) { tell(sender, "Usage: /br play <solo|duo|squad>"); return true; }
                joinGame(player, mode);
            }
            case "join" -> {
                if (!(sender instanceof Player player)) { tell(sender, "Players only."); return true; }
                Mode mode = args.length >= 2 ? Mode.parse(args[1]) : playerModes.getOrDefault(player.getUniqueId(), activeMatch == null ? Mode.SOLO : activeMatch.mode);
                if (mode == null) { tell(sender, "Usage: /br join <solo|duo|squad>"); return true; }
                joinGame(player, mode);
            }
            case "leave" -> {
                if (!(sender instanceof Player player)) { tell(sender, "Players only."); return true; }
                leaveGame(player);
            }
            case "setlobby" -> {
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player player)) { tell(sender, "Stand at your lobby location and run /br setlobby in-game."); return true; }
                saveLobby(player);
            }
            case "status" -> sendStatus(sender);
            case "start" -> {
                if (!requireAdmin(sender)) return true;
                if (activeMatch == null || activeMatch.state != State.WAITING) { tell(sender, "There is no waiting match to start."); return true; }
                forceStart(sender);
            }
            case "cancel" -> {
                if (!requireAdmin(sender)) return true;
                cancelWaitingMatch(sender);
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                reloadConfig();
                tell(sender, "Configuration reloaded. Existing match settings are unchanged.");
            }
            case "team" -> {
                if (!(sender instanceof Player player)) { tell(sender, "Players only."); return true; }
                if (args.length < 2) { tell(sender, "Use native team commands: /team create, invite, join, leave, open, disband."); return true; }
                StringBuilder nativeCommand = new StringBuilder("team");
                for (int i = 1; i < args.length; i++) nativeCommand.append(' ').append(args[i]);
                player.performCommand(nativeCommand.toString());
            }
            default -> tell(sender, "Unknown command. Use /br for help.");
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("hgauto.admin")) return true;
        tell(sender, "You do not have permission to do that.");
        return false;
    }

    private void saveLobby(Player player) {
        Location loc = player.getLocation();
        getConfig().set("lobby.world", loc.getWorld().getName());
        getConfig().set("lobby.x", loc.getX());
        getConfig().set("lobby.y", loc.getY());
        getConfig().set("lobby.z", loc.getZ());
        getConfig().set("lobby.yaw", loc.getYaw());
        getConfig().set("lobby.pitch", loc.getPitch());
        saveConfig();
        if (!hungerGamesPresent()) {
            tell(player, "Saved the lobby in HGAuto, but HungerGameSSS is not enabled, so its post-match lobby wasn't updated.");
            return;
        }
        boolean result = runAsHgAdmin(player, "hg setlobby");
        if (result) tell(player, "Lobby saved and HungerGameSSS post-match lobby updated.");
        else tell(player, "Lobby saved in HGAuto, but /hg setlobby did not confirm success. Check the HungerGameSSS console/log.");
    }

    private Location getSavedLobby() {
        String worldName = getConfig().getString("lobby.world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null || !getConfig().contains("lobby.x")) return null;
        return new Location(world,
                getConfig().getDouble("lobby.x"),
                getConfig().getDouble("lobby.y"),
                getConfig().getDouble("lobby.z"),
                (float) getConfig().getDouble("lobby.yaw"),
                (float) getConfig().getDouble("lobby.pitch"));
    }

    private void joinGame(Player player, Mode mode) {
        if (!hungerGamesPresent()) { tell(player, "HungerGameSSS is missing or disabled. Install it and restart the server."); return; }
        playerModes.put(player.getUniqueId(), mode);
        if (activeMatch == null) {
            createMatchAndJoin(player, mode);
            return;
        }
        if (activeMatch.state == State.PREPARING) {
            nextQueue.put(player.getUniqueId(), mode);
            tell(player, "The arena is being prepared. You have been queued for the next available join slot.");
            return;
        }
        if (activeMatch.state == State.WAITING && activeMatch.mode == mode) {
            joinActiveWaiting(player);
            return;
        }
        if (activeMatch.state == State.WAITING && activeMatch.mode != mode) {
            nextQueue.put(player.getUniqueId(), mode);
            tell(player, "A " + activeMatch.mode.id + " match is already waiting. You are queued for the next " + mode.id + " match.");
            return;
        }
        nextQueue.put(player.getUniqueId(), mode);
        tell(player, "The current match has already started. You are queued for the next " + mode.id + " match.");
    }

    private String chooseCornucopiaTheme() {
        String configured = getConfig().getString("cornucopia-theme", "random");
        if (configured == null || !configured.equalsIgnoreCase("random")) {
            return configured == null ? "classic" : configured.toLowerCase(Locale.ROOT);
        }
        // Themes documented by HungerGameSSS. Pick independently for every newly generated match.
        List<String> themes = List.of("classic", "ultimate", "speedsilver", "ancient", "void", "royal",
                "industrial", "modern", "crystal", "jungle", "end", "nether", "grand");
        return themes.get(ThreadLocalRandom.current().nextInt(themes.size()));
    }

    private void createMatchAndJoin(Player firstPlayer, Mode mode) {
        Location lobby = getSavedLobby();
        if (lobby == null) {
            tell(firstPlayer, "An admin must stand in the separate lobby world and run /br setlobby once before games can start.");
            return;
        }
        if (activeMatch != null) { tell(firstPlayer, "A match is already being prepared."); return; }
        long seed = nextSeed();
        String suffix = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36), 36);
        String worldName = cleanName(getConfig().getString("world-prefix", "hga_world") + "_" + mode.id + "_" + suffix);
        String arenaName = cleanName("hga_" + mode.id + "_" + suffix);
        MatchSession match = new MatchSession(arenaName, worldName, mode, seed);
        activeMatch = match;
        saveSession();
        Location originalLocation = firstPlayer.getLocation().clone();
        tell(firstPlayer, "Preparing a new " + mode.id + " world with seed " + seed + ". The first world generation may briefly affect TPS.");
        try {
            WorldCreator creator = new WorldCreator(worldName).seed(seed).environment(World.Environment.NORMAL).generateStructures(true);
            World world = creator.createWorld();
            if (world == null) throw new IllegalStateException("Paper failed to create world " + worldName);
            world.setDifficulty(Difficulty.HARD);
            world.setPVP(true);

            // The command is location-sensitive, so execute setup as the joining player with a temporary permission attachment.
            firstPlayer.teleport(lobby);
            if (!runAsHgAdmin(firstPlayer, "hg setlobby")) {
                throw new IllegalStateException("HungerGameSSS did not accept /hg setlobby.");
            }
            Location center = safeLocation(world, 0, 0);
            firstPlayer.teleport(center);
            if (!runAsHgAdmin(firstPlayer, "hg create " + arenaName)) {
                throw new IllegalStateException("HungerGameSSS did not accept /hg create " + arenaName);
            }
            String theme = chooseCornucopiaTheme();
            if (!runAsHgAdmin(firstPlayer, "hg setcornucopia " + arenaName + " " + theme)) {
                throw new IllegalStateException("HungerGameSSS did not accept /hg setcornucopia.");
            }

            int spawnCount = Math.max(2, getConfig().getInt("spawn-points", 24));
            int radius = Math.max(25, getConfig().getInt("spawn-radius", 55));
            for (int i = 0; i < spawnCount; i++) {
                Location spawn = spawnLocation(world, i, spawnCount, radius);
                firstPlayer.teleport(spawn);
                if (!runAsHgAdmin(firstPlayer, "hg addspawn " + arenaName)) {
                    throw new IllegalStateException("HungerGameSSS did not accept /hg addspawn at point " + (i + 1));
                }
            }
            firstPlayer.teleport(originalLocation);
            match.state = State.WAITING;
            saveSession();
            joinActiveWaiting(firstPlayer);
            tell(firstPlayer, "Arena ready: " + arenaName + " (Cornucopia X/Z = 0,0; theme=" + theme + "; " + spawnCount + " spawns). Waiting for players.");
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not prepare match " + arenaName + ". The world may have been partially created.", ex);
            firstPlayer.teleport(originalLocation);
            tell(firstPlayer, "Arena setup failed: " + ex.getMessage() + ". Check the console. No match was started.");
            activeMatch = null;
            saveSession();
            // Do not delete/unload a partially configured world here; HungerGameSSS may have persisted partial arena data.
        }
    }

    private boolean runAsHgAdmin(Player player, String command) {
        PermissionAttachment attachment = player.addAttachment(this, "hoplitebr.admin", true);
        try {
            return player.performCommand(command);
        } finally {
            attachment.remove();
        }
    }

    private long nextSeed() {
        long seed;
        long previous = getConfig().getLong("last-seed", Long.MIN_VALUE);
        do { seed = ThreadLocalRandom.current().nextLong(); } while (seed == previous);
        getConfig().set("last-seed", seed);
        saveConfig();
        return seed;
    }

    private String cleanName(String raw) {
        String name = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return name.length() <= 48 ? name : name.substring(0, 48);
    }

    private Location safeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z) + 1;
        y = Math.max(world.getMinHeight() + 1, Math.min(y, world.getMaxHeight() - 2));
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private Location spawnLocation(World world, int index, int count, int radius) {
        double baseAngle = 2.0 * Math.PI * index / count;
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = baseAngle + attempt * 0.19;
            double spread = radius * (0.88 + ((index + attempt) % 5) * 0.035);
            int x = (int) Math.round(Math.cos(angle) * spread);
            int z = (int) Math.round(Math.sin(angle) * spread);
            int y = world.getHighestBlockYAt(x, z);
            if (y < world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) continue;
            Block ground = world.getBlockAt(x, y, z);
            Block feet = ground.getRelative(BlockFace.UP);
            Block head = feet.getRelative(BlockFace.UP);
            Material type = ground.getType();
            String materialName = type.name();
            if (ground.isSolid() && !ground.isLiquid() && !materialName.endsWith("_LEAVES")
                    && !materialName.equals("MAGMA_BLOCK") && feet.isPassable() && head.isPassable()) {
                return new Location(world, x + 0.5, y + 1.0, z + 0.5);
            }
        }
        // A fallback keeps arena creation deterministic even on mostly-water terrain.
        return safeLocation(world, (int) Math.round(Math.cos(baseAngle) * radius), (int) Math.round(Math.sin(baseAngle) * radius));
    }

    private void joinActiveWaiting(Player player) {
        MatchSession match = activeMatch;
        if (match == null || match.state != State.WAITING) { nextQueue.put(player.getUniqueId(), playerModes.getOrDefault(player.getUniqueId(), Mode.SOLO)); return; }
        if (match.queuedPlayers.contains(player.getUniqueId())) { tell(player, "You are already queued for this match."); return; }
        int maxPlayers = Math.max(2, getConfig().getInt("max-players", 24));
        if (match.queuedPlayers.size() >= maxPlayers) { nextQueue.put(player.getUniqueId(), match.mode); tell(player, "This match is full; you are queued for the next one."); return; }
        boolean joined = performWithHgPermission(player, "hg join " + match.arenaName);
        if (!joined) {
            tell(player, "HungerGameSSS rejected the join command. Check its permissions and console log.");
            return;
        }
        match.queuedPlayers.add(player.getUniqueId());
        nextQueue.remove(player.getUniqueId());
        tell(player, "Joined the " + match.mode.id + " queue (" + match.queuedPlayers.size() + "/" + maxPlayers + ").");
        if (match.queuedPlayers.size() >= match.mode.minPlayers(this)) scheduleLaunch(match);
        saveSession();
    }

    private boolean performWithHgPermission(Player player, String command) {
        PermissionAttachment attachment = player.addAttachment(this, "hoplitebr.hg", true);
        try { return player.performCommand(command); }
        finally { attachment.remove(); }
    }

    private void scheduleLaunch(MatchSession match) {
        if (match.startCountdownScheduled || match.state != State.WAITING) return;
        match.startCountdownScheduled = true;
        int seconds = Math.max(1, getConfig().getInt("launch-delay-seconds", 15));
        Bukkit.broadcastMessage(prefix() + "A " + match.mode.id + " match starts in " + seconds + " seconds! Use /br join to enter.");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeMatch != match || match.state != State.WAITING) return;
            int required = match.mode.minPlayers(this);
            if (match.queuedPlayers.size() < required) {
                match.startCountdownScheduled = false;
                tell(Bukkit.getConsoleSender(), "Launch cancelled because the queue dropped below " + required + " players.");
                return;
            }
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            boolean accepted = Bukkit.dispatchCommand(console, "hg start " + match.arenaName);
            if (!accepted) {
                match.startCountdownScheduled = false;
                getLogger().warning("Console /hg start " + match.arenaName + " was not handled. Check the installed HungerGameSSS version.");
                Bukkit.broadcastMessage(prefix() + "Couldn't start the match. Please check the server console.");
                return;
            }
            match.state = State.RUNNING;
            match.startedAtMillis = System.currentTimeMillis();
            saveSession();
            Bukkit.broadcastMessage(prefix() + "The " + match.mode.id + " Hunger Games match has started!");
        }, seconds * 20L);
    }

    private void forceStart(CommandSender sender) {
        MatchSession match = activeMatch;
        if (match.queuedPlayers.size() < match.mode.minPlayers(this)) {
            tell(sender, "Not enough players. Need " + match.mode.minPlayers(this) + ", currently " + match.queuedPlayers.size() + ".");
            return;
        }
        if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hg start " + match.arenaName)) {
            match.state = State.RUNNING;
            match.startedAtMillis = System.currentTimeMillis();
            saveSession();
            tell(sender, "Start command sent to HungerGameSSS.");
        } else tell(sender, "HungerGameSSS did not accept /hg start. Check the console.");
    }

    private void leaveGame(Player player) {
        UUID id = player.getUniqueId();
        nextQueue.remove(id);
        if (activeMatch != null && activeMatch.queuedPlayers.contains(id)) {
            performWithHgPermission(player, "hg leave");
            activeMatch.queuedPlayers.remove(id);
            tell(player, "You left the current Hunger Games queue.");
            if (activeMatch.state == State.WAITING && activeMatch.queuedPlayers.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (activeMatch != null && activeMatch.state == State.WAITING && activeMatch.queuedPlayers.isEmpty()) finishMatch(activeMatch, "queue empty");
                }, 100L);
            }
        } else if (activeMatch != null && activeMatch.state == State.RUNNING) {
            performWithHgPermission(player, "hg leave");
            tell(player, "You left the current match.");
        } else tell(player, "You are not in an active match queue.");
    }

    private void pollMatch() {
        MatchSession match = activeMatch;
        if (match == null) return;
        if (match.state == State.RUNNING) {
            World world = Bukkit.getWorld(match.worldName);
            if (world == null) {
                finishMatch(match, "arena world was unloaded");
                return;
            }
            if (world.getPlayers().isEmpty()) {
                if (match.emptySinceMillis == 0L) match.emptySinceMillis = System.currentTimeMillis();
                long emptySeconds = Math.max(15, getConfig().getInt("end-detection.empty-world-seconds", 45));
                long minRunSeconds = Math.max(60, getConfig().getInt("end-detection.minimum-run-seconds", 90));
                if (System.currentTimeMillis() - match.emptySinceMillis >= emptySeconds * 1000L
                        && System.currentTimeMillis() - match.startedAtMillis >= minRunSeconds * 1000L) {
                    finishMatch(match, "the match world remained empty");
                }
            } else match.emptySinceMillis = 0L;
        } else if (match.state == State.WAITING && match.queuedPlayers.isEmpty()) {
            // A server restart may leave an empty waiting arena behind.
            if (Bukkit.getWorld(match.worldName) == null) activeMatch = null;
        }
    }

    private void finishMatch(MatchSession match, String reason) {
        if (activeMatch != match || match.state == State.CLEANING) return;
        match.state = State.CLEANING;
        saveSession();
        int delay = Math.max(30, getConfig().getInt("world-unload-delay-seconds", 120));
        getLogger().info("Match " + match.arenaName + " looks finished (" + reason + "). World unload scheduled in " + delay + " seconds.");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeMatch != match) return;
            World world = Bukkit.getWorld(match.worldName);
            if (world != null && !world.getPlayers().isEmpty()) {
                match.state = State.RUNNING;
                match.emptySinceMillis = 0L;
                saveSession();
                return;
            }
            if (world != null) {
                boolean unloaded = Bukkit.unloadWorld(world, false);
                if (!unloaded) getLogger().warning("Could not unload old game world " + match.worldName + "; leaving it loaded for safety.");
            }
            // World folders are retained by default. Deleting one before HungerGameSSS finishes restoring blocks
            // can corrupt its reset pipeline or leave stale arena metadata.
            activeMatch = null;
            clearSessionConfig();
            startNextQueuedMatch();
        }, delay * 20L);
    }

    private void startNextQueuedMatch() {
        UUID firstId = null;
        Mode mode = null;
        for (Map.Entry<UUID, Mode> entry : nextQueue.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) { firstId = entry.getKey(); mode = entry.getValue(); break; }
        }
        if (firstId == null || mode == null) return;
        Player first = Bukkit.getPlayer(firstId);
        if (first == null) return;
        nextQueue.remove(firstId);
        createMatchAndJoin(first, mode);
        if (activeMatch != null && activeMatch.state == State.WAITING) {
            List<Map.Entry<UUID, Mode>> pending = new ArrayList<>(nextQueue.entrySet());
            for (Map.Entry<UUID, Mode> entry : pending) {
                if (entry.getValue() != activeMatch.mode) continue;
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) joinActiveWaiting(player);
            }
        }
    }

    private void cancelWaitingMatch(CommandSender sender) {
        if (activeMatch == null) { tell(sender, "There is no active match."); return; }
        if (activeMatch.state == State.RUNNING) { tell(sender, "Refusing to cancel a running game. Let HungerGameSSS end/reset it first."); return; }
        MatchSession match = activeMatch;
        for (UUID id : new HashSet<>(match.queuedPlayers)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) performWithHgPermission(player, "hg leave");
        }
        match.queuedPlayers.clear();
        finishMatch(match, "admin cancelled the waiting queue");
        tell(sender, "Waiting match cancelled. Its world will be unloaded later; its folder is retained.");
    }

    private void sendStatus(CommandSender sender) {
        if (activeMatch == null) {
            tell(sender, "No active match. Queued for next match: " + nextQueue.size() + ".");
            return;
        }
        MatchSession m = activeMatch;
        tell(sender, "State=" + m.state + " | mode=" + m.mode.id + " | arena=" + m.arenaName + " | world=" + m.worldName);
        tell(sender, "Seed=" + m.seed + " | queued=" + m.queuedPlayers.size() + " | next queue=" + nextQueue.size() + " | max=" + getConfig().getInt("max-players", 24));
    }

    private void restoreSessionIfPresent() {
        String state = getConfig().getString("session.state", "IDLE");
        if ("IDLE".equalsIgnoreCase(state)) return;
        String arena = getConfig().getString("session.arena", "");
        String worldName = getConfig().getString("session.world", "");
        Mode mode = Mode.parse(getConfig().getString("session.mode", "solo"));
        long seed = getConfig().getLong("session.seed", 0L);
        if (arena.isBlank() || worldName.isBlank() || mode == null || Bukkit.getWorld(worldName) == null) {
            clearSessionConfig();
            return;
        }
        MatchSession m = new MatchSession(arena, worldName, mode, seed);
        try { m.state = State.valueOf(state.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { m.state = State.WAITING; }
        if (m.state == State.PREPARING || m.state == State.CLEANING) m.state = State.WAITING;
        m.startedAtMillis = getConfig().getLong("session.started-at", System.currentTimeMillis());
        activeMatch = m;
        getLogger().warning("Restored match metadata after startup. Queued player roster isn't restored; use /br status and /br cancel if the state looks stale.");
    }

    private void saveSession() {
        if (activeMatch == null) { clearSessionConfig(); return; }
        getConfig().set("session.state", activeMatch.state.name());
        getConfig().set("session.arena", activeMatch.arenaName);
        getConfig().set("session.world", activeMatch.worldName);
        getConfig().set("session.mode", activeMatch.mode.id);
        getConfig().set("session.seed", activeMatch.seed);
        getConfig().set("session.started-at", activeMatch.startedAtMillis);
        saveConfig();
    }

    private void clearSessionConfig() {
        getConfig().set("session.state", "IDLE");
        getConfig().set("session.arena", null);
        getConfig().set("session.world", null);
        getConfig().set("session.mode", null);
        getConfig().set("session.seed", null);
        getConfig().set("session.started-at", null);
        saveConfig();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2) return;
        String[] parts = message.substring(1).trim().split("\\s+");
        if (parts.length < 2) return;
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (!root.equals("team")) return;
        Player player = event.getPlayer();
        String action = parts[1].toLowerCase(Locale.ROOT);
        Mode mode = playerModes.getOrDefault(player.getUniqueId(), activeMatch == null ? Mode.SOLO : activeMatch.mode);
        int cap = mode.teamSize;
        if (action.equals("leave") || action.equals("disband")) {
            Bukkit.getScheduler().runTaskLater(this, () -> updateTeamAfterExit(player, action), 1L);
            return;
        }
        if (action.equals("create")) {
            if (cap <= 1) { event.setCancelled(true); tell(player, "Teams are disabled in solo mode."); return; }
            if (parts.length < 3) return;
            String name = normalizeTeamName(parts[2]);
            if (teamsByName.containsKey(name)) { event.setCancelled(true); tell(player, "HGAuto is already tracking a team named '" + name + "'."); return; }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                TeamGroup group = new TeamGroup(name, player.getUniqueId());
                teamsByName.put(name, group);
                teamByMember.put(player.getUniqueId(), group);
            }, 1L);
            return;
        }
        if (action.equals("invite")) {
            if (cap <= 1) { event.setCancelled(true); tell(player, "Teams are disabled in solo mode."); return; }
            TeamGroup group = teamByMember.get(player.getUniqueId());
            if (group == null || !group.owner.equals(player.getUniqueId())) {
                event.setCancelled(true); tell(player, "Create the team with /team create first; only its leader can invite players."); return;
            }
            if (parts.length < 3) return;
            Player target = Bukkit.getPlayer(parts[2]);
            if (target == null) return; // Let HungerGameSSS return its normal offline-player message.
            if (group.members.size() + group.pendingInvites.size() >= cap) {
                event.setCancelled(true); tell(player, "This " + mode.id + " team is full (max " + cap + " players)."); return;
            }
            if (group.members.contains(target.getUniqueId())) { event.setCancelled(true); tell(player, "That player is already on your team."); return; }
            group.pendingInvites.add(target.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> group.pendingInvites.remove(target.getUniqueId()), 60L * 20L);
            return;
        }
        if (action.equals("join")) {
            if (cap <= 1) { event.setCancelled(true); tell(player, "Teams are disabled in solo mode."); return; }
            if (parts.length < 3) return;
            TeamGroup group = teamsByName.get(normalizeTeamName(parts[2]));
            if (group == null) {
                event.setCancelled(true); tell(player, "For team-size enforcement, join a team created after its leader selects duo/squad."); return;
            }
            if (group.members.contains(player.getUniqueId())) return;
            if (group.members.size() >= cap) { event.setCancelled(true); tell(player, "That team is full (max " + cap + " players)."); return; }
            boolean invited = group.pendingInvites.contains(player.getUniqueId());
            if (!invited && !group.open) { event.setCancelled(true); tell(player, "That team is invite-only. Ask its leader to invite you."); return; }
            group.pendingInvites.remove(player.getUniqueId());
            group.members.add(player.getUniqueId());
            teamByMember.put(player.getUniqueId(), group);
        } else if (action.equals("open")) {
            TeamGroup group = teamByMember.get(player.getUniqueId());
            if (group != null && group.owner.equals(player.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(this, () -> group.open = !group.open, 1L);
            }
        }
    }

    private String normalizeTeamName(String name) { return name.toLowerCase(Locale.ROOT); }

    private void updateTeamAfterExit(Player player, String action) {
        TeamGroup group = teamByMember.get(player.getUniqueId());
        if (group == null) return;
        if (action.equals("disband") && group.owner.equals(player.getUniqueId())) {
            removeTeam(group);
            return;
        }
        group.members.remove(player.getUniqueId());
        teamByMember.remove(player.getUniqueId());
        if (group.owner.equals(player.getUniqueId()) || group.members.isEmpty()) removeTeam(group);
    }

    private void removeTeam(TeamGroup group) {
        teamsByName.remove(group.name);
        for (UUID member : new HashSet<>(group.members)) teamByMember.remove(member);
        group.pendingInvites.clear();
        group.members.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activeMatch != null && activeMatch.state == State.WAITING) {
            MatchSession match = activeMatch;
            match.queuedPlayers.remove(player.getUniqueId());
            if (match.queuedPlayers.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (activeMatch == match && match.state == State.WAITING && match.queuedPlayers.isEmpty()) {
                        finishMatch(match, "all queued players disconnected");
                    }
                }, 100L);
            }
        }
        // Keep party membership over temporary disconnects so HungerGameSSS's own reconnect support can work.
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Mode queuedMode = nextQueue.get(event.getPlayer().getUniqueId());
        if (queuedMode != null && activeMatch == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> joinGame(event.getPlayer(), queuedMode), 40L);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("br")) return Collections.emptyList();
        if (args.length == 1) return filter(List.of("play", "join", "leave", "status", "setlobby", "start", "cancel", "reload", "team"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("play"))) return filter(List.of("solo", "duo", "squad"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("team")) return filter(List.of("create", "invite", "join", "leave", "open", "disband"), args[1]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.startsWith(p)) result.add(value);
        return result;
    }
}
