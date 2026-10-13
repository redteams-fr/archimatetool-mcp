package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolException;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Creation of several elements and relationships in one call, as a single undoable command.
 */
final class BatchTools {

    private BatchTools() {}

    static void register(ToolRegistry registry) {
        registry.register(new Tool("create_batch", "Create elements and relationships",
                "Creates several elements and relationships in one call, as ONE undoable command, and optionally shows them on a view. "
                        + "Give each element a local 'ref' (e.g. \"crm\"): a relationship's source and target are a ref of this call "
                        + "or the id of an existing element or relationship. A relationship can also point to a relationship listed before it. "
                        + "Everything is checked before anything is created: if one item is invalid, nothing is created and the error names it "
                        + "(e.g. relationships[2]). Search first (search_elements) to reuse existing elements instead of creating duplicates.",
                Schema.object()
                        .string("model_id", "Model id or name. Optional when a single model is open or when view_id is given.", false)
                        .objectArray("elements", "Elements to create.", Schema.object()
                                .string("ref", "Local reference that relationships of this call can use as source or target.", false)
                                .string("type", "Element type, e.g. BusinessActor, ApplicationComponent, Node (see list_element_types).", true)
                                .string("name", "Element name.", true)
                                .string("documentation", "Optional documentation.", false)
                                .stringMap("properties", "Optional properties as {key: value}.")
                                .string("folder_id", "Optional sub-folder id; defaults to the folder of the element's layer.", false)
                                .string("parent", "With view_id: nest the node in a container, given as the ref of an element "
                                        + "listed before (e.g. a Grouping of this call) or the id of a node already on the view.", false)
                                .integer("x", "With view_id: X position, relative to the parent. Omit to place it automatically.", false)
                                .integer("y", "With view_id: Y position, relative to the parent. Omit to place it automatically.", false)
                                .integer("width", "With view_id: node width (default: Archi's default size).", false)
                                .integer("height", "With view_id: node height (default: Archi's default size).", false),
                                false)
                        .objectArray("relationships", "Relationships to create, validated against the ArchiMate rules.", Schema.object()
                                .string("ref", "Optional local reference, so that a later relationship can point to this one.", false)
                                .string("type", "Relationship type, e.g. Serving, Assignment, Realization, Flow, Composition.", true)
                                .string("source", "A ref of this call, or the id of an existing element or relationship.", true)
                                .string("target", "A ref of this call, or the id of an existing element or relationship.", true)
                                .string("name", "Optional name.", false)
                                .string("documentation", "Optional documentation.", false)
                                .stringMap("properties", "Optional properties as {key: value}."),
                                false)
                        .string("view_id", "Optional view: the new elements are added to it, and the new relationships are drawn "
                                + "when both ends are on it (existing elements are not added: use add_to_view for them).", false)
                        .build(),
                false, BatchTools::createBatch));
    }

    private record NewElement(String ref, IArchimateElement element, IFolder folder,
            String parent, Integer x, Integer y, Integer width, Integer height) {}

    private record NewNode(String ref, IDiagramModelArchimateObject node, IDiagramModelContainer container) {}

    private record NewRelationship(String ref, IArchimateRelationship relationship, IArchimateConcept source,
            IArchimateConcept target, IFolder folder) {}

