package org.xjcraft.bigcoin.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

@RConfig(configName = "message.yml")
@Data
public class MessageConfig {
    @Instance
    public static MessageConfig config = new MessageConfig();
    String needHopper = "你需要把牌子贴在漏斗上来注册矿机";
    String createMinerSuccess = "注册矿机成功！";
    String createMinerFail = "注册矿机失败！";
    String timeLeft = "距离本轮挖矿结束还剩%count%分钟。";
    String timeOver = "本轮挖矿已结束！";
    String winners = "共有%people%分享了%amount%国债！";
}
