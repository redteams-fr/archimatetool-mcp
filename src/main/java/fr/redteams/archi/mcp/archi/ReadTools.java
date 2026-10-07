package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.EditPart;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.viewpoints.IViewpoint;
import com.archimatetool.model.viewpoints.ViewpointManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Read-only tools.
 */
final class ReadTools {

    private ReadTools() {}

    static void register(ToolRegistry registry) {
        registry.register(new Tool("list_models", "List open models",
                "Lists the ArchiMate models currently open in Archi, with their id, file, unsaved state and size.",
                Schema.object().build(), true, ReadTools::listModels));

        registry.register(new Tool("list_element_types", "List ArchiMate types",
                "Lists the valid element types (grouped by layer), relationship types and viewpoint ids.",
                Schema.object().build(), true, ReadTools::listTypes));

        registry.register(new Tool("search_elements", "Search elements",
                "Searches elements (and optionally relationships) by name/documentation text, type and property. "
                        + "All filters are optional and combined with AND.",
                Schema.object()
                        .string("model_id", "Model id or name. Defaults to all open models.", false)
                        .string("query", "Case-insensitive text searched in name and documentation.", false)
                        .string("type", "ArchiMate type, e.g. ApplicationComponent or ServingRelationship.", false)
                        .string("property_key", "Only concepts having this property.", false)
                        .string("property_value", "With property_key: required property value (case-insensitive).", false)
                        .bool("include_relationships", "Also return relationships (default false, implied when type is a relationship).", false)
                        .integer("limit", "Maximum number of results (default 50, max 500).", false)
                        .build(),
                true, ReadTools::search));

        registry.register(new Tool("get_element", "Get element details",
                "Returns an element or relationship with its documentation, properties, specialization, "
                        + "relationships (with the element at the other end) and the views showing it.",
                Schema.object()
                        .string("id", "Element or relationship id.", true)
                        .string("model_id", "Optional model id or name.", false)
                        .build(),
                true, ReadTools::getElement));

        registry.register(new Tool("list_views", "List views",
                "Lists the views (diagrams) of a model with their id, name, viewpoint and folder.",
                Schema.object()
                        .string("model_id", "Model id or name. Optional when a single model is open.", false)
                        .build(),
                true, ReadTools::listViews));

        registry.register(new Tool("get_view", "Get view content",
                "Returns the content of a view: nested nodes (with the element they show and their bounds) and connections.",
                Schema.object()
                        .string("id", "View id.", true)
                        .build(),
                true, ReadTools::getView));

        registry.register(new Tool("get_selection", "Get user selection",
                "Returns what the user has currently selected in Archi (model tree or diagram), and the active view if any.",
                Schema.object().build(), true, ReadTools::getSelection));
    }

    private static ToolResult listModels(Arguments args) throws Exception {
        return ArchiAccess.onUiThread(() -> {
            JsonArray array = new JsonArray();
            for (IArchimateModel m : ArchiAccess.models()) {
                array.add(ArchiJson.model(m));
            }
            JsonObject result = new JsonObject();
            result.add("models", array);
            return ToolResult.json(result);
        });
    }

    private static ToolResult listTypes(Arguments args) throws Exception {
        return ArchiAccess.onUiThread(() -> {
            JsonObject elements = new JsonObject();
            for (Map.Entry<String, EClass[]> layer : ConceptTypes.elementLayers().entrySet()) {
                JsonArray names = new JsonArray();
                for (EClass c : layer.getValue()) {
                    names.add(c.getName());
                }
                elements.add(layer.getKey(), names);
            }
            JsonArray relationships = new JsonArray();
            ConceptTypes.relationshipTypes().forEach(c -> relationships.add(c.getName()));
            JsonArray viewpoints = new JsonArray();
            for (IViewpoint vp : ViewpointManager.INSTANCE.getAllViewpoints()) {
                JsonObject v = new JsonObject();
                v.addProperty("id", vp.getID());
                v.addProperty("name", vp.getName());
                viewpoints.add(v);
            }
            JsonObject result = new JsonObject();
            result.add("element_types", elements);
            result.add("relationship_types", relationships);
            result.add("viewpoints", viewpoints);
            return ToolResult.json(result);
        });
    }

