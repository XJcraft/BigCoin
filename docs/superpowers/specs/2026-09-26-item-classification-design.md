# 物品白/黑名单自动归类设计

## 背景

`BigCoin` 的矿机玩法靠 `items.yml` 里的 `items` 列表随机抽取 5 个物品作为一轮需求。每次 Minecraft 服务端升级都可能新增材料，但 `items` 列表是**旧版本一次性生成后固化的**，新增物品永远不会自动进入随机池，需要人工维护。

同时，`1.20.6` 把 `SCUTE` 重命名为 `TURTLE_SCUTE` 这类改名，已由前一次修复（`resolveMaterial` + 别名表 + 回退）处理。本次解决的是**新增物品如何进入/排除随机池**。

## 目标

服务端升级后，提供一种（可重复执行的）归类机制：

- 把「存在于服务器配方体系内」的新物品加入 `items`（随机池）
- 把「不在任何配方体系内」的新物品加入 `excludes`（黑名单）

触发方式：**仅手动指令**（不随插件启动自动执行），由人在确认升级完成后主动触发。

## 判定语义（已确认）

对每个候选物品，遍历**服务器当前加载的全部配方**（`Server#recipeIterator()`，包含工作台、熔炉、高炉、烟熏炉、切石机、营火、铁匠台，以及数据包/插件自定义配方）：

- 该物品是**任一配方的产物**（`Recipe#getResult()`），**或**
- 该物品是**任一配方的材料**（展开 `RecipeChoice` 得到的 Material 集合），

任一命中 → 归入 `items`；两者都不命中 → 归入 `excludes`。

此外，`craftable` 集合还并入一份**硬编码的酿造材料清单**（见下文「酿造缺口」），因为这些配方不在 `recipeIterator()` 覆盖范围内。

### 已知且已接受的偏差

本规则把「配方外物品」当作黑名单，**不等价于「不可获取」**。典型如下：

| 物品 | 判定结果 | 实际可获取性 |
|---|---|---|
| 沙子 / 圆石 / 砂砾 / 黏土球 | 进 `items`（是熔炉配方的材料） | 可挖 |
| 泥土 | 进 `excludes` | 可挖（但挖出来当矿机需求无意义） |
| 各类原矿（铁矿等） | 需按实际配方核实 | 可挖 |

即：**只能挖取、且不属于任何配方产物/材料的物品会被判进黑名单**。用户已明确接受——矿机需求列表排除泥土一类物品符合玩法预期。

副作用：若服务端安装了非原版配方（数据包/插件），归类结果会随之变化，这是期望行为（贴近「服里实际能不能造出来」）。

## 覆盖范围总览

已核实 26.2 全部 18 个 `Recipe` 子类与体系外机制：

| 来源 | 覆盖方式 |
|---|---|
| 工作台、熔炉、高炉、烟熏炉、营火、切石机、铁匠台 | `recipeIterator()` |
| 特殊合成（染色、地图复制等 `DyeRecipe`/`ComplexRecipe`） | `recipeIterator()`（有 `getResult()`） |
| 转化/灌注（`TransmuteRecipe`/`ImbueRecipe`，1.21.5+） | `recipeIterator()` + 分派表取材料 |
| 酿造台 | **硬编码清单**（体系外，见下文「酿造缺口」） |
| 村民交易 | 不覆盖（`MerchantRecipe` 动态生成，不在 `recipeIterator` 内） |
| 铁砧/砂轮/织布机/制图台/堆肥桶 | 不覆盖（工具操作，不产生新物品种类，判入黑名单符合预期） |

## 作用对象（已确认）

扫描全部 `Material`，只处理**既不在 `items` 也不在 `excludes`** 的物品。两个列表里已有的条目**不重扫、不覆盖**，保留人工调整结果。

## 方案

### 数据流

```
手动指令
  → 遍历 recipeIterator()，构建 Set<Material> craftable
      每个配方：getResult() 的 type 加入集合
      按类型分派取材料，展开 RecipeChoice 的 getChoices()，type 逐个加入集合
  → 把硬编码的酿造材料清单 resolveMaterial 后并入 craftable
  → craftable 仍为空 → 中止并告警（未提供配方的异常环境）
  → 扫描 Material.values()，过滤 isItem() && !isLegacy() && maxStackSize > 1
      跳过已在 items / excludes 的
      命中 craftable → items
      否则          → excludes
  → saveConfig(items.yml)
  → 输出分类报告（新增到 items 的、归入 excludes 的）
```

### 材料提取的类型分派

26.2 的 `Recipe` 子类没有统一的材料访问接口，必须按类型分派。已用 `javap` 核实的方法：

