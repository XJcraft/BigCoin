package org.xjcraft.bigcoin;

import com.zjyl1994.minecraftplugin.multicurrency.services.BankService;
import com.zjyl1994.minecraftplugin.multicurrency.services.CurrencyService;
import com.zjyl1994.minecraftplugin.multicurrency.utils.OperateResult;
import com.zjyl1994.minecraftplugin.multicurrency.utils.TxTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.xjcraft.bigcoin.config.*;
import org.xjcraft.utils.MathUtil;
import org.xjcraft.utils.StringUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class BigCoinManager {
    private BigCoin plugin;
    private Timer timer = new Timer();
    Checker checker;
    Material[] quest = null;
    int[] needs = null;

    public BigCoinManager(BigCoin plugin) {
        this.plugin = plugin;
        checker = new Checker(DataConfig.config.getCount());
        if (DataConfig.config.getMaterials() != null && DataConfig.config.getNeeds() != null) {
            quest = new Material[5];
            needs = new int[5];
            for (int i = 0; i < 5; i++) {
                quest[i] = Material.valueOf(DataConfig.config.getMaterials().get(i));
                needs[i] = DataConfig.config.getNeeds().get(i);
            }
            printQuest();
        }
        timer.schedule(checker, 1000L, 1000L * 60L);
    }

    public void checker() {
        if (quest != null) {
            World world = plugin.getServer().getWorld(Config.config.getWorld());
            if (world == null) {
                plugin.getLogger().warning("需要先配置世界！");
                return;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                check2(world);
                plugin.getServer().getScheduler().runTaskLater(plugin, this::newQuest, 1);
            }, 1);


        } else {
            if (ItemsConfig.config.getItems().size() == 0) {
                for (Material value : Material.values()) {
                    if (value.getMaxStackSize() > 1) {
                        if (value.isItem())
                            ItemsConfig.config.getItems().add(value.name());
                    }
                }
                plugin.saveConfig(ItemsConfig.class);
            }
            plugin.getServer().getScheduler().runTask(plugin, this::newQuest);

        }


    }

    public void check2(World world) {
        List<String> winners = getWinners(world);
        if (winners.size() > 0) {
            DataConfig.config.setBoost(DataConfig.config.getBoost() + 1);
            double v = Config.config.getBase() * ((Math.min(DataConfig.config.getBoost(), Config.config.getMaxBoost())) * Config.config.getBoost() + 1);
            plugin.getServer().broadcastMessage(StringUtil.applyPlaceHolder(MessageConfig.config.getWinners(), new HashMap<String, String>() {{
                put("people", winners.size() + "");
                put("amount", String.format("%.2f", v));
            }}));
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                BigDecimal price = new BigDecimal(v);
                OperateResult result = CurrencyService.reserveIncr(Config.config.getCurrency(), price, Config.config.getOwner());
                if (!result.getSuccess()) {
                    plugin.getLogger().warning("fail to increase reserve because:" + result.getReason());
                    return;
                }
                price = price.divide(new BigDecimal(winners.size()), 2, RoundingMode.DOWN);
                for (String winner : winners) {
                    result = BankService.transferTo("$" + Config.config.getCurrency(), winner, Config.config.getCurrency(), price, TxTypeEnum.ELECTRONIC_TRANSFER_OUT, "BigCoin");
                    if (!result.getSuccess()) {
                        plugin.getLogger().warning("fail to transfer because:" + result.getReason());
                        return;
                    }
                }

            });
        } else {
            DataConfig.config.setBoost(DataConfig.config.getBoost() - 1);
            if (DataConfig.config.getBoost() < 0) DataConfig.config.setBoost(0);
        }
    }

    public List<String> getWinners(World world) {
        List<String> winners = new ArrayList<>();
        Chunk[] loadedChunks = world.getLoadedChunks();

        for (Chunk chunk : loadedChunks) {
            if (!chunk.isLoaded()) continue;
            Map<String, String> map = MinersConfig.config.getHoppers().get(String.format("%s,%s", chunk.getX(), chunk.getZ()));
            if (map == null) continue;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                try {
                    String position = entry.getKey();
                    String[] split = position.split(",");
                    Block block = world.getBlockAt(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
                    if (block.getState() instanceof Hopper) {
                        Inventory inventory = ((Hopper) block.getState()).getInventory();
                        ItemStack[] contents = inventory.getContents();
                        if (isFinished(contents)) {
                            winners.add(entry.getValue());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }
        return winners;
    }

    public boolean isFinished(ItemStack[] contents) {
        for (int i = 0; i < 5; i++) {
            ItemStack content = contents[i];
            if (content == null || content.getType() != quest[i] || content.getMaxStackSize() != content.getAmount()) {
                return false;
            }
        }
        return true;
    }

    public void newQuest() {
        try {
            quest = new Material[5];
            needs = new int[5];

            for (int i = 0; i < 5; i++) {
                quest[i] = Material.valueOf(ItemsConfig.config.getItems().get(MathUtil.random(0, ItemsConfig.config.getItems().size() - 1)));
                if (i > DataConfig.config.getBoost() / 2) {
                    needs[i] = 0;
                } else {
                    needs[i] = MathUtil.random(1, Config.config.getMaxItem());
                }

            }
            printQuest();

            World world = plugin.getServer().getWorld(Config.config.getWorld());
            if (world == null) {
                plugin.getLogger().warning("需要先配置世界！");
                return;
            }

            Chunk[] loadedChunks = world.getLoadedChunks();
            for (Chunk chunk : loadedChunks) {
                Map<String, String> map = MinersConfig.config.getHoppers().get(String.format("%s,%s", chunk.getX(), chunk.getZ()));
                if (map == null) continue;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    try {
                        String position = entry.getKey();
                        String[] split = position.split(",");
                        Block block = world.getBlockAt(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
                        refresh(block);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }
            saveQuest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void printQuest() {
        String name = "";
        for (int i = 0; i < 5; i++) {
            name += quest[i].name();
            name += "*";
            name += needs[i];
            name += ";";
        }
        plugin.getLogger().info("generate or load quest:" + name);
    }

    public void refresh(Block block) {
        if (block.getState() instanceof Hopper) {
            Inventory inventory = ((Hopper) block.getState()).getInventory();
            refresh(inventory);
        }
    }

    public void refresh(Inventory inventory) {
        if (!hasRefreshed(inventory))
            for (int i = 0; i < 5; i++) {
                inventory.setItem(i, new ItemStack(quest[i], quest[i].getMaxStackSize() - needs[i]));
            }
    }

    private boolean hasRefreshed(Inventory inventory) {

        for (int i = 0; i < 5; i++) {
            if (inventory.getItem(i) == null || inventory.getItem(i).getType() != quest[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean registerMiner(Location block, Player player) {
        Chunk chunk = block.getChunk();
        Map<String, String> map = MinersConfig.config.getHoppers().computeIfAbsent(String.format("%s,%s", chunk.getX(), chunk.getZ()), k -> new HashMap<>());
        String positon = String.format("%s,%s,%s", block.getBlockX(), block.getBlockY(), block.getBlockZ());
        String s = map.get(positon);
        if (s != null) return false;
        String put = map.put(positon, player.getName());
        plugin.saveConfig(MinersConfig.class);
        return true;
    }

    public String getMinerOwner(Location block) {
        Chunk chunk = block.getChunk();
        Map<String, String> map = MinersConfig.config.getHoppers().get(String.format("%s,%s", chunk.getX(), chunk.getZ()));
        if (map == null) return null;
        String positon = String.format("%s,%s,%s", block.getBlockX(), block.getBlockY(), block.getBlockZ());
        return map.get(positon);

    }

    public boolean destroyMiner(Location block) {
        Chunk chunk = block.getChunk();
        Map<String, String> map = MinersConfig.config.getHoppers().computeIfAbsent(String.format("%s,%s", chunk.getX(), chunk.getZ()), k -> new HashMap<>());
        String positon = String.format("%s,%s,%s", block.getBlockX(), block.getBlockY(), block.getBlockZ());
        String remove = map.remove(positon);
        if (map.size() == 0) MinersConfig.config.getHoppers().remove(String.format("%s,%s", chunk.getX(), chunk.getZ()));
        if (remove == null) return false;
        plugin.saveConfig(MinersConfig.class);
        return true;

    }

    public int getRemainTime() {
        return checker.getCount();

    }

    public void saveQuest() {
        DataConfig.config.setMaterials(new ArrayList<String>(5));
        for (int i = 0; i < quest.length; i++) {
            Material material = quest[i];
            DataConfig.config.getMaterials().add(i, material.name());
        }

        DataConfig.config.setNeeds(new ArrayList<Integer>(5));
        for (int i = 0; i < needs.length; i++) {
            int need = needs[i];
            DataConfig.config.getNeeds().add(i, need);
        }
        DataConfig.config.setCount(checker.count);
        plugin.saveConfig(DataConfig.class);
    }

    @AllArgsConstructor
    class Checker extends TimerTask {
        @Getter
        private int count;


        @Override
        public void run() {
            if (count == 0) {
                plugin.getServer().broadcastMessage(MessageConfig.config.getTimeOver());
                count = Config.config.getPeriod();
                checker();
//                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, BigCoinManager.this::checker);
            } else if (count < 5) {
                plugin.getServer().broadcastMessage(StringUtil.applyPlaceHolder(MessageConfig.config.getTimeLeft(), new HashMap<String, String>() {{
                    put("count", count + "");
                }}));
            }
            count--;
        }
    }
}
