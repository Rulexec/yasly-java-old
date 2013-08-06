package by.muna.yasly;

import by.muna.yasly.SocketThread.SocketTask.SocketTaskConnect;
import by.muna.yasly.SocketThread.SocketTask.SocketTaskDisconnect;
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
            CONNECT, SEND, DISCONNECT
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
    void disconnect(SocketData socket) {
        SocketChannel channel = socket.getChannel();

        socket.close(true);

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
            while (!this.socketTasks.isEmpty()) {
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

                        newSocketChannel.register(this.selector, SelectionKey.OP_CONNECT);

                        newSocketChannel.connect(newConnectAddress);

                        newConnectSocketData.setChannel(newSocketChannel);
                        sockets.put(newSocketChannel, newConnectSocketData);
                    } catch (IOException e) {
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
                        sendSocketData.addSendData(sendTask.getData());
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

                    this.logger.onDisconnect(disconnectSocketData.getAddress(), true);

                    disconnectSocketData.getController().connectError(true);
                    break;
                default:
                    throw new RuntimeException("Unknown socket task: " + task.getType());
                }
            }

            selection: {
                try {
                    if (this.selector.select() == 0) break selection;
                } catch (IOException e) {
                    throw new RuntimeException("Selector select exception", e);
                }

                for (SelectionKey key : this.selector.selectedKeys()) {
                    SocketChannel sc = (SocketChannel) key.channel();
                    SocketData socketData = sockets.get(sc);

                    // needed for logging
                    InetSocketAddress address = socketData.getAddress();

                    try {
                        if (socketData.isClosedGracefully()) throw new SocketClosedException();

                        if (key.isConnectable() && sc.finishConnect()) {
                            this.logger.onConnect(address);

                            sc.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                        }

                        if (key.isWritable()) {
                            socketData.send();
                        }

                        if (key.isReadable()) {
                            socketData.receive();
                        }
                    } catch (IOException e) {
                        exceptionHappenedSockets.add(socketData);
                    }
                }
            }

            while (!exceptionHappenedSockets.isEmpty()) {
                SocketData socketData = exceptionHappenedSockets.poll();
                SocketChannel channel = socketData.getChannel();

                InetSocketAddress address = socketData.getAddress();

                boolean gracefully = socketData.isClosedGracefully();

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
}
