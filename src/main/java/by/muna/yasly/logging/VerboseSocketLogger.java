package by.muna.yasly.logging;

import by.muna.yasly.SocketThread;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Vector;

public class VerboseSocketLogger implements ISocketLogger {
    @Override
    public void onConnect(InetSocketAddress address) {
        System.out.println("@" + VerboseSocketLogger.addressToString(address) + " connected.");
    }

    @Override
    public void onDisconnect(InetSocketAddress address, boolean graceful) {
        System.out.println("^" + VerboseSocketLogger.addressToString(address) + " disconnected " +
            (graceful ? "gracefully" : "dirty"));
    }

    @Override
    public void onSent(InetSocketAddress address, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return;

        System.out.println(">" + VerboseSocketLogger.addressToString(address) + " sent:");
        VerboseSocketLogger.logBuffer(buffer);
    }

    @Override
    public void onReceived(InetSocketAddress address, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return;

        System.out.println("<" + VerboseSocketLogger.addressToString(address) + " received:");
        VerboseSocketLogger.logBuffer(buffer);
    }

    @Override
    public void onStop(SocketThread socketThread) {
        System.out.println("Socket thread stopped gracefully.");
    }

    public static String addressToString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    public static void logBuffer(ByteBuffer buffer) {
        VerboseSocketLogger.logBytes(buffer.array(), buffer.arrayOffset(), buffer.limit());
    }
    public static void logBytes(byte[] bytes) {
        VerboseSocketLogger.logBytes(bytes, 0, bytes.length);
    }
    public static void logBytes(byte[] bytes, int offset, int length) {
        int end = offset + length;

        int lineByte = 0;

        for (; offset < end; offset++) {
            if (lineByte != 0) {
                if (lineByte == 16) {
                    System.out.println();
                    lineByte = 0;
                } else if (lineByte == 8) {
                    System.out.print(" | ");
                } else if (lineByte % 4 == 0) {
                    System.out.print("  ");
                } else {
                    System.out.print(' ');
                }
            }

            String hexString = Integer.toHexString(bytes[offset] & 0xff);

            if (hexString.length() == 1) System.out.print('0');

            System.out.print(hexString);

            lineByte++;
        }

        if (lineByte != 0) System.out.println();

        System.out.println("-----");
    }
}
