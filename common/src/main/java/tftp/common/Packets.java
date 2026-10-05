package tftp.common;

import java.nio.charset.StandardCharsets;

/**
 * Opcodes, error codes and builders for the protocol's packets.
 *
 * Every packet starts with a 2-byte big-endian opcode. Strings are UTF-8 and end with a 0 byte.
 *
 *   RRQ, WRQ, DELRQ, LOGRQ   | opcode | string | 0 |
 *   DATA                     | opcode | size (2) | block (2) | size bytes |
 *   ACK                      | opcode | block (2) |
 *   ERROR                    | opcode | error code (2) | message | 0 |
 *   BCAST                    | opcode | 0 = deleted, 1 = added | file name | 0 |
 *   DIRQ, DISC               | opcode |
 */
public final class Packets {

    public static final int RRQ = 1;
    public static final int WRQ = 2;
    public static final int DATA = 3;
    public static final int ACK = 4;
    public static final int ERROR = 5;
    public static final int DIRQ = 6;
    public static final int LOGRQ = 7;
    public static final int DELRQ = 8;
    public static final int BCAST = 9;
    public static final int DISC = 10;

    /** A DATA packet carries at most this many bytes. A shorter one ends the transfer. */
    public static final int MAX_DATA = 512;

    public static final int ERR_NOT_DEFINED = 0;
    public static final int ERR_FILE_NOT_FOUND = 1;
    public static final int ERR_ACCESS_VIOLATION = 2;
    public static final int ERR_DISK_FULL = 3;
    public static final int ERR_ILLEGAL_OPERATION = 4;
    public static final int ERR_FILE_EXISTS = 5;
    public static final int ERR_NOT_LOGGED_IN = 6;
    public static final int ERR_ALREADY_LOGGED_IN = 7;

    private Packets() {
    }

    public static int opcode(byte[] packet) {
        return u16(packet, 0);
    }

    /** Reads an unsigned big-endian 16-bit number. */
    public static int u16(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    /** Reads the 0-terminated string that starts at {@code from}. */
    public static String string(byte[] packet, int from) {
        int end = from;
        while (end < packet.length && packet[end] != 0)
            end++;
        return new String(packet, from, end - from, StandardCharsets.UTF_8);
    }

    /** RRQ, WRQ, DELRQ and LOGRQ: an opcode followed by one string. */
    public static byte[] request(int opcode, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[2 + bytes.length + 1];
        putU16(packet, 0, opcode);
        System.arraycopy(bytes, 0, packet, 2, bytes.length);
        return packet;
    }

    /** DIRQ and DISC: just the opcode. */
    public static byte[] bare(int opcode) {
        byte[] packet = new byte[2];
        putU16(packet, 0, opcode);
        return packet;
    }

    public static byte[] ack(int block) {
        byte[] packet = new byte[4];
        putU16(packet, 0, ACK);
        putU16(packet, 2, block);
        return packet;
    }

    public static byte[] data(int block, byte[] source, int offset, int length) {
        byte[] packet = new byte[6 + length];
        putU16(packet, 0, DATA);
        putU16(packet, 2, length);
        putU16(packet, 4, block);
        System.arraycopy(source, offset, packet, 6, length);
        return packet;
    }

    public static byte[] error(int code, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[4 + text.length + 1];
        putU16(packet, 0, ERROR);
        putU16(packet, 2, code);
        System.arraycopy(text, 0, packet, 4, text.length);
        return packet;
    }

    public static byte[] bcast(boolean added, String fileName) {
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[3 + name.length + 1];
        putU16(packet, 0, BCAST);
        packet[2] = (byte) (added ? 1 : 0);
        System.arraycopy(name, 0, packet, 3, name.length);
        return packet;
    }

    private static void putU16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >> 8);
        bytes[at + 1] = (byte) value;
    }
}
