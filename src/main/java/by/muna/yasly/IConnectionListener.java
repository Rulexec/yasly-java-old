package by.muna.yasly;

public interface IConnectionListener {
    void onConnected(SocketController controller);
    void onConnectError(SocketController controller, boolean graceful);
}
