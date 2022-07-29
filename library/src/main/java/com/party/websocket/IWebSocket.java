package com.party.websocket;

import okhttp3.WebSocket;
import okio.ByteString;



interface IWebSocket {

  WebSocket getWebSocket();

  void startConnect();

  void stopConnect();

  boolean isConnected();

  int getCurrentStatus();

  void setCurrentStatus(int currentStatus);

  boolean sendMessage(String msg);

  boolean sendMessage(ByteString byteString);
}
