package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolException;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.editor.diagram.ArchimateDiagramModelFactory;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.viewpoints.IViewpoint;
import com.archimatetool.model.viewpoints.ViewpointManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tools creating and filling views (diagrams).
 */
final class ViewTools {

    private ViewTools() {}

    /** Grid used to place nodes when no position is given. */
    private static final int GRID_X = 160;
    private static final int GRID_Y = 100;
    private static final int GRID_COLUMNS = 5;
    private static final int MARGIN = 24;

    static void register(ToolRegistry registry) {
        registry.register(new Tool("create_view", "Create view",
                "Creates an empty ArchiMate view (diagram), optionally with a viewpoint.",
                Schema.object()
                        .string("name", "View name.", true)
                        .string("model_id", "Model id or name. Optional when a single model is open.", false)
                        .string("viewpoint", "Optional viewpoint id, e.g. application_cooperation (see list_element_types).", false)
                        .string("documentation", "Optional documentation.", false)
                        .string("folder_id", "Optional sub-folder of the Views folder.", false)
                        .build(),
                false, ViewTools::createView));

        registry.register(new Tool("add_to_view", "Add element to view",
                "Adds an element to a view as a node, optionally nested in another node. By default it also draws "
                        + "the element's relationships with the elements already on the view.",
                Schema.object()
                        .string("view_id", "View id.", true)
                        .string("element_id", "Element id.", true)
                        .integer("x", "X position (relative to the parent). Omit to place it automatically.", false)
                        .integer("y", "Y position (relative to the parent). Omit to place it automatically.", false)
                        .integer("width", "Width (default: Archi's default size).", false)
                        .integer("height", "Height (default: Archi's default size).", false)
                        .string("parent_node_id", "Optional node to nest the new node into (a group or an element node).", false)
                        .bool("add_connections", "Draw existing relationships with elements already on the view (default true).", false)
                        .build(),
                false, ViewTools::addToView));

        registry.register(new Tool("add_relationship_to_view", "Draw relationship on view",
                "Draws an existing relationship on a view, between nodes showing its source and target.",
                Schema.object()
                        .string("view_id", "View id.", true)
                        .string("relationship_id", "Relationship id.", true)
                        .string("source_node_id", "Optional source node (when the element appears several times).", false)
                        .string("target_node_id", "Optional target node (when the element appears several times).", false)
                        .build(),
                false, ViewTools::addRelationshipToView));

        registry.register(new Tool("open_view", "Open view in Archi",
                "Opens a view in Archi's diagram editor so the user can see it.",
                Schema.object()
                        .string("view_id", "View id.", true)
                        .build(),
                true, ViewTools::openView));
    }

