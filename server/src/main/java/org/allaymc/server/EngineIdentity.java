package org.allaymc.server;

/**
 * Central branding for the Engine distribution. Legacy Allay names stay in
 * Java packages and public APIs so existing plugins can link unchanged.
 */
public final class EngineIdentity {
    public static final String NAME = "Engine";
    public static final String DEFAULT_MOTD = "Engine Bedrock Server";
    public static final String DEFAULT_SUB_MOTD = "Engine by RyzeMCBE";

    private EngineIdentity() {
    }
}
