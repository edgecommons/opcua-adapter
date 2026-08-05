package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.EdgeCommonsInstance;
import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.facades.SignalUpdate;
import com.mbreissi.edgecommons.uns.UnsValidationException;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Publishes the Tier-1 {@code SouthboundSignalUpdate} envelope (docs/SOUTHBOUND.md §2) for signal
 * value changes onto the UNS <b>{@code data}</b> class, through the library's {@code data()} publish
 * facade (DESIGN-class-facades §2.1) — the facade constructs + validates the body (device/signal/
 * samples), applies the quality/timestamp defaulting, mints the topic, and stamps the envelope
 * identity; this class only maps OPC UA reads onto the facade's {@code SignalUpdate} builder. When
 * {@code batchMs > 0}, value changes are buffered per node and flushed together by {@link #flush()}
 * (driven by the device's scheduler); otherwise each change publishes immediately.
 *
 * <p><b>Addressing (UNS §2.0).</b> Each update is published to
 * {@code ecv1/{device}/{component}/{instance}/data/{signalPath}}, minted by the facade's bound
 * {@code Uns} builder — never a hand-assembled string. {@code signalPath} is the signal's
 * {@link CanonicalSignalId#channelToken()} — a namespace discriminator, the identifier type, and the
 * identifier — sanitized to a single UNS channel token ({@link ConfigManager#sanitize}) <b>before</b>
 * it reaches the facade, so it stays exactly one channel token even though the facade would otherwise
 * split on {@code /}. Discriminating by namespace and id type is what keeps two distinct signals off
 * one topic. The <b>stable</b> {@code signal.id} in the body (the canonical {@code nsu=…} form) remains
 * what consumers key on; the sanitized path is only the routing address. The top-level {@code identity}
 * element is stamped automatically by the facade — the site hierarchy rides there, not in the topic.
 * OPC UA's own {@code StatusCode}-derived quality ({@link ValueCodec#normalizeQuality}) is passed
 * explicitly on every sample so the facade never falls back to its {@code GOOD} default.
 *
 * <p><b>Backpressure.</b> Each signal's buffer is bounded. A consumer that stops draining — a wedged
 * broker, a slow flush — cannot grow the buffer without limit; the oldest samples are discarded and
 * counted on the operational {@code OpcUaSubscription.DroppedSample} measures, so the loss is visible
 * rather than silent. A publish failure re-buffers the batch instead of discarding it.
 */
public class SignalUpdatePublisher {

    private static final Logger LOGGER = LogManager.getLogger(SignalUpdatePublisher.class);

    private final EdgeCommonsInstance instance;
    private final ServerConfiguration serverConfig;
    private final NamespaceTable namespaceTable;
    private final HealthState health;
    private final ClientMetrics counters;
    private final int maxBufferedSamples;
    private final Map<UaVariableNode, Buffer> pending = new ConcurrentHashMap<>();

    public SignalUpdatePublisher(EdgeCommonsInstance instance, ServerConfiguration serverConfig,
                                 NamespaceTable namespaceTable, HealthState health, ClientMetrics counters) {
        this.instance = instance;
        this.serverConfig = serverConfig;
        this.namespaceTable = namespaceTable;
        this.health = health;
        this.counters = counters;
        this.maxBufferedSamples = serverConfig.getLimits().getMaxBufferedSamples();
    }

    /**
     * Offer a value change for a signal — buffered (batch) or published immediately. The
     * adapter-receive moment ({@code receivedTs}, SOUTHBOUND.md §2's four-slot model) is captured
     * <b>here</b>, not at publish — under batching the two diverge. While the instance is paused
     * ({@code sb/pause}), value changes are dropped rather than published, so no telemetry leaves
     * the adapter while it is suspended.
     */
    public void offer(UaVariableNode node, SignalSpec spec, DataValue value) {
        if (health != null && health.isPaused()) {
            return;
        }
        Received received = new Received(value, Instant.now());
        if (serverConfig.getBatchMs() > 0) {
            Buffer buffer = pending.computeIfAbsent(node, n -> new Buffer(spec, capacityFor(spec)));
            enqueue(buffer, received);
        } else {
            publish(node, List.of(received));
        }
    }

    /**
     * The per-signal buffer capacity: generous relative to the monitored-item queue the server is
     * asked to keep, but finite.
     */
    private int capacityFor(SignalSpec spec) {
        return Math.max(spec.getQueueSize() * 4, maxBufferedSamples);
    }

    /** Add a sample, discarding the oldest (and counting it) when the buffer is at capacity. */
    private void enqueue(Buffer buffer, Received received) {
        long dropped = 0;
        while (!buffer.queue.offer(received)) {
            if (buffer.queue.poll() == null) {
                break;
            }
            dropped++;
        }
        if (dropped > 0 && counters != null) {
            counters.recordDroppedSamples(dropped);
            LOGGER.warn("[{}] publish buffer full; dropped {} oldest sample(s)", serverConfig.getId(), dropped);
        }
    }

    /**
     * Flush all buffered values (one message per node). Called on the device tick. A no-op while
     * paused. Each node is flushed independently so one signal's failure cannot abort the sweep.
     */
    public void flush() {
        if (health != null && health.isPaused()) {
            return;
        }
        pending.forEach((node, buffer) -> {
            List<Received> values = new ArrayList<>();
            buffer.queue.drainTo(values);
            if (values.isEmpty()) {
                return;
            }
            try {
                publish(node, values);
            } catch (RuntimeException e) {
                // A transport-level failure: put the batch back rather than losing it, up to the bound.
                LOGGER.warn("[{}] publish failed, re-buffering {} sample(s): {}",
                        serverConfig.getId(), values.size(), e.toString());
                requeue(buffer, values);
            }
        });
    }

    /** Return an unpublished batch to the front of its buffer, honouring the capacity bound. */
    private void requeue(Buffer buffer, List<Received> values) {
        long dropped = 0;
        for (Received received : values) {
            if (!buffer.queue.offer(received)) {
                dropped++;
            }
        }
        if (dropped > 0 && counters != null) {
            counters.recordDroppedSamples(dropped);
        }
    }

    private void publish(UaVariableNode node, List<Received> values) {
        if (values.isEmpty()) {
            return;
        }
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";

        CanonicalSignalId canonicalId;
        try {
            canonicalId = CanonicalSignalId.of(node.getNodeId(), namespaceTable);
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] dropping update for {}: {}", serverConfig.getId(), node.getNodeId(), e.getMessage());
            return;
        }
        String signalId = canonicalId.toString();

        // Sanitize the canonical channel token into a single UNS channel token: the identifier can
        // legally contain '/', long/GUID forms, etc. that the UNS token rule + IoT-Core depth guard
        // would otherwise reject at build time. Collapsing to one token here (before the facade's own
        // sanitize pass, which is idempotent) keeps the topic depth-safe by construction.
        String signalPath = ConfigManager.sanitize(canonicalId.channelToken());

        List<SignalUpdate.Sample> samples = new ArrayList<>(values.size());
        for (Received received : values) {
            samples.add(ValueCodec.toSampleParts(received.value(), received.receivedAt()));
        }

        try {
            long started = System.nanoTime();
            instance.data().signal(signalId)
                    .name(!displayName.isEmpty() ? displayName : browseName)
                    .address(ValueCodec.address(node.getNodeId(), namespaceTable))
                    .device("opcua", serverConfig.getId(), serverConfig.getConnection().getEndpoint())
                    .addSamples(samples)
                    .signalPath(signalPath)
                    .publish();
            if (health != null) {
                // Feed southbound_health: the publish round-trip, and the staleness tracker (a signal
                // that keeps publishing is not stale). Keyed on the stable signal.id, as seeded.
                health.setPublishLatencyMs(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
                health.onSignalUpdate(signalId, System.nanoTime());
            }
        } catch (UnsValidationException e) {
            // e.g. an identifier so long the total topic exceeds the IoT-Core 256-byte limit. The
            // message can never become valid by retrying, so drop it rather than re-buffer forever;
            // the stable signal.id still names it in a log.
            LOGGER.warn("[{}] dropping update for '{}': cannot mint a valid UNS data topic ({})",
                    serverConfig.getId(), signalId, e.getMessage());
        }
    }

    /** One received value change: the OPC UA {@link DataValue} + the adapter-receive moment. */
    private record Received(DataValue value, Instant receivedAt) {
    }

    private static final class Buffer {
        final SignalSpec spec;
        final LinkedBlockingQueue<Received> queue;

        Buffer(SignalSpec spec, int capacity) {
            this.spec = spec;
            this.queue = new LinkedBlockingQueue<>(Math.max(1, capacity));
        }
    }
}
