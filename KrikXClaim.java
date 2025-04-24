package net.instinkt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.event.block.Action;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.UUID;

public class KrikXClaim extends JavaPlugin implements TabCompleter, Listener {

    // Storage-Layer für Claims (MySQL oder YAML)
    private ClaimStorage storage;
    // Temporäre Positionen zum Setzen eines Claims (pro Spieler)
    private final Map<UUID, Location> pos1Map = new HashMap<>();
    private final Map<UUID, Location> pos2Map = new HashMap<>();

    // Temporäre Positionen für Adminzonen (pro Spieler)
    private final Map<UUID, Location> adminPos1Map = new HashMap<>();
    private final Map<UUID, Location> adminPos2Map = new HashMap<>();

    // Cooldown-Maps: show (10 Sekunden) und list (1 Sekunde)
    private final Map<UUID, Long> lastShowTime = new HashMap<>();
    private final Map<UUID, Long> lastListTime = new HashMap<>();

    // Map, die jedem Spieler (UUID) ein Mapping aus ArmorStand (Hologramm) -> BukkitRunnable (Partikel-Task) zuordnet
    private final Map<UUID, Map<ArmorStand, BukkitRunnable>> activeVisuals = new HashMap<>();

    @Override
    public void onEnable() {
        createDefaultConfig();
        reloadConfig();
        // Storage initialisieren
        String storageType = getConfig().getString("storage.type", "mysql").toLowerCase();
        if (storageType.equals("mysql")) {
            storage = new MySQLClaimStorage(this);
        } else if (storageType.equals("yaml")) {
            storage = new YamlClaimStorage(this);
        } else {
            getLogger().warning("Invalid storage type in config. Defaulting to MySQL.");
            storage = new MySQLClaimStorage(this);
        }
        try {
            storage.connect();
        } catch (Exception e) {
            getLogger().severe("Error establishing storage connection: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Registriere Befehle und Events
        String cmd = getConfig().getString("commands.claim", "claim");
        getCommand(cmd).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(), this);

        getLogger().info("KrikXClaim Plugin started successfully. Version: " + getDescription().getVersion());
    }
    @Override
    public void onDisable() {
        if (storage != null) {
            storage.disconnect();
        }
        // Entferne alle aktiven Visualisierungen
        for (Map<ArmorStand, BukkitRunnable> visuals : activeVisuals.values()) {
            for (Map.Entry<ArmorStand, BukkitRunnable> entry : visuals.entrySet()) {
                entry.getValue().cancel();
                entry.getKey().remove();
            }
        }
        activeVisuals.clear();
        getLogger().info("KrikXClaim Plugin disabled!");
    }

    private void createDefaultConfig() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                // Speichere die Standardconfig (inklusive aller neuen Einträge)
                saveResource("config.yml", false);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error creating config file", e);
        }
    }

    // Command-Handler für /claim
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be executed by a player.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                if (!player.hasPermission("instinkt.claim.reload")) {
                    player.sendMessage("§cDu hast keine Berechtigung, die Config neu zu laden.");
                    return true;
                }
                reloadConfig();
                player.sendMessage("§aConfig neu geladen.");
                break;
            case "1":
                int allowedClaims = determineMaxClaims(player);
                pos1Map.put(player.getUniqueId(), player.getLocation());
                player.sendMessage(getMessage("messages.claim.pos1_set").replace("{pos}", formatLocation(player.getLocation())));
                player.sendMessage("§aDu darfst bis zu " + allowedClaims + " Claims erstellen.");
                break;
            case "2":
                pos2Map.put(player.getUniqueId(), player.getLocation());
                player.sendMessage(getMessage("messages.claim.pos2_set").replace("{pos}", formatLocation(player.getLocation())));
                if (pos1Map.containsKey(player.getUniqueId())) {
                    createClaim(player);
                } else {
                    player.sendMessage(getMessage("messages.errors.pos1_missing"));
                }
                break;
            case "list":
                long nowList = System.currentTimeMillis();
                if (lastListTime.containsKey(player.getUniqueId())) {
                    long last = lastListTime.get(player.getUniqueId());
                    if (nowList - last < 1000) {
                        player.sendMessage("§cBitte warte einen Moment bevor du /claim list erneut benutzt.");
                        return true;
                    }
                }
                lastListTime.put(player.getUniqueId(), nowList);
                listClaims(player);
                break;
            case "rename":
                if (args.length < 3) {
                    player.sendMessage(getMessage("messages.errors.usage_rename"));
                } else {
                    renameClaim(player, args[1], args[2]);
                }
                break;
            case "delete":
            case "del":
                if (args.length < 2) {
                    player.sendMessage(getMessage("messages.errors.usage_delete"));
                } else {
                    deleteClaim(player, args[1]);
                    removeClaimVisuals(args[1]);
                }
                break;
            case "invite":
                if (!getConfig().getBoolean("claims.allow_invite", true)) {
                    player.sendMessage("§cEinladungen sind deaktiviert.");
                    break;
                }
                if (args.length < 3) {
                    player.sendMessage(getMessage("messages.errors.usage_invite"));
                } else {
                    String claimName = args[1];
                    String targetName = args[2];
                    Claim claim = null;
                    try {
                        claim = storage.getClaim(player.getUniqueId().toString(), claimName);
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Error retrieving claim for invite", e);
                    }
                    if (claim == null) {
                        player.sendMessage(getMessage("messages.errors.claim_not_found").replace("{name}", claimName));
                        break;
                    }
                    int maxUsers = getConfig().getInt("claims.invite.max_users", 5);
                    if (claim.invited.size() >= maxUsers) {
                        player.sendMessage("§cMaximale Anzahl eingeladener Spieler für diesen Claim erreicht.");
                        break;
                    }
                    boolean distanceCheck = getConfig().getBoolean("claims.invite.distance_check", true);
                    double maxDistance = getConfig().getDouble("claims.invite.max_distance", 0.0);
                    if (distanceCheck && maxDistance > 0) {
                        Player targetPlayer = Bukkit.getPlayer(targetName);
                        if (targetPlayer != null) {
                            double centerX = (claim.x1 + claim.x2) / 2.0;
                            double centerY = (claim.y1 + claim.y2) / 2.0;
                            double centerZ = (claim.z1 + claim.z2) / 2.0;
                            Location center = new Location(Bukkit.getWorld(claim.world), centerX, centerY, centerZ);
                            double distance = center.distance(targetPlayer.getLocation());
                            if (distance > maxDistance) {
                                player.sendMessage("§cDer Spieler ist zu weit entfernt (Distanz: " 
                                        + String.format("%.2f", distance) + ", max: " + maxDistance + ").");
                                break;
                            }
                        }
                    }
                    try {
                        boolean success = storage.invitePlayer(player.getUniqueId().toString(), claimName, targetName);
                        if (success) {
                            player.sendMessage(getMessage("messages.claim.invite_sent")
                                    .replace("{name}", claimName)
                                    .replace("{target}", targetName));
                        } else {
                            player.sendMessage(getMessage("messages.errors.claim_not_found").replace("{name}", claimName));
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Error inviting player", e);
                        player.sendMessage(getMessage("messages.errors.database"));
                    }
                }
                break;
            case "uninvite":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /claim uninvite <ClaimName> <Player>");
                } else {
                    String claimName = args[1];
                    String targetName = args[2];
                    Claim claim = null;
                    try {
                        claim = storage.getClaim(player.getUniqueId().toString(), claimName);
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Error retrieving claim for uninvite", e);
                    }
                    if (claim == null) {
                        player.sendMessage(getMessage("messages.errors.claim_not_found").replace("{name}", claimName));
                        break;
                    }
                    if (!claim.owner.equals(player.getUniqueId().toString())) {
                        player.sendMessage("§cNur der Owner des Claims kann Spieler entfernen.");
                        break;
                    }
                    try {
                        boolean success = storage.uninvitePlayer(player.getUniqueId().toString(), claimName, targetName);
                        if (success) {
                            player.sendMessage("§aSpieler " + targetName + " wurde aus dem Claim " + claimName + " entfernt.");
                        } else {
                            player.sendMessage("§cSpieler " + targetName + " ist nicht für Claim " + claimName + " eingeladen.");
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Error uninviting player", e);
                        player.sendMessage(getMessage("messages.errors.database"));
                    }
                }
                break;
            case "show":
                clearClaimVisuals(player);
                long nowShow = System.currentTimeMillis();
                if (lastShowTime.containsKey(player.getUniqueId())) {
                    long last = lastShowTime.get(player.getUniqueId());
                    if (nowShow - last < 10000) {
                        player.sendMessage("§cBitte warte, bevor du /claim show erneut benutzt.");
                        return true;
                    }
                }
                lastShowTime.put(player.getUniqueId(), nowShow);
                showClaims(player);
                break;
            // ADMINZONEN-BEFEHLE (nur für Spieler mit Permission "instinkt.claim.adminzone")
            case "adminzone1":
                if (!player.hasPermission("instinkt.claim.adminzone")) {
                    player.sendMessage("§cDu hast keine Berechtigung für Adminzonen.");
                    return true;
                }
                adminPos1Map.put(player.getUniqueId(), player.getLocation());
                player.sendMessage(getMessage("messages.adminzone.pos1_set", "§aAdminzone Position 1 gesetzt bei {pos}")
                        .replace("{pos}", formatLocation(player.getLocation())));
                break;
            case "adminzone2":
                if (!player.hasPermission("instinkt.claim.adminzone")) {
                    player.sendMessage("§cDu hast keine Berechtigung für Adminzonen.");
                    return true;
                }
                adminPos2Map.put(player.getUniqueId(), player.getLocation());
                player.sendMessage(getMessage("messages.adminzone.pos2_set", "§aAdminzone Position 2 gesetzt bei {pos}")
                        .replace("{pos}", formatLocation(player.getLocation())));
                if (adminPos1Map.containsKey(player.getUniqueId())) {
                    createAdminZone(player);
                } else {
                    player.sendMessage(getMessage("messages.errors.pos1_missing"));
                }
                break;
            case "adminzone":
                if (!player.hasPermission("instinkt.claim.adminzone")) {
                    player.sendMessage("§cDu hast keine Berechtigung für Adminzonen.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /claim adminzone <rename|delete|list>");
                    return true;
                }
                String adminSub = args[1].toLowerCase();
                switch (adminSub) {
                    case "rename":
                        if (args.length < 4) {
                            player.sendMessage("§cUsage: /claim adminzone rename <alterName> <neuerName>");
                        } else {
                            String oldName = args[2];
                            String newName = args[3];
                            try {
                                boolean success = storage.renameClaim("Admin", oldName, newName);
                                if (success) {
                                    player.sendMessage(getMessage("messages.adminzone.renamed", "§aAdminzone umbenannt von {old} zu {new}")
                                            .replace("{old}", oldName).replace("{new}", newName));
                                    removeClaimVisuals(oldName);
                                    Claim updatedZone = storage.getClaim("Admin", newName);
                                    for (Player online : Bukkit.getOnlinePlayers()) {
                                        if (online.hasPermission("instinkt.claim.adminzone.view")) {
                                            showClaimVisual(online, updatedZone);
                                        }
                                    }
                                } else {
                                    player.sendMessage(getMessage("messages.errors.adminzone_not_found", "§cAdminzone '{name}' nicht gefunden.")
                                            .replace("{name}", oldName));
                                }
                            } catch (Exception e) {
                                getLogger().log(Level.SEVERE, "Error renaming admin zone", e);
                                player.sendMessage(getMessage("messages.errors.database"));
                            }
                        }
                        break;
                    case "delete":
                        if (args.length < 3) {
                            player.sendMessage("§cUsage: /claim adminzone delete <Name>");
                        } else {
                            String zoneName = args[2];
                            try {
                                boolean success = storage.deleteClaim("Admin", zoneName);
                                if (success) {
                                    player.sendMessage(getMessage("messages.adminzone.deleted", "§aAdminzone {name} gelöscht.")
                                            .replace("{name}", zoneName));
                                    removeClaimVisuals(zoneName);
                                } else {
                                    player.sendMessage(getMessage("messages.errors.adminzone_not_found", "§cAdminzone '{name}' nicht gefunden.")
                                            .replace("{name}", zoneName));
                                }
                            } catch (Exception e) {
                                getLogger().log(Level.SEVERE, "Error deleting admin zone", e);
                                player.sendMessage(getMessage("messages.errors.database"));
                            }
                        }
                        break;
                    case "list":
                        try {
                            List<Claim> zones = storage.getClaims("Admin");
                            if (zones.isEmpty()) {
                                player.sendMessage("§cKeine Adminzonen vorhanden.");
                            } else {
                                player.sendMessage("§aAdminzonen:");
                                for (Claim zone : zones) {
                                    String displayName;
                                    if (player.hasPermission("instinkt.claim.adminzone.view")) {
                                        displayName = zone.name;
                                    } else {
                                        displayName = "Adminzone";
                                    }
                                    player.sendMessage("§e" + displayName + " §7(World: " + zone.world +
                                            ", Pos1: " + formatLocation(new Location(Bukkit.getWorld(zone.world), zone.x1, zone.y1, zone.z1)) +
                                            ", Pos2: " + formatLocation(new Location(Bukkit.getWorld(zone.world), zone.x2, zone.y2, zone.z2)) + ")");
                                }
                            }
                        } catch (Exception e) {
                            getLogger().log(Level.SEVERE, "Error listing admin zones", e);
                            player.sendMessage(getMessage("messages.errors.database"));
                        }
                        break;
                    default:
                        player.sendMessage("§cUnbekannter Adminzone-Befehl.");
                        break;
                }
                break;
            default:
                sendUsage(player);
                break;
        }
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(getMessage("messages.claim.usage"));
    }

    // Entfernt alle aktiven Visualisierungen des Spielers (Hologramme und zugehörige Tasks)
    private void clearClaimVisuals(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeVisuals.containsKey(uuid)) {
            Map<ArmorStand, BukkitRunnable> visuals = activeVisuals.get(uuid);
            for (Map.Entry<ArmorStand, BukkitRunnable> entry : visuals.entrySet()) {
                entry.getValue().cancel();
                entry.getKey().remove();
            }
            activeVisuals.remove(uuid);
        }
    }

    // Entfernt Visualisierungen eines bestimmten Claims aus allen aktiven Anzeigen
    private void removeClaimVisuals(String claimName) {
        for (UUID uuid : activeVisuals.keySet()) {
            Map<ArmorStand, BukkitRunnable> visuals = activeVisuals.get(uuid);
            Iterator<Map.Entry<ArmorStand, BukkitRunnable>> iterator = visuals.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ArmorStand, BukkitRunnable> entry = iterator.next();
                String customName = entry.getKey().getCustomName();
                if (customName != null && customName.startsWith(claimName)) {
                    entry.getValue().cancel();
                    entry.getKey().remove();
                    iterator.remove();
                }
            }
        }
    }

    // Zeigt dem Spieler alle Claims, auf die er berechtigt ist, visuell an
    private void showClaims(Player player) {
        List<Claim> myClaims = new ArrayList<>();
        try {
            List<Claim> allClaims = storage.getAllClaims();
            for (Claim claim : allClaims) {
                if (claim.owner.equals("Admin")) {
                    // Adminzonen anzeigen, wenn entweder adminzones.visible=true oder der Spieler die View-Permission hat
                    if (getConfig().getBoolean("adminzones.visible", true) || player.hasPermission("instinkt.claim.adminzone.view")) {
                        myClaims.add(claim);
                    }
                } else if (claim.owner.equals(player.getUniqueId().toString()) ||
                        claim.invited.contains(player.getName()) ||
                        player.hasPermission("instinkt.claim.viewall")) {
                    myClaims.add(claim);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error retrieving claims for visualization", e);
            player.sendMessage(getMessage("messages.errors.database"));
            return;
        }
        if (myClaims.isEmpty()) {
            player.sendMessage("§cDu hast keine Claims, die angezeigt werden können.");
            return;
        }
        for (Claim claim : myClaims) {
            showClaimVisual(player, claim);
        }
    }

    // Visualisiert einen Claim: Erzeugt ein ArmorStand-Hologramm und startet einen Partikel-Task.
    // Bei Adminzonen: Spieler ohne die View-Permission sehen nur "Adminzone" plus Tag.
    private void showClaimVisual(Player player, Claim claim) {
        World world = Bukkit.getWorld(claim.world);
        if (world == null) return;
        double minX = Math.min(claim.x1, claim.x2);
        double maxX = Math.max(claim.x1, claim.x2);
        double minZ = Math.min(claim.z1, claim.z2);
        double maxZ = Math.max(claim.z1, claim.z2);
        double centerX = (claim.x1 + claim.x2) / 2.0;
        double centerZ = (claim.z1 + claim.z2) / 2.0;
        double displayY = player.getLocation().getY() + 2.0;
        Location center = new Location(world, centerX, displayY, centerZ);
        
        String ownerTag = getConfig().getString("display.owner_tag", " §c(Owner)");
        String allowedTag = getConfig().getString("display.allowed_tag", " §9(Allowed)");
        String displayName;
        if (claim.owner.equals("Admin")) {
            String adminTag = " §6(Adminzone)";
            if (player.hasPermission("instinkt.claim.adminzone.view")) {
                displayName = claim.name + adminTag;
            } else {
                displayName = "Adminzone" + adminTag;
            }
        } else {
            boolean isOwner = claim.owner.equals(player.getUniqueId().toString());
            String labelSuffix = isOwner ? ownerTag : allowedTag;
            displayName = claim.name + labelSuffix;
        }
        
        ArmorStand hologram = world.spawn(center, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setCustomNameVisible(true);
            stand.setCustomName(displayName);
            stand.setMarker(true);
            stand.setMetadata("claim_hologram", new FixedMetadataValue(KrikXClaim.this, true));
        });
        
        int duration = 200; // 200 Ticks = 10 Sekunden
        BukkitRunnable particleTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks >= duration) {
                    hologram.remove();
                    cancel();
                    return;
                }
                int particlesPerEdge = 20;
                Material particleMaterial;
                if (claim.owner.equals("Admin")) {
                    particleMaterial = Material.GOLD_BLOCK;
                } else {
                    particleMaterial = claim.owner.equals(player.getUniqueId().toString()) ? Material.RED_CONCRETE : Material.BLUE_CONCRETE;
                }
                for (int i = 0; i < particlesPerEdge; i++) {
                    double factor = (double) i / (particlesPerEdge - 1);
                    Location loc1 = new Location(world, minX + factor * (maxX - minX), displayY, minZ);
                    Location loc2 = new Location(world, maxX, displayY, minZ + factor * (maxZ - minZ));
                    Location loc3 = new Location(world, maxX - factor * (maxX - minX), displayY, maxZ);
                    Location loc4 = new Location(world, minX, displayY, maxZ - factor * (maxZ - minZ));
                    player.spawnParticle(Particle.FALLING_DUST, loc1, 1, particleMaterial.createBlockData());
                    player.spawnParticle(Particle.FALLING_DUST, loc2, 1, particleMaterial.createBlockData());
                    player.spawnParticle(Particle.FALLING_DUST, loc3, 1, particleMaterial.createBlockData());
                    player.spawnParticle(Particle.FALLING_DUST, loc4, 1, particleMaterial.createBlockData());
                }
                ticks++;
            }
        };
        particleTask.runTaskTimer(this, 0L, 5L);
        
        activeVisuals.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(hologram, particleTask);
        
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] showClaimVisual: Showing claim '" + claim.name + "' to " + player.getName());
        }
    }

    // Erstellt einen Claim aus zwei Positionen (inkl. Überschneidungsprüfung)
    private void createClaim(Player player) {
        UUID uuid = player.getUniqueId();
        Location pos1 = pos1Map.get(uuid);
        Location pos2 = pos2Map.get(uuid);
        
        List<String> allowedWorlds = getConfig().getStringList("allowed-worlds");
        if (!allowedWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(getMessage("messages.errors.world_not_allowed"));
            pos1Map.remove(uuid);
            pos2Map.remove(uuid);
            return;
        }
        
        double fixedY1 = getConfig().getDouble("claims.fixed_y_min", 0);
        double fixedY2 = getConfig().getDouble("claims.fixed_y_max", 1000);
        pos1 = pos1.clone();
        pos2 = pos2.clone();
        pos1.setY(fixedY1);
        pos2.setY(fixedY2);
        
        Location pos1XZ = new Location(pos1.getWorld(), pos1.getX(), 0, pos1.getZ());
        Location pos2XZ = new Location(pos2.getWorld(), pos2.getX(), 0, pos2.getZ());
        double diag = pos1XZ.distance(pos2XZ);
        double maxDiag = getConfig().getDouble("claims.max_diagonal", 1000.0);
        if (diag > maxDiag) {
            player.sendMessage(getMessage("messages.errors.claim_too_large")
                    .replace("{diag}", String.format("%.2f", diag))
                    .replace("{max}", String.valueOf(maxDiag)));
            pos1Map.remove(uuid);
            pos2Map.remove(uuid);
            return;
        }
        
        Claim newClaim;
        try {
            newClaim = new Claim(uuid.toString(), "Claim#" + (storage.countClaims(uuid.toString()) + 1),
                    player.getWorld().getName(),
                    pos1.getX(), pos1.getY(), pos1.getZ(),
                    pos2.getX(), pos2.getY(), pos2.getZ());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        
        try {
            List<Claim> allClaims = storage.getAllClaims();
            for (Claim claim : allClaims) {
                if (claimsOverlap(newClaim, claim)) {
                    player.sendMessage(getMessage("messages.errors.overlap"));
                    pos1Map.remove(uuid);
                    pos2Map.remove(uuid);
                    if (getConfig().getBoolean("debug", false)) {
                        getLogger().info("[DEBUG] createClaim: Overlap detected between new claim and claim '" 
                                + claim.name + "' (owner: " + claim.owner + ")");
                    }
                    return;
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error checking claim overlap", e);
            player.sendMessage(getMessage("messages.errors.database"));
            return;
        }
        
        int allowedClaims = determineMaxClaims(player);
        int currentClaims;
        try {
            currentClaims = storage.countClaims(uuid.toString());
        } catch (Exception e) {
            e.printStackTrace();
            currentClaims = 0;
        }
        if (currentClaims >= allowedClaims) {
            player.sendMessage(getMessage("messages.errors.claim_limit_reached"));
            pos1Map.remove(uuid);
            pos2Map.remove(uuid);
            return;
        }
        
        String claimName = "Claim#" + (currentClaims + 1);
        newClaim.name = claimName;
        try {
            storage.addClaim(uuid.toString(), newClaim);
            player.sendMessage(getMessage("messages.claim.created").replace("{name}", claimName));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error adding claim", e);
            player.sendMessage(getMessage("messages.errors.database"));
        }
        
        pos1Map.remove(uuid);
        pos2Map.remove(uuid);
    }
    
    // Erstellt eine Adminzone (ohne Overlap-/Claimlimit-Prüfung)
    private void createAdminZone(Player player) {
        UUID uuid = player.getUniqueId();
        Location pos1 = adminPos1Map.get(uuid);
        Location pos2 = adminPos2Map.get(uuid);
        
        pos1 = pos1.clone();
        pos2 = pos2.clone();
        double fixedY1 = getConfig().getDouble("claims.fixed_y_min", -66);
        double fixedY2 = getConfig().getDouble("claims.fixed_y_max", 255);
        pos1.setY(fixedY1);
        pos2.setY(fixedY2);
        
        Claim newZone;
        try {
            newZone = new Claim("Admin", "Adminzone#" + (storage.countClaims("Admin") + 1),
                    pos1.getWorld().getName(),
                    pos1.getX(), pos1.getY(), pos1.getZ(),
                    pos2.getX(), pos2.getY(), pos2.getZ());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        
        try {
            // Bei Adminzonen wird keine Overlap-Prüfung durchgeführt.
            storage.addClaim("Admin", newZone);
            player.sendMessage(getMessage("messages.adminzone.created", "§aAdminzone erstellt: {name}")
                    .replace("{name}", newZone.name));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error adding admin zone", e);
            player.sendMessage(getMessage("messages.errors.database"));
        }
        
        adminPos1Map.remove(uuid);
        adminPos2Map.remove(uuid);
    }
    
    // Überprüft, ob zwei Claims (in XZ) sich überschneiden
    private boolean claimsOverlap(Claim c1, Claim c2) {
        if (!c1.world.equals(c2.world)) return false;
        double c1_minX = Math.min(c1.x1, c1.x2), c1_maxX = Math.max(c1.x1, c1.x2);
        double c1_minZ = Math.min(c1.z1, c1.z2), c1_maxZ = Math.max(c1.z1, c1.z2);
        
        double c2_minX = Math.min(c2.x1, c2.x2), c2_maxX = Math.max(c2.x1, c2.x2);
        double c2_minZ = Math.min(c2.z1, c2.z2), c2_maxZ = Math.max(c2.z1, c2.z2);
        
        boolean overlapX = c1_maxX >= c2_minX && c2_maxX >= c1_minX;
        boolean overlapZ = c1_maxZ >= c2_minZ && c2_maxZ >= c1_minZ;
        return overlapX && overlapZ;
    }
    
    private void listClaims(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            List<Claim> claims = storage.getClaims(uuid.toString());
            if (claims.isEmpty()) {
                player.sendMessage(getMessage("messages.claim.no_claims"));
                return;
            }
            player.sendMessage(getMessage("messages.claim.list_header"));
            for (Claim c : claims) {
                String invited = String.join(", ", c.invited);
                player.sendMessage(getMessage("messages.claim.list_entry")
                        .replace("{name}", c.name)
                        .replace("{world}", c.world)
                        .replace("{pos1}", formatLocation(new Location(Bukkit.getWorld(c.world), c.x1, c.y1, c.z1)))
                        .replace("{pos2}", formatLocation(new Location(Bukkit.getWorld(c.world), c.x2, c.y2, c.z2)))
                        .replace("{invited}", invited.isEmpty() ? "none" : invited));
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error listing claims", e);
            player.sendMessage(getMessage("messages.errors.database"));
        }
    }
    
    private void renameClaim(Player player, String oldName, String newName) {
        UUID uuid = player.getUniqueId();
        try {
            boolean success = storage.renameClaim(uuid.toString(), oldName, newName);
            if (success) {
                player.sendMessage(getMessage("messages.claim.renamed")
                        .replace("{old}", oldName)
                        .replace("{new}", newName));
                removeClaimVisuals(oldName);
                Claim updatedClaim = storage.getClaim(uuid.toString(), newName);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (updatedClaim.owner.equals(uuid.toString()) ||
                        updatedClaim.invited.contains(online.getName()) ||
                        online.hasPermission("instinkt.claim.viewall")) {
                        showClaimVisual(online, updatedClaim);
                    }
                }
            } else {
                player.sendMessage(getMessage("messages.errors.claim_not_found").replace("{name}", oldName));
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error renaming claim", e);
            player.sendMessage(getMessage("messages.errors.database"));
        }
    }
    
    private void deleteClaim(Player player, String name) {
        UUID uuid = player.getUniqueId();
        try {
            boolean success = storage.deleteClaim(uuid.toString(), name);
            if (success) {
                player.sendMessage(getMessage("messages.claim.deleted").replace("{name}", name));
            } else {
                player.sendMessage(getMessage("messages.errors.claim_not_found").replace("{name}", name));
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error deleting claim", e);
            player.sendMessage(getMessage("messages.errors.database"));
        }
    }
    
    // Ermittelt die maximale Anzahl an Claims anhand dynamischer Permissions
    private int determineMaxClaims(Player player) {
        int fallback = getConfig().getInt("claims.default_max", 3);
        boolean checkDynamic = getConfig().getBoolean("claims.check_dynamic_groups", true);
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] determineMaxClaims: Fallback value = " + fallback);
            getLogger().info("[DEBUG] determineMaxClaims: Dynamic group check is " + (checkDynamic ? "enabled" : "disabled"));
        }
        if (checkDynamic) {
            int dynamicGroups = getConfig().getInt("claims.dynamic_groups", fallback);
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] determineMaxClaims: Checking dynamic permissions from " + dynamicGroups + " down to 1 for " + player.getName());
            }
            for (int i = dynamicGroups; i >= 1; i--) {
                String perm = "instinkt.claims." + i;
                if (player.hasPermission(perm)) {
                    if (getConfig().getBoolean("debug", false)) {
                        getLogger().info("[DEBUG] determineMaxClaims: " + player.getName() + " HAS permission " + perm + " (max claims = " + i + ")");
                    }
                    return i;
                } else {
                    if (getConfig().getBoolean("debug", false)) {
                        getLogger().info("[DEBUG] determineMaxClaims: " + player.getName() + " does NOT have permission " + perm);
                    }
                }
            }
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] determineMaxClaims: No dynamic permission found for " + player.getName() + ". Using fallback " + fallback);
            }
            return fallback;
        } else {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] determineMaxClaims: Dynamic group check disabled. Using fallback " + fallback);
            }
            return fallback;
        }
    }
    
    // Formatiert eine Location in einen lesbaren String
    private String formatLocation(Location loc) {
        return "X:" + String.format("%.2f", loc.getX()) + " Y:" + String.format("%.2f", loc.getY()) + " Z:" + String.format("%.2f", loc.getZ());
    }
    
    /**
     * Helper-Methode für Nachrichten.
     * Falls der Schlüssel nicht existiert, wird der übergebene defaultValue verwendet.
     */
    private String getMessage(String path) {
        return getConfig().getString(path, "§cMessage not defined: " + path);
    }
    private String getMessage(String path, String defaultValue) {
        return getConfig().getString(path, defaultValue);
    }
    
    // Schutz-Event-Handler
    public class ProtectionListener implements Listener {
        
        @EventHandler
        public void onBlockPlace(BlockPlaceEvent event) {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] BlockPlaceEvent triggered by " + player.getName() + " at " + block.getLocation());
            }
            Claim claim = getClaimAt(block.getLocation());
            if (claim != null) {
                if (claim.owner.equals("Admin")) {
                    // Verwende Admin-spezifische Konfiguration für Block Place
                    if (getConfig().getBoolean("admin.protection.block_place", true)) {
                        if (!player.hasPermission("instinkt.claim.adminzone.bypass")) {
                            event.setCancelled(true);
                            if (getConfig().getBoolean("admin.protection.notify", true)) {
                                player.sendMessage(getMessage("messages.adminzone.protection_no_build", "§cIn Adminzonen darfst du nicht bauen."));
                            }
                            return;
                        }
                    }
                } else if (!claim.owner.equals(player.getUniqueId().toString()) && !claim.invited.contains(player.getName())) {
                    String ownerName = Bukkit.getOfflinePlayer(UUID.fromString(claim.owner)).getName();
                    event.setCancelled(true);
                    if (getConfig().getBoolean("protection.notify", true)) {
                        player.sendMessage(getMessage("messages.protection.no_build") + " - Owner: " + ownerName);
                    }
                    return;
                }
            } else {
                if (!getConfig().getBoolean("protection.allow_unclaimed", true)) {
                    event.setCancelled(true);
                    if (getConfig().getBoolean("protection.notify", true)) {
                        player.sendMessage(getMessage("messages.protection.no_build_unclaimed"));
                    }
                    return;
                }
            }
        }
        
        @EventHandler
        public void onBlockBreak(BlockBreakEvent event) {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] BlockBreakEvent triggered by " + player.getName() + " at " + block.getLocation());
            }
            Claim claim = getClaimAt(block.getLocation());
            if (claim != null) {
                if (claim.owner.equals("Admin")) {
                    // Verwende Admin-spezifische Konfiguration für Block Break
                    if (getConfig().getBoolean("admin.protection.block_break", true)) {
                        if (!player.hasPermission("instinkt.claim.adminzone.bypass")) {
                            event.setCancelled(true);
                            if (getConfig().getBoolean("admin.protection.notify", true)) {
                                player.sendMessage(getMessage("messages.adminzone.protection_no_break", "§cIn Adminzonen darfst du keine Blöcke abbauen."));
                            }
                            return;
                        }
                    }
                } else if (!claim.owner.equals(player.getUniqueId().toString()) && !claim.invited.contains(player.getName())) {
                    String ownerName = Bukkit.getOfflinePlayer(UUID.fromString(claim.owner)).getName();
                    event.setCancelled(true);
                    if (getConfig().getBoolean("protection.notify", true)) {
                        player.sendMessage(getMessage("messages.protection.no_break") + " - Owner: " + ownerName);
                    }
                    return;
                }
            } else {
                if (!getConfig().getBoolean("protection.allow_unclaimed", true)) {
                    event.setCancelled(true);
                    if (getConfig().getBoolean("protection.notify", true)) {
                        player.sendMessage(getMessage("messages.protection.no_break_unclaimed"));
                    }
                    return;
                }
            }
        }
        
        @EventHandler
        public void onPlayerInteract(PlayerInteractEvent event) {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            Player player = event.getPlayer();
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] PlayerInteractEvent triggered by " + player.getName());
            }
            if (player.isOp() || player.hasPermission("instinkt.claim.allowall")) {
                return;
            }
            if (event.getClickedBlock() == null) return;
            Material type = event.getClickedBlock().getType();
            switch (type) {
                case CHEST:
                case TRAPPED_CHEST:
                case BARREL:
                case FURNACE:
                case BLAST_FURNACE:
                case SMOKER:
                case SHULKER_BOX:
                case BLACK_SHULKER_BOX:
                    Claim claim = getClaimAt(event.getClickedBlock().getLocation());
                    // Prüfe, ob ChestShop installiert ist
                    if (Bukkit.getPluginManager().getPlugin("ChestShop") != null && isChestShop(event.getClickedBlock())) {
                        if (claim != null && claim.owner.equals("Admin")) {
                            if (getConfig().getBoolean("chestshop.restrict_adminzones", false)
                                    && !player.hasPermission("instinkt.claim.adminzone.bypass")) {
                                event.setCancelled(true);
                                if (getConfig().getBoolean("admin.protection.notify", true)) {
                                    player.sendMessage("§cChestShop-Interaktionen sind in Adminzonen deaktiviert.");
                                }
                                return;
                            }
                        } else {
                            if (getConfig().getBoolean("chestshop.restrict_claims", false)) {
                                event.setCancelled(true);
                                if (getConfig().getBoolean("protection.notify", true)) {
                                    player.sendMessage("§cChestShop-Interaktionen sind in Claims deaktiviert.");
                                }
                                return;
                            }
                        }
                    }
                    // Standard-Container-Schutz
                    if (claim != null) {
                        if (claim.owner.equals("Admin")) {
                            if (getConfig().getBoolean("admin.protection.container_interact", true)) {
                                if (!player.hasPermission("instinkt.claim.adminzone.bypass")) {
                                    event.setCancelled(true);
                                    if (getConfig().getBoolean("admin.protection.notify", true)) {
                                        player.sendMessage(getMessage("messages.adminzone.protection_no_container", "§cIn Adminzonen darfst du nicht mit Containern interagieren."));
                                    }
                                    return;
                                }
                            }
                        } else if (!claim.owner.equals(player.getUniqueId().toString()) && !claim.invited.contains(player.getName())) {
                            String ownerName = Bukkit.getOfflinePlayer(UUID.fromString(claim.owner)).getName();
                            event.setCancelled(true);
                            if (getConfig().getBoolean("protection.notify", true)) {
                                player.sendMessage(getMessage("messages.protection.no_container") + " - Owner: " + ownerName);
                            }
                        }
                    } else {
                        if (!getConfig().getBoolean("protection.allow_unclaimed", true)) {
                            event.setCancelled(true);
                            if (getConfig().getBoolean("protection.notify", true)) {
                                player.sendMessage(getMessage("messages.protection.no_container_unclaimed"));
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }
    
    // Hilfsmethode, um zu prüfen, ob der angeklickte Block ein ChestShop ist
    @SuppressWarnings("deprecation")
	private boolean isChestShop(Block container) {
        // Suche in angrenzenden Blöcken nach einem Schild, das "[ChestShop]" enthält
        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP }) {
            Block relative = container.getRelative(face);
            if (relative.getState() instanceof Sign) {
                Sign sign = (Sign) relative.getState();
                if (sign.getLine(0).equalsIgnoreCase("[ChestShop]")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    // Liefert den Claim, in dessen Bereich sich eine Location befindet
    private Claim getClaimAt(Location loc) {
        try {
            List<Claim> allClaims = storage.getAllClaims();
            for (Claim claim : allClaims) {
                if (!claim.world.equals(loc.getWorld().getName())) continue;
                double minX = Math.min(claim.x1, claim.x2);
                double maxX = Math.max(claim.x1, claim.x2);
                double minY = Math.min(claim.y1, claim.y2);
                double maxY = Math.max(claim.y1, claim.y2);
                double minZ = Math.min(claim.z1, claim.z2);
                double maxZ = Math.max(claim.z1, claim.z2);
                if (loc.getX() >= minX && loc.getX() <= maxX &&
                    loc.getY() >= minY && loc.getY() <= maxY &&
                    loc.getZ() >= minZ && loc.getZ() <= maxZ) {
                    return claim;
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error retrieving claims for location check", e);
        }
        return null;
    }
    
    // Tab-Completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(Arrays.asList("reload", "1", "2", "list", "rename", "delete", "del", "invite", "uninvite", "show"));
            if (player.hasPermission("instinkt.claim.adminzone")) {
                completions.add("adminzone1");
                completions.add("adminzone2");
                completions.add("adminzone");
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("rename") || args[0].equalsIgnoreCase("delete") ||
                args[0].equalsIgnoreCase("del") || args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("uninvite")) {
                List<Claim> claims = null;
				try {
					claims = storage.getClaims(player.getUniqueId().toString());
				} catch (Exception e) {
					getLogger().log(Level.SEVERE, "Error in getting claims List", e);
				}
                for (Claim c : claims) {
                    completions.add(c.name);
                }
            } else if (args[0].equalsIgnoreCase("adminzone")) {
                completions.addAll(Arrays.asList("rename", "delete", "list"));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("adminzone") &&
                   (args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("delete"))) {
            try {
                List<Claim> adminZones = storage.getClaims("Admin");
                for (Claim cz : adminZones) {
                    completions.add(cz.name);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error in adminzone tab completion", e);
            }
        }
        String last = args[args.length - 1].toLowerCase();
        List<String> filtered = new ArrayList<>();
        for (String s : completions) {
            if (s.toLowerCase().startsWith(last)) {
                filtered.add(s);
            }
        }
        return filtered;
    }
    
    /*
     * -----------------------------------------------------
     * Storage-Layer: Interface und Implementierungen (MySQL & YAML)
     * -----------------------------------------------------
     */
    public interface ClaimStorage {
        void connect() throws Exception;
        void disconnect();
        List<Claim> getClaims(String uuid) throws Exception;
        Claim getClaim(String uuid, String name) throws Exception;
        void addClaim(String uuid, Claim claim) throws Exception;
        boolean deleteClaim(String uuid, String name) throws Exception;
        boolean renameClaim(String uuid, String oldName, String newName) throws Exception;
        int countClaims(String uuid) throws Exception;
        List<Claim> getAllClaims() throws Exception;
        boolean invitePlayer(String uuid, String claimName, String invitedPlayer) throws Exception;
        boolean uninvitePlayer(String uuid, String claimName, String uninvitedPlayer) throws Exception;
    }
    
    public static class MySQLClaimStorage implements ClaimStorage {
        private Connection connection;
        private final JavaPlugin plugin;
        
        public MySQLClaimStorage(JavaPlugin plugin) {
            this.plugin = plugin;
        }
        
        @Override
        public void connect() throws Exception {
            String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
            String port = String.valueOf(plugin.getConfig().getInt("storage.mysql.port", 3306));
            String database = plugin.getConfig().getString("storage.mysql.database", "minecraft");
            String user = plugin.getConfig().getString("storage.mysql.user", "root");
            String password = plugin.getConfig().getString("storage.mysql.password", "");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database;
            connection = DriverManager.getConnection(url, user, password);
            plugin.getLogger().info("Successfully connected to the MySQL database for claims.");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS claims (" +
                        "uuid VARCHAR(36)," +
                        "name VARCHAR(50)," +
                        "world VARCHAR(50)," +
                        "x1 DOUBLE, y1 DOUBLE, z1 DOUBLE," +
                        "x2 DOUBLE, y2 DOUBLE, z2 DOUBLE," +
                        "invited TEXT," +
                        "PRIMARY KEY (uuid, name)" +
                        ")");
            }
        }
        
        @Override
        public void disconnect() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Error closing MySQL connection for claims", e);
                }
            }
        }
        
        @Override
        public List<Claim> getClaims(String uuid) throws Exception {
            List<Claim> claims = new ArrayList<>();
            PreparedStatement stmt = connection.prepareStatement("SELECT * FROM claims WHERE uuid = ?");
            stmt.setString(1, uuid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Claim claim = new Claim(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("z1"),
                        rs.getDouble("x2"), rs.getDouble("y2"), rs.getDouble("z2")
                );
                String invitedStr = rs.getString("invited");
                if (invitedStr != null && !invitedStr.isEmpty()) {
                    claim.invited.addAll(Arrays.asList(invitedStr.split(",")));
                }
                claims.add(claim);
            }
            rs.close();
            stmt.close();
            return claims;
        }
        
        @Override
        public Claim getClaim(String uuid, String name) throws Exception {
            PreparedStatement stmt = connection.prepareStatement("SELECT * FROM claims WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid);
            stmt.setString(2, name);
            ResultSet rs = stmt.executeQuery();
            Claim claim = null;
            if (rs.next()) {
                claim = new Claim(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("z1"),
                        rs.getDouble("x2"), rs.getDouble("y2"), rs.getDouble("z2")
                );
                String invitedStr = rs.getString("invited");
                if (invitedStr != null && !invitedStr.isEmpty()) {
                    claim.invited.addAll(Arrays.asList(invitedStr.split(",")));
                }
            }
            rs.close();
            stmt.close();
            return claim;
        }
        
        @Override
        public void addClaim(String uuid, Claim claim) throws Exception {
            PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO claims (uuid, name, world, x1, y1, z1, x2, y2, z2, invited) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );
            stmt.setString(1, uuid);
            stmt.setString(2, claim.name);
            stmt.setString(3, claim.world);
            stmt.setDouble(4, claim.x1);
            stmt.setDouble(5, claim.y1);
            stmt.setDouble(6, claim.z1);
            stmt.setDouble(7, claim.x2);
            stmt.setDouble(8, claim.y2);
            stmt.setDouble(9, claim.z2);
            stmt.setString(10, "");
            stmt.executeUpdate();
            stmt.close();
        }
        
        @Override
        public boolean deleteClaim(String uuid, String name) throws Exception {
            PreparedStatement stmt = connection.prepareStatement("DELETE FROM claims WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid);
            stmt.setString(2, name);
            int rows = stmt.executeUpdate();
            stmt.close();
            return rows > 0;
        }
        
        @Override
        public boolean renameClaim(String uuid, String oldName, String newName) throws Exception {
            PreparedStatement stmt = connection.prepareStatement("UPDATE claims SET name = ? WHERE uuid = ? AND name = ?");
            stmt.setString(1, newName);
            stmt.setString(2, uuid);
            stmt.setString(3, oldName);
            int rows = stmt.executeUpdate();
            stmt.close();
            return rows > 0;
        }
        
        @Override
        public int countClaims(String uuid) throws Exception {
            PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) AS count FROM claims WHERE uuid = ?");
            stmt.setString(1, uuid);
            ResultSet rs = stmt.executeQuery();
            int count = 0;
            if (rs.next()) {
                count = rs.getInt("count");
            }
            rs.close();
            stmt.close();
            return count;
        }
        
        @Override
        public List<Claim> getAllClaims() throws Exception {
            List<Claim> claims = new ArrayList<>();
            PreparedStatement stmt = connection.prepareStatement("SELECT * FROM claims");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Claim claim = new Claim(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("z1"),
                        rs.getDouble("x2"), rs.getDouble("y2"), rs.getDouble("z2")
                );
                String invitedStr = rs.getString("invited");
                if (invitedStr != null && !invitedStr.isEmpty()) {
                    claim.invited.addAll(Arrays.asList(invitedStr.split(",")));
                }
                claims.add(claim);
            }
            rs.close();
            stmt.close();
            return claims;
        }
        
        @Override
        public boolean invitePlayer(String uuid, String claimName, String invitedPlayer) throws Exception {
            Claim claim = getClaim(uuid, claimName);
            if (claim == null) return false;
            PreparedStatement stmt = connection.prepareStatement("SELECT invited FROM claims WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid);
            stmt.setString(2, claimName);
            ResultSet rs = stmt.executeQuery();
            String invitedStr = "";
            if (rs.next()) {
                invitedStr = rs.getString("invited");
                if (invitedStr == null) invitedStr = "";
            }
            rs.close();
            stmt.close();
            Set<String> invitedSet = new HashSet<>();
            if (!invitedStr.isEmpty()) {
                invitedSet.addAll(Arrays.asList(invitedStr.split(",")));
            }
            invitedSet.add(invitedPlayer);
            String newInvited = String.join(",", invitedSet);
            PreparedStatement updateStmt = connection.prepareStatement("UPDATE claims SET invited = ? WHERE uuid = ? AND name = ?");
            updateStmt.setString(1, newInvited);
            updateStmt.setString(2, uuid);
            updateStmt.setString(3, claimName);
            int rows = updateStmt.executeUpdate();
            updateStmt.close();
            return rows > 0;
        }
        
        @Override
        public boolean uninvitePlayer(String uuid, String claimName, String uninvitedPlayer) throws Exception {
            Claim claim = getClaim(uuid, claimName);
            if (claim == null) return false;
            PreparedStatement stmt = connection.prepareStatement("SELECT invited FROM claims WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid);
            stmt.setString(2, claimName);
            ResultSet rs = stmt.executeQuery();
            String invitedStr = "";
            if (rs.next()) {
                invitedStr = rs.getString("invited");
                if (invitedStr == null) invitedStr = "";
            }
            rs.close();
            stmt.close();
            Set<String> invitedSet = new HashSet<>();
            if (!invitedStr.isEmpty()) {
                invitedSet.addAll(Arrays.asList(invitedStr.split(",")));
            }
            if (!invitedSet.remove(uninvitedPlayer)) {
                return false;
            }
            String newInvited = String.join(",", invitedSet);
            PreparedStatement updateStmt = connection.prepareStatement("UPDATE claims SET invited = ? WHERE uuid = ? AND name = ?");
            updateStmt.setString(1, newInvited);
            updateStmt.setString(2, uuid);
            updateStmt.setString(3, claimName);
            int rows = updateStmt.executeUpdate();
            updateStmt.close();
            return rows > 0;
        }
    }
    
    public static class YamlClaimStorage implements ClaimStorage {
        private File file;
        private YamlConfiguration config;
        private final JavaPlugin plugin;
        
        public YamlClaimStorage(JavaPlugin plugin) {
            this.plugin = plugin;
        }
        
        @Override
        public void connect() throws Exception {
            file = new File(plugin.getDataFolder(), plugin.getConfig().getString("storage.yaml.claim_file", "claims.yml"));
            if (!file.exists()) {
                file.createNewFile();
            }
            config = YamlConfiguration.loadConfiguration(file);
            plugin.getLogger().info("YAML claim storage loaded from " + file.getAbsolutePath());
        }
        
        @Override
        public void disconnect() {
            saveConfig();
        }
        
        private void saveConfig() {
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Error saving YAML claim storage file", e);
            }
        }
        
        @Override
        public List<Claim> getClaims(String uuid) throws Exception {
            List<Claim> claims = new ArrayList<>();
            if (config.contains("claims." + uuid)) {
                for (String claimName : config.getConfigurationSection("claims." + uuid).getKeys(false)) {
                    String path = "claims." + uuid + "." + claimName;
                    String world = config.getString(path + ".world");
                    double x1 = config.getDouble(path + ".x1");
                    double y1 = config.getDouble(path + ".y1");
                    double z1 = config.getDouble(path + ".z1");
                    double x2 = config.getDouble(path + ".x2");
                    double y2 = config.getDouble(path + ".y2");
                    double z2 = config.getDouble(path + ".z2");
                    Claim claim = new Claim(uuid, claimName, world, x1, y1, z1, x2, y2, z2);
                    claim.invited = config.getStringList(path + ".invited");
                    claims.add(claim);
                }
            }
            return claims;
        }
        
        @Override
        public Claim getClaim(String uuid, String name) throws Exception {
            String path = "claims." + uuid + "." + name;
            if (config.contains(path)) {
                String world = config.getString(path + ".world");
                double x1 = config.getDouble(path + ".x1");
                double y1 = config.getDouble(path + ".y1");
                double z1 = config.getDouble(path + ".z1");
                double x2 = config.getDouble(path + ".x2");
                double y2 = config.getDouble(path + ".y2");
                double z2 = config.getDouble(path + ".z2");
                Claim claim = new Claim(uuid, name, world, x1, y1, z1, x2, y2, z2);
                claim.invited = config.getStringList(path + ".invited");
                return claim;
            }
            return null;
        }
        
        @Override
        public void addClaim(String uuid, Claim claim) throws Exception {
            String path = "claims." + uuid + "." + claim.name;
            config.set(path + ".world", claim.world);
            config.set(path + ".x1", claim.x1);
            config.set(path + ".y1", claim.y1);
            config.set(path + ".z1", claim.z1);
            config.set(path + ".x2", claim.x2);
            config.set(path + ".y2", claim.y2);
            config.set(path + ".z2", claim.z2);
            config.set(path + ".invited", new ArrayList<String>());
            saveConfig();
        }
        
        @Override
        public boolean deleteClaim(String uuid, String name) throws Exception {
            String path = "claims." + uuid + "." + name;
            if (config.contains(path)) {
                config.set(path, null);
                saveConfig();
                return true;
            }
            return false;
        }
        
        @Override
        public boolean renameClaim(String uuid, String oldName, String newName) throws Exception {
            String oldPath = "claims." + uuid + "." + oldName;
            if (!config.contains(oldPath)) return false;
            String world = config.getString(oldPath + ".world");
            double x1 = config.getDouble(oldPath + ".x1");
            double y1 = config.getDouble(oldPath + ".y1");
            double z1 = config.getDouble(oldPath + ".z1");
            double x2 = config.getDouble(oldPath + ".x2");
            double y2 = config.getDouble(oldPath + ".y2");
            double z2 = config.getDouble(oldPath + ".z2");
            List<String> invited = config.getStringList(oldPath + ".invited");
            config.set(oldPath, null);
            String newPath = "claims." + uuid + "." + newName;
            config.set(newPath + ".world", world);
            config.set(newPath + ".x1", x1);
            config.set(newPath + ".y1", y1);
            config.set(newPath + ".z1", z1);
            config.set(newPath + ".x2", x2);
            config.set(newPath + ".y2", y2);
            config.set(newPath + ".z2", z2);
            config.set(newPath + ".invited", invited);
            saveConfig();
            return true;
        }
        
        @Override
        public int countClaims(String uuid) throws Exception {
            if (config.contains("claims." + uuid)) {
                return config.getConfigurationSection("claims." + uuid).getKeys(false).size();
            }
            return 0;
        }
        
        @Override
        public List<Claim> getAllClaims() throws Exception {
            List<Claim> claims = new ArrayList<>();
            if (config.contains("claims")) {
                for (String owner : config.getConfigurationSection("claims").getKeys(false)) {
                    for (String claimName : config.getConfigurationSection("claims." + owner).getKeys(false)) {
                        String path = "claims." + owner + "." + claimName;
                        String world = config.getString(path + ".world");
                        double x1 = config.getDouble(path + ".x1");
                        double y1 = config.getDouble(path + ".y1");
                        double z1 = config.getDouble(path + ".z1");
                        double x2 = config.getDouble(path + ".x2");
                        double y2 = config.getDouble(path + ".y2");
                        double z2 = config.getDouble(path + ".z2");
                        Claim claim = new Claim(owner, claimName, world, x1, y1, z1, x2, y2, z2);
                        claim.invited = config.getStringList(path + ".invited");
                        claims.add(claim);
                    }
                }
            }
            return claims;
        }
        
        @Override
        public boolean invitePlayer(String uuid, String claimName, String invitedPlayer) throws Exception {
            String path = "claims." + uuid + "." + claimName;
            if (!config.contains(path)) return false;
            List<String> invited = config.getStringList(path + ".invited");
            if (!invited.contains(invitedPlayer)) {
                invited.add(invitedPlayer);
                config.set(path + ".invited", invited);
                saveConfig();
            }
            return true;
        }
        
        @Override
        public boolean uninvitePlayer(String uuid, String claimName, String uninvitedPlayer) throws Exception {
            String path = "claims." + uuid + "." + claimName;
            if (!config.contains(path)) return false;
            List<String> invited = config.getStringList(path + ".invited");
            if (!invited.contains(uninvitedPlayer)) {
                return false;
            }
            invited.remove(uninvitedPlayer);
            config.set(path + ".invited", invited);
            saveConfig();
            return true;
        }
    }
    
    // Claim-Datenklasse
    public static class Claim {
        public String owner;
        public String name;
        public String world;
        public double x1, y1, z1;
        public double x2, y2, z2;
        public List<String> invited;
        
        public Claim(String owner, String name, String world,
                     double x1, double y1, double z1,
                     double x2, double y2, double z2) {
            this.owner = owner;
            this.name = name;
            this.world = world;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.invited = new ArrayList<>();
        }
    }
}
