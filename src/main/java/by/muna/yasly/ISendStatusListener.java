package by.muna.yasly;

public interface ISendStatusListener {
    void onSent();
    void onConnectError(boolean gracefully);
}
