package by.muna.yasly;

public interface IConnectErrorListener {
    void onConnectError(SocketController controller, boolean graceful);
}
