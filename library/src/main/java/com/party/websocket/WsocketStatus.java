package com.party.websocket;


/**
 * @author 困了想吃肉
 * date   2022/7/18
 * desc WebSocket 链接状态
 */
public interface WsocketStatus {

    int CONNECTED = 1;//连接了
    int CONNECTING = 0;//连接中
    int RECONNECT = 2;//重连
    int DISCONNECTED = -1;//断开连接

    interface CODE {
        int NORMAL_CLOSE = 1000;
        int ABNORMAL_CLOSE = 1001;
    }

    interface TIP {
        String NORMAL_CLOSE = "normal close";
        String ABNORMAL_CLOSE = "abnormal close";

    }
}
