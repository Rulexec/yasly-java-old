package by.muna.yasly;

import by.muna.yasly.SocketThread.SocketTask.SocketTaskConnect;
import by.muna.yasly.SocketThread.SocketTask.SocketTaskDisconnect;
import by.muna.yasly.SocketThread.SocketTask.SocketTaskExecute;
import by.muna.yasly.SocketThread.SocketTask.SocketTaskSend;
import by.muna.yasly.by.muna.yasly.exceptions.SocketClosedException;
import by.muna.yasly.logging.ISocketLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

public class SocketThread {
    protected static class SocketTask {
        public static enum SocketTaskType {
            CONNECT, SEND, DISCONNECT, EXECUTE
        }

        public static class SocketTaskConnect {
            private SocketData socket;

            public SocketTaskConnect(SocketData data) {
                this.socket = data;
            }

            public SocketData getSocket() {
                return this.socket;
            }
        }

        public static class SocketTaskSend {
            private SocketData socket;
            private SocketSendData data;

            public SocketTaskSend(SocketData socket, SocketSendData data) {
                this.socket = socket;
                this.data = data;
            }

            public SocketData getSocket() {
                return this.socket;
            }

            public SocketSendData getData() {
                return this.data;
            }
        }

        public static class SocketTaskDisconnect {
            private SocketData socket;

            public SocketTaskDisconnect(SocketData socket) {
                this.socket = socket;
            }

            public SocketData getSocket() {
                return this.socket;
            }
        }

        public static class SocketTaskExecute {
            private CancellableRunnable runnable;

            public SocketTaskExecute(CancellableRunnable runnable) {
                this.runnable = runnable;
            }

            public CancellableRunnable getRunnable() {
                return this.runnable;
            }
        }

        private SocketTaskType type;
        private Object task;

        public SocketTask(SocketTaskType type, Object data) {
            this.type = type;
            this.task = data;
        }
        public SocketTask(SocketTaskConnect task) {
            this(SocketTaskType.CONNECT, task);
        }
        public SocketTask(SocketTaskSend task) {
            this(SocketTaskType.SEND, task);
        }
        public SocketTask(SocketTaskDisconnect task) {
            this(SocketTaskType.DISCONNECT, task);
        }
        public SocketTask(SocketTaskExecute task) { this(SocketTaskType.EXECUTE, task); }

        public SocketTaskType getType() {
            return this.type;
        }
        public Object getTask() {
            return this.task;
        }
    }

    private Thread thread;

    private ConcurrentLinkedQueue<SocketTask> socketTasks = new ConcurrentLinkedQueue<SocketTask>();

    private CountDownLatch selectorCreated = new CountDownLatch(1);
    private Selector selector;

    private boolean isStop = false;

    private ISocketLogger logger;

