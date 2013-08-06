package by.muna.yasly;

import by.muna.yasly.by.muna.yasly.exceptions.SocketClosedException;
import by.muna.yasly.logging.ISocketLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;

public class SocketController {
    private IConnectErrorListener connectErrorListener;
    private IDataListener dataListener;

    private CountDownLatch connectErrorListenerSet = new CountDownLatch(1);
    private CountDownLatch dataListenerSet = new CountDownLatch(1);

    private SocketThread socketThread;
    private SocketData socket;

    private boolean connectErrorTelled = false;

    private ISocketLogger logger;

    public SocketController(SocketThread socketThread) {
        this.socketThread = socketThread;

        this.logger = this.socketThread.getLogger();
    }

    void setSocket(SocketData socket) {
        this.socket = socket;
    }

    void connectError(boolean graceful) {
        if (this.connectErrorTelled) return;
        else this.connectErrorTelled = true;

        try {
            this.connectErrorListenerSet.await();
        } catch (InterruptedException e) {}

        this.connectErrorListener.onConnectError(this, graceful);
    }

    boolean data(final SocketChannel sc) throws SocketClosedException {
        try {
            this.dataListenerSet.await();
        } catch (InterruptedException e) {}

        class SocketIndicator {
            boolean isClosed = false;
            boolean isReaded = false;
        }

        final SocketIndicator indicator = new SocketIndicator();

        this.dataListener.onData(this, new ISocketReadable() {
            @Override
            public int read(ByteBuffer buffer) {
                ByteBuffer loggingBuffer = buffer.slice();

                int readed;
                try {
                    readed = sc.read(buffer);
                } catch (IOException e) {
                    indicator.isClosed = true;
                    return 0;
                }

                if (readed != -1) {
                    loggingBuffer.limit(readed);
                    SocketController.this.logger.onReceived(SocketController.this.socket.getAddress(), loggingBuffer);

                    indicator.isReaded = true;
                    return readed;
                } else {
                    indicator.isClosed = true;
                    return 0;
                }
            }
        });

        if (indicator.isClosed) {
            throw new SocketClosedException();
        } else {
            return indicator.isReaded;
        }
    }

    public void send(IBufferProvider bufferProvider, ISendStatusListener listener) {
        this.socketThread.send(this.socket, bufferProvider, listener);
    }

    public void disconnect() {
        this.socketThread.disconnect(this.socket);
    }

    public void setOnConnectError(IConnectErrorListener listener) {
        this.connectErrorListenerSet.countDown();
        this.connectErrorListener = listener;
    }
    public void setOnData(IDataListener listener) {
        this.dataListenerSet.countDown();
        this.dataListener = listener;
    }
}