| Recipe 类型 | 取材料方法 |
|---|---|
| `ShapedRecipe` | `getChoiceMap().values()` |
| `ShapelessRecipe` | `getChoiceList()` |
| `CookingRecipe`（Furnace/Blasting/Smoking/Campfire） | `getInputChoice()` |
| `StonecuttingRecipe` | `getInputChoice()` |
| `SmithingRecipe`（Transform/Trim） | `getTemplate()`、`getBase()`、`getAddition()` |
| `TransmuteRecipe` / `ImbueRecipe`（1.21.5+ 新增） | `getInput()`、`getMaterial()` |
| 其余/未知类型 | 无材料可提取，只取 `getResult()` |

`RecipeChoice` 在 26.2 只有 `ExactChoice` 与 `MaterialChoice` 两个实现，二者**都提供 `getChoices()`**，统一抽出 `List<ItemStack>` → `.getType()`。对 `getChoices()` 为空或未知实现的 `RecipeChoice`，回落到 `getItemStack().getType()`，全程 try/catch 兜底，单个配方解析失败不影响整体。

上述分派表已用 `javap` 对 26.2 spigot-api 逐类核实方法名。

### 酿造缺口（硬编码清单）

**酿造台上的配方不在 Bukkit 的 `Recipe` 体系内**——26.2 没有 `BrewingRecipe` 类，`PotionBrewer` 只提供药水效果计算，`recipeIterator()` 不会返回酿造配方。Paper 有 `PotionMix` 但会绑定 Paper，本项目依赖 Spigot API，不使用。

因此把**酿造材料**硬编码进代码，并入 `craftable` 集合。清单从 1.21 服务端 jar 的 `PotionBrewer` 字节码提取（`javap -c` 得到的 `Items.XXX` 常量），共 21 项：

```
NETHER_WART, GLOWSTONE_DUST, REDSTONE, SUGAR, RABBIT_FOOT, GOLDEN_CARROT,
GLISTERING_MELON_SLICE, MAGMA_CREAM, BLAZE_POWDER, GHAST_TEAR,
FERMENTED_SPIDER_EYE, SPIDER_EYE, PUFFERFISH,
GUNPOWDER, DRAGON_BREATH,
TURTLE_HELMET, PHANTOM_MEMBRANE,
SLIME_BLOCK, STONE, COBWEB, BREEZE_ROD
```

**不包含酿造产出**（`POTION` / `SPLASH_POTION` / `LINGERING_POTION`，已确认）：产出物本就不在任何配方中，会被规则默认判进黑名单，无需额外处理。

代码中该项通过 `resolveMaterial` 解析（字符串 → Material），本版本不存在的（如旧服务端的 `BREEZE_ROD`）自动跳过，不报错。

**已知代价**：这份清单是硬编码的，未来版本新增酿造材料时需手工补充——本功能消除的是「全量物品」的人工维护，酿造这 21 项仍需少量维护。Bukkit API 无替代方案，已在文档中如实记录。

### 接口形态

新增一个私有方法承载归类逻辑，复用现有 `resolveMaterial` 的过滤口径（`isLegacy` / `isItem` 排除）：

```java
private Set<Material> collectCraftableMaterials();   // 构建 craftable 集合
private void classifyItems(CommandSender sender);    // 执行归类并输出报告
```

指令复用现有 `@RCommand("update")`（`BigCoinManager#update`），**替换**其当前实现。

### 删除 `pending`

`ItemsConfig#pending` 字段：当前只在 `update()` 里 `add`，全项目无任何读取点（已用 grep 确认），本次归类机制取代其作用，删掉该字段与相关写入。

## 错误处理

- 单条配方解析异常 → 捕获、记日志、继续下一条，不中断归类
- `craftable` 为空 → 说明服务端未提供配方（异常环境），**中止归类并告警**，避免把所有物品误判进黑名单
- 归类结果导致 `items` 少于 5 项 → 告警（现有 `ensureItemPool` 的重建逻辑仍然兜底）

## 测试

无自动化测试框架。验证方式：

1. `javac` + lombok 编译通过（沿用前次修复的 maven wrapper 缺失替代方案）
2. 归类逻辑依赖服务端运行时（`recipeIterator` 需要 Server 实例），**无法在纯 jar 环境下跑探针**——这一点必须如实说明，最终效果需在真实服务端执行指令后观察日志
3. 可在服务端上核对：`items.yml`/`excludes` 的条目变化是否与预期一致（如沙子进 items、泥土进 excludes）

## 不做（YAGNI）

- 不自动随启动执行
- 不做「挖掘掉落 / 战利品表」判定——仅在用户日后认为偏差不可接受时才考虑
- 不区分配方类型（熔炉与工作台同等对待，已确认）
- 不做 GUI 或交互式挑选