    public SocketThread() {
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SocketThread.this.run();
            }
        });

        this.thread.start();

        try {
            this.selectorCreated.await();
        } catch (InterruptedException e) { e.printStackTrace(); }
    }

    public void setLogger(ISocketLogger logger) {
        this.logger = logger;
    }
    ISocketLogger getLogger() {
        return this.logger;
    }

    public SocketController connect(InetSocketAddress address) {
        SocketController controller = new SocketController(this);

        SocketData socketData = new SocketData(this, address, controller);
        controller.setSocket(socketData);

        this.socketTasks.add(new SocketTask(new SocketTaskConnect(socketData)));
        this.wakeupThread();

        return controller;
    }

    public void stop() {
        this.isStop = true;
        this.wakeupThread();

        try {
            this.thread.join();
        } catch (InterruptedException e) { e.printStackTrace(); }
    }

    public void execute(CancellableRunnable runnable) {
        this.socketTasks.add(new SocketTask(new SocketTaskExecute(runnable)));
        this.wakeupThread();
    }

    // method that called from SocketController
    void send(SocketData socket, IBufferProvider bufferProvider, ISendStatusListener statusListener) {
        SocketSendData sendData = new SocketSendData(bufferProvider, statusListener);

        if (socket.isClosed()) {
            statusListener.onConnectError(socket.isClosedGracefully());
        } else {
            this.socketTasks.add(new SocketTask(new SocketTaskSend(socket, sendData)));
        }

        this.wakeupThread();
    }
    void disconnect(SocketData socket, boolean gracefully) {
        SocketChannel channel = socket.getChannel();

        socket.close(gracefully);

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) { e.printStackTrace(); }
        }

        this.socketTasks.add(new SocketTask(new SocketTaskDisconnect(socket)));
        this.wakeupThread();
    }

    private void wakeupThread() {
        this.selector.wakeup();
    }

    private void run() {
        try {
            this.selector = Selector.open();
            this.selectorCreated.countDown();
        } catch (IOException e) {
            throw new RuntimeException("Selector open exception", e);
        }

        Map<SocketChannel, SocketData> sockets = new WeakHashMap<SocketChannel, SocketData>();
        Queue<SocketData> exceptionHappenedSockets = new LinkedList<SocketData>();

        while (!this.isStop) {
            while (!this.socketTasks.isEmpty() && !this.isStop) {
                SocketTask task = this.socketTasks.poll();

                switch (task.getType()) {
                case CONNECT:
                    SocketTaskConnect newConnectTask = (SocketTaskConnect) task.getTask();
                    SocketData newConnectSocketData = newConnectTask.getSocket();
                    InetSocketAddress newConnectAddress = newConnectSocketData.getAddress();

                    if (newConnectSocketData.isClosedGracefully()) break;

                    try {
                        SocketChannel newSocketChannel = SocketChannel.open();
                        newSocketChannel.configureBlocking(false);

                        int interest;

                        if (newSocketChannel.connect(newConnectAddress)) {
                            if (!newSocketChannel.isConnected()) {
                                throw new SocketClosedException();
                            } else {
                                interest = SelectionKey.OP_READ;
                            }
                        } else {
                            interest = SelectionKey.OP_CONNECT | SelectionKey.OP_READ;
                        }

                        SelectionKey selectionKey = newSocketChannel.register(this.selector, interest);
                        newConnectSocketData.setSelectionKey(selectionKey);

                        newConnectSocketData.setChannel(newSocketChannel);
                        sockets.put(newSocketChannel, newConnectSocketData);
                    } catch (IOException e) {
                        newConnectSocketData.closed();
                        exceptionHappenedSockets.add(newConnectSocketData);
                    }
                    break;
                case SEND:
                    SocketTaskSend sendTask = (SocketTaskSend) task.getTask();
                    SocketData sendSocketData = sendTask.getSocket();

                    if (sendSocketData.isClosed()) {
                        sendTask.getData().connectError(sendSocketData.isClosedGracefully());
                        break;
                    }

                    try {
                        if (!sendSocketData.addSendData(sendTask.getData())) {
                            SelectionKey key = sendSocketData.getSelectionKey();
                            if ((key.interestOps() & SelectionKey.OP_CONNECT) == 0) {
                                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                            }
                        }
                    } catch (IOException e) {
                        exceptionHappenedSockets.add(sendSocketData);
                    }
                    break;
                case DISCONNECT:
                    SocketTaskDisconnect disconnectTask = (SocketTaskDisconnect) task.getTask();
                    SocketData disconnectSocketData = disconnectTask.getSocket();

                    SocketChannel disconnectChannel = disconnectSocketData.getChannel();
                    if (disconnectChannel != null) {
                        try {
                            disconnectChannel.close();
                        } catch (IOException e) { e.printStackTrace(); }

                        sockets.remove(disconnectChannel);
                    }

                    this.logger.onDisconnect(disconnectSocketData.getAddress(), disconnectSocketData.isClosedGracefully());

                    disconnectSocketData.getController().connectError(true);
                    break;
                case EXECUTE:
                    CancellableRunnable runnable = ((SocketTaskExecute) task.getTask()).getRunnable();

                    runnable.run();

                    break;
                default:
                    throw new RuntimeException("Unknown socket task: " + task.getType());
                }
            }

            this.handleExceptionHappenedSockets(exceptionHappenedSockets, sockets);

            selection: {
                try {
                    if (this.selector.select() == 0) break selection;
                } catch (IOException e) {
                    throw new RuntimeException("Selector select exception", e);
                }

                Iterator<SelectionKey> keys = this.selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    SocketChannel sc = (SocketChannel) key.channel();
                    SocketData socketData = sockets.get(sc);

                    // needed for logging
                    InetSocketAddress address = socketData.getAddress();

                    try {
                        if (socketData.isClosedGracefully()) throw new SocketClosedException();

                        if ((key.interestOps() & SelectionKey.OP_CONNECT) != 0 && key.isConnectable() && sc.finishConnect()) {
                            this.logger.onConnect(address);

                            socketData.getController().connected();

                            int interest;

                            if (socketData.isSendQueueEmpty()) {
                                interest = SelectionKey.OP_READ;
                            } else {
                                interest = SelectionKey.OP_WRITE | SelectionKey.OP_READ;
                            }

                            key.interestOps(interest);
                        }

                        if (socketData.send()) {
                            key.interestOps(SelectionKey.OP_READ);
                        }

                        if (key.isReadable()) {
                            socketData.receive();
                        }
                    } catch (IOException e) {
                        exceptionHappenedSockets.add(socketData);
                    }
                }
            }

            this.handleExceptionHappenedSockets(exceptionHappenedSockets, sockets);
        }

        while (!this.socketTasks.isEmpty()) {
            SocketTask task = this.socketTasks.poll();

            switch (task.getType()) {
            case CONNECT:
                SocketTaskConnect newConnectTask = (SocketTaskConnect) task.getTask();
                SocketData newConnectSocketData = newConnectTask.getSocket();

                newConnectSocketData.getController().connectError(true);
                break;
            case SEND:
                SocketTaskSend sendTask = (SocketTaskSend) task.getTask();

                sendTask.getData().connectError(true);
                break;
            case DISCONNECT:
                SocketTaskDisconnect disconnectTask = (SocketTaskDisconnect) task.getTask();
                SocketData disconnectSocketData = disconnectTask.getSocket();

                SocketChannel disconnectChannel = disconnectSocketData.getChannel();
                if (disconnectChannel == null) {
                    disconnectSocketData.getController().connectError(true);
                } // else channel in sockets map and will be closed/notified
                break;
            case EXECUTE:
                CancellableRunnable runnable = ((SocketTaskExecute) task.getTask()).getRunnable();

                runnable.cancelled();

                break;
            }
        }

        for (SocketData socketData : sockets.values()) {
            SocketChannel channel = socketData.getChannel();

            try { channel.close(); } catch (IOException e) { e.printStackTrace(); }

            socketData.close(true);

            this.logger.onDisconnect(socketData.getAddress(), true);

            socketData.getController().connectError(true);
        }

        this.logger.onStop(this);
    }

    private void handleExceptionHappenedSockets(
        Queue<SocketData> exceptionHappenedSockets, Map<SocketChannel, SocketData> sockets
    ) {
        while (!exceptionHappenedSockets.isEmpty()) {
            SocketData socketData = exceptionHappenedSockets.poll();
            SocketChannel channel = socketData.getChannel();
            SelectionKey selectionKey = socketData.getSelectionKey();

            InetSocketAddress address = socketData.getAddress();

            boolean gracefully = socketData.isClosedGracefully();

            if (selectionKey != null) {
                selectionKey.cancel();
            }

            if (channel != null) {
                try { channel.close(); } catch (IOException ex) { ex.printStackTrace(); }
            }

            if (!gracefully) {
                this.logger.onDisconnect(address, false);

                socketData.getController().connectError(false);
            }

            socketData.close(false);

            sockets.remove(channel);
        }
    }
}
