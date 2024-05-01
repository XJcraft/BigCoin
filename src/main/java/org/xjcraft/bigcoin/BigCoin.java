package org.xjcraft.bigcoin;

import org.xjcraft.CommonPlugin;
import org.xjcraft.bigcoin.listener.HoverListener;

public final class BigCoin extends CommonPlugin {
    BigCoinManager manager;
    @Override
    public void onEnable() {
        // Plugin startup logic
        loadConfigs();
        manager = new BigCoinManager(this);
        this.registerCommand(manager);
        getServer().getPluginManager().registerEvents(new HoverListener(this, manager), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        manager.saveQuest();

    }
}
