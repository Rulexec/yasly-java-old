package by.muna.yasly;

import by.muna.yasly.by.muna.yasly.exceptions.SocketClosedException;
import by.muna.yasly.logging.ISocketLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;

public class SocketController {
    private IConnectionListener connectListener;
    private IDataListener dataListener;

    private CountDownLatch connectionListenerSet = new CountDownLatch(1);
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

    void connected() {
        try {
            this.connectionListenerSet.await();
        } catch (InterruptedException e) { e.printStackTrace(); }

        this.connectListener.onConnected(this);
    }
    void connectError(boolean graceful) {
        if (this.connectErrorTelled) return;
        else this.connectErrorTelled = true;

        try {
            this.connectionListenerSet.await();
        } catch (InterruptedException e) { e.printStackTrace(); }

        this.connectListener.onConnectError(this, graceful);
    }

    boolean data(final SocketChannel sc) throws SocketClosedException {
        try {
            this.dataListenerSet.await();
        } catch (InterruptedException e) { e.printStackTrace(); }

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
                } catch (Exception e) {
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
        this.disconnect(true);
    }
    public void disconnect(boolean gracefully) {
        this.socketThread.disconnect(this.socket, gracefully);
    }

    public void setConnectionListener(IConnectionListener listener) {
        this.connectListener = listener;
        this.connectionListenerSet.countDown();
    }
    public void setOnData(IDataListener listener) {
        this.dataListener = listener;
        this.dataListenerSet.countDown();
    }
}
