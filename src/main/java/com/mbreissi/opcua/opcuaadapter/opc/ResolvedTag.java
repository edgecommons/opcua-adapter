package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.opcua.opcuaadapter.opc.config.TagSpec;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * A tag that a subscription has bound to: the actual {@link NodeId} it resolved to (carrying the
 * current namespace index) plus the {@link TagSpec} that selected it. Used for the {@code
 * subscriptions} control query.
 */
public record ResolvedTag(NodeId nodeId, TagSpec spec) {
}
