package by.muna.yasly.logging;

import by.muna.yasly.SocketThread;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class SilentSocketLogger implements ISocketLogger {
    @Override public void onConnect(InetSocketAddress address) {}
    @Override public void onDisconnect(InetSocketAddress address, boolean graceful) {}
    @Override public void onSent(InetSocketAddress address, ByteBuffer buffer) {}
    @Override public void onReceived(InetSocketAddress address, ByteBuffer buffer) {}
    @Override public void onStop(SocketThread socketThread) {}
}
