package org.xjcraft.bigcoin;

import com.zjyl1994.minecraftplugin.multicurrency.services.BankService;
import com.zjyl1994.minecraftplugin.multicurrency.services.CurrencyService;
import com.zjyl1994.minecraftplugin.multicurrency.utils.OperateResult;
import com.zjyl1994.minecraftplugin.multicurrency.utils.TxTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.xjcraft.annotation.RCommand;
import org.xjcraft.api.CommonCommandExecutor;
import org.xjcraft.bigcoin.api.XJLogin;
import org.xjcraft.bigcoin.config.*;
import org.xjcraft.utils.MathUtil;
import org.xjcraft.utils.StringUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class BigCoinManager implements CommonCommandExecutor {

    /**
     * 服务端版本升级时被重命名的 Material，旧名 -> 当前名，方向唯一。
     */
    private static final Map<String, String> RENAMED_MATERIALS = new HashMap<String, String>() {{
        put("SCUTE", "TURTLE_SCUTE");
        put("GRASS_PATH", "DIRT_PATH");
        put("SIGN", "OAK_SIGN");
        put("WALL_SIGN", "OAK_WALL_SIGN");
    }};

    /**
     * 酿造台配方不在 Bukkit 的 Recipe 体系内（26.2 无 BrewingRecipe 类，
     * recipeIterator() 不返回酿造配方），这里硬编码酿造原料清单，并入 craftable。
     * 清单来自 1.21 服务端 PotionBrewer 字节码；不存在的项由 resolveMaterial 自动跳过。
     */
    private static final String[] BREWING_MATERIALS = {
            "NETHER_WART", "GLOWSTONE_DUST", "REDSTONE", "SUGAR", "RABBIT_FOOT",
            "GOLDEN_CARROT", "GLISTERING_MELON_SLICE", "MAGMA_CREAM", "BLAZE_POWDER",
            "GHAST_TEAR", "FERMENTED_SPIDER_EYE", "SPIDER_EYE", "PUFFERFISH",
            "GUNPOWDER", "DRAGON_BREATH", "TURTLE_HELMET", "PHANTOM_MEMBRANE",
            "SLIME_BLOCK", "STONE", "COBWEB", "BREEZE_ROD"
    };

    private BigCoin plugin;
    private Timer timer = new Timer();
    Checker checker;
    Material[] quest = null;
    int[] needs = null;
    private final Set<String> spawns = new HashSet<>();

    /**
     * 把配置里的物品名解析成 Material。
     * 服务端升级会重命名枚举常量（如 1.20.6 的 SCUTE -> TURTLE_SCUTE），
     * 旧名字先按别名表取新名，新名在本版本不存在时回退原名——这样新旧服务端都能解析。
     * 全部解析不到返回 null 并告警，绝不抛异常。
     */
    private Material resolveMaterial(String name) {
        if (name == null) return null;
        String key = name.trim().toUpperCase(Locale.ENGLISH);
        Material material = lookupUsableMaterial(key);
        if (material == null) {
            String renamed = RENAMED_MATERIALS.get(key);
            if (renamed != null) {
                material = lookupUsableMaterial(renamed);
                if (material != null) {
                    plugin.getLogger().info("物品名已随服务端版本更新:" + name + " -> " + material.name());
                }
            }
        }
        if (material == null) {
            plugin.getLogger().warning("无法解析的物品名，已忽略:" + name);
        }
        return material;
    }

    private Material lookupUsableMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) return null;
        if (material.isLegacy()) return null;
        if (!material.isItem()) return null;
        return material;
    }

    /**
     * 把单个 RecipeChoice 展开成 Material 集合。
     * MaterialChoice#getChoices() 返回 List&lt;Material&gt;，
     * ExactChoice#getChoices() 返回 List&lt;ItemStack&gt;，签名不同必须分支。
     */
    private void collectRecipeChoices(RecipeChoice choice, Set<Material> out) {
        if (choice == null) return;
        try {
            if (choice instanceof RecipeChoice.MaterialChoice) {
                List<Material> materials = ((RecipeChoice.MaterialChoice) choice).getChoices();
                if (materials != null && !materials.isEmpty()) {
                    for (Material material : materials) {
                        if (material != null) out.add(material);
                    }
                    return;
                }
            } else if (choice instanceof RecipeChoice.ExactChoice) {
                List<ItemStack> stacks = ((RecipeChoice.ExactChoice) choice).getChoices();
                if (stacks != null && !stacks.isEmpty()) {
                    for (ItemStack stack : stacks) {
                        if (stack != null && stack.getType() != null) out.add(stack.getType());
                    }
                    return;
                }
            }
            ItemStack single = choice.getItemStack();
            if (single != null && single.getType() != null) out.add(single.getType());
        } catch (Throwable t) {
            plugin.getLogger().warning("解析配方材料失败，已跳过:" + t.getMessage());
        }
    }

    /**
     * 按 Recipe 类型分派提取材料 Material。
     * TransmuteRecipe / ImbueRecipe 是 1.21.5+ 的类，旧服务端不存在，一律反射访问，
     * 避免类引用触发 NoClassDefFoundError。
     */
    private void collectRecipeIngredients(Recipe recipe, Set<Material> out) {
        try {
            if (recipe instanceof ShapedRecipe) {
                for (RecipeChoice choice : ((ShapedRecipe) recipe).getChoiceMap().values()) {
                    collectRecipeChoices(choice, out);
                }
            } else if (recipe instanceof ShapelessRecipe) {
                for (RecipeChoice choice : ((ShapelessRecipe) recipe).getChoiceList()) {
                    collectRecipeChoices(choice, out);
                }
            } else if (recipe instanceof CookingRecipe) {
                collectRecipeChoices(((CookingRecipe<?>) recipe).getInputChoice(), out);
            } else if (recipe instanceof StonecuttingRecipe) {
                collectRecipeChoices(((StonecuttingRecipe) recipe).getInputChoice(), out);
            } else if (recipe instanceof SmithingRecipe) {
                collectRecipeChoices(((SmithingRecipe) recipe).getBase(), out);
                collectRecipeChoices(((SmithingRecipe) recipe).getAddition(), out);
                invokeChoice(recipe, "getTemplate", out);
            } else {
                invokeChoice(recipe, "getInput", out);
                invokeChoice(recipe, "getMaterial", out);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("解析配方失败，已跳过:" + t.getMessage());
        }
    }

    /**
     * 反射调用无参方法，期望返回 RecipeChoice，展开后并入 out。
     * 方法不存在 / 返回类型不符 / 抛异常，一律静默跳过。
     */
    private void invokeChoice(Object target, String method, Set<Material> out) {
        try {
            Object result = target.getClass().getMethod(method).invoke(target);
            if (result instanceof RecipeChoice) {
                collectRecipeChoices((RecipeChoice) result, out);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 遍历服务端全部配方，构建「可合成材料」集合：任一配方的产物或材料都算命中。
     * 另并入硬编码的酿造材料清单（酿造不在 Recipe 体系内）。
     */
    private Set<Material> collectCraftableMaterials() {
        Set<Material> craftable = new HashSet<>();
        Iterator<Recipe> iterator = plugin.getServer().recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe == null) continue;
            try {
                ItemStack result = recipe.getResult();
                if (result != null && result.getType() != null) {
                    craftable.add(result.getType());
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("读取配方产物失败，已跳过:" + t.getMessage());
            }
            collectRecipeIngredients(recipe, craftable);
        }
        for (String name : BREWING_MATERIALS) {
            Material material = resolveMaterial(name);
            if (material != null) craftable.add(material);
        }
        return craftable;
    }

    /**
     * 扫描全部 Material，把不在 items/excludes 里的物品按是否命中 craftable 分流。
     * craftable 为空时中止，避免把所有物品误判进黑名单。
     */
    private void classifyItems(CommandSender sender) {
        Set<Material> craftable = collectCraftableMaterials();
        if (craftable.isEmpty()) {
            plugin.getLogger().warning("未从服务端取得任何配方，已中止归类");
            if (sender != null) sender.sendMessage("未取得任何配方，已中止归类");
            return;
        }

        Set<String> known = new HashSet<>(ItemsConfig.config.getItems());
        known.addAll(ItemsConfig.config.getExcludes());

        List<String> addedToItems = new ArrayList<>();
        List<String> addedToExcludes = new ArrayList<>();

        for (Material value : Material.values()) {
            if (value.isLegacy() || !value.isItem() || value.getMaxStackSize() <= 1) continue;
            if (known.contains(value.name())) continue;
            if (craftable.contains(value)) {
                ItemsConfig.config.getItems().add(value.name());
                addedToItems.add(value.name());
            } else {
                ItemsConfig.config.getExcludes().add(value.name());
                addedToExcludes.add(value.name());
            }
        }

        plugin.saveConfig(ItemsConfig.class);

        plugin.getLogger().info("归类完成：新增到 items " + addedToItems.size()
                + " 项，归入 excludes " + addedToExcludes.size() + " 项");
        plugin.getLogger().info("新增到 items:" + StringUtil.join(addedToItems.toArray(), ","));
        plugin.getLogger().info("归入 excludes:" + StringUtil.join(addedToExcludes.toArray(), ","));

        if (sender != null) {
            sender.sendMessage("归类完成：items +" + addedToItems.size()
                    + "，excludes +" + addedToExcludes.size());
        }

        if (ItemsConfig.config.getItems().size() < 5) {
            plugin.getLogger().warning("items 少于 5 项，矿机可能无法正常刷新");
            if (sender != null) sender.sendMessage("警告：items 少于 5 项");
        }
    }

    /**
     * 保证 items.yml 里至少有 5 个可用物品，不够时用当前服务端的物品重建。
     */
    private void ensureItemPool() {
        if (ItemsConfig.config.getItems().size() >= 5) return;
        ItemsConfig.config.getItems().clear();
        for (Material value : Material.values()) {
            if (value.isItem() && value.getMaxStackSize() > 1 && !value.isLegacy()) {
                ItemsConfig.config.getItems().add(value.name());
            }
        }
        plugin.saveConfig(ItemsConfig.class);
        plugin.getLogger().info("items.yml 可用物品不足，已重建为 " + ItemsConfig.config.getItems().size() + " 项");
    }

    /**
     * 按 items.yml 里的候选池重建 quest/needs，解析失败的条目直接剔除。
     */
    private void buildRandomQuest() {
        List<Material> pool = new ArrayList<>();
        for (String name : new ArrayList<>(ItemsConfig.config.getItems())) {
            Material material = resolveMaterial(name);
            if (material != null) {
                pool.add(material);
            } else {
                ItemsConfig.config.getItems().remove(name);
            }
        }
        if (pool.size() < 5) {
            if (ItemsConfig.config.getItems().size() < 5) {
                ensureItemPool();
                plugin.getServer().getScheduler().runTask(plugin, this::newQuest);
            } else {
                plugin.getLogger().warning("可用物品仍不足 5 项，本轮不刷新矿机");
            }
            return;
        }
        quest = new Material[5];
        needs = new int[5];
        for (int i = 0; i < 5; i++) {
            quest[i] = pool.get(MathUtil.random(0, pool.size() - 1));
            if (i > (DataConfig.config.getBoost() / Config.config.getStep())) {
                needs[i] = 0;
            } else {
                needs[i] = MathUtil.random(1, Config.config.getMaxItem());
            }
        }
        printQuest();
        refreshAll();
        saveQuest();
    }

    /**
     * 恢复上次未结算的 quest，按当前版本重新解析，已失效的槽位用候选池补齐。
     * @return 是否成功恢复
     */
    private boolean restoreQuest() {
        List<String> materials = DataConfig.config.getMaterials();
        List<Integer> savedNeeds = DataConfig.config.getNeeds();
        if (materials == null || savedNeeds == null || materials.size() < 5 || savedNeeds.size() < 5) {
            return false;
        }
        List<Material> resolved = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Material material = resolveMaterial(materials.get(i));
            if (material == null) {
                ensureItemPool();
                return false;
            }
            resolved.add(material);
        }
        quest = resolved.toArray(new Material[5]);
        needs = new int[5];
        for (int i = 0; i < 5; i++) {
            needs[i] = savedNeeds.get(i);
        }
        printQuest();
        return true;
    }

    private void refreshAll() {
        World world = plugin.getServer().getWorld(Config.config.getWorld());
        if (world == null) {
            plugin.getLogger().warning("需要先配置世界！");
            return;
        }
        for (Chunk chunk : world.getLoadedChunks()) {
            Map<String, String> map = MinersConfig.config.getHoppers().get(String.format("%s,%s", chunk.getX(), chunk.getZ()));
            if (map == null) continue;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                try {
                    String[] split = entry.getKey().split(",");
                    refresh(world.getBlockAt(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2])));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public BigCoinManager(BigCoin plugin) {
        this.plugin = plugin;
        checker = new Checker(DataConfig.config.getCount());
        ensureItemPool();
        if (!restoreQuest()) {
            plugin.getServer().getScheduler().runTask(plugin, this::newQuest);
        }
        timer.schedule(checker, 1000L, 1000L * 60L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Chunk chunk = Bukkit.getServer().getWorld("MainLand").getSpawnLocation().getChunk();
            for (int i = -1; i < 2; i++) {
                for (int j = -1; j < 2; j++) {
                    spawns.add(String.format("%s,%s", chunk.getX() + i, chunk.getZ() + j));
                }
            }
        });

    }

    @RCommand("valid")
    public void valid(CommandSender sender) {
        Map<String, Map<String, String>> hoppers = MinersConfig.config.getHoppers();
        ArrayList<Map.Entry<String, Map<String, String>>> list = new ArrayList<>(hoppers.entrySet());
        LinkedHashMap<String, Map<String, String>> newMap = new LinkedHashMap<>();
        Bukkit.getLogger().info("[BigCoinManager] valid start");
        runValid(list, newMap);

    }

    private void runValid(ArrayList<Map.Entry<String, Map<String, String>>> list, LinkedHashMap<String, Map<String, String>> newMap) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!list.isEmpty()) {
                Map.Entry<String, Map<String, String>> entry = list.get(0);
                list.remove(0);
                String chunk = entry.getKey();
                String[] split = chunk.split(",");
                World mainLand = Bukkit.getServer().getWorld("MainLand");
                Chunk chunkAt = Objects.requireNonNull(mainLand).getChunkAt(Integer.parseInt(split[0]), Integer.parseInt(split[1]));
                if (!chunkAt.isLoaded()) {
                    chunkAt.load();
                }
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                entry.getValue().forEach((k, v) -> {
                    String[] strings = k.split(",");
                    Block block = mainLand.getBlockAt(Integer.parseInt(strings[0]), Integer.parseInt(strings[1]), Integer.parseInt(strings[2]));
                    if (block.getState() instanceof Hopper) {
                        values.put(k, v);
                    } else {
                        Bukkit.getLogger().warning("[BigCoinManager] " + k + " is not a Hopper");
                    }
                });
                if (!values.isEmpty()) {
                    newMap.put(chunk, values);
                }
                runValid(list, newMap);
            } else {
                MinersConfig.config.setHoppers(newMap);
                plugin.saveConfig(MinersConfig.class);
                Bukkit.getLogger().info("[BigCoinManager] valid done");
            }
        });
    }

    @RCommand("update")
    public void update(CommandSender sender) {
        classifyItems(sender);

        for (String s : new ArrayList<>(ItemsConfig.config.getItems())) {
            if (resolveMaterial(s) == null) {
                plugin.getLogger().info("remove deprecated material:" + s);
                ItemsConfig.config.getItems().remove(s);
            }
        }
        for (String s : new ArrayList<>(ItemsConfig.config.getExcludes())) {
            if (resolveMaterial(s) == null) {
                plugin.getLogger().info("remove deprecated material:" + s);
                ItemsConfig.config.getExcludes().remove(s);
            }
        }

        plugin.saveConfig(ItemsConfig.class);
    }

    /**
     * 结算矿机
     */
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
            ensureItemPool();
            plugin.getServer().getScheduler().runTask(plugin, this::newQuest);

        }


    }

    public void check2(World world) {
        List<String> winners = getWinners(world);
        if (!winners.isEmpty()) {
            DataConfig.config.setBoost(Math.min(DataConfig.config.getBoost() + 1, Config.config.getMaxBoost()));
            double v = Config.config.getBase() * ((Math.min(DataConfig.config.getBoost(), Config.config.getMaxBoost())) * Config.config.getBoost() + 1);
            String message = StringUtil.applyPlaceHolder(MessageConfig.config.getWinners(), new HashMap<String, String>() {{
                put("people", String.valueOf(winners.size()));
                put("amount", String.format("%.2f", v));
                put("boost", String.format("%s", DataConfig.config.getBoost()));
            }});
            if (plugin.getServer().getPluginManager().getPlugin("XJLogin") != null && DataConfig.config.getBoost() >= 5) {
                plugin.getLogger().info("send message to XJLogin");
//                    XJLogin.sendMessage(message);
                plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> XJLogin.sendMessage(message), 10);
            }
            plugin.getServer().broadcastMessage(message);
            plugin.getLogger().info(message);
            plugin.getLogger().info("winners:" + StringUtil.join(winners.toArray(), ","));

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
            DataConfig.config.setBoost(DataConfig.config.getBoost() - Config.config.getBoostDecrease());
            if (DataConfig.config.getBoost() < 0) DataConfig.config.setBoost(0);
        }
    }

    public List<String> getWinners(World world) {
        List<String> winners = new ArrayList<>();
        Chunk[] loadedChunks = world.getLoadedChunks();

        for (Chunk chunk : loadedChunks) {
            if (!chunk.isLoaded()) continue;
            String chunkKey = String.format("%s,%s", chunk.getX(), chunk.getZ());
            Map<String, String> map = MinersConfig.config.getHoppers().get(chunkKey);
            if (map == null) continue;
            boolean insideSpawn = spawns.contains(chunkKey);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                try {
                    String position = entry.getKey();
                    String[] split = position.split(",");
                    Block block = world.getBlockAt(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
                    if (block.getState() instanceof Hopper) {
                        if (insideSpawn) {
                            //todo 未来搞成爆炸
                            plugin.getLogger().info("inside spawn" + position);
                            continue;
                        }
                        Inventory inventory = ((Hopper) block.getState()).getInventory();
                        ItemStack[] contents = inventory.getContents();
                        if (isFinished(contents)) {
                            winners.add(entry.getValue());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("fail to get winners:" + e.getMessage());
                    e.printStackTrace();
                }

            }
        }
        return winners;
    }

    public boolean isFinished(ItemStack[] contents) {
        if (quest == null || needs == null) return false;
        for (int i = 0; i < 5; i++) {
            if (quest[i] == null) return false;
            ItemStack content = contents[i];
            if (content == null || content.getType() != quest[i] || content.getMaxStackSize() != content.getAmount()) {
                return false;
            }
        }
        return true;
    }

    public void newQuest() {
        try {
            buildRandomQuest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void printQuest() {
        if (quest == null || needs == null) return;
        String name = "";
        for (int i = 0; i < 5; i++) {
            name += quest[i] == null ? "null" : quest[i].name();
            name += "*";
            name += needs[i];
            name += ";";
        }
        plugin.getLogger().info("generate or load quest:" + name);
//        if (plugin.getServer().getPluginManager().getPlugin("XJLogin") != null) {
//            String message = "当前回合矿机需求为:" + name;
//            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> XJLogin.sendMessage(message), 20);
//        }
    }

    public void refresh(Block block) {
        if (block.getState() instanceof Hopper) {
            Inventory inventory = ((Hopper) block.getState()).getInventory();
            refresh(inventory);
        }
    }

    public void refresh(Inventory inventory) {
        if (quest == null || needs == null) return;
        if (hasRefreshed(inventory))
            return;
        for (int i = 0; i < 5; i++) {
            if (quest[i] == null) continue;
            inventory.setItem(i, new ItemStack(quest[i], quest[i].getMaxStackSize() - needs[i]));
        }
    }

    private boolean hasRefreshed(Inventory inventory) {
        if (quest == null || needs == null) return true;
        for (int i = 0; i < 5; i++) {
            if (quest[i] == null) return true;
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
        if (quest == null || needs == null) return;
        for (int i = 0; i < quest.length; i++) {
            if (quest[i] == null) return;
        }
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
//                if (plugin.getServer().getPluginManager().getPlugin("XJLogin") != null) {
//                    plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> XJLogin.sendMessage(MessageConfig.config.getTimeOver()),0);
//                }
                count = Config.config.getPeriod();
                checker();
//                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, BigCoinManager.this::checker);
            } else if (count < 5) {
                String message = StringUtil.applyPlaceHolder(MessageConfig.config.getTimeLeft(), new HashMap<String, String>() {{
                    put("count", count + "");
                }});
                plugin.getServer().broadcastMessage(message);
//                if (plugin.getServer().getPluginManager().getPlugin("XJLogin") != null) {
//                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> XJLogin.sendMessage(message));
//                }
            }
            count--;
        }
    }
}
