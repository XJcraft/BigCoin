# 物品白/黑名单自动归类 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `/[指令] update` 扫描服务端全部配方,把「能进入配方体系」的新物品加进 `items`(随机池)、其余加进 `excludes`(黑名单),取代原来只写 `pending` 的半成品逻辑。

**Architecture:** 在 `BigCoinManager` 内新增两个私有方法:`collectCraftableMaterials()` 遍历 `Server#recipeIterator()` 构建"可合成材料"集合(含硬编码酿造清单),`classifyItems(sender)` 扫描 `Material.values()` 按命中与否分流到两个列表并输出报告。`update()` 改为调用 `classifyItems`,并保留原有的失效条目清理。`ItemsConfig#pending` 字段删除。

**Tech Stack:** Java 8, Spigot API (26.2 编译, 兼容旧服务端), Lombok, XJCraft 框架 (`@RConfig`/`@RCommand`)。

**关键兼容性约束(务必遵守):**
- `RecipeChoice.MaterialChoice#getChoices()` 返回 `List<Material>`,而 `RecipeChoice.ExactChoice#getChoices()` 返回 `List<ItemStack>`——**签名不同,必须按实现类分支**,不能统一按 `List<ItemStack>` 处理。
- `TransmuteRecipe` / `ImbueRecipe` 是 1.21.5+ 才有的类。编译期能用(编译目标是 26.2),但**运行在旧服务端时这两个类不存在**,任何 `instanceof TransmuteRecipe` / `catch (TransmuteRecipe e)` 都会触发 `NoClassDefFoundError`。因此这两个类型**只用字符串名字反射访问**,不出现类型引用。
- `Server#recipeIterator()` 返回 `Iterator<Recipe>`,运行时来自服务端实例,无法在纯 jar 环境探针验证,只能编译验证 + 真机观察。

---

### Task 1: 删除 `ItemsConfig#pending` 字段

**Files:**
- Modify: `src/main/java/org/xjcraft/bigcoin/config/ItemsConfig.java`

- [ ] **Step 1: 删除 pending 字段**

把 `ItemsConfig.java` 改为(整文件内容):

```java
package org.xjcraft.bigcoin.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.ArrayList;
import java.util.List;

@RConfig(configName = "items.yml")
@Data
public class ItemsConfig {
    @Instance
    public static ItemsConfig config = new ItemsConfig();
    List<String> items = new ArrayList<>();
    List<String> excludes = new ArrayList<>();
}
```

- [ ] **Step 2: 确认全项目无残留引用**

Run: `grep -rn "getPending\|setPending\|pending" src/main/java/`
Expected: 无输出(空)。若有输出,说明还有读点,回到 spec 重新确认——不应有。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/bigcoin/config/ItemsConfig.java
git commit -m "$(cat <<'EOF'
remove unused pending field from ItemsConfig

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 2: 新增酿造材料常量表

**Files:**
- Modify: `src/main/java/org/xjcraft/bigcoin/BigCoinManager.java`

- [ ] **Step 1: 在 `RENAMED_MATERIALS` 之后新增常量**

在 `BigCoinManager.java` 里,`RENAMED_MATERIALS` 静态块之后、`private BigCoin plugin;` 之前,插入:

```java
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
```

- [ ] **Step 2: 编译验证(此步尚未接线,应无影响)**

本章节所有任务完成后统一编译;此步可跳过,直接进入 Task 3。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/bigcoin/BigCoinManager.java
git commit -m "$(cat <<'EOF'
add hardcoded brewing material list

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 3: 新增配方材料提取方法

**Files:**
- Modify: `src/main/java/org/xjcraft/bigcoin/BigCoinManager.java`

- [ ] **Step 1: 新增 `collectRecipeChoices` 辅助方法**

在 `lookupUsableMaterial` 方法之后插入。该方法接受任意 `RecipeChoice`,按实现类分支提取 Material,全程 try/catch:

```java
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
                if (materials != null) {
                    for (Material material : materials) {
                        if (material != null) out.add(material);
                    }
                    return;
                }
            } else if (choice instanceof RecipeChoice.ExactChoice) {
                List<ItemStack> stacks = ((RecipeChoice.ExactChoice) choice).getChoices();
                if (stacks != null) {
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
```

