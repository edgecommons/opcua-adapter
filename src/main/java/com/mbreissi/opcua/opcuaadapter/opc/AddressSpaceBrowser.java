package com.mbreissi.opcua.opcuaadapter.opc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import java.util.HashMap;
import java.util.Map;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/** Recursively browses an OPC UA server's address space and collects its variable nodes. */
public class AddressSpaceBrowser {

    private static final Logger LOGGER = LogManager.getLogger(AddressSpaceBrowser.class);

    private final OpcUaClient client;
    private final String instanceId;

    public AddressSpaceBrowser(OpcUaClient client, String instanceId) {
        this.client = client;
        this.instanceId = instanceId;
    }

    public Map<NodeId, UaVariableNode> browseAll() {
        Map<NodeId, UaVariableNode> nodes = new HashMap<>();
        LOGGER.info("[{}] browsing address space...", instanceId);
        browseFolder(NodeIds.RootFolder, nodes);
        LOGGER.info("[{}] browse complete: {} variable nodes", instanceId, nodes.size());
        return nodes;
    }

    private void browseFolder(NodeId folderNodeId, Map<NodeId, UaVariableNode> nodes) {
        BrowseDescription browse = new BrowseDescription(
                folderNodeId,
                BrowseDirection.Forward,
                NodeIds.References,
                true,
                uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()),
                uint(BrowseResultMask.All.getValue()));
        try {
            BrowseResult result = client.browse(browse);
            ReferenceDescription[] references = result.getReferences();
            if (references == null) {
                return;
            }
            for (ReferenceDescription rd : references) {
                rd.getNodeId().toNodeId(client.getNamespaceTable()).ifPresent(nodeId -> {
                    try {
                        if (rd.getNodeClass() == NodeClass.Object) {
                            browseFolder(nodeId, nodes);
                        } else if (rd.getNodeClass() == NodeClass.Variable) {
                            UaNode node = client.getAddressSpace().getNode(nodeId);
                            if (node instanceof UaVariableNode) {
                                nodes.put(nodeId, (UaVariableNode) node);
                            }
                        }
                    } catch (UaException e) {
                        LOGGER.trace("[{}] skipping node {}: {}", instanceId, nodeId, e.getMessage());
                    }
                });
            }
        } catch (UaException e) {
            LOGGER.warn("[{}] browse of {} failed: {}", instanceId, folderNodeId, e.getMessage());
        }
    }
}