    private static ToolResult createBatch(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        String viewId = args.optString("view_id", null);
        List<Arguments> elementArgs = args.optObjectArray("elements");
        List<Arguments> relationshipArgs = args.optObjectArray("relationships");
        List<Arguments> elementItems = elementArgs != null ? elementArgs : List.of();
        List<Arguments> relationshipItems = relationshipArgs != null ? relationshipArgs : List.of();
        if (elementItems.isEmpty() && relationshipItems.isEmpty()) {
            throw new ToolException("Nothing to create: pass elements and/or relationships");
        }

        return ArchiAccess.onUiThread(() -> {
            IArchimateDiagramModel view = null;
            IArchimateModel model;
            if (viewId != null && !viewId.isBlank()) {
                String scope = modelId != null && !modelId.isBlank() ? ArchiAccess.model(modelId).getId() : null;
                view = ArchiAccess.object(viewId, scope, IArchimateDiagramModel.class, "ArchiMate view");
                model = view.getArchimateModel();
            }
            else {
                model = ArchiAccess.model(modelId);
            }

            // 1. Build and check everything; the model is not touched until the command runs
            Map<String, IArchimateConcept> refs = new HashMap<>();
            List<NewElement> elements = new ArrayList<>();
            for (int i = 0; i < elementItems.size(); i++) {
                Arguments item = elementItems.get(i);
                try {
                    String ref = item.optString("ref", null);
                    IArchimateElement element = EditTools.newElement(item.string("type"), item.string("name"),
                            item.optString("documentation", null), item.optStringMap("properties"));
                    IFolder folder = EditTools.targetFolder(model, element, item.optString("folder_id", null));
                    String parent = item.optString("parent", null);
                    if (view != null) {
                        ViewTools.checkAllowed(view, element.eClass());
                    }
                    else if (parent != null) {
                        throw new ToolException("'parent' needs view_id");
                    }
                    addRef(refs, ref, element);
                    elements.add(new NewElement(ref, element, folder, parent, item.optInteger("x"), item.optInteger("y"),
                            item.optInteger("width"), item.optInteger("height")));
                }
                catch (ToolException e) {
                    throw new ToolException("elements[" + i + "]: " + e.getMessage() + " (nothing was created)");
                }
            }

            List<NewRelationship> relationships = new ArrayList<>();
            for (int i = 0; i < relationshipItems.size(); i++) {
                Arguments item = relationshipItems.get(i);
                try {
                    String ref = item.optString("ref", null);
                    IArchimateConcept source = endpoint(item.string("source"), refs, model);
                    IArchimateConcept target = endpoint(item.string("target"), refs, model);
                    IArchimateRelationship relationship = EditTools.newRelationship(item.string("type"), source, target,
                            item.optString("name", null), item.optString("documentation", null), item.optStringMap("properties"));
                    addRef(refs, ref, relationship);
                    relationships.add(new NewRelationship(ref, relationship, source, target, model.getDefaultFolderForObject(relationship)));
                }
                catch (ToolException e) {
                    throw new ToolException("relationships[" + i + "]: " + e.getMessage() + " (nothing was created)");
                }
            }

            // 2. Plan the view: a node per new element, a connection per new relationship whose ends are both shown
            List<NewNode> nodes = new ArrayList<>();
            List<ViewTools.Link> links = new ArrayList<>();
            JsonArray notDrawn = new JsonArray();
            if (view != null) {
                Map<IArchimateElement, IDiagramModelArchimateObject> newNodes = new LinkedHashMap<>();
                Map<String, IDiagramModelArchimateObject> nodesByRef = new HashMap<>();
                Map<IDiagramModelContainer, Integer> gridIndexes = new HashMap<>();
                for (int i = 0; i < elements.size(); i++) {
                    NewElement e = elements.get(i);
                    IDiagramModelContainer container;
                    try {
                        container = container(view, e.parent, nodesByRef);
                    }
                    catch (ToolException ex) {
                        throw new ToolException("elements[" + i + "]: " + ex.getMessage() + " (nothing was created)");
                    }
                    int gridIndex = gridIndexes.merge(container, 1, Integer::sum) - 1 + container.getChildren().size();
                    IDiagramModelArchimateObject node = ViewTools.newNode(e.element, gridIndex, e.x, e.y, e.width, e.height);
                    newNodes.put(e.element, node);
                    if (e.ref != null && !e.ref.isBlank()) {
                        nodesByRef.put(e.ref, node);
                    }
                    nodes.add(new NewNode(e.ref, node, container));
                }
                for (NewRelationship r : relationships) {
                    List<IDiagramModelArchimateObject> sources = shownNodes(view, r.source, newNodes);
                    List<IDiagramModelArchimateObject> targets = shownNodes(view, r.target, newNodes);
                    if (sources.isEmpty() || targets.isEmpty()) {
                        JsonObject skipped = withRef(ArchiJson.ref(r.relationship), r.ref);
                        skipped.addProperty("reason", (sources.isEmpty() ? "source" : "target") + " is not an element on the view");
                        notDrawn.add(skipped);
                        continue;
                    }
                    for (IDiagramModelArchimateObject s : sources) {
                        for (IDiagramModelArchimateObject t : targets) {
                            links.add(new ViewTools.Link(ViewTools.newConnection(r.relationship), s, t));
                        }
                    }
                }
            }

            // 3. One command for everything, so that a single Edit > Undo removes it all
            String label = "Create " + elements.size() + " element(s) and " + relationships.size() + " relationship(s)";
            ArchiAccess.execute(model, new ModelCommand(label,
                    () -> {
                        elements.forEach(e -> e.folder.getElements().add(e.element));
                        for (NewRelationship r : relationships) {
                            r.relationship.connect(r.source, r.target);
                            r.folder.getElements().add(r.relationship);
                        }
                        nodes.forEach(n -> n.container.getChildren().add(n.node));
                        links.forEach(ViewTools.Link::connect);
                    },
                    () -> {
                        links.forEach(l -> l.connection().disconnect());
                        nodes.reversed().forEach(n -> n.container.getChildren().remove(n.node));
                        for (NewRelationship r : relationships.reversed()) {
                            r.folder.getElements().remove(r.relationship);
                            r.relationship.disconnect();
                        }
                        elements.forEach(e -> e.folder.getElements().remove(e.element));
                    }));

            JsonObject result = new JsonObject();
            result.addProperty("model_id", model.getId());
            JsonArray createdElements = new JsonArray();
            for (NewElement e : elements) {
                JsonObject json = withRef(ArchiJson.summary(e.element), e.ref);
                json.addProperty("folder", ArchiJson.folderPath(e.element));
                createdElements.add(json);
            }
            result.add("elements", createdElements);
            JsonArray createdRelationships = new JsonArray();
            relationships.forEach(r -> createdRelationships.add(withRef(ArchiJson.summary(r.relationship), r.ref)));
            result.add("relationships", createdRelationships);
            if (view != null) {
                JsonObject viewJson = ArchiJson.ref(view);
                JsonArray nodesJson = new JsonArray();
                nodes.forEach(n -> nodesJson.add(withRef(ArchiJson.node(n.node), n.ref)));
                viewJson.add("nodes_added", nodesJson);
                JsonArray connections = new JsonArray();
                links.forEach(l -> connections.add(ArchiJson.connection(l.connection())));
                viewJson.add("connections_added", connections);
                viewJson.add("relationships_not_drawn", notDrawn);
                result.add("view", viewJson);
            }
            return ToolResult.json(result);
        });
    }

