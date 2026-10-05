package tftp.common;

/**
 * Turns a byte stream into messages and messages back into bytes.
 * One instance per connection, since it keeps the partial message it has read so far.
 */
public interface MessageEncoderDecoder<T> {

    /** Feeds one byte from the stream. Returns a complete message, or null if more bytes are needed. */
    T decodeNextByte(byte nextByte);

    /** Returns the bytes to write for a message. */
    byte[] encode(T message);
}
