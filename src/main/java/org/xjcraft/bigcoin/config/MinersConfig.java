package org.xjcraft.bigcoin.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.LinkedHashMap;
import java.util.Map;

@RConfig(configName = "miners.yml")
@Data
public class MinersConfig {
    @Instance
    public static MinersConfig config = new MinersConfig();
    Map<String, Map<String, String>> hoppers = new LinkedHashMap<>();
}