    private static ToolResult search(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        String query = lower(args.optString("query", null));
        String type = args.optString("type", null);
        String propertyKey = args.optString("property_key", null);
        String propertyValue = lower(args.optString("property_value", null));
        boolean withRelationships = args.optBoolean("include_relationships", false);
        int limit = Math.max(1, Math.min(500, args.optInt("limit", 50)));

        return ArchiAccess.onUiThread(() -> {
            EClass typeFilter = null;
            if (type != null && !type.isBlank()) {
                try {
                    typeFilter = ConceptTypes.element(type);
                }
                catch (Exception e) {
                    typeFilter = ConceptTypes.relationship(type);
                }
            }
            boolean includeRelationships = withRelationships
                    || (typeFilter != null && ConceptTypes.relationshipTypes().contains(typeFilter));

            List<IArchimateModel> scope = modelId != null ? List.of(ArchiAccess.model(modelId)) : ArchiAccess.models();
            JsonArray results = new JsonArray();
            int total = 0;
            for (IArchimateModel m : scope) {
                for (Iterator<EObject> it = m.eAllContents(); it.hasNext();) {
                    if (!(it.next() instanceof IArchimateConcept c)) {
                        continue;
                    }
                    if (c instanceof IArchimateRelationship && !includeRelationships) {
                        continue;
                    }
                    if (typeFilter != null && c.eClass() != typeFilter) {
                        continue;
                    }
                    if (query != null && !contains(c.getName(), query) && !contains(c.getDocumentation(), query)) {
                        continue;
                    }
                    if (propertyKey != null && !hasProperty(c, propertyKey, propertyValue)) {
                        continue;
                    }
                    total++;
                    if (results.size() < limit) {
                        JsonObject json = ArchiJson.summary(c);
                        if (scope.size() > 1) {
                            json.addProperty("model_id", m.getId());
                        }
                        results.add(json);
                    }
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("total", total);
            result.addProperty("returned", results.size());
            result.add("results", results);
            return ToolResult.json(result);
        });
    }

    private static boolean hasProperty(IArchimateConcept c, String key, String lowerValue) {
        for (IProperty p : c.getProperties()) {
            if (key.equals(p.getKey()) && (lowerValue == null || lowerValue.equals(lower(p.getValue())))) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String text, String lowerQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static ToolResult getElement(Arguments args) throws Exception {
        String id = args.string("id");
        String modelId = args.optString("model_id", null);
        return ArchiAccess.onUiThread(() ->
                ToolResult.json(ArchiJson.details(ArchiAccess.object(id, modelId, IArchimateConcept.class, "element or relationship"))));
    }

    private static ToolResult listViews(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = ArchiAccess.model(modelId);
            JsonArray views = new JsonArray();
            for (IDiagramModel dm : model.getDiagramModels()) {
                JsonObject json = ArchiJson.summary(dm);
                json.addProperty("folder", ArchiJson.folderPath(dm));
                views.add(json);
            }
            JsonObject result = new JsonObject();
            result.addProperty("model_id", model.getId());
            result.add("views", views);
            return ToolResult.json(result);
        });
    }

    private static ToolResult getView(Arguments args) throws Exception {
        String id = args.string("id");
        return ArchiAccess.onUiThread(() -> ToolResult.json(ArchiJson.view(ArchiAccess.object(id, null, IDiagramModel.class, "view"))));
    }

    private static ToolResult getSelection(Arguments args) throws Exception {
        return ArchiAccess.onUiThread(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
                window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
            }
            JsonObject result = new JsonObject();
            JsonArray items = new JsonArray();
            if (window != null) {
                IWorkbenchPart part = window.getActivePage() != null ? window.getActivePage().getActivePart() : null;
                if (part != null) {
                    result.addProperty("active_part", part.getTitle());
                }
                IEditorPart editor = window.getActivePage() != null ? window.getActivePage().getActiveEditor() : null;
                if (editor instanceof IDiagramModelEditor diagramEditor && diagramEditor.getModel() != null) {
                    result.add("active_view", ArchiJson.ref(diagramEditor.getModel()));
                }
                ISelection selection = window.getSelectionService().getSelection();
                if (selection instanceof IStructuredSelection structured) {
                    for (EObject o : selectionObjects(structured)) {
                        items.add(selectionItem(o));
                    }
                }
            }
            result.add("selection", items);
            return ToolResult.json(result);
        });
    }

    private static List<EObject> selectionObjects(IStructuredSelection selection) {
        List<EObject> objects = new ArrayList<>();
        for (Object o : selection.toList()) {
            if (o instanceof EditPart part) {
                o = part.getModel();
            }
            if (o instanceof EObject e) {
                objects.add(e);
            }
        }
        return objects;
    }

    private static JsonObject selectionItem(EObject o) {
        if (o instanceof IDiagramModelArchimateComponent dc) {
            JsonObject json = ArchiJson.summary(dc.getArchimateConcept());
            json.addProperty("node_id", dc.getId());
            json.addProperty("view_id", dc.getDiagramModel().getId());
            return json;
        }
        JsonObject json = ArchiJson.summary(o);
        if (o instanceof IDiagramModelComponent dc && dc.getDiagramModel() != null && !(o instanceof IDiagramModel)) {
            json.addProperty("view_id", dc.getDiagramModel().getId());
        }
        return json;
    }
}
