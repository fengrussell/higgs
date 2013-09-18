package io.higgs.hmq.protocol;

/**
 * @author Courtney Robinson <courtney@crlog.info>
 */
public enum SocketType {
    PAIR((byte) 0x00),
    PUB((byte) 0x01),
    SUB((byte) 0x02),
    REQ((byte) 0x03),
    REP((byte) 0x04),
    DEALER((byte) 0x05),
    ROUTER((byte) 0x06),
    PULL((byte) 0x07),
    PUSH((byte) 0x08);
    private byte val;

    SocketType(byte value) {
        val = value;
    }

    public byte getValue() {
        return val;
    }
}
