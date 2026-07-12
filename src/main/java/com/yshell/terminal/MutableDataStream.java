package com.yshell.terminal;

import com.jediterm.terminal.TerminalDataStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可变数据流：把 SSH/PTY 读到的字节解码为 char，再喂给 JediTerm 解析器。
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>读线程（SSH InputStream）调用 {@link #append(byte[], int, int)} 把字节塞进 byteBuf</li>
 *   <li>JavaFX 线程（渲染定时器）调用 {@link #pump()} 把字节解码为 char 写入 charBuf</li>
 *   <li>JediTerm 在 JavaFX 线程上调用 {@link #getChar()} 消费 charBuf 里的数据</li>
 * </ul>
 */
public class MutableDataStream implements TerminalDataStream {

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 未解码的字节缓冲（ring buffer）
     */
    private byte[] byteBuf = new byte[16 * 1024];
    private int byteHead = 0;
    private int byteTail = 0;
    private int byteSize = 0;

    /**
     * 解码后的 char 缓冲（ring buffer），JediTerm 直接消费
     */
    private char[] charBuf = new char[16 * 1024];
    private int charHead = 0;
    private int charTail = 0;
    private int charSize = 0;
    private Charset charset = StandardCharsets.UTF_8;
    private CharsetDecoder decoder = newDecoder(charset);

    // ========================================================
    //  字节输入（读线程）
    // ========================================================

    /**
     * 追加从远端读到的原始字节（来自 SSH/PTY 读线程）。
     */
    public void append(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        lock.lock();
        try {
            ensureByteCapacity(length);
            int cap = byteBuf.length;
            for (int i = 0; i < length; i++) {
                byteBuf[byteTail] = data[offset + i];
                byteTail = (byteTail + 1) % cap;
                byteSize++;
            }
        } finally {
            lock.unlock();
        }
    }

    public void append(byte[] data) {
        if (data != null) append(data, 0, data.length);
    }

    public void setCharset(Charset charset) {
        lock.lock();
        try {
            this.charset = charset != null ? charset : StandardCharsets.UTF_8;
            this.decoder = newDecoder(this.charset);
        } finally {
            lock.unlock();
        }
    }

    // ========================================================
    //  字符解码（JavaFX 线程）
    // ========================================================

    /**
     * 解码 byteBuf 里的字节为 char 写入 charBuf，直到解码不出完整字符为止。
     * 应在 JavaFX 线程上调用，再让 JediTerm 消费。
     *
     * <p>解码器使用 REPLACE 错误策略：坏字节只替换自身，不能丢弃前面已经
     * 解出的 ESC/CSI 控制序列，否则 vim 等全屏程序的光标移动会失效。</p>
     */
    public void pump() {
        lock.lock();
        try {
            if (byteSize == 0) {
                return;
            }
            byte[] bytes = snapshotBytes();
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out = CharBuffer.allocate(Math.max(32, (int) Math.ceil(byteSize * decoder.maxCharsPerByte()) + 8));
            decoder.decode(in, out, false);
            out.flip();
            while (out.hasRemaining()) {
                enqueueChar(out.get());
            }
            int consumed = in.position();
            for (int i = 0; i < consumed; i++) {
                advanceByte();
            }
        } finally {
            lock.unlock();
        }
    }

    // ========================================================
    //  TerminalDataStream 接口实现（JediTerm 在解析时调用）
    // ========================================================

    private byte[] snapshotBytes() {
        byte[] bytes = new byte[byteSize];
        int cap = byteBuf.length;
        for (int i = 0; i < byteSize; i++) {
            bytes[i] = byteBuf[(byteHead + i) % cap];
        }
        return bytes;
    }

    private static CharsetDecoder newDecoder(Charset charset) {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    @Override
    public char getChar() throws IOException {
        lock.lock();
        try {
            if (charSize == 0) {
                throw new IOException("Empty stream");
            }
            char c = charBuf[charHead];
            charHead = (charHead + 1) % charBuf.length;
            charSize--;
            return c;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pushChar(char c) {
        // 推回一个 char：等价于在 head 之前插入
        lock.lock();
        try {
            ensureCharCapacity(1);
            charHead = (charHead - 1 + charBuf.length) % charBuf.length;
            charBuf[charHead] = c;
            charSize++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String readNonControlCharacters(int max) {
        lock.lock();
        try {
            int n = Math.min(max, charSize);
            int cap = charBuf.length;
            int idx = charHead;
            int consumed = 0;
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                char c = charBuf[idx];
                // 控制字符（C0 + DEL）截断
                if (c < 0x20 || c == 0x7F) break;
                sb.append(c);
                idx = (idx + 1) % cap;
                consumed++;
            }
            charHead = (charHead + consumed) % cap;
            charSize -= consumed;
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pushBackBuffer(char[] chars, int length) {
        if (chars == null || length <= 0) return;
        lock.lock();
        try {
            ensureCharCapacity(length);
            int cap = charBuf.length;
            for (int i = length - 1; i >= 0; i--) {
                charHead = (charHead - 1 + cap) % cap;
                charBuf[charHead] = chars[i];
                charSize++;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return charSize == 0;
        } finally {
            lock.unlock();
        }
    }

    // ========================================================
    //  内部辅助
    // ========================================================

    /**
     * 把 char 写入 charBuf 尾部（仅在持锁时调用）
     */
    private void enqueueChar(char c) {
        ensureCharCapacity(1);
        charBuf[charTail] = c;
        charTail = (charTail + 1) % charBuf.length;
        charSize++;
    }

    private void ensureByteCapacity(int additional) {
        if (byteSize + additional <= byteBuf.length) return;
        int newSize = Math.max(byteBuf.length * 2, byteSize + additional);
        byte[] nb = new byte[newSize];
        if (byteSize > 0) {
            if (byteHead < byteTail) {
                System.arraycopy(byteBuf, byteHead, nb, 0, byteSize);
            } else {
                int first = byteBuf.length - byteHead;
                System.arraycopy(byteBuf, byteHead, nb, 0, first);
                System.arraycopy(byteBuf, 0, nb, first, byteTail);
            }
        }
        byteBuf = nb;
        byteHead = 0;
        byteTail = byteSize;
    }

    private void ensureCharCapacity(int additional) {
        if (charSize + additional <= charBuf.length) return;
        int newSize = Math.max(charBuf.length * 2, charSize + additional);
        char[] nb = new char[newSize];
        if (charSize > 0) {
            if (charHead < charTail) {
                System.arraycopy(charBuf, charHead, nb, 0, charSize);
            } else {
                int first = charBuf.length - charHead;
                System.arraycopy(charBuf, charHead, nb, 0, first);
                System.arraycopy(charBuf, 0, nb, first, charTail);
            }
        }
        charBuf = nb;
        charHead = 0;
        charTail = charSize;
    }

    private void advanceByte() {
        byteHead = (byteHead + 1) % byteBuf.length;
        byteSize -= 1;
    }

}
