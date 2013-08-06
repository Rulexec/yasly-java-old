package by.muna.yasly;

import java.nio.ByteBuffer;

class SocketSendData {
    private IBufferProvider bufferProvider;
    private ISendStatusListener statusListener;

    private ByteBuffer buffer;

    public SocketSendData(IBufferProvider bufferProvider, ISendStatusListener statusListener) {
        this.bufferProvider = bufferProvider;
        this.statusListener = statusListener;
    }

    ByteBuffer getBuffer() {
        if (this.buffer == null) this.buffer = this.bufferProvider.getBuffer();

        return this.buffer;
    }

    void sent() {
        this.statusListener.onSent();
    }
    void connectError(boolean gracefully) {
        this.statusListener.onConnectError(gracefully);
    }
}
