package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolException;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
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
                        + "(e.g. relationships[2]). With reuse_existing, the batch can be replayed: an element or relationship that already "
                        + "exists is reused instead of being duplicated (see reuse_existing).",
                Schema.object()
                        .string("model_id", "Model id or name. Optional when a single model is open or when view_id is given.", false)
                        .bool("reuse_existing", "Reuse what already exists instead of creating duplicates (default false). An element is "
                                + "reused when the model has one of the same type and name (case and surrounding spaces ignored), and is left "
                                + "unchanged (its documentation and properties are not updated); several candidates are an error. A relationship "
                                + "is reused when one of the same type, source, target and name exists. On the view, a reused element keeps "
                                + "its existing node, and a reused relationship is not drawn twice. Reused items are flagged \"reused\": true.",
                                false)
                        .objectArray("elements", "Elements to create (or reuse).", Schema.object()
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
                                .integer("height", "With view_id: node height (default: Archi's default size).", false)
                                .string("fill_color", "With view_id: fill colour of the node, e.g. #ffcc00.", false)
                                .string("line_color", "With view_id: border colour of the node, e.g. #333333.", false)
                                .string("font_color", "With view_id: text colour of the node, e.g. #000000.", false)
                                .integer("alpha", "With view_id: fill opacity of the node, from 0 to 255 (opaque, the default).", false),
                                false)
                        .objectArray("relationships", "Relationships to create (or reuse), validated against the ArchiMate rules.", Schema.object()
                                .string("ref", "Optional local reference, so that a later relationship can point to this one.", false)
                                .string("type", "Relationship type, e.g. Serving, Assignment, Realization, Flow, Composition.", true)
                                .string("source", "A ref of this call, or the id of an existing element or relationship.", true)
                                .string("target", "A ref of this call, or the id of an existing element or relationship.", true)
                                .string("name", "Optional name.", false)
                                .string("documentation", "Optional documentation.", false)
                                .stringMap("properties", "Optional properties as {key: value}.")
                                .bool("draw", "With view_id: draw it on the view when both ends are on it (default true). "
                                        + "false creates the relationship in the model only.", false),
                                false)
                        .string("view_id", "Optional view: the elements of the batch are shown on it (new node, or the existing node of a "
                                + "reused element), and the relationships are drawn when both ends are on it.", false)
                        .build(),
                false, BatchTools::createBatch));
    }

    private record NewElement(String ref, IArchimateElement element, boolean reused, IFolder folder,
            String parent, Integer x, Integer y, Integer width, Integer height,
            String fill, String line, String font, Integer alpha) {

        boolean styled() {
            return fill != null || line != null || font != null || alpha != null;
        }
    }

    private record NewNode(String ref, IDiagramModelArchimateObject node, IDiagramModelContainer container) {}

    private record NewRelationship(String ref, IArchimateRelationship relationship, boolean reused, boolean draw,
            IArchimateConcept source, IArchimateConcept target, IFolder folder) {}

    /** A style change on a node that already exists, kept so that undo can restore it. */
    private record Restyle(IDiagramModelObject node, ViewTools.Style before, ViewTools.Style after) {}

    private static ToolResult createBatch(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        String viewId = args.optString("view_id", null);
        boolean reuse = args.optBoolean("reuse_existing", false);
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
            Map<String, List<IArchimateElement>> existing = reuse ? elementsByKey(model) : Map.of();
            Set<String> keys = new HashSet<>();
            List<NewElement> elements = new ArrayList<>();
            for (int i = 0; i < elementItems.size(); i++) {
                Arguments item = elementItems.get(i);
                try {
                    String ref = item.optString("ref", null);
                    IArchimateElement element = EditTools.newElement(item.string("type"), item.string("name"),
                            item.optString("documentation", null), item.optStringMap("properties"));
                    String parent = item.optString("parent", null);
                    String fill = ViewTools.color(item, "fill_color");
                    String line = ViewTools.color(item, "line_color");
                    String font = ViewTools.color(item, "font_color");
                    Integer alpha = item.optInteger("alpha");
                    if (alpha != null && (alpha < 0 || alpha > 255)) {
                        throw new ToolException("'alpha' must be between 0 and 255, got " + alpha);
                    }
                    boolean reused = false;
                    if (reuse) {
                        String key = key(element.eClass(), element.getName());
                        if (!keys.add(key)) {
                            throw new ToolException("an element listed before has the same type and name");
                        }
                        List<IArchimateElement> matches = existing.getOrDefault(key, List.of());
                        if (matches.size() > 1) {
                            throw new ToolException(matches.size() + " existing " + element.eClass().getName() + " are named '"
                                    + element.getName().strip() + "' (" + String.join(", ", matches.stream().map(IArchimateElement::getId).toList())
                                    + "): reuse is ambiguous, rename them in Archi");
                        }
                        if (matches.size() == 1) {
                            element = matches.get(0);
                            reused = true;
                        }
                    }
                    IFolder folder = reused ? null : EditTools.targetFolder(model, element, item.optString("folder_id", null));
                    if (view != null) {
                        ViewTools.checkAllowed(view, element.eClass());
                    }
                    else if (parent != null) {
                        throw new ToolException("'parent' needs view_id");
                    }
                    NewElement e = new NewElement(ref, element, reused, folder, parent, item.optInteger("x"), item.optInteger("y"),
                            item.optInteger("width"), item.optInteger("height"), fill, line, font, alpha);
                    if (view == null && e.styled()) {
                        throw new ToolException("fill_color, line_color, font_color and alpha need view_id");
                    }
                    addRef(refs, ref, element);
                    elements.add(e);
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
                    boolean reused = false;
                    if (reuse) {
                        String key = source.getId() + ">" + target.getId() + ":" + key(relationship.eClass(), relationship.getName());
                        if (!keys.add(key)) {
                            throw new ToolException("a relationship listed before has the same type, source, target and name");
                        }
                        IArchimateRelationship found = existingRelationship(relationship.eClass(), source, target, relationship.getName());
                        if (found != null) {
                            relationship = found;
                            reused = true;
                        }
                    }
                    addRef(refs, ref, relationship);
                    relationships.add(new NewRelationship(ref, relationship, reused, item.optBoolean("draw", true), source, target,
                            reused ? null : model.getDefaultFolderForObject(relationship)));
                }
                catch (ToolException e) {
                    throw new ToolException("relationships[" + i + "]: " + e.getMessage() + " (nothing was created)");
                }
            }

            // 2. Plan the view: a node per element (unless a reused element is already shown), a connection per
            // relationship to draw whose ends are both shown and that is not drawn there yet
            List<NewNode> nodes = new ArrayList<>();
            List<NewNode> reusedNodes = new ArrayList<>();
            List<Restyle> restyles = new ArrayList<>();
            List<ViewTools.Link> links = new ArrayList<>();
            JsonArray notDrawn = new JsonArray();
            if (view != null) {
                Map<IArchimateElement, IDiagramModelArchimateObject> newNodes = new LinkedHashMap<>();
                Map<String, IDiagramModelArchimateObject> nodesByRef = new HashMap<>();
                Map<IDiagramModelContainer, Integer> gridIndexes = new HashMap<>();
                for (int i = 0; i < elements.size(); i++) {
                    NewElement e = elements.get(i);
                    List<IDiagramModelArchimateObject> shown = e.reused ? ViewTools.nodesOf(view, e.element) : List.of();
                    IDiagramModelArchimateObject node;
                    if (!shown.isEmpty()) {
                        node = shown.get(0);
                        reusedNodes.add(new NewNode(e.ref, node, null));
                    }
                    else {
                        IDiagramModelContainer container;
                        try {
                            container = container(view, e.parent, nodesByRef);
                        }
                        catch (ToolException ex) {
                            throw new ToolException("elements[" + i + "]: " + ex.getMessage() + " (nothing was created)");
                        }
                        int gridIndex = gridIndexes.merge(container, 1, Integer::sum) - 1 + container.getChildren().size();
                        node = ViewTools.newNode(e.element, gridIndex, e.x, e.y, e.width, e.height);
                        newNodes.put(e.element, node);
                        nodes.add(new NewNode(e.ref, node, container));
                    }
                    if (e.ref != null && !e.ref.isBlank()) {
                        nodesByRef.put(e.ref, node);
                    }
                    if (e.styled()) {
                        ViewTools.Style before = ViewTools.Style.of(node);
                        ViewTools.Style after = before.with(e.fill, e.line, e.font, e.alpha);
                        if (shown.isEmpty()) {
                            after.applyTo(node);  // a new node: undo removes it, style included
                        }
                        else {
                            restyles.add(new Restyle(node, before, after));
                        }
                    }
                }
                for (NewRelationship r : relationships) {
                    if (!r.draw) {
                        continue;
                    }
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
                            if (!r.reused || !isDrawn(s, t, r.relationship)) {
                                links.add(new ViewTools.Link(ViewTools.newConnection(r.relationship), s, t));
                            }
                        }
                    }
                }
            }

            // 3. One command for everything, so that a single Edit > Undo removes it all
            List<NewElement> created = elements.stream().filter(e -> !e.reused).toList();
            List<NewRelationship> createdRelationships = relationships.stream().filter(r -> !r.reused).toList();
            if (!created.isEmpty() || !createdRelationships.isEmpty() || !nodes.isEmpty() || !links.isEmpty() || !restyles.isEmpty()) {
                String label = "Create " + created.size() + " element(s) and " + createdRelationships.size() + " relationship(s)";
                ArchiAccess.execute(model, new ModelCommand(label,
                        () -> {
                            created.forEach(e -> e.folder.getElements().add(e.element));
                            for (NewRelationship r : createdRelationships) {
                                r.relationship.connect(r.source, r.target);
                                r.folder.getElements().add(r.relationship);
                            }
                            nodes.forEach(n -> n.container.getChildren().add(n.node));
                            links.forEach(ViewTools.Link::connect);
                            restyles.forEach(s -> s.after.applyTo(s.node));
                        },
                        () -> {
                            restyles.reversed().forEach(s -> s.before.applyTo(s.node));
                            links.forEach(l -> l.connection().disconnect());
                            nodes.reversed().forEach(n -> n.container.getChildren().remove(n.node));
                            for (NewRelationship r : createdRelationships.reversed()) {
                                r.folder.getElements().remove(r.relationship);
                                r.relationship.disconnect();
                            }
                            created.forEach(e -> e.folder.getElements().remove(e.element));
                        }));
            }

            JsonObject result = new JsonObject();
            result.addProperty("model_id", model.getId());
            JsonArray elementsJson = new JsonArray();
            for (NewElement e : elements) {
                JsonObject json = withRef(ArchiJson.summary(e.element), e.ref);
                if (e.reused) {
                    json.addProperty("reused", true);
                }
                else {
                    json.addProperty("folder", ArchiJson.folderPath(e.element));
                }
                elementsJson.add(json);
            }
            result.add("elements", elementsJson);
            JsonArray relationshipsJson = new JsonArray();
            for (NewRelationship r : relationships) {
                JsonObject json = withRef(ArchiJson.summary(r.relationship), r.ref);
                if (r.reused) {
                    json.addProperty("reused", true);
                }
                relationshipsJson.add(json);
            }
            result.add("relationships", relationshipsJson);
            if (view != null) {
                JsonObject viewJson = ArchiJson.ref(view);
                JsonArray nodesJson = new JsonArray();
                nodes.forEach(n -> nodesJson.add(withRef(ArchiJson.node(n.node), n.ref)));
                viewJson.add("nodes_added", nodesJson);
                if (reuse) {
                    JsonArray reusedJson = new JsonArray();
                    reusedNodes.forEach(n -> reusedJson.add(withRef(ArchiJson.node(n.node), n.ref)));
                    viewJson.add("nodes_reused", reusedJson);
                }
                JsonArray connections = new JsonArray();
                links.forEach(l -> connections.add(ArchiJson.connection(l.connection())));
                viewJson.add("connections_added", connections);
                viewJson.add("relationships_not_drawn", notDrawn);
                result.add("view", viewJson);
            }
            return ToolResult.json(result);
        });
    }

    /** Type and name, the identity of an element (or the type-and-name part of a relationship's) for reuse. */
    private static String key(EClass type, String name) {
        return type.getName() + ":" + (name == null ? "" : name.strip().toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<IArchimateElement>> elementsByKey(IArchimateModel model) {
        Map<String, List<IArchimateElement>> index = new HashMap<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IArchimateElement e) {
                index.computeIfAbsent(key(e.eClass(), e.getName()), k -> new ArrayList<>()).add(e);
            }
        }
        return index;
    }

    private static IArchimateRelationship existingRelationship(EClass type, IArchimateConcept source,
            IArchimateConcept target, String name) {
        String wanted = key(type, name);
        for (IArchimateRelationship r : source.getSourceRelationships()) {
            if (r.getTarget() == target && key(r.eClass(), r.getName()).equals(wanted)) {
                return r;
            }
        }
        return null;
    }

    private static boolean isDrawn(IDiagramModelArchimateObject source, IDiagramModelArchimateObject target,
            IArchimateRelationship relationship) {
        for (IDiagramModelConnection c : source.getSourceConnections()) {
            if (c.getTarget() == target && c instanceof IDiagramModelArchimateConnection ac
                    && ac.getArchimateRelationship() == relationship) {
                return true;
            }
        }
        return false;
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
