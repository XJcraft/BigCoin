package org.xjcraft.bigcoin.config;

import lombok.Data;
import org.xjcraft.annotation.Comment;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

@RConfig
@Data
public class Config {
    @Instance
    public static Config config = new Config();
    @Comment("牌子第一行的名称")
    String name = "[矿机]";
    @Comment("所在世界")
    String world = "MainLand";
    @Comment("时间间隔")
    Integer period = 60;
    @Comment("加成比例")
    Double boost = 0.1;
    @Comment("最大加成比例")
    Integer maxBoost = 10;
    @Comment("基础货币数量")
    Integer base = 100;
    @Comment("需要几次boost难度提升")
    Integer step = 2;
    @Comment("最大随机物品")
    Integer maxItem = 5;
    @Comment("付出的货币")
    String currency = "GOV";
    @Comment("货币所有者")
    String owner = "Ree_OP";
}