- [ ] **Step 2: 新增 `collectRecipeIngredients` 辅助方法**

紧跟在上一步之后插入。按 Recipe 类型分派取材料;`TransmuteRecipe`/`ImbueRecipe` 用反射访问,避免旧服务端 `NoClassDefFoundError`:

```java
    /**
     * 按 Recipe 类型分派提取材料 Material。
     * 未知类型只取产物，不抛异常。
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
                // getTemplate 只在 SmithingTransformRecipe / SmithingTrimRecipe 上，反射取
                invokeChoice(recipe, "getTemplate", out);
            } else {
                // TransmuteRecipe (1.21.5+) / ImbueRecipe (1.21.5+)：类在旧服务端不存在，用反射
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
            // 方法不存在或无返回值，属预期情况
        }
    }
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/bigcoin/BigCoinManager.java
git commit -m "$(cat <<'EOF'
add recipe ingredient extraction helpers

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 4: 新增 `collectCraftableMaterials` 与 `classifyItems`

**Files:**
- Modify: `src/main/java/org/xjcraft/bigcoin/BigCoinManager.java`

- [ ] **Step 1: 新增 `collectCraftableMaterials`**

在上一步的 `invokeChoice` 之后插入。遍历全部配方取产物与材料,再并入酿造清单:

```java
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
```

- [ ] **Step 2: 新增 `classifyItems`**

紧跟其后插入。扫描 `Material.values()`,只处理两列表都没有的物品,输出报告:

```java
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
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/bigcoin/BigCoinManager.java
git commit -m "$(cat <<'EOF'
add craftable collection and item classification

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 5: 改写 `update()` 指令接线

**Files:**
- Modify: `src/main/java/org/xjcraft/bigcoin/BigCoinManager.java:242-273`(原 `update()`)

- [ ] **Step 1: 替换 `update()` 实现**

把现有 `update()` 方法整体替换为:

```java
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
```

注意两点改动:(1) 方法签名加了 `CommandSender sender` 参数——XJCraft 的 `@RCommand` 支持注入指令发送者,XJCraft 框架的既有 `valid(CommandSender)` 就是这个形态;(2) 清理循环改为遍历 `new ArrayList<>(...)` 副本,避免边遍历边 `remove` 触发 `ConcurrentModificationException`(原代码直接在 `current` 的副本 HashSet 上遍历、在 config 列表上删除,本次统一为副本遍历)。

- [ ] **Step 2: 确认 @RCommand 支持 CommandSender 参数**

Run: `grep -rn "CommandSender" src/main/java/ | grep -i "RCommand\|valid" `
参照 `valid(CommandSender sender)`(第 199 行)已存在且可用,签名形态一致。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/bigcoin/BigCoinManager.java
git commit -m "$(cat <<'EOF'
wire update command to item classification

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 6: 全量编译验证

**Files:**
- 无(仅验证)

- [ ] **Step 1: 组装 classpath 并编译**

`mvn` 在本机不可用(maven wrapper 不完整),沿用前次修复的 `javac` 直接编译方案。先取依赖 jar:

Run:
```bash
mvn_cp() { echo ~/.m2/repository/org/spigotmc/spigot-api/26.2-R0.1-SNAPSHOT/spigot-api-26.2-R0.1-SNAPSHOT.jar; }
ls ~/.m2/repository/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar
```
Expected: lombok jar 存在。

- [ ] **Step 2: 编译全部源文件**

Run(在项目根目录):
```bash
CP=$(find ~/.m2/repository -name "*.jar" \( -path "*spigot-api*26.2*" -o -path "*SimpleSpigot*" -o -path "*MultiCurrency*" -o -path "*XJLogin*" -o -path "*XJCraft*" -o -path "*lombok*1.18.48*" \) | tr '\n' ';')
mkdir -p /tmp/bcout
javac -encoding UTF-8 -source 8 -target 8 -cp "$CP" -processorpath ~/.m2/repository/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar -d /tmp/bcout $(find src/main/java -name "*.java")
```
Expected: 只有 deprecation / unchecked 警告,**无 error**。

