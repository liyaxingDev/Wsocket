package com.party.websocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.ByteString;

/**
 * @author 困了想吃肉
 * date   2022/7/18
 * desc WebSocket 管理类
 */
public class WsocketManager extends WebSocketListener implements IWebSocket {

    private static final String TAG = "全局WsManager";
    private int reconnectCount = 0;   //重连次数

    private int customReconnectInterval;   //重连间隔初始值（秒）
    private int reconnectInterval; //计算的重连间隔 （秒）
    private int reconnectMAxInterval; //默认最大重连间隔 （秒）
    private int mCurrentStatus = WsocketStatus.DISCONNECTED; //websocket连接状态

    private WebSocket mWebSocket;
    private Lock mLock;
    private OkHttpClient mOkHttpClient;
    private WsMsgEvent mMsgEvent;
    private static volatile WsocketManager mInstance;
    private boolean isManualClose = false;         //是否为手动关闭websocket连接
    private Handler wsMainHandler = new Handler(Looper.getMainLooper());
    private boolean isNeedReconnect = false;//是否需要断线自动重连
    private boolean isNetWorkState = false;//是否需要监听网络状态
    private Context mContext;

    private int indexUrl = 0;//UrlList 去哪个 indexUrl
    List<String> wsUrl = new ArrayList();


    private WsocketManager() {
        this.mLock = new ReentrantLock();
        this.mMsgEvent = new WsMsgEvent();
        initWebSocket();
    }

    public static WsocketManager create() {
        if (mInstance == null) {
            synchronized (WsocketManager.class) {
                if (mInstance == null) {
                    mInstance = new WsocketManager();
                }
            }
        }
        return mInstance;
    }


    private void initWebSocket() {
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor(message -> {
//            Log.i(TAG, " message: " + message);
        });
        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        setOkHttpClient(new OkHttpClient().newBuilder()
                .addInterceptor(httpLoggingInterceptor)
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .sslSocketFactory(SocketSSLSocketClient.getSSLSocketFactory(), SocketSSLSocketClient.geX509tTrustManager())
                .hostnameVerifier(SocketSSLSocketClient.getHostnameVerifier())
                .build());
    }


    BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                Log.i(TAG, "网络监听 = " + IsNetWorkEnable(context));
                reconnectInterval = customReconnectInterval;   //重制重连间隔

