package com.conveyor.common.security;

/** ADR-5: the two roles the system knows about — read/write is not finer-grained than this. */
public final class ConveyorRoles {

  public static final String OPS = "OPS";
  public static final String ADMIN = "ADMIN";

  private ConveyorRoles() {}
}
