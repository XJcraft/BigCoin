package org.xjcraft.bigcoin.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.List;

@RConfig(configName = "data.yml")
@Data
public class DataConfig {

    @Instance
    public static DataConfig config = new DataConfig();
    Integer boost = 0;
    Integer count = 0;
    List<String> materials = null;
    List<Integer> needs = null;

}