    private static void addRef(Map<String, IArchimateConcept> refs, String ref, IArchimateConcept concept) throws ToolException {
        if (ref == null || ref.isBlank()) {
            return;
        }
        if (refs.putIfAbsent(ref, concept) != null) {
            throw new ToolException("ref '" + ref + "' is used twice");
        }
    }

    /** A ref of this call, otherwise the id of an existing element or relationship of the model. */
    private static IArchimateConcept endpoint(String key, Map<String, IArchimateConcept> refs, IArchimateModel model) throws ToolException {
        IArchimateConcept concept = refs.get(key);
        if (concept != null) {
            return concept;
        }
        EObject o = ArchimateModelUtils.getObjectByID(model, key);
        if (o instanceof IArchimateConcept c) {
            return c;
        }
        if (o != null) {
            throw new ToolException("'" + key + "' is a " + o.eClass().getName() + ", not an element or relationship");
        }
        throw new ToolException("'" + key + "' is neither a ref of this call (elements, or relationships listed before) "
                + "nor the id of an element or relationship in model '" + model.getName() + "'");
    }

    /** The view itself, or the container named by parent: a node of this call (by ref) or a node already on the view. */
    private static IDiagramModelContainer container(IArchimateDiagramModel view, String parent,
            Map<String, IDiagramModelArchimateObject> nodesByRef) throws ToolException {
        if (parent == null || parent.isBlank()) {
            return view;
        }
        IDiagramModelArchimateObject added = nodesByRef.get(parent);
        if (added != null) {
            return added;
        }
        IDiagramModelObject node;
        try {
            node = ViewTools.node(view, parent);
        }
        catch (ToolException e) {
            throw new ToolException("parent '" + parent + "' is neither the ref of an element listed before "
                    + "nor the id of a node of view '" + view.getName() + "' (see get_view)");
        }
        if (!(node instanceof IDiagramModelContainer container)) {
            throw new ToolException("parent node '" + parent + "' cannot contain other nodes");
        }
        return container;
    }

    /** The nodes showing the concept on the view once the batch is applied (relationships are never nodes). */
    private static List<IDiagramModelArchimateObject> shownNodes(IArchimateDiagramModel view, IArchimateConcept concept,
            Map<IArchimateElement, IDiagramModelArchimateObject> newNodes) {
        IDiagramModelArchimateObject added = newNodes.get(concept);
        return added != null ? List.of(added) : ViewTools.nodesOf(view, concept);
    }

    private static JsonObject withRef(JsonObject json, String ref) {
        if (ref != null && !ref.isBlank()) {
            JsonObject result = new JsonObject();
            result.addProperty("ref", ref);
            json.entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
            return result;
        }
        return json;
    }
}
