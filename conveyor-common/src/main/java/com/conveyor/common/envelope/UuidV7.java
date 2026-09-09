package com.conveyor.common.envelope;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7: a 48-bit big-endian Unix millisecond timestamp followed by random bits.
 * Time-ordered so that any index keyed on it (the inbox table's primary key, in particular —
 * ARCHITECTURE.md §6.1) stays append-friendly instead of randomly distributed the way UUIDv4 would
 * leave it.
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID generate() {
    long ts = System.currentTimeMillis() & 0xFFFFFFFFFFFFL; // 48-bit, big-endian

    byte[] rand = new byte[10];
    RANDOM.nextBytes(rand);

    int randA = ((rand[0] & 0x0F) << 8) | (rand[1] & 0xFF); // 12 random bits
    long msb = (ts << 16) | (0x7L << 12) | randA; // timestamp | version 7 | rand_a

    long randB = 0L;
    for (int i = 0; i < 8; i++) {
      randB = (randB << 8) | (rand[2 + i] & 0xFFL);
    }
    // variant 10 in the top 2 bits, 62 random bits (rand_b) in the rest.
    long lsb = 0x8000000000000000L | (randB & 0x3FFFFFFFFFFFFFFFL);

    return new UUID(msb, lsb);
  }
}
