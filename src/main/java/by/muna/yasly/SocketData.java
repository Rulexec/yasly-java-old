package by.muna.yasly;

import by.muna.yasly.by.muna.yasly.exceptions.SocketClosedException;
import by.muna.yasly.logging.ISocketLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

class SocketData {
    private InetSocketAddress address;
    private SocketController controller;
    private SocketChannel channel;
    private boolean closed = false;
    private boolean closedGracefully = false;

    private Queue<SocketSendData> sendQueue = new LinkedList<SocketSendData>();

    private ISocketLogger logger;
    private ByteBuffer loggingBuffer;

    public SocketData(SocketThread socketThread, InetSocketAddress address, SocketController controller) {
        this.address = address;
        this.controller = controller;

        this.logger = socketThread.getLogger();
    }

    void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    boolean isClosed() {
        return this.closed;
    }
    boolean isClosedGracefully() {
        return this.closedGracefully;
    }

    void close(boolean gracefully) {
        this.closed = true;
        if (gracefully) this.closedGracefully = true;

        while (!this.sendQueue.isEmpty()) {
            SocketSendData data = this.sendQueue.poll();

            data.connectError(gracefully);
        }
    }
    void addSendData(SocketSendData sendData) throws IOException {
        this.sendQueue.add(sendData);
        this.send();
    }
    void send() throws IOException {
        while (!this.sendQueue.isEmpty()) {
            SocketSendData data = this.sendQueue.peek();

            ByteBuffer buffer = data.getBuffer();

            if (buffer == null) {
                // if IBufferProvider returns null, it means, that user don't want send packet.
                data.sent();

                this.sendQueue.poll();
                continue;
            }

            if (this.loggingBuffer == null) {
                this.loggingBuffer = buffer.slice();
                this.loggingBuffer.limit(buffer.limit() - buffer.position());
            }

            int writed;

            try {
                writed = this.channel.write(buffer);
            } catch (NotYetConnectedException e) {
                break;
            }

            // will be called .close(false)
            if (writed == -1) throw new SocketClosedException();

            if (!buffer.hasRemaining()) {
                this.sendQueue.poll();

                this.logger.onSent(this.address, this.loggingBuffer);
                this.loggingBuffer = null;

                data.sent();
            }

            if (writed == 0) break;
        }
    }
    void receive() throws SocketClosedException {
        this.getController().data(this.channel);
    }

    SocketController getController() {
        return this.controller;
    }
    SocketChannel getChannel() {
        return this.channel;
    }
    InetSocketAddress getAddress() {
        return this.address;
    }
}
