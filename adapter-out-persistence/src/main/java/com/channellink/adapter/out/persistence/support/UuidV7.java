package com.channellink.adapter.out.persistence.support;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();

        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long randA = ((randomBytes[0] & 0xFFL) << 4) | ((randomBytes[1] & 0xF0L) >> 4);
        long mostSigBits = (timestamp << 16) | (0x7L << 12) | (randA & 0x0FFFL);

        long randB = 0;
        for (int i = 2; i < 10; i++) {
            randB = (randB << 8) | (randomBytes[i] & 0xFFL);
        }
        long leastSigBits = (0b10L << 62) | (randB & 0x3FFFFFFFFFFFFFFFL);

        return new UUID(mostSigBits, leastSigBits);
    }
}
