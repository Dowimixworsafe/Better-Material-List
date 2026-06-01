package com.example.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Central channel and packet-type definitions for BML.
 * ALL network messages go through a single "bml:sync" channel; the message type is
 * discriminated by the JSON "type" field.
 *
 * Uses the Fabric Networking API (MC 1.21+) based on CustomPacketPayload.
 */
public class BmlPackets {

    // ── Channel id ─────────────────────────────────────────────────────────────
    public static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("bml", "sync");

    /**
     * Data protocol version. Bumped whenever the meaning/shape of sync fields changes.
     * v2: chests are global (keyed by containerId), checkedItems keyed by a stable
     * "checklistKey" (sorted enabled-placement names) — incompatible with v1.
     */
    public static final String PROTOCOL_VERSION = "2";

    // ── Handshake ──────────────────────────────────────────────────────────────
    public static final String BML_HELLO     = "BML_HELLO";
    public static final String BML_HELLO_ACK = "BML_HELLO_ACK";

    // ── Party management ─────────────────────────────────────────────────────────
    public static final String PARTY_INVITE        = "PARTY_INVITE";
    public static final String PARTY_INVITE_NOTIFY = "PARTY_INVITE_NOTIFY";
    public static final String PARTY_ACCEPT        = "PARTY_ACCEPT";
    public static final String PARTY_LEAVE         = "PARTY_LEAVE";
    public static final String PARTY_UPDATE        = "PARTY_UPDATE";
    public static final String PARTY_ERROR         = "PARTY_ERROR";

    // ── Data sync ────────────────────────────────────────────────────────────────
    public static final String SYNC_CHECKED    = "SYNC_CHECKED";
    public static final String SYNC_CONTAINER  = "SYNC_CONTAINER";
    public static final String SYNC_CONTAINER_MARKED = "SYNC_CONTAINER_MARKED";
    public static final String SYNC_FULL_STATE = "SYNC_FULL_STATE";
    public static final String SYNC_PLACEMENT  = "SYNC_PLACEMENT";
    public static final String SYNC_PLACEMENT_REQUEST = "SYNC_PLACEMENT_REQUEST";
    public static final String PARTY_KICK         = "PARTY_KICK";
    public static final String PARTY_TARGET_UPDATE = "PARTY_TARGET_UPDATE";

    // ── CustomPacketPayload ───────────────────────────────────────────────────

    /**
     * Simple payload carrying raw bytes (JSON as UTF-8).
     * One payload type for all BML packets.
     */
    public record BmlPayload(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<BmlPayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL_ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, BmlPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeByteArray(payload.data()),
            buf -> new BmlPayload(buf.readByteArray())
        );

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