                if (IsNetWorkEnable(context)) {
                    buildConnect();//重新连接socket
                }
            }
        }
    };

    /**
     * 网络是否可用
     *
     * @param context
     * @return
     */
    private boolean IsNetWorkEnable(Context context) {
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) {
                return false;
            }

            NetworkInfo info = connectivity.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                // 判断当前网络是否已经连接
                if (info.getState() == NetworkInfo.State.CONNECTED) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    private void tryReconnect() {
        if (!isNeedReconnect | isManualClose) {
            return;
        }
        // 判断网络
        if (isNetWorkState) {
            if (!isNetworkConnected(mContext)) {
                setCurrentStatus(WsocketStatus.DISCONNECTED);
                return;
            }
        }

        Log.i(TAG, "tryReconnect WsocketStatus= " + getCurrentStatus() + "  = " + Looper.myLooper());
        //已经在重连中了
        if (getCurrentStatus() == WsocketStatus.CONNECTING) {
            return;
        }

        //重新连接
        setCurrentStatus(WsocketStatus.RECONNECT);


        Log.i(TAG, "tryReconnect 重连间隔= " + reconnectInterval);
        wsMainHandler.removeCallbacksAndMessages(null);
        wsMainHandler.postDelayed(reconnectRunnable, reconnectInterval * 1000L);
        reconnectCount++;

        reconnectInterval = reconnectInterval * 2;
        //如果超过最大重连间隔，值改为最大重连间隔
        if (reconnectInterval > reconnectMAxInterval) {
            reconnectInterval = reconnectMAxInterval;
        }
    }


    final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "treconnectRunnable = " + reconnectCount + " = " + getCurrentStatus() + " = " + Looper.myLooper());
            mMsgEvent.mWsocketListener.onReconnect(reconnectCount);
            indexUrl++;
            buildConnect();
        }
    };


    private synchronized void buildConnect() {
        Log.i(TAG, "buildConnect= " + getCurrentStatus() + " = " + Looper.myLooper());
        // 判断网络
        if (isNetWorkState) {
            if (!isNetworkConnected(mContext)) {
                setCurrentStatus(WsocketStatus.DISCONNECTED);
                return;
            }
        }

        switch (getCurrentStatus()) {
            case WsocketStatus.CONNECTED:
            case WsocketStatus.CONNECTING:
                break;
            default:
                setCurrentStatus(WsocketStatus.CONNECTING);
                initWs();
        }
    }

    private void initWs() {
        Log.i(TAG, " initWs: = " + getCurrentStatus() + " = " + Looper.myLooper());

        if (indexUrl > wsUrl.size() - 1) {
            indexUrl = 0;
        }

        String responseUrl = wsUrl.get(indexUrl);
        Log.i(TAG, " responseUrl: =  " + responseUrl);
        try {
            if (mOkHttpClient == null) {
                initWebSocket();
            }
            if (mWebSocket != null) {
                mWebSocket.cancel();
                mWebSocket = null;
            }
            Request request = new Request.Builder()
                    .url(responseUrl)
                    .build();
            mOkHttpClient.dispatcher().cancelAll();
            mLock.lockInterruptibly();
            try {
                mOkHttpClient.newWebSocket(request, this);
            } finally {
                mLock.unlock();
            }
        } catch (Exception e) {
            mMsgEvent.mWsocketListener.onFailure(new Throwable(e.getMessage()), null);
            e.printStackTrace();
        }
    }


    //检查网络是否连接
    private boolean isNetworkConnected(Context context) {
        if (context != null) {
            ConnectivityManager mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo mNetworkInfo = mConnectivityManager.getActiveNetworkInfo();
            if (mNetworkInfo != null) {
                return mNetworkInfo.isAvailable();
            }
        }
        return false;
    }


    /**
     * 断开连接，去重连
     */
    public void disconnect() {
        if (mCurrentStatus == WsocketStatus.DISCONNECTED) {
            return;
        }
        cancelReconnect();
        if (mOkHttpClient != null) {
            mOkHttpClient.dispatcher().cancelAll();
        }
        if (mWebSocket != null) {
            boolean isClosed = mWebSocket.close(WsocketStatus.CODE.NORMAL_CLOSE, WsocketStatus.TIP.NORMAL_CLOSE);
            Log.i(TAG, "disconnect isClosed = " + isClosed);
            //非正常关闭连接
//            if (!isClosed) {
//                     mMsgEvent.mWsocketListener.onClosed(WsocketStatus.CODE.ABNORMAL_CLOSE, WsocketStatus.TIP.ABNORMAL_CLOSE);
//            }
        }

        setCurrentStatus(WsocketStatus.DISCONNECTED);
    }

    private void connected() {
        cancelReconnect();
    }

    private void cancelReconnect() {
        wsMainHandler.removeCallbacks(reconnectRunnable);
        reconnectCount = 0;
    }


    private void setOkHttpClient(OkHttpClient okHttpClient) {
        mOkHttpClient = okHttpClient;
    }

    /**
     * @param socketUrl 连接socket的url
     */
    public void setWsUrlList(List<String> socketUrl) {
        wsUrl = socketUrl;
        indexUrl = 0;
    }

    /**
     * @param needReconnect 是否需要重连
     */
    public void setNeedReconnect(boolean needReconnect) {
        isNeedReconnect = needReconnect;
    }

    /**
     * @param interval    重连间隔（秒）
     * @param maxInterval 重连最大间隔 （秒）
     */
    public void setNeedReconnectTime(int interval, int maxInterval) {
        customReconnectInterval = interval;
        reconnectInterval = interval;
        reconnectMAxInterval = maxInterval;
    }

    /**
     * 注册网络状态监听
     *
     * @param context
     */
    public void registerNetWorkState(Context context) {
        isNetWorkState = true;
        mContext = context;
        IntentFilter filterNetWork = new IntentFilter();
        filterNetWork.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        //注册一个网络监听广播
        context.registerReceiver(netReceiver, filterNetWork);
    }


    public void addWsocketListener(WsocketListener listener) {
        this.mMsgEvent.addWsocketListener(listener);
    }

    public void removeWsocketListener(WsocketListener listener) {
        this.mMsgEvent.removeWsocketListener(listener);
    }

    public void clearWsocketListener() {
        this.mMsgEvent.clearWsocketListener();
    }


    /**
     * 连接socket
     */
    @Override
    public void startConnect() {
        Log.i(TAG, " startConnect: " + "  = " + Looper.myLooper());
        isManualClose = false;
        if (customReconnectInterval == 0) {
            throw new RuntimeException(TAG + "请设置重连间隔 ");
        }
        if (reconnectMAxInterval == 0) {
            throw new RuntimeException(TAG + "请设置最大重连间隔 ");
        }
        if (wsUrl == null || wsUrl.size() == 0) {
            throw new RuntimeException(TAG + "Socket连接Url 不能为空");
        }


        buildConnect();
    }

    /**
     * 停止socket连接
     */
    @Override
    public void stopConnect() {
        Log.i(TAG, " stopConnect:  = " + Looper.myLooper());
        isManualClose = true;
        disconnect();
    }

    @Override
    public WebSocket getWebSocket() {
        return mWebSocket;
    }


    @Override
    public synchronized boolean isConnected() {
        return mWebSocket != null && mCurrentStatus == WsocketStatus.CONNECTED;
    }

    @Override
    public synchronized int getCurrentStatus() {
        return mCurrentStatus;
    }

    @Override
    public synchronized void setCurrentStatus(int currentStatus) {
        this.mCurrentStatus = currentStatus;
    }

    @Override
    public boolean sendMessage(String msg) {
        return send(msg);
    }

    @Override
    public boolean sendMessage(ByteString byteString) {
        return send(byteString);
    }

    private boolean send(Object msg) {
        boolean isSend = false;
        if (mWebSocket != null && mCurrentStatus == WsocketStatus.CONNECTED) {
            if (msg instanceof String) {
                isSend = mWebSocket.send((String) msg);
            } else if (msg instanceof ByteString) {
                isSend = mWebSocket.send((ByteString) msg);
            }
//            //发送消息失败，尝试重连
//            if (!isSend) {
//                tryReconnect();
//            }
        }
        //发送消息失败，尝试重连
        if (!isSend) {
            tryReconnect();
        }

        Log.i(TAG, " 发送socket  = isSend : " + isSend + " send：" + msg + " webSocket = " + (mWebSocket != null ? mWebSocket.hashCode() : "") + "--" + Looper.myLooper());

        return isSend;
    }


    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.i(TAG, " onOpen: " + response + "  webSocket = " + webSocket.hashCode());
        mWebSocket = webSocket;
        reconnectInterval = customReconnectInterval;   //重制重连间隔
        setCurrentStatus(WsocketStatus.CONNECTED);
        mMsgEvent.mWsocketListener.onOpen(response);

        connected();

    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.i(TAG, " onMessage String : " + text + "  webSocket = " + webSocket.hashCode());
        mMsgEvent.mWsocketListener.onMessage(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        Log.i(TAG, " onMessage ByteString : " + bytes + "  webSocket = " + webSocket.hashCode());
        mMsgEvent.mWsocketListener.onMessage(bytes);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        Log.i(TAG, " onClosing: code = " + code + " reason = " + reason + "  webSocket = " + webSocket.hashCode());
        setCurrentStatus(WsocketStatus.DISCONNECTED);
        mMsgEvent.mWsocketListener.onClosing(code, reason);

        tryReconnect();
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.i(TAG, " onClosed: code = " + code + " reason = " + reason + "  webSocket = " + webSocket.hashCode());
        setCurrentStatus(WsocketStatus.DISCONNECTED);
        mMsgEvent.mWsocketListener.onClosed(code, reason);

        tryReconnect();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.i(TAG, " onFailure: " + t + " response = " + response + "  webSocket = " + webSocket.hashCode() + "--" + Looper.myLooper());

        setCurrentStatus(WsocketStatus.DISCONNECTED);
        mMsgEvent.mWsocketListener.onFailure(t, response);

        tryReconnect();

    }


}