package com.party.websocket;

import okhttp3.Response;
import okio.ByteString;

/**
 * @author 困了想吃肉
 * date   2022/7/18
 * desc 可用于监听ws连接状态
 */
public class WsocketListener {
    public void onOpen(Response response) {
    }

    public void onMessage(String text) {
    }

    public void onMessage(ByteString bytes) {
    }

    public void onReconnect(int reconnectCount ) {

    }

    public void onClosing(int code, String reason) {
    }

    public void onClosed(int code, String reason) {
    }

    public void onFailure(Throwable t, Response response) {
    }
}
