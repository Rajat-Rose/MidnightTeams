package com.midnightsmp.midnightteams;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class MidnightTeams extends JavaPlugin implements Listener, CommandExecutor {

    public enum Role { OWNER, MANAGER, MEMBER }

    // TeamName (lowercase) -> (Player UUID -> Role)
    private final Map<String, Map<UUID, Role>> teamMembers = new HashMap<>();
    
    // Player UUID -> TeamName (original case)
    private final Map<UUID, String> playerTeamMap = new HashMap<>();

    // TeamName (lowercase) -> Set of Ally Team Names (lowercase)
    private final Map<String, Set<String>> teamAllies = new HashMap<>();

    // TeamName (lowercase) -> Set of Enemy Team Names (lowercase)
    private final Map<String, Set<String>> teamEnemies = new HashMap<>();

    // Invited Player UUID -> Inviter's TeamName (lowercase)
    private final Map<UUID, String> pendingInvites = new HashMap<>();

    private String guiTitle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guiTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui-title", "&8» &c&lTeam Management"));

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("team").setExecutor(this);
        getCommand("tc").setExecutor(this);

        getLogger().info("MidnightTeams (No Size Limit + GUI + Roles + Allies) enabled!");
    }

    // --- 1. FRIENDLY FIRE PROTECTION (TEAMS & ALLIES) ---
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player p) attacker = p;

        if (attacker == null || attacker.equals(victim)) return;

        String attackerTeam = playerTeamMap.get(attacker.getUniqueId());
        String victimTeam = playerTeamMap.get(victim.getUniqueId());

        if (attackerTeam == null || victimTeam == null) return;

        String aKey = attackerTeam.toLowerCase();
        String vKey = victimTeam.toLowerCase();

        // Same Team
        if (aKey.equals(vKey)) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "🛡️ You cannot attack team member " + victim.getName() + "!");
            return;
        }

        // Ally Protection
        if (teamAllies.getOrDefault(aKey, Collections.emptySet()).contains(vKey)) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.BLUE + "🛡️ You cannot attack your ally " + victim.getName() + " (" + victimTeam + ")!");
        }
    }

    // --- 2. COMMAND SYSTEM ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (cmd.getName().equalsIgnoreCase("tc")) {
            return handleTeamChat(player, args);
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            openTeamGUI(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team create <Name>");
                else createTeam(player, args[1]);
            }
            case "invite" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team invite <Player>");
                else invitePlayer(player, args[1]);
            }
            case "join" -> joinTeam(player);
            case "leave" -> leaveTeam(player);
            case "kick" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team kick <Player>");
                else kickPlayer(player, args[1]);
            }
            case "promote" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team promote <Player>");
                else setRole(player, args[1], Role.MANAGER);
            }
            case "demote" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team demote <Player>");
                else setRole(player, args[1], Role.MEMBER);
            }
            case "ally" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team ally <TeamName>");
                else toggleRelation(player, args[1], true);
            }
            case "enemy" -> {
                if (args.length < 2) player.sendMessage(ChatColor.RED + "Usage: /team enemy <TeamName>");
                else toggleRelation(player, args[1], false);
            }
            case "info" -> sendInfo(player);
            default -> sendHelp(player);
        }

        return true;
    }

    // --- 3. TEAM OPERATIONS ---
    private void createTeam(Player creator, String teamName) {
        if (playerTeamMap.containsKey(creator.getUniqueId())) {
            creator.sendMessage(ChatColor.RED + "You are already in a team!");
            return;
        }

        String key = teamName.toLowerCase();
        if (teamMembers.containsKey(key)) {
            creator.sendMessage(ChatColor.RED + "A team with that name already exists!");
            return;
        }

        Map<UUID, Role> members = new HashMap<>();
        members.put(creator.getUniqueId(), Role.OWNER);

        teamMembers.put(key, members);
        playerTeamMap.put(creator.getUniqueId(), teamName);

        creator.sendMessage(ChatColor.GREEN + "✅ Team " + ChatColor.GOLD + teamName + ChatColor.GREEN + " created! You are the Owner.");
    }

    private void invitePlayer(Player sender, String targetName) {
        String teamName = playerTeamMap.get(sender.getUniqueId());
        if (teamName == null) {
            sender.sendMessage(ChatColor.RED + "You are not in a team!");
            return;
        }

        String key = teamName.toLowerCase();
        Role role = teamMembers.get(key).get(sender.getUniqueId());

        if (role == Role.MEMBER) {
            sender.sendMessage(ChatColor.RED + "Members cannot invite players! Only Managers and Owners can.");
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }

        pendingInvites.put(target.getUniqueId(), key);
        sender.sendMessage(ChatColor.GREEN + "Invite sent to " + target.getName() + "!");
        target.sendMessage(ChatColor.GOLD + "✉️ Invited to join " + ChatColor.YELLOW + teamName + ChatColor.GOLD + "! Type " + ChatColor.GREEN + "/team join");
    }

    private void joinTeam(Player player) {
        if (playerTeamMap.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already in a team!");
            return;
        }

        String key = pendingInvites.remove(player.getUniqueId());
        if (key == null || !teamMembers.containsKey(key)) {
            player.sendMessage(ChatColor.RED + "You have no pending invites!");
            return;
        }

        teamMembers.get(key).put(player.getUniqueId(), Role.MEMBER);
        playerTeamMap.put(player.getUniqueId(), key);

        broadcastToTeam(key, ChatColor.GREEN + "➕ " + player.getName() + " joined the team!");
    }

    private void leaveTeam(Player player) {
        String teamName = playerTeamMap.get(player.getUniqueId());
        if (teamName == null) return;

        String key = teamName.toLowerCase();
        Role role = teamMembers.get(key).remove(player.getUniqueId());
        playerTeamMap.remove(player.getUniqueId());

        player.sendMessage(ChatColor.YELLOW + "You left " + teamName + ".");
        broadcastToTeam(key, ChatColor.RED + "➖ " + player.getName() + " left the team.");

        if (role == Role.OWNER || teamMembers.get(key).isEmpty()) {
            disbandTeam(key);
        }
    }

    private void kickPlayer(Player sender, String targetName) {
        String teamName = playerTeamMap.get(sender.getUniqueId());
        if (teamName == null) return;

        String key = teamName.toLowerCase();
        Role senderRole = teamMembers.get(key).get(sender.getUniqueId());

        if (senderRole != Role.OWNER) {
            sender.sendMessage(ChatColor.RED + "❌ Only the Team OWNER can kick members!");
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        UUID targetUUID = (target != null) ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        if (targetUUID.equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Use /team leave to leave or disband!");
            return;
        }

        if (teamMembers.get(key).remove(targetUUID) != null) {
            playerTeamMap.remove(targetUUID);
            sender.sendMessage(ChatColor.GREEN + "Kicked " + targetName + " from team.");
            if (target != null) target.sendMessage(ChatColor.RED + "You were kicked from " + teamName + ".");
        } else {
            sender.sendMessage(ChatColor.RED + "Player not found in team.");
        }
    }

    private void setRole(Player sender, String targetName, Role newRole) {
        String teamName = playerTeamMap.get(sender.getUniqueId());
        if (teamName == null) return;

        String key = teamName.toLowerCase();
        if (teamMembers.get(key).get(sender.getUniqueId()) != Role.OWNER) {
            sender.sendMessage(ChatColor.RED + "❌ Only the Team OWNER can change roles!");
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !teamMembers.get(key).containsKey(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Player not found in team!");
            return;
        }

        teamMembers.get(key).put(target.getUniqueId(), newRole);
        sender.sendMessage(ChatColor.GREEN + target.getName() + " is now a " + newRole.name() + "!");
        target.sendMessage(ChatColor.GOLD + "Your role in " + teamName + " was set to " + newRole.name() + "!");
    }

    private void toggleRelation(Player sender, String targetTeamInput, boolean isAlly) {
        String teamName = playerTeamMap.get(sender.getUniqueId());
        if (teamName == null) return;

        String key = teamName.toLowerCase();
        Role role = teamMembers.get(key).get(sender.getUniqueId());

        if (role == Role.MEMBER) {
            sender.sendMessage(ChatColor.RED + "Members cannot modify allies or enemies!");
            return;
        }

        String targetKey = targetTeamInput.toLowerCase();
        if (!teamMembers.containsKey(targetKey)) {
            sender.sendMessage(ChatColor.RED + "Team not found!");
            return;
        }

        if (isAlly) {
            teamAllies.computeIfAbsent(key, k -> new HashSet<>()).add(targetKey);
            teamEnemies.getOrDefault(key, Collections.emptySet()).remove(targetKey);
            sender.sendMessage(ChatColor.BLUE + "🤝 " + targetTeamInput + " is now an ALLY!");
        } else {
            teamEnemies.computeIfAbsent(key, k -> new HashSet<>()).add(targetKey);
            teamAllies.getOrDefault(key, Collections.emptySet()).remove(targetKey);
            sender.sendMessage(ChatColor.DARK_RED + "⚔️ " + targetTeamInput + " is now marked as an ENEMY!");
        }
    }

    private void disbandTeam(String key) {
        for (UUID uuid : teamMembers.get(key).keySet()) {
            playerTeamMap.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(ChatColor.RED + "The team has been disbanded!");
        }
        teamMembers.remove(key);
        teamAllies.remove(key);
        teamEnemies.remove(key);
    }

    // --- 4. GUI MANAGEMENT ---
    private void openTeamGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, guiTitle);
        String teamName = playerTeamMap.get(player.getUniqueId());

        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) gui.setItem(i, glass);

        if (teamName == null) {
            gui.setItem(13, createGuiItem(Material.RED_BANNER, ChatColor.RED + "No Team", ChatColor.GRAY + "Type /team create <name> to start one!"));
        } else {
            String key = teamName.toLowerCase();
            Role role = teamMembers.get(key).get(player.getUniqueId());

            gui.setItem(10, createGuiItem(Material.PLAYER_HEAD, ChatColor.GOLD + "Team Info", 
                    ChatColor.YELLOW + "Name: " + ChatColor.WHITE + teamName,
                    ChatColor.YELLOW + "Your Role: " + ChatColor.GREEN + role.name(),
                    ChatColor.YELLOW + "Total Members: " + ChatColor.GREEN + teamMembers.get(key).size()));

            gui.setItem(13, createGuiItem(Material.CYAN_BANNER, ChatColor.BLUE + "Allies & Enemies",
                    ChatColor.GRAY + "Allies: " + teamAllies.getOrDefault(key, Collections.emptySet()).size(),
                    ChatColor.GRAY + "Enemies: " + teamEnemies.getOrDefault(key, Collections.emptySet()).size()));

            gui.setItem(16, createGuiItem(Material.BARRIER, ChatColor.RED + "Leave Team", ChatColor.GRAY + "Click to leave team"));
        }

        player.openInventory(gui);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(guiTitle)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getSlot() == 16 && playerTeamMap.containsKey(player.getUniqueId())) {
            leaveTeam(player);
            player.closeInventory();
        }
    }

    private boolean handleTeamChat(Player sender, String[] args) {
        String teamName = playerTeamMap.get(sender.getUniqueId());
        if (teamName == null) {
            sender.sendMessage(ChatColor.RED + "You are not in a team!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /tc <message>");
            return true;
        }

        String msg = String.join(" ", args);
        String formatted = ChatColor.GOLD + "[TeamChat] " + ChatColor.YELLOW + sender.getName() + ": " + ChatColor.WHITE + msg;
        broadcastToTeam(teamName.toLowerCase(), formatted);
        return true;
    }

    private void sendInfo(Player player) {
        String teamName = playerTeamMap.get(player.getUniqueId());
        if (teamName == null) {
            player.sendMessage(ChatColor.YELLOW + "You are not in a team.");
            return;
        }

        String key = teamName.toLowerCase();
        player.sendMessage(ChatColor.GOLD + "=== Team: " + teamName + " ===");
        player.sendMessage(ChatColor.YELLOW + "Members (" + teamMembers.get(key).size() + "):");

        for (Map.Entry<UUID, Role> entry : teamMembers.get(key).entrySet()) {
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            player.sendMessage(ChatColor.GRAY + "• " + name + " - " + ChatColor.GREEN + entry.getValue().name());
        }
    }

    private void broadcastToTeam(String key, String message) {
        if (!teamMembers.containsKey(key)) return;
        for (UUID uuid : teamMembers.get(key).keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== MidnightTeams Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/team gui " + ChatColor.GRAY + "- Open Team Menu");
        player.sendMessage(ChatColor.YELLOW + "/team create <Name> " + ChatColor.GRAY + "- Create team");
        player.sendMessage(ChatColor.YELLOW + "/team invite <Player> " + ChatColor.GRAY + "- Invite player");
        player.sendMessage(ChatColor.YELLOW + "/team join " + ChatColor.GRAY + "- Join invited team");
        player.sendMessage(ChatColor.YELLOW + "/team kick <Player> " + ChatColor.GRAY + "- Owner: Kick member");
        player.sendMessage(ChatColor.YELLOW + "/team promote/demote <Player> " + ChatColor.GRAY + "- Owner: Manage roles");
        player.sendMessage(ChatColor.YELLOW + "/team ally/enemy <Team> " + ChatColor.GRAY + "- Manage relations");
        player.sendMessage(ChatColor.YELLOW + "/tc <msg> " + ChatColor.GRAY + "- Send Team Chat");
    }
}
