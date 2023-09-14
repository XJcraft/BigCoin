package org.xjcraft.bigcoin.api;

import org.xjcraft.login.api.MessageAPI;

public class XJLogin {
    public static void sendMessage(String message) {
        MessageAPI.sendQQMessage(message);
    }
}