    private static ToolResult createView(Arguments args) throws Exception {
        String name = args.string("name");
        String modelId = args.optString("model_id", null);
        String viewpoint = args.optString("viewpoint", null);
        String documentation = args.optString("documentation", null);
        String folderId = args.optString("folder_id", null);

        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = ArchiAccess.model(modelId);
            IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
            ArchiAccess.ensureId(view);
            view.setName(name);
            if (viewpoint != null && !viewpoint.isBlank()) {
                view.setViewpoint(resolveViewpoint(viewpoint));
            }
            if (documentation != null) {
                view.setDocumentation(documentation);
            }
            IFolder folder = EditTools.targetFolder(model, view, folderId);
            ArchiAccess.execute(model, new ModelCommand("Create view " + name,
                    () -> folder.getElements().add(view),
                    () -> folder.getElements().remove(view)));
            return ToolResult.json(ArchiJson.summary(view));
        });
    }

    private static String resolveViewpoint(String viewpoint) throws ToolException {
        List<String> ids = new ArrayList<>();
        for (IViewpoint vp : ViewpointManager.INSTANCE.getAllViewpoints()) {
            if (vp.getID().equalsIgnoreCase(viewpoint) || vp.getName().equalsIgnoreCase(viewpoint)) {
                return vp.getID();
            }
            ids.add(vp.getID());
        }
        throw new ToolException("Unknown viewpoint '" + viewpoint + "'. Valid ids: " + String.join(", ", ids));
    }

    private static ToolResult addToView(Arguments args) throws Exception {
        String viewId = args.string("view_id");
        String elementId = args.string("element_id");
        Integer x = args.optInteger("x");
        Integer y = args.optInteger("y");
        Integer width = args.optInteger("width");
        Integer height = args.optInteger("height");
        String parentNodeId = args.optString("parent_node_id", null);
        boolean addConnections = args.optBoolean("add_connections", true);

        return ArchiAccess.onUiThread(() -> {
            IArchimateDiagramModel view = ArchiAccess.object(viewId, null, IArchimateDiagramModel.class, "ArchiMate view");
            IArchimateModel model = view.getArchimateModel();
            IArchimateElement element = ArchiAccess.object(elementId, model.getId(), IArchimateElement.class, "element");
            if (!ViewpointManager.INSTANCE.isAllowedConceptForDiagramModel(view, element.eClass())) {
                throw new ToolException(element.eClass().getName() + " is not allowed in view '" + view.getName()
                        + "' because of its viewpoint (" + view.getViewpoint() + ")");
            }

            IDiagramModelContainer parent = view;
            if (parentNodeId != null && !parentNodeId.isBlank()) {
                IDiagramModelObject parentNode = node(view, parentNodeId);
                if (!(parentNode instanceof IDiagramModelContainer container)) {
                    throw new ToolException("Node '" + parentNodeId + "' cannot contain other nodes");
                }
                parent = container;
            }

            IDiagramModelArchimateObject node = ArchimateDiagramModelFactory.createDiagramModelArchimateObject(element);
            ArchiAccess.ensureId(node);
            IBounds defaults = node.getBounds();
            int index = parent.getChildren().size();
            int nx = x != null ? x : MARGIN + (index % GRID_COLUMNS) * GRID_X;
            int ny = y != null ? y : MARGIN + (index / GRID_COLUMNS) * GRID_Y;
            int nw = width != null ? width : defaults != null ? defaults.getWidth() : -1;
            int nh = height != null ? height : defaults != null ? defaults.getHeight() : -1;
            node.setBounds(nx, ny, nw, nh);

            // Connections to the nodes of related elements already on the view
            List<Link> links = new ArrayList<>();
            if (addConnections) {
                for (IArchimateRelationship r : element.getSourceRelationships()) {
                    for (IDiagramModelArchimateObject other : nodesOf(view, r.getTarget())) {
                        links.add(new Link(newConnection(r), node, other));
                    }
                }
                for (IArchimateRelationship r : element.getTargetRelationships()) {
                    for (IDiagramModelArchimateObject other : nodesOf(view, r.getSource())) {
                        links.add(new Link(newConnection(r), other, node));
                    }
                }
            }

            IDiagramModelContainer container = parent;
            ArchiAccess.execute(model, new ModelCommand("Add " + element.getName() + " to view",
                    () -> {
                        container.getChildren().add(node);
                        links.forEach(Link::connect);
                    },
                    () -> {
                        links.forEach(l -> l.connection.disconnect());
                        container.getChildren().remove(node);
                    }));

            JsonObject result = ArchiJson.node(node);
            JsonArray connections = new JsonArray();
            links.forEach(l -> connections.add(ArchiJson.connection(l.connection)));
            result.add("connections_added", connections);
            return ToolResult.json(result);
        });
    }

    private static ToolResult addRelationshipToView(Arguments args) throws Exception {
        String viewId = args.string("view_id");
        String relationshipId = args.string("relationship_id");
        String sourceNodeId = args.optString("source_node_id", null);
        String targetNodeId = args.optString("target_node_id", null);

        return ArchiAccess.onUiThread(() -> {
            IArchimateDiagramModel view = ArchiAccess.object(viewId, null, IArchimateDiagramModel.class, "ArchiMate view");
            IArchimateModel model = view.getArchimateModel();
            IArchimateRelationship relationship = ArchiAccess.object(relationshipId, model.getId(), IArchimateRelationship.class, "relationship");

            IDiagramModelArchimateObject source = endNode(view, sourceNodeId, relationship, true);
            IDiagramModelArchimateObject target = endNode(view, targetNodeId, relationship, false);
            for (IDiagramModelConnection c : source.getSourceConnections()) {
                if (c.getTarget() == target && c instanceof IDiagramModelArchimateConnection ac
                        && ac.getArchimateRelationship() == relationship) {
                    throw new ToolException("This relationship is already drawn between these nodes (connection " + c.getId() + ")");
                }
            }

            Link link = new Link(newConnection(relationship), source, target);
            ArchiAccess.execute(model, new ModelCommand("Add relationship to view",
                    link::connect,
                    () -> link.connection.disconnect()));
            return ToolResult.json(ArchiJson.connection(link.connection));
        });
    }

    private static ToolResult openView(Arguments args) throws Exception {
        String viewId = args.string("view_id");
        return ArchiAccess.onUiThread(() -> {
            IDiagramModel view = ArchiAccess.object(viewId, null, IDiagramModel.class, "view");
            EditorManager.openDiagramEditor(view);
            return ToolResult.text("Opened view '" + view.getName() + "' in Archi");
        });
    }

    private static IDiagramModelArchimateObject endNode(IArchimateDiagramModel view, String nodeId,
            IArchimateRelationship relationship, boolean isSource) throws ToolException {
        EObject end = isSource ? relationship.getSource() : relationship.getTarget();
        String label = isSource ? "source" : "target";
        if (nodeId != null && !nodeId.isBlank()) {
            if (node(view, nodeId) instanceof IDiagramModelArchimateObject n && n.getArchimateElement() == end) {
                return n;
            }
            throw new ToolException("Node '" + nodeId + "' does not show the relationship's " + label);
        }
        List<IDiagramModelArchimateObject> nodes = nodesOf(view, end);
        if (nodes.isEmpty()) {
            throw new ToolException("The relationship's " + label + " (" + ArchiJson.ref(end) + ") is not on the view: add it with add_to_view first");
        }
        return nodes.get(0);
    }

    private static IDiagramModelObject node(IDiagramModel view, String nodeId) throws ToolException {
        for (Iterator<EObject> it = view.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IDiagramModelObject o && nodeId.equals(o.getId())) {
                return o;
            }
        }
        throw new ToolException("No node '" + nodeId + "' in view '" + view.getName() + "' (see get_view)");
    }

    private static List<IDiagramModelArchimateObject> nodesOf(IDiagramModel view, EObject element) {
        List<IDiagramModelArchimateObject> nodes = new ArrayList<>();
        for (Iterator<EObject> it = view.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IDiagramModelArchimateObject o && o.getArchimateElement() == element) {
                nodes.add(o);
            }
        }
        return nodes;
    }

    private static IDiagramModelArchimateConnection newConnection(IArchimateRelationship relationship) {
        IDiagramModelArchimateConnection connection = ArchimateDiagramModelFactory.createDiagramModelArchimateConnection(relationship);
        ArchiAccess.ensureId(connection);
        return connection;
    }

    private record Link(IDiagramModelArchimateConnection connection, IConnectable source, IConnectable target) {
        void connect() {
            connection.connect(source, target);
        }
    }
}