- [ ] **Step 3: 若报错则修复**

常见错误与处理:
- `TransmuteRecipe` / `ImbueRecipe` 符号找不到 → 说明误用了类型引用,应改回字符串反射(Task 3 Step 2 的 `invokeChoice`)。本计划刻意不 import 这两个类。
- `CookingRecipe<?>` 泛型不匹配 → 确认 `CookingRecipe` 在 26.2 是泛型类;若编译器拒绝,改为裸类型 `((CookingRecipe) recipe).getInputChoice()` 并加 `@SuppressWarnings("rawtypes")`。
- `StringUtil.join(addedToItems.toArray(), ",")` 签名不符 → 用 `StringUtil.join(addedToItems.toArray(), ",")`,参照第 317 行既有用法 `StringUtil.join(winners.toArray(), ",")`。

- [ ] **Step 4: 提交(若 Step 3 有改动)**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix compile issues in item classification

rer.zhu@hairobotics.com
EOF
)"
```

---

### Task 7: 真机验证与文档记录

**Files:**
- Modify: `docs/superpowers/specs/2026-09-26-item-classification-design.md`(如验证结果偏离 spec,补充「实施备注」)

- [ ] **Step 1: 部署到真实服务端执行指令**

将构建产物放入服务器 plugins,启动后执行 `/[指令] update`。

Expected(日志):
- `归类完成：新增到 items N 项，归入 excludes M 项`
- `新增到 items:` 一行包含 `SAND`、`COBBLESTONE`、`GRAVEL`、`CLAY_BALL`(它们是熔炉配方材料)
- `归入 excludes:` 一行包含 `DIRT`

- [ ] **Step 2: 核对 items.yml**

打开 `plugins/BigCoin/items.yml`:
- `items` 下新增项与日志一致
- `excludes` 下出现 `DIRT`
- 原有的 `SCUTE`(若存在)被清理逻辑移除,或其别名已解析

- [ ] **Step 3: 重复执行一次,确认幂等**

再次执行 `/[指令] update`。
Expected: `items +0，excludes +0`(两列表都不在的物品为空,不重复添加)。

- [ ] **Step 4: 记录验证结果**

若实际结果与 spec「已知且已接受的偏差」表一致,在 spec 末尾追加一节:

```markdown
## 实施备注（YYYY-MM-DD 真机验证）

在 <服务端版本> 上执行 update 后：
- 沙子 / 圆石 / 砂砾 / 黏土球 → items（符合预期）
- 泥土 → excludes（符合预期）
- 重复执行幂等（第二次 +0）
```

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-09-26-item-classification-design.md
git commit -m "$(cat <<'EOF'
record item classification field verification

rer.zhu@hairobotics.com
EOF
)"
```

---

## 自检(spec 覆盖核对)

| spec 要求 | 对应任务 |
|---|---|
| 遍历 `recipeIterator()` 建 craftable | Task 4 Step 1 |
| 产物 + 材料任一命中 | Task 3 + Task 4 Step 1 |
| 材料提取的类型分派(Shaped/Shapeless/Cooking/Stonecutting/Smithing/Transmute/Imbue) | Task 3 Step 2 |
| 酿造硬编码清单并入 craftable | Task 2 + Task 4 Step 1 |
| 只处理两列表都不在的物品 | Task 4 Step 2(`known` 集合跳过) |
| craftable 为空 → 中止告警 | Task 4 Step 2 |
| items < 5 → 告警 | Task 4 Step 2 末段 |
| 替换 `update()` 实现 | Task 5 |
| 删除 `pending` | Task 1 |
| 属性过滤 `isItem && !isLegacy && maxStackSize > 1` | Task 4 Step 2 |
| 单条配方失败不中断 | Task 3 全部 try/catch |
| 无自动化测试,真机验证 | Task 7 |

**类型一致性核对:** `collectRecipeChoices(RecipeChoice, Set<Material>)`、`collectRecipeIngredients(Recipe, Set<Material>)`、`invokeChoice(Object, String, Set<Material>)`、`collectCraftableMaterials()` 返回 `Set<Material>`、`classifyItems(CommandSender)` 在 Task 3/4/5 之间签名一致。
