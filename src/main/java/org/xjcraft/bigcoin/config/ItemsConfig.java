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
    List<String> pending = new ArrayList<>();
}
