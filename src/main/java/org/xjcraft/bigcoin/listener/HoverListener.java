package org.xjcraft.bigcoin.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Hopper;
import org.bukkit.block.Sign;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.xjcraft.bigcoin.BigCoin;
import org.xjcraft.bigcoin.BigCoinManager;
import org.xjcraft.bigcoin.config.Config;
import org.xjcraft.bigcoin.config.MessageConfig;
import org.xjcraft.bigcoin.util.SignUtils;
import org.xjcraft.utils.StringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class HoverListener implements Listener {
    private BigCoin plugin;
    private BigCoinManager manager;

    public HoverListener(BigCoin plugin, BigCoinManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void sign(SignChangeEvent event) {
        if (Objects.equals(event.getLine(0), Config.config.getName())) {
            Block depBlock = SignUtils.getSignDep(event.getBlock());
            if (depBlock != null && depBlock.getType() == Material.HOPPER) {
                boolean success = manager.registerMiner(depBlock.getLocation(), event.getPlayer());
                if (success) {
                    event.getPlayer().sendMessage(MessageConfig.config.getCreateMinerSuccess());
                    event.setLine(1, event.getPlayer().getName());
                    manager.refresh(depBlock);
                } else {
                    event.getPlayer().sendMessage(MessageConfig.config.getCreateMinerFail());
                    event.setLine(0, "");
                }

            } else {
                event.getPlayer().sendMessage(MessageConfig.config.getNeedHopper());
                event.setLine(0, "");
            }
        }
    }

    @EventHandler
    public void active(InventoryMoveItemEvent event) {
        if (event.getSource().getType() == InventoryType.HOPPER) {
            Location location = event.getSource().getLocation();
            if (location != null) {
                String minerOwner = manager.getMinerOwner(location);
                if (minerOwner != null) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void bbreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        breakMiner(event, block);
    }

    public void breakMiner(Cancellable event, Block block) {
        if (block.getType() == Material.HOPPER) {
            boolean success = manager.destroyMiner(block.getLocation());
            if (success) {
                Inventory inventory = ((Container) block.getState()).getInventory();
                inventory.clear();
            }
        } else if (block.getState() instanceof Sign) {
            if (((Sign) block.getState()).getLine(0).equals(Config.config.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Hopper) {
            Location location = ((Hopper) holder).getLocation();
            String minerOwner = manager.getMinerOwner(location);
            if (minerOwner != null) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Hopper) {
            if (event.getAction() == InventoryAction.CLONE_STACK) return;
            Location location = ((Hopper) holder).getLocation();
            String minerOwner = manager.getMinerOwner(location);
            if (minerOwner != null) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void open(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Hopper) {
            Location location = ((Hopper) holder).getLocation();
            String minerOwner = manager.getMinerOwner(location);
            if (minerOwner != null) {
                manager.refresh(event.getInventory());
                event.getPlayer().sendMessage(StringUtil.applyPlaceHolder(MessageConfig.config.getTimeLeft(), new HashMap<String, String>() {{
                    put("count", manager.getRemainTime() + "");
                }}));
            }
        }
    }

    @EventHandler
    public void change(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        breakMiner(event, block);
    }

    @EventHandler
    public void explode(EntityExplodeEvent event) {
        List<Block> blocks = event.blockList();
        ArrayList<Block> list = new ArrayList<>(blocks);
        for (Block block : list) {
            if (block.getType() == Material.HOPPER) {
                boolean success = manager.destroyMiner(block.getLocation());
                if (success) {
                    Inventory inventory = ((Container) block.getState()).getInventory();
                    inventory.clear();
                }
            }
        }
    }


}
