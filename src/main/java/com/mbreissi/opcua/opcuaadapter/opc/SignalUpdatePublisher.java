package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.ggcommons.GgInstance;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.facades.SignalUpdate;
import com.mbreissi.ggcommons.uns.UnsValidationException;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

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
 * (driven by the device's timer); otherwise each change publishes immediately.
 *
 * <p><b>Addressing (UNS §2.0).</b> Each update is published to
 * {@code ecv1/{device}/{component}/{instance}/data/{signalPath}}, minted by the facade's bound
 * {@code Uns} builder — never a hand-assembled string. {@code signalPath} is the signal's OPC UA bare
 * identifier sanitized to a single UNS channel token ({@link ConfigManager#sanitize}: {@code / \ + #}
 * and control chars → {@code _}, and {@code ..} collapsed) <b>before</b> it reaches the facade, so it
 * stays exactly one channel token even though the facade would otherwise split on {@code /} (the
 * sanitize pass removes any embedded slashes first, and is idempotent under the facade's own
 * second pass) — depth-safe by construction. The <b>stable</b> {@code signal.id} in the body (the
 * {@code ns=…;…=…} parseable form) remains what consumers key on; the sanitized path is only the
 * routing address. The top-level {@code identity} element is stamped automatically by the facade —
 * the site hierarchy rides there, not in the topic. OPC UA's own {@code StatusCode}-derived quality
 * ({@link ValueCodec#normalizeQuality}) is passed explicitly on every sample so the facade never
 * falls back to its {@code GOOD} default.
 */
public class SignalUpdatePublisher {

    private static final Logger LOGGER = LogManager.getLogger(SignalUpdatePublisher.class);

    private final GgInstance instance;
    private final ServerConfiguration serverConfig;
    private final NamespaceTable namespaceTable;
    private final Map<UaVariableNode, Buffer> pending = new ConcurrentHashMap<>();

    public SignalUpdatePublisher(GgInstance instance, ServerConfiguration serverConfig,
                                 NamespaceTable namespaceTable) {
        this.instance = instance;
        this.serverConfig = serverConfig;
        this.namespaceTable = namespaceTable;
    }

    /** Offer a value change for a signal — buffered (batch) or published immediately. */
    public void offer(UaVariableNode node, SignalSpec spec, DataValue value) {
        if (serverConfig.getBatchMs() > 0) {
            pending.computeIfAbsent(node, n -> new Buffer(spec)).queue.add(value);
        } else {
            publish(node, List.of(value));
        }
    }

    /** Flush all buffered values (one message per node). Called on the batch timer. */
    public void flush() {
        pending.forEach((node, buffer) -> {
            List<DataValue> values = new ArrayList<>();
            buffer.queue.drainTo(values);
            if (!values.isEmpty()) {
                publish(node, values);
            }
        });
    }

    private void publish(UaVariableNode node, List<DataValue> values) {
        if (values.isEmpty()) {
            return;
        }
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";

        String signalId = node.getNodeId().toParseableString();

        // Sanitize the OPC UA bare identifier into a single UNS channel token: it can legally contain
        // '/', long/GUID forms, etc. that the UNS token rule + IoT-Core depth guard would otherwise
        // reject at build time. Collapsing to one token here (before the facade's own sanitize pass,
        // which is idempotent) keeps the topic depth-safe by construction.
        String signalPath = ConfigManager.sanitize(node.getNodeId().getIdentifier().toString());

        List<SignalUpdate.Sample> samples = new ArrayList<>(values.size());
        for (DataValue value : values) {
            samples.add(ValueCodec.toSampleParts(value));
        }

        try {
            instance.data().signal(signalId)
                    .name(!displayName.isEmpty() ? displayName : browseName)
                    .address(ValueCodec.address(node.getNodeId(), namespaceTable))
                    .device("opcua", serverConfig.getId(), serverConfig.getConnection().getEndpoint())
                    .addSamples(samples)
                    .signalPath(signalPath)
                    .publish();
        } catch (UnsValidationException e) {
            // e.g. an identifier so long the total topic exceeds the IoT-Core 256-byte limit. Drop the
            // message rather than crash the flush loop; the stable signal.id still names it in a log.
            LOGGER.warn("[{}] dropping update for '{}': cannot mint a valid UNS data topic ({})",
                    serverConfig.getId(), signalId, e.getMessage());
        }
    }

    private static final class Buffer {
        final SignalSpec spec;
        final LinkedBlockingQueue<DataValue> queue = new LinkedBlockingQueue<>();

        Buffer(SignalSpec spec) {
            this.spec = spec;
        }
    }
}
