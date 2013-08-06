package by.muna.yasly.logging;

import by.muna.yasly.SocketThread;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface ISocketLogger {
    void onConnect(InetSocketAddress address);
    void onDisconnect(InetSocketAddress address, boolean graceful);

    void onSent(InetSocketAddress address, ByteBuffer buffer);
    void onReceived(InetSocketAddress address, ByteBuffer buffer);

    void onStop(SocketThread socketThread);
}
