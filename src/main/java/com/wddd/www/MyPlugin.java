package com.wddd.www;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

public class MyPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> joinTimeMap = new ConcurrentHashMap<>();
    private Map<String, String> itemNames = new HashMap<>();
    private Economy economy = null;
    private boolean vaultEnabled = false;
    private HttpServer webServer;
    private int webPort = 8080;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private File playerDataFolder = null;
    private File cacheFile = null;

    @Override
    public void onEnable() {
        loadConfig();

        org.bukkit.World world = Bukkit.getWorld("world");
        if (world != null) {
            playerDataFolder = new File(world.getWorldFolder(), "playerdata");
        }

        cacheFile = new File("usercache.json");
        loadItemNames();

        if (vaultEnabled) {
            if (setupEconomy()) {
                getLogger().info("[PlayerMonitor] Connected to Vault");
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        startWebServer();

        getLogger().info("[PlayerMonitor] Plugin enabled!");
        getLogger().info("[PlayerMonitor] Web: http://yourIP:" + webPort);
        getLogger().info("[PlayerMonitor] Item names: " + itemNames.size());
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop(0);
        }
        getLogger().info("[PlayerMonitor] Plugin disabled!");
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();
        webPort = config.getInt("web.port", 8080);
        vaultEnabled = config.getBoolean("vault.enabled", false);
    }

    private void loadItemNames() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File jsonFile = new File(dataFolder, "item_names.json");

        if (!jsonFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/item_names.json");
                 OutputStream out = new FileOutputStream(jsonFile)) {
                if (in == null) {
                    getLogger().warning("item_names.json not found in jar, creating empty file");
                    try (Writer writer = new FileWriter(jsonFile)) {
                        writer.write("{}");
                    }
                    itemNames = new HashMap<>();
                    return;
                }
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                getLogger().info("Created default item_names.json");
            } catch (IOException e) {
                getLogger().warning("Failed to create item_names.json: " + e.getMessage());
                itemNames = new HashMap<>();
                return;
            }
        }

        try (Reader reader = new FileReader(jsonFile)) {
            java.lang.reflect.Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                itemNames = loaded;
            } else {
                itemNames = new HashMap<>();
            }
            getLogger().info("Loaded " + itemNames.size() + " item names from item_names.json");
        } catch (IOException e) {
            getLogger().warning("Failed to load item_names.json: " + e.getMessage());
            itemNames = new HashMap<>();
        }
    }

    public String getItemName(String englishId) {
        String name = itemNames.get(englishId);
        return name == null ? "未知物品" : name;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private double getPlayerBalance(Player player) {
        if (economy == null || !vaultEnabled) return 0.0;
        try { return economy.getBalance(player); } catch (Exception e) { return 0.0; }
    }

    private double getOfflinePlayerBalance(UUID uuid) {
        if (economy == null || !vaultEnabled) return 0.0;
        try { return economy.getBalance(Bukkit.getOfflinePlayer(uuid)); } catch (Exception e) { return 0.0; }
    }

    private String getPlayerName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        if (op != null && op.getName() != null) return op.getName();
        if (cacheFile != null && cacheFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
                String search = "\"uuid\":\"" + uuid.toString() + "\"";
                int idx = content.indexOf(search);
                if (idx != -1) {
                    int nameIdx = content.indexOf("\"name\":\"", idx);
                    if (nameIdx != -1) {
                        int start = nameIdx + 8;
                        int end = content.indexOf("\"", start);
                        if (end != -1) {
                            String name = content.substring(start, end);
                            if (!name.isEmpty()) return name;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "Player_" + uuid.toString().substring(0, 8);
    }

    private List<UUID> getAllPlayerUUIDs() {
        List<UUID> result = new ArrayList<>();
        if (playerDataFolder == null || !playerDataFolder.exists()) return result;
        File[] datFiles = playerDataFolder.listFiles((dir, name) -> name.endsWith(".dat"));
        if (datFiles == null) return result;
        for (File file : datFiles) {
            String uuidStr = file.getName().replace(".dat", "");
            try {
                result.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private PlayerData loadPlayerFromDat(UUID uuid) {
        if (playerDataFolder == null || !playerDataFolder.exists()) return null;
        File datFile = new File(playerDataFolder, uuid.toString() + ".dat");
        if (!datFile.exists()) return null;

        PlayerData pd = new PlayerData();
        pd.uuid = uuid;
        pd.name = getPlayerName(uuid);
        pd.online = false;

        // ========== 使用 OfflinePlayer API 读取统计信息 ==========
        try {
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

            // 玩家击杀
            try { pd.playerKills = offlinePlayer.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception e) {}
            // 死亡
            try { pd.deaths = offlinePlayer.getStatistic(Statistic.DEATHS); } catch (Exception e) {}
            // 行走距离 (厘米转米)
            try { pd.walkDistance = offlinePlayer.getStatistic(Statistic.WALK_ONE_CM) / 100.0; } catch (Exception e) {}
            // 疾跑距离
            try { pd.sprintDistance = offlinePlayer.getStatistic(Statistic.SPRINT_ONE_CM) / 100.0; } catch (Exception e) {}
            // 游泳距离
            try { pd.swimDistance = offlinePlayer.getStatistic(Statistic.SWIM_ONE_CM) / 100.0; } catch (Exception e) {}
            // 摔落距离
            try { pd.fallDistance = offlinePlayer.getStatistic(Statistic.FALL_ONE_CM) / 100.0; } catch (Exception e) {}
            // 矿车距离
            try { pd.minecartDistance = offlinePlayer.getStatistic(Statistic.MINECART_ONE_CM) / 100.0; } catch (Exception e) {}
            // 船距离
            try { pd.boatDistance = offlinePlayer.getStatistic(Statistic.BOAT_ONE_CM) / 100.0; } catch (Exception e) {}
            // 骑猪距离
            try { pd.pigDistance = offlinePlayer.getStatistic(Statistic.PIG_ONE_CM) / 100.0; } catch (Exception e) {}
            // 骑马距离
            try { pd.horseDistance = offlinePlayer.getStatistic(Statistic.HORSE_ONE_CM) / 100.0; } catch (Exception e) {}
            // 鞘翅飞行距离 (1.16+)
            try { pd.elytraDistance = offlinePlayer.getStatistic(Statistic.valueOf("ELYTRA_ONE_CM")) / 100.0; } catch (Exception e) {}
            // 造成伤害
            try { pd.damageDealt = offlinePlayer.getStatistic(Statistic.DAMAGE_DEALT); } catch (Exception e) {}
            // 承受伤害
            try { pd.damageTaken = offlinePlayer.getStatistic(Statistic.DAMAGE_TAKEN); } catch (Exception e) {}
            // 生物击杀
            try { pd.mobKills = offlinePlayer.getStatistic(Statistic.MOB_KILLS); } catch (Exception e) {}
            // 胜利突袭
            try { pd.raidWins = offlinePlayer.getStatistic(Statistic.RAID_WIN); } catch (Exception e) {}
            // 村民交易
            try { pd.trades = offlinePlayer.getStatistic(Statistic.TRADED_WITH_VILLAGER); } catch (Exception e) {}
            // 钓鱼
            try { pd.fishCaught = offlinePlayer.getStatistic(Statistic.FISH_CAUGHT); } catch (Exception e) {}
            // 跳跃次数
            try { pd.jumps = offlinePlayer.getStatistic(Statistic.JUMP); } catch (Exception e) {}
            // 潜行时间 (厘米转米)
            try { pd.crouchTime = offlinePlayer.getStatistic(Statistic.CROUCH_ONE_CM) / 100.0; } catch (Exception e) {}

            // ===== 在线时间 (stat.playOneMinute 单位是 tick, 1 tick = 50ms) =====
            try {
                // 方法1: 直接使用 OfflinePlayer.getStatistic
                pd.totalOnlineTime = offlinePlayer.getStatistic(Statistic.valueOf("PLAY_ONE_MINUTE")) * 50L;
            } catch (Exception e) {
                // 方法2: 用 PLAY_ONE_TICK (1 tick = 50ms)
                try {
                    pd.totalOnlineTime = offlinePlayer.getStatistic(Statistic.valueOf("PLAY_ONE_TICK")) * 50L;
                } catch (Exception ex) {
                    // 方法3: 从 NBT 读取
                    try {
                        byte[] rawData = Files.readAllBytes(datFile.toPath());
                        byte[] data;
                        if (rawData.length > 2 && rawData[0] == 0x1F && rawData[1] == (byte)0x8B) {
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(rawData);
                                 GZIPInputStream gzip = new GZIPInputStream(bais);
                                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = gzip.read(buffer)) != -1) {
                                    baos.write(buffer, 0, len);
                                }
                                data = baos.toByteArray();
                            }
                        } else {
                            data = rawData;
                        }

                        NBTReader reader = new NBTReader(data);
                        int tagType = reader.readTagType();
                        if (tagType == 10) {
                            int nameLen = reader.readUnsignedShort();
                            reader.skip(nameLen);
                            CompoundTag root = reader.readCompound();
                            Object statsObj = root.get("bukkit");
                            if (statsObj instanceof CompoundTag) {
                                CompoundTag bukkitTag = (CompoundTag) statsObj;
                                Object statsTagObj = bukkitTag.get("bukkit");
                                if (statsTagObj instanceof CompoundTag) {
                                    CompoundTag statsTag = (CompoundTag) statsTagObj;
                                    Object statObj = statsTag.get("stats");
                                    if (statObj instanceof CompoundTag) {
                                        CompoundTag statCompound = (CompoundTag) statObj;
                                        Object playTime = statCompound.get("stat.playOneMinute");
                                        if (playTime != null) {
                                            long ticks = 0;
                                            if (playTime instanceof Integer) ticks = (Integer) playTime;
                                            else if (playTime instanceof Long) ticks = (Long) playTime;
                                            else if (playTime instanceof Short) ticks = (Short) playTime;
                                            pd.totalOnlineTime = ticks * 50;
                                        }
                                        if (pd.totalOnlineTime == 0) {
                                            Object timeSinceDeath = statCompound.get("stat.timeSinceDeath");
                                            if (timeSinceDeath != null) {
                                                long ticks = 0;
                                                if (timeSinceDeath instanceof Integer) ticks = (Integer) timeSinceDeath;
                                                else if (timeSinceDeath instanceof Long) ticks = (Long) timeSinceDeath;
                                                else if (timeSinceDeath instanceof Short) ticks = (Short) timeSinceDeath;
                                                pd.totalOnlineTime = ticks * 50;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ex2) {}
                }
            }

            // 打印调试日志
            //getLogger().info("Loaded stats for " + pd.name + ": kills=" + pd.playerKills + ", deaths=" + pd.deaths + ", playTime=" + pd.totalOnlineTime + "ms");

        } catch (Exception e) {
            getLogger().warning("Failed to load stats for " + uuid + ": " + e.getMessage());
        }

        // ========== 读取 NBT 数据（背包、护甲、位置等） ==========
        try {
            byte[] rawData = Files.readAllBytes(datFile.toPath());
            byte[] data;
            if (rawData.length > 2 && rawData[0] == 0x1F && rawData[1] == (byte)0x8B) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(rawData);
                     GZIPInputStream gzip = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzip.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    data = baos.toByteArray();
                }
            } else {
                data = rawData;
            }

            NBTReader reader = new NBTReader(data);
            int tagType = reader.readTagType();
            if (tagType != 10) return pd;
            int nameLen = reader.readUnsignedShort();
            reader.skip(nameLen);
            CompoundTag root = reader.readCompound();

            pd.lastUpdate = datFile.lastModified();
            pd.health = root.getDouble("Health", 20.0);
            pd.maxHealth = 20.0;
            pd.food = root.getInt("foodLevel", 20);
            pd.exp = root.getFloat("XpP", 0);
            pd.level = root.getInt("XpLevel", 0);

            Object posObj = root.get("Pos");
            if (posObj instanceof ListTag) {
                ListTag pos = (ListTag) posObj;
                if (pos.size() >= 3) {
                    pd.x = pos.getDouble(0);
                    pd.y = pos.getDouble(1);
                    pd.z = pos.getDouble(2);
                }
            }
            pd.world = "world";

            int gt = root.getInt("playerGameType", 0);
            switch (gt) {
                case 0: pd.gamemode = "生存"; break;
                case 1: pd.gamemode = "创造"; break;
                case 2: pd.gamemode = "冒险"; break;
                case 3: pd.gamemode = "旁观"; break;
                default: pd.gamemode = "生存";
            }

            if (vaultEnabled) pd.balance = getOfflinePlayerBalance(uuid);

            // 背包
            Object invObj = root.get("Inventory");
            pd.inventory = new ItemStack[36];
            if (invObj instanceof ListTag) {
                ListTag inv = (ListTag) invObj;
                for (CompoundTag tag : inv.getCompounds()) {
                    int slot = tag.getInt("Slot", -1);
                    if (slot >= 0 && slot < 36) {
                        ItemStack item = parseItem(tag);
                        if (item != null) pd.inventory[slot] = item;
                    }
                }
            }

            // 护甲
            Object armorObj = root.get("ArmorItems");
            pd.armor = new ItemStack[4];
            if (armorObj instanceof ListTag) {
                ListTag armorList = (ListTag) armorObj;
                for (CompoundTag tag : armorList.getCompounds()) {
                    int slot = tag.getInt("Slot", -1);
                    if (slot >= 0 && slot < 4) {
                        ItemStack item = parseItem(tag);
                        if (item != null) {
                            int displaySlot = 3 - slot;
                            pd.armor[displaySlot] = item;
                        }
                    }
                }
            }

            // 副手
            Object offHandObj = root.get("OffHand");
            if (offHandObj instanceof CompoundTag) {
                CompoundTag tag = (CompoundTag) offHandObj;
                pd.offHand = parseItem(tag);
            }

            // 末影箱
            Object enderObj = root.get("EnderItems");
            pd.enderChest = new ItemStack[27];
            if (enderObj instanceof ListTag) {
                ListTag ender = (ListTag) enderObj;
                for (CompoundTag tag : ender.getCompounds()) {
                    int slot = tag.getInt("Slot", -1);
                    if (slot >= 0 && slot < 27) {
                        ItemStack item = parseItem(tag);
                        if (item != null) pd.enderChest[slot] = item;
                    }
                }
            }

        } catch (Exception e) {
            getLogger().warning("Failed to load NBT data for " + uuid + ": " + e.getMessage());
        }

        return pd;
    }

    private ItemStack parseItem(CompoundTag tag) {
        try {
            String id = tag.getString("id", "");
            if (id.isEmpty()) return null;
            if (id.startsWith("minecraft:")) id = id.substring(10);
            int count = tag.getInt("Count", 1);
            Material mat = Material.getMaterial(id.toUpperCase());
            if (mat == null) return null;
            return new ItemStack(mat, Math.min(count, 64));
        } catch (Exception e) {
            return null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinTimeMap.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        joinTimeMap.remove(e.getPlayer().getUniqueId());
    }

    private void startWebServer() {
        try {
            webServer = HttpServer.create(new InetSocketAddress(webPort), 0);
            webServer.createContext("/api/players", new PlayerDataHandler());
            webServer.createContext("/", new WebPageHandler());
            webServer.setExecutor(null);
            webServer.start();
            getLogger().info("Web server started on port: " + webPort);
        } catch (IOException e) {
            getLogger().severe("Web server failed: " + e.getMessage());
        }
    }

    private class PlayerDataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            List<UUID> allUUIDs = getAllPlayerUUIDs();
            List<Map<String, Object>> playerList = new ArrayList<>();
            Map<UUID, Boolean> processed = new HashMap<>();

            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                PlayerData data = loadPlayerFromDat(uuid);
                if (data == null) {
                    data = new PlayerData();
                    data.uuid = uuid;
                    data.name = player.getName();
                }

                data.online = true;
                data.name = player.getName();
                data.health = player.getHealth();
                data.maxHealth = player.getMaxHealth();
                data.food = player.getFoodLevel();
                data.exp = player.getExp();
                data.level = player.getLevel();
                Location loc = player.getLocation();
                data.world = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
                data.x = loc.getX();
                data.y = loc.getY();
                data.z = loc.getZ();
                data.gamemode = player.getGameMode().name();
                data.lastUpdate = System.currentTimeMillis();
                if (vaultEnabled) data.balance = getPlayerBalance(player);

                PlayerInventory inv = player.getInventory();

                data.inventory = new ItemStack[36];
                for (int i = 0; i < 36; i++) {
                    ItemStack item = inv.getItem(i);
                    data.inventory[i] = (item != null && !item.getType().isAir()) ? item.clone() : null;
                }

                ItemStack[] armorContents = inv.getArmorContents();
                data.armor = new ItemStack[4];
                data.armor[0] = (armorContents[3] != null && !armorContents[3].getType().isAir()) ? armorContents[3].clone() : null;
                data.armor[1] = (armorContents[2] != null && !armorContents[2].getType().isAir()) ? armorContents[2].clone() : null;
                data.armor[2] = (armorContents[1] != null && !armorContents[1].getType().isAir()) ? armorContents[1].clone() : null;
                data.armor[3] = (armorContents[0] != null && !armorContents[0].getType().isAir()) ? armorContents[0].clone() : null;

                ItemStack offHandItem = inv.getItemInOffHand();
                data.offHand = (offHandItem != null && !offHandItem.getType().isAir()) ? offHandItem.clone() : null;

                ItemStack[] ender = player.getEnderChest().getContents();
                data.enderChest = new ItemStack[27];
                for (int i = 0; i < 27; i++) {
                    ItemStack item = ender[i];
                    data.enderChest[i] = (item != null && !item.getType().isAir()) ? item.clone() : null;
                }

                // 在线玩家统计 - 用 API 覆盖（更准确）
                try { data.playerKills = player.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception e) {}
                try { data.deaths = player.getStatistic(Statistic.DEATHS); } catch (Exception e) {}
                try { data.walkDistance = player.getStatistic(Statistic.WALK_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.sprintDistance = player.getStatistic(Statistic.SPRINT_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.swimDistance = player.getStatistic(Statistic.SWIM_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.fallDistance = player.getStatistic(Statistic.FALL_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.minecartDistance = player.getStatistic(Statistic.MINECART_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.boatDistance = player.getStatistic(Statistic.BOAT_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.pigDistance = player.getStatistic(Statistic.PIG_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.horseDistance = player.getStatistic(Statistic.HORSE_ONE_CM) / 100.0; } catch (Exception e) {}
                try { data.elytraDistance = player.getStatistic(Statistic.valueOf("ELYTRA_ONE_CM")) / 100.0; } catch (Exception e) {}
                try { data.damageDealt = player.getStatistic(Statistic.DAMAGE_DEALT); } catch (Exception e) {}
                try { data.damageTaken = player.getStatistic(Statistic.DAMAGE_TAKEN); } catch (Exception e) {}
                try { data.mobKills = player.getStatistic(Statistic.MOB_KILLS); } catch (Exception e) {}
                try { data.raidWins = player.getStatistic(Statistic.RAID_WIN); } catch (Exception e) {}
                try { data.trades = player.getStatistic(Statistic.TRADED_WITH_VILLAGER); } catch (Exception e) {}
                try { data.fishCaught = player.getStatistic(Statistic.FISH_CAUGHT); } catch (Exception e) {}
                try { data.jumps = player.getStatistic(Statistic.JUMP); } catch (Exception e) {}
                try { data.crouchTime = player.getStatistic(Statistic.CROUCH_ONE_CM) / 100.0; } catch (Exception e) {}

                // 在线时间：从 NBT 读取累计时间 + 当前会话时间
                long savedTime = 0;
                try {
                    PlayerData pd = loadPlayerFromDat(uuid);
                    if (pd != null) savedTime = pd.totalOnlineTime;
                } catch (Exception e) {}
                Long joinTime = joinTimeMap.get(uuid);
                long currentSession = joinTime != null ? System.currentTimeMillis() - joinTime : 0;
                data.totalOnlineTime = savedTime + currentSession;

                playerList.add(dataToMap(data));
                processed.put(uuid, true);
            }

            for (UUID uuid : allUUIDs) {
                if (processed.containsKey(uuid)) continue;
                PlayerData data = loadPlayerFromDat(uuid);
                if (data != null) {
                    data.online = false;
                    playerList.add(dataToMap(data));
                }
            }

            playerList.sort((a, b) -> {
                boolean oa = (boolean) a.get("online");
                boolean ob = (boolean) b.get("online");
                if (oa && !ob) return -1;
                if (!oa && ob) return 1;
                return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
            });

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("timestamp", System.currentTimeMillis());
            response.put("totalCount", playerList.size());
            response.put("onlineCount", (int) playerList.stream().filter(p -> (boolean) p.get("online")).count());
            response.put("players", playerList);

            String json = gson.toJson(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private Map<String, Object> dataToMap(PlayerData data) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", data.name);
            map.put("uuid", data.uuid.toString());
            map.put("online", data.online);
            map.put("health", String.format("%.1f", data.health));
            map.put("maxHealth", String.format("%.1f", data.maxHealth));
            map.put("food", data.food);
            map.put("exp", String.format("%.2f", data.exp));
            map.put("level", data.level);
            map.put("world", data.world);
            map.put("x", String.format("%.1f", data.x));
            map.put("y", String.format("%.1f", data.y));
            map.put("z", String.format("%.1f", data.z));
            map.put("gamemode", data.gamemode);
            map.put("balance", data.balance);

            map.put("playerKills", data.playerKills);
            map.put("deaths", data.deaths);
            map.put("walkDistance", String.format("%.1f", data.walkDistance));
            map.put("sprintDistance", String.format("%.1f", data.sprintDistance));
            map.put("swimDistance", String.format("%.1f", data.swimDistance));
            map.put("fallDistance", String.format("%.1f", data.fallDistance));
            map.put("minecartDistance", String.format("%.1f", data.minecartDistance));
            map.put("boatDistance", String.format("%.1f", data.boatDistance));
            map.put("pigDistance", String.format("%.1f", data.pigDistance));
            map.put("horseDistance", String.format("%.1f", data.horseDistance));
            map.put("elytraDistance", String.format("%.1f", data.elytraDistance));
            map.put("damageDealt", data.damageDealt);
            map.put("damageTaken", data.damageTaken);
            map.put("mobKills", data.mobKills);
            map.put("raidWins", data.raidWins);
            map.put("trades", data.trades);
            map.put("fishCaught", data.fishCaught);
            map.put("jumps", data.jumps);
            map.put("crouchTime", String.format("%.1f", data.crouchTime));
            map.put("totalOnlineTime", data.totalOnlineTime);

            List<Map<String, Object>> invList = new ArrayList<>();
            if (data.inventory != null) {
                for (ItemStack item : data.inventory) {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    if (item != null && !item.getType().isAir()) {
                        String id = item.getType().name().toLowerCase();
                        slot.put("id", id);
                        slot.put("name_cn", getItemName(id));
                        slot.put("amount", item.getAmount());
                    } else {
                        slot.put("id", null);
                        slot.put("name_cn", null);
                        slot.put("amount", 0);
                    }
                    invList.add(slot);
                }
            }
            map.put("inventory", invList);

            List<Map<String, Object>> armorList = new ArrayList<>();
            if (data.armor != null) {
                for (ItemStack item : data.armor) {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    if (item != null && !item.getType().isAir()) {
                        String id = item.getType().name().toLowerCase();
                        slot.put("id", id);
                        slot.put("name_cn", getItemName(id));
                        slot.put("amount", item.getAmount());
                    } else {
                        slot.put("id", null);
                        slot.put("name_cn", null);
                        slot.put("amount", 0);
                    }
                    armorList.add(slot);
                }
            }
            map.put("armor", armorList);

            Map<String, Object> offHandMap = new LinkedHashMap<>();
            if (data.offHand != null && !data.offHand.getType().isAir()) {
                String id = data.offHand.getType().name().toLowerCase();
                offHandMap.put("id", id);
                offHandMap.put("name_cn", getItemName(id));
                offHandMap.put("amount", data.offHand.getAmount());
            } else {
                offHandMap.put("id", null);
                offHandMap.put("name_cn", null);
                offHandMap.put("amount", 0);
            }
            map.put("offHand", offHandMap);

            List<Map<String, Object>> enderList = new ArrayList<>();
            if (data.enderChest != null) {
                for (ItemStack item : data.enderChest) {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    if (item != null && !item.getType().isAir()) {
                        String id = item.getType().name().toLowerCase();
                        slot.put("id", id);
                        slot.put("name_cn", getItemName(id));
                        slot.put("amount", item.getAmount());
                    } else {
                        slot.put("id", null);
                        slot.put("name_cn", null);
                        slot.put("amount", 0);
                    }
                    enderList.add(slot);
                }
            }
            map.put("enderChest", enderList);

            return map;
        }
    }

    private class WebPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = getHtmlPage().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private String getHtmlPage() {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>玩家数据面板</title>
            <style>
                *{margin:0;padding:0;box-sizing:border-box}
                body{background:#f0f2f5;color:#1a1a2e;font-family:-apple-system,'Segoe UI',Arial,sans-serif;padding:12px}
                .container{max-width:1600px;margin:0 auto}
                .header{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;margin-bottom:12px;gap:8px}
                h1{font-size:22px;color:#1a1a2e}
                h1 small{font-size:13px;color:#6c757d;font-weight:400}
                .status-bar{background:#ffffff;padding:8px 16px;margin-bottom:10px;border:1px solid #dce1e8;font-size:13px;color:#2d3748;display:flex;flex-wrap:wrap;gap:20px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.04)}
                .status-bar .num{font-weight:700;color:#1a1a2e}
                .search-box{width:100%;padding:8px 14px;border:2px solid #dce1e8;background:#ffffff;color:#1a1a2e;font-size:14px;margin-bottom:10px;border-radius:8px;transition:border-color 0.2s}
                .search-box:focus{outline:none;border-color:#4a90d9}
                .player-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:10px}
                .player-card{background:#ffffff;border:1px solid #dce1e8;padding:12px 14px;cursor:pointer;transition:border-color 0.25s,box-shadow 0.25s,transform 0.15s;box-shadow:0 1px 2px rgba(0,0,0,0.03);border-radius:10px}
                .player-card:hover{border-color:#4a90d9;box-shadow:0 4px 12px rgba(0,0,0,0.07);transform:translateY(-1px)}
                .player-card .top{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:4px}
                .player-card .name{font-size:16px;font-weight:600}
                .player-card .name .offline{color:#8b949e}
                .player-card .name .online{color:#1a1a2e}
                .badge-online{display:inline-block;width:8px;height:8px;background:#28a745;margin-right:5px;border-radius:50%}
                .badge-offline{display:inline-block;width:8px;height:8px;background:#adb5bd;margin-right:5px;border-radius:50%}
                .status-tag{font-size:11px;padding:1px 10px;font-weight:500;border-radius:12px}
                .status-tag.online{background:#d4edda;color:#155724}
                .status-tag.offline{background:#e9ecef;color:#6c757d}
                .arrow{color:#8b949e;font-size:12px;display:inline-block;transition:transform 0.35s cubic-bezier(0.4,0,0.2,1)}
                .arrow.open{transform:rotate(90deg)}
                .detail-panel{display:grid;grid-template-rows:0fr;transition:grid-template-rows 0.4s cubic-bezier(0.4,0,0.2,1);overflow:hidden}
                .detail-panel.open{grid-template-rows:1fr}
                .detail-panel-inner{overflow:hidden;min-height:0}
                .detail-panel:not(.open) .detail-panel-inner{padding-top:0 !important;margin-top:0 !important}
                .detail-panel.open .detail-panel-inner{margin-top:10px;padding-top:10px;border-top:1px solid #e9ecef}
                .detail-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:2px 12px;font-size:13px}
                .detail-grid .l{color:#6c757d;font-size:12px}
                .detail-grid .v{color:#1a1a2e;text-align:right;font-weight:500;font-size:13px}
                .health-bar-wrap{display:inline-block;width:60px;height:5px;background:#e9ecef;overflow:hidden;vertical-align:middle;margin-left:5px;border-radius:3px}
                .health-bar-wrap .fill{display:block;height:100%;border-radius:3px}
                .detail-section{margin-top:8px}
                .detail-section .title{font-size:11px;font-weight:600;color:#1a1a2e;margin-bottom:3px;letter-spacing:0.3px}
                .inv-grid{display:grid;grid-template-columns:repeat(9,1fr);gap:2px}
                .inv-grid.ender{grid-template-columns:repeat(9,1fr)}
                .inv-grid.armor-grid{grid-template-columns:repeat(4,1fr)}
                .inv-grid.offhand-grid{grid-template-columns:60px !important;justify-content:start}
                .inv-slot{background:#f8f9fa;border:1px solid #e2e8f0;padding:2px 1px;text-align:center;min-height:36px;display:flex;flex-direction:column;align-items:center;justify-content:center;position:relative;font-size:9px;border-radius:4px;transition:background 0.15s}
                .inv-slot:hover{background:#edf2f7}
                .inv-slot .item-name{font-size:8px;color:#1a1a2e;line-height:1.2;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500}
                .inv-slot .item-amount{font-size:8px;font-weight:600;color:#2d3748}
                .inv-slot .tooltip{display:none;position:absolute;bottom:100%;left:50%;transform:translateX(-50%);background:#1a1a2e;padding:3px 8px;font-size:10px;color:#f0f6fc;white-space:nowrap;z-index:10;margin-bottom:3px;border-radius:4px}
                .inv-slot:hover .tooltip{display:block}
                .inv-slot.empty{background:#f8f9fa;border-color:#e2e8f0;min-height:36px;color:#b0b8c4;font-size:8px}
                .inv-slot.gap{background:transparent !important;border:none !important;min-height:0 !important;height:3px;grid-column:1 / -1;padding:0;margin:2px 0;pointer-events:none}
                .inv-grid.offhand-grid .inv-slot{min-height:40px;padding:3px 6px}
                .no-data{color:#8b949e;font-size:11px;padding:2px 0}
                .balance-gold{color:#f1c40f;font-weight:600}
                .load{text-align:center;padding:40px;color:#8b949e}
                .readonly-badge{font-size:10px;color:#6c757d;background:#e9ecef;padding:1px 10px;margin-left:8px;border-radius:10px}
                .sub-title{font-size:10px;color:#6c757d;font-weight:400;margin-left:4px}
                .stat-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(110px,1fr));gap:1px 10px;font-size:12px;background:#f8f9fa;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px}
                .stat-item{display:flex;justify-content:space-between;padding:1px 0;border-bottom:1px solid #edf2f7}
                .stat-item.stat-full{grid-column:1 / -1;border-bottom:2px solid #dce1e8;padding-bottom:3px;margin-bottom:1px}
                .stat-item.stat-sub{padding-left:12px;border-bottom:1px solid #f0f2f5}
                .stat-label{color:#6c757d;font-size:11px}
                .stat-value{color:#1a1a2e;font-weight:500;font-size:12px}
                .stat-item.stat-full .stat-label{font-weight:600;color:#2d3748}
                .stat-item.stat-full .stat-value{font-weight:600;color:#2b6cb0}
                .stat-kdr{color:#e74c3c;font-weight:700}
                @media (max-width:768px){.player-grid{grid-template-columns:1fr}.detail-grid{grid-template-columns:1fr 1fr}.stat-grid{grid-template-columns:1fr 1fr}.header h1{font-size:18px}.status-bar{font-size:12px;gap:10px;padding:6px 12px}}
                @media (max-width:480px){.detail-grid{grid-template-columns:1fr}.stat-grid{grid-template-columns:1fr}.inv-grid{gap:1px}.inv-slot{min-height:30px;padding:1px 1px}.inv-slot .item-name{font-size:7px}.inv-slot .item-amount{font-size:7px}body{padding:6px}}
                @media (min-width:1200px){.player-grid{grid-template-columns:repeat(auto-fill,minmax(360px,1fr))}}
                @media (min-width:1600px){.player-grid{grid-template-columns:repeat(auto-fill,minmax(380px,1fr))}}
            </style>
        </head>
        <body>
        <div class="container">
            <div class="header">
                <h1>玩家数据 <small id="time"></small></h1>
                <span class="readonly-badge">只读模式</span>
            </div>
            <div class="status-bar">
                <span>在线: <span class="num" id="onlineCount">0</span></span>
                <span>总计: <span class="num" id="totalCount">0</span></span>
                <span>更新: <span class="num" id="update">--</span></span>
            </div>
            <input class="search-box" id="search" placeholder="搜索玩家名称..." oninput="filterPlayers()">
            <div class="player-grid" id="list"><div class="load">加载中...</div></div>
        </div>
        <script>
        let allPlayers = [];
        let expanded = {};

        function renderInventory(inv){
            if(!inv || inv.length === 0) return '<div class="no-data">空</div>';
            let html = '';
            let count = 0;
            for(const slot of inv){
                const id = slot.id, amount = slot.amount || 0;
                if(id){
                    const name = slot.name_cn || id.replace(/_/g, ' ');
                    html += `<div class="inv-slot">
                        <span class="item-name">${name}</span>
                        <span class="item-amount">x${amount}</span>
                        <span class="tooltip">${name} x${amount}</span>
                    </div>`;
                }else{
                    html += `<div class="inv-slot empty">空</div>`;
                }
                count++;
                if(count === 27){
                    html += `<div class="inv-slot gap"></div>`;
                }
            }
            return html;
        }

        function renderArmor(armor){
            if(!armor || armor.length === 0) return '<div class="no-data">空</div>';
            const slotNames = ['头盔', '胸甲', '护腿', '靴子'];
            let html = '';
            for(let i = 0; i < armor.length; i++){
                const slot = armor[i];
                const id = slot.id, amount = slot.amount || 0;
                if(id){
                    const name = slot.name_cn || id.replace(/_/g, ' ');
                    html += `<div class="inv-slot">
                        <span class="item-name">${name}</span>
                        <span class="item-amount">x${amount}</span>
                        <span class="tooltip">${slotNames[i]}: ${name} x${amount}</span>
                    </div>`;
                }else{
                    html += `<div class="inv-slot empty">${slotNames[i]}</div>`;
                }
            }
            return html;
        }

        function renderOffHand(offHand){
            if(!offHand || !offHand.id) return '<div class="no-data">空</div>';
            const name = offHand.name_cn || offHand.id.replace(/_/g, ' ');
            const amount = offHand.amount || 1;
            return `<div class="inv-slot">
                <span class="item-name">${name}</span>
                <span class="item-amount">x${amount}</span>
                <span class="tooltip">副手: ${name} x${amount}</span>
            </div>`;
        }

        function formatTime(ms){
            if(!ms || ms < 0) return '0分0秒';
            const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
            let r = '';
            if(d > 0) r += d + '天 ';
            if(h > 0 || d > 0) r += String(h).padStart(2, '0') + '时 ';
            r += String(m).padStart(2, '0') + '分 ' + String(sec).padStart(2, '0') + '秒';
            return r;
        }

        function formatMoney(b){return b === undefined || b === null ? '0.00' : b.toFixed(2);}

        function toggleDetail(uuid){
            if(expanded[uuid]) delete expanded[uuid];
            else expanded[uuid] = true;
            filterPlayers();
        }

        async function loadData(){
            try{
                const r = await fetch('/api/players');
                if(!r.ok) throw new Error('HTTP ' + r.status);
                const d = await r.json();
                allPlayers = d.players;
                document.getElementById('onlineCount').textContent = d.onlineCount;
                document.getElementById('totalCount').textContent = d.totalCount;
                document.getElementById('update').textContent = new Date(d.timestamp).toLocaleTimeString('zh-CN');
                document.getElementById('time').textContent = '· ' + new Date(d.timestamp).toLocaleString('zh-CN');
                renderList(allPlayers);
            }catch(e){
                document.getElementById('list').innerHTML = '<div style="color:#dc3545;text-align:center;padding:20px;">错误: ' + e.message + '</div>';
                console.error('loadData error:', e);
            }
        }

        function filterPlayers(){
            const q = document.getElementById('search').value.toLowerCase();
            const filtered = allPlayers.filter(p => p.name.toLowerCase().includes(q));
            renderList(filtered);
        }

        function renderList(list){
            const c = document.getElementById('list');
            if(!list || list.length === 0){
                c.innerHTML = '<div style="color:#6c757d;text-align:center;padding:20px;">未找到玩家</div>';
                return;
            }
            let html = '';
            for(const p of list){
                const isOnline = p.online;
                const nameCls = isOnline ? 'online' : 'offline';
                const tag = isOnline ? '<span class="status-tag online">在线</span>' : '<span class="status-tag offline">离线</span>';
                const isOpen = expanded[p.uuid] === true;
                const arrowCls = isOpen ? 'arrow open' : 'arrow';
                const hp = parseFloat(p.health), maxHp = parseFloat(p.maxHealth);
                const hpPercent = Math.min(100, (hp / maxHp) * 100 || 0);
                const hpColor = hpPercent < 25 ? '#dc3545' : hpPercent < 50 ? '#fd7e14' : '#28a745';
                const badge = isOnline ? '<span class="badge-online"></span>' : '<span class="badge-offline"></span>';
                const kills = p.playerKills || 0;
                const deaths = p.deaths || 0;
                const kdr = deaths > 0 ? (kills / deaths).toFixed(2) : kills > 0 ? kills.toFixed(2) : '0.00';
                const totalDist = (parseFloat(p.walkDistance||0) + parseFloat(p.sprintDistance||0) + parseFloat(p.swimDistance||0) + parseFloat(p.fallDistance||0) + parseFloat(p.minecartDistance||0) + parseFloat(p.boatDistance||0) + parseFloat(p.pigDistance||0) + parseFloat(p.horseDistance||0) + parseFloat(p.elytraDistance||0)).toFixed(1);

                html += `<div>
                    <div class="player-card" onclick="toggleDetail('${p.uuid}')">
                        <div class="top">
                            <span class="name"><span class="${nameCls}">${badge}${p.name}</span></span>
                            ${tag}
                            <span class="${arrowCls}">></span>
                        </div>
                        <div class="detail-panel ${isOpen ? 'open' : ''}">
                            <div class="detail-panel-inner">
                                <div class="detail-grid">
                                    <span class="l">生命值</span>
                                    <span class="v">${p.health} / ${p.maxHealth} <span class="health-bar-wrap"><span class="fill" style="width:${hpPercent}%;background:${hpColor}"></span></span></span>
                                    <span class="l">饥饿值</span><span class="v">${p.food} / 20</span>
                                    <span class="l">经验值</span><span class="v">${p.exp} (等级 ${p.level})</span>
                                    <span class="l">坐标</span><span class="v">${p.x}, ${p.y}, ${p.z}</span>
                                    <span class="l">世界</span><span class="v">${p.world || '未知'}</span>
                                    <span class="l">游戏模式</span><span class="v">${p.gamemode || '生存'}</span>
                                    <span class="l">余额</span><span class="v balance-gold">$ ${formatMoney(p.balance)}</span>
                                </div>
                                <div class="detail-section">
                                    <div class="title">统计信息</div>
                                    <div class="stat-grid">
                                        <div class="stat-item"><span class="stat-label">游玩时间</span><span class="stat-value">${formatTime(p.totalOnlineTime || 0)}</span></div>
                                        <div class="stat-item"><span class="stat-label">玩家击杀</span><span class="stat-value">${kills}</span></div>
                                        <div class="stat-item"><span class="stat-label">死亡</span><span class="stat-value">${deaths}</span></div>
                                        <div class="stat-item"><span class="stat-label">击杀/死亡</span><span class="stat-value stat-kdr">${kdr}</span></div>
                                        <div class="stat-item stat-full"><span class="stat-label">旅行距离</span><span class="stat-value">合计: ${totalDist}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">行走</span><span class="stat-value">${p.walkDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">疾跑</span><span class="stat-value">${p.sprintDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">游泳</span><span class="stat-value">${p.swimDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">摔落</span><span class="stat-value">${p.fallDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">矿车</span><span class="stat-value">${p.minecartDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">船</span><span class="stat-value">${p.boatDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">骑猪</span><span class="stat-value">${p.pigDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">骑马</span><span class="stat-value">${p.horseDistance || '0.0'}</span></div>
                                        <div class="stat-item stat-sub"><span class="stat-label">鞘翅</span><span class="stat-value">${p.elytraDistance || '0.0'}</span></div>
                                        <div class="stat-item"><span class="stat-label">魔像击杀</span><span class="stat-value">${p.mobKills || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">村民交易</span><span class="stat-value">${p.trades || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">钓鱼</span><span class="stat-value">${p.fishCaught || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">造成伤害</span><span class="stat-value">${p.damageDealt || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">承受伤害</span><span class="stat-value">${p.damageTaken || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">跳跃</span><span class="stat-value">${p.jumps || 0}</span></div>
                                        <div class="stat-item"><span class="stat-label">潜行</span><span class="stat-value">${p.crouchTime || '0.0'}</span></div>
                                    </div>
                                </div>
                                <div class="detail-section">
                                    <div class="title">护甲 <span class="sub-title">(4格)</span></div>
                                    <div class="inv-grid armor-grid">${renderArmor(p.armor)}</div>
                                </div>
                                <div class="detail-section">
                                    <div class="title">副手 <span class="sub-title">(1格)</span></div>
                                    <div class="inv-grid offhand-grid">${renderOffHand(p.offHand)}</div>
                                </div>
                                <div class="detail-section">
                                    <div class="title">背包 <span class="sub-title">(36格)</span></div>
                                    <div class="inv-grid">${renderInventory(p.inventory)}</div>
                                </div>
                                <div class="detail-section">
                                    <div class="title">末影箱 <span class="sub-title">(27格)</span></div>
                                    <div class="inv-grid ender">${renderInventory(p.enderChest)}</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>`;
            }
            c.innerHTML = html;
        }

        loadData();
        setInterval(loadData, 5000);
        </script>
        </body>
        </html>
        """;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "你没有权限！");
                return true;
            }
            reloadConfig();
            loadConfig();
            loadItemNames();
            sender.sendMessage(ChatColor.GREEN + "配置已重载，物品名称已重新加载");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.YELLOW + "=== PlayerMonitor ===");
            sender.sendMessage(ChatColor.GRAY + "端口: " + webPort);
            sender.sendMessage(ChatColor.GRAY + "物品名: " + itemNames.size());
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "PlayerMonitor v1.0");
        sender.sendMessage(ChatColor.GRAY + "网页: http://你的IP:" + webPort);
        sender.sendMessage(ChatColor.GRAY + "命令: /playermonitor reload | info");
        return true;
    }

    private static class PlayerData {
        UUID uuid;
        String name;
        double health = 20;
        double maxHealth = 20;
        int food = 20;
        double exp = 0;
        int level = 0;
        String world = "world";
        double x = 0, y = 0, z = 0;
        String gamemode = "生存";
        boolean online = false;
        double balance = 0.0;
        long totalOnlineTime = 0;
        long lastUpdate = 0;
        ItemStack[] inventory = new ItemStack[36];
        ItemStack[] enderChest = new ItemStack[27];
        ItemStack[] armor = new ItemStack[4];
        ItemStack offHand = null;

        int playerKills = 0;
        int deaths = 0;
        double walkDistance = 0;
        double sprintDistance = 0;
        double swimDistance = 0;
        double fallDistance = 0;
        double minecartDistance = 0;
        double boatDistance = 0;
        double pigDistance = 0;
        double horseDistance = 0;
        double elytraDistance = 0;
        int damageDealt = 0;
        int damageTaken = 0;
        int mobKills = 0;
        int raidWins = 0;
        int trades = 0;
        int fishCaught = 0;
        int jumps = 0;
        double crouchTime = 0;
    }

    private static class NBTReader {
        private final byte[] data;
        private int pos = 0;

        public NBTReader(byte[] data) { this.data = data; }

        public int readTagType() { return data[pos++] & 0xFF; }

        public int readUnsignedShort() {
            return ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
        }

        public int readInt() {
            return ((data[pos++] & 0xFF) << 24) | ((data[pos++] & 0xFF) << 16) |
                    ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
        }

        public long readLong() {
            return ((long) data[pos++] << 56) | ((long) data[pos++] << 48) |
                    ((long) data[pos++] << 40) | ((long) data[pos++] << 32) |
                    ((long) data[pos++] << 24) | ((long) data[pos++] << 16) |
                    ((long) data[pos++] << 8) | (data[pos++] & 0xFF);
        }

        public float readFloat() { return Float.intBitsToFloat(readInt()); }

        public double readDouble() { return Double.longBitsToDouble(readLong()); }

        public String readString() {
            int len = readUnsignedShort();
            if (len == 0) return "";
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        public void skip(int bytes) { pos += bytes; }

        public CompoundTag readCompound() {
            CompoundTag tag = new CompoundTag();
            while (true) {
                int type = readTagType();
                if (type == 0) break;
                String name = readString();
                Object value = readTag(type);
                if (value != null) {
                    tag.put(name, value);
                }
            }
            return tag;
        }

        private Object readTag(int type) {
            switch (type) {
                case 1: return (byte) readUnsignedByte();
                case 2: return (short) readUnsignedShort();
                case 3: return readInt();
                case 4: return readLong();
                case 5: return readFloat();
                case 6: return readDouble();
                case 7:
                    int len = readInt();
                    byte[] arr = new byte[len];
                    System.arraycopy(data, pos, arr, 0, len);
                    pos += len;
                    return arr;
                case 8: return readString();
                case 9: return readList();
                case 10: return readCompound();
                case 11:
                    int intLen = readInt();
                    int[] intArr = new int[intLen];
                    for (int i = 0; i < intLen; i++) {
                        intArr[i] = readInt();
                    }
                    return intArr;
                case 12:
                    int longLen = readInt();
                    long[] longArr = new long[longLen];
                    for (int i = 0; i < longLen; i++) {
                        longArr[i] = readLong();
                    }
                    return longArr;
                default: return null;
            }
        }

        private int readUnsignedByte() { return data[pos++] & 0xFF; }

        private ListTag readList() {
            int type = readTagType();
            int len = readInt();
            ListTag list = new ListTag(type);
            for (int i = 0; i < len; i++) {
                list.add(readTag(type));
            }
            return list;
        }
    }

    private static class CompoundTag {
        private final Map<String, Object> map = new HashMap<>();

        public void put(String key, Object value) { map.put(key, value); }
        public Object get(String key) { return map.get(key); }

        public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }

        public int getInt(String key, int def) {
            Object v = map.get(key);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Short) return ((Short) v).intValue();
            if (v instanceof Byte) return ((Byte) v).intValue();
            return def;
        }

        public float getFloat(String key, float def) {
            Object v = map.get(key);
            return v instanceof Float ? (Float) v : def;
        }

        public double getDouble(String key, double def) {
            Object v = map.get(key);
            return v instanceof Double ? (Double) v : def;
        }

        public ListTag getList(String key) {
            Object v = map.get(key);
            return v instanceof ListTag ? (ListTag) v : null;
        }
    }

    private static class ListTag {
        private final List<Object> list = new ArrayList<>();
        private final int type;

        public ListTag(int type) { this.type = type; }

        public void add(Object value) { list.add(value); }
        public int size() { return list.size(); }
        public Object get(int index) { return list.get(index); }

        public List<CompoundTag> getCompounds() {
            List<CompoundTag> result = new ArrayList<>();
            for (Object obj : list) {
                if (obj instanceof CompoundTag) result.add((CompoundTag) obj);
            }
            return result;
        }

        public double getDouble(int index) {
            Object v = get(index);
            if (v instanceof Double) return (Double) v;
            if (v instanceof Float) return ((Float) v).doubleValue();
            return 0.0;
        }
    }
}