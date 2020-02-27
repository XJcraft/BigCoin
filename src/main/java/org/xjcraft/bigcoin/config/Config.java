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
    String world = "MainLand";
    Integer period = 60;
    Double boost = 0.1;
    Integer maxBoost = 10;
    Integer base = 100;
    Integer maxItem = 8;
    String currency ="GOV";
    String owner ="Ree_OP";
}
