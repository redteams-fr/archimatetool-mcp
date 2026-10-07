package fr.redteams.archi.mcp.archi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ITextContent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * JSON views of Archi model objects, shaped for an LLM: ids, types and names first.
 */
final class ArchiJson {

    private ArchiJson() {}

    static JsonObject summary(EObject o) {
        JsonObject json = new JsonObject();
        if (o instanceof IIdentifier id) {
            json.addProperty("id", id.getId());
        }
        json.addProperty("type", o.eClass().getName());
        if (o instanceof INameable n) {
            json.addProperty("name", n.getName());
        }
        if (o instanceof IArchimateRelationship r) {
            json.add("source", ref(r.getSource()));
            json.add("target", ref(r.getTarget()));
        }
        if (o instanceof IArchimateDiagramModel dm && dm.getViewpoint() != null && !dm.getViewpoint().isEmpty()) {
            json.addProperty("viewpoint", dm.getViewpoint());
        }
        return json;
    }

    /** Minimal reference: id, type, name. */
    static JsonObject ref(EObject o) {
        JsonObject json = new JsonObject();
        if (o == null) {
            return json;
        }
        if (o instanceof IIdentifier id) {
            json.addProperty("id", id.getId());
        }
        json.addProperty("type", o.eClass().getName());
        if (o instanceof INameable n) {
            json.addProperty("name", n.getName());
        }
        return json;
    }

    static JsonObject model(IArchimateModel model) {
        int elements = 0;
        int relationships = 0;
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IArchimateRelationship) {
                relationships++;
            }
            else if (o instanceof IArchimateConcept) {
                elements++;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", model.getId());
        json.addProperty("name", model.getName());
        json.addProperty("file", model.getFile() != null ? model.getFile().getAbsolutePath() : null);
        json.addProperty("unsaved_changes", IEditorModelManager.INSTANCE.isModelDirty(model));
        json.addProperty("elements", elements);
        json.addProperty("relationships", relationships);
        json.addProperty("views", model.getDiagramModels().size());
        if (model.getPurpose() != null && !model.getPurpose().isEmpty()) {
            json.addProperty("purpose", model.getPurpose());
        }
        return json;
    }

    static JsonObject details(IArchimateConcept concept) {
        JsonObject json = summary(concept);
        json.addProperty("model_id", concept.getArchimateModel().getId());
        json.addProperty("folder", folderPath(concept));
        IProfile profile = concept.getPrimaryProfile();
        if (profile != null) {
            json.addProperty("specialization", profile.getName());
        }
        json.addProperty("documentation", concept.getDocumentation());
        json.add("properties", properties(concept));

        JsonArray relations = new JsonArray();
        for (IArchimateRelationship r : concept.getSourceRelationships()) {
            relations.add(relation(r, "outgoing", r.getTarget()));
        }
        for (IArchimateRelationship r : concept.getTargetRelationships()) {
            relations.add(relation(r, "incoming", r.getSource()));
        }
        json.add("relationships", relations);

        Map<String, JsonObject> views = new LinkedHashMap<>();
        for (IDiagramModelArchimateComponent c : concept.getReferencingDiagramComponents()) {
            IDiagramModel dm = c.getDiagramModel();
            if (dm != null) {
                views.putIfAbsent(dm.getId(), ref(dm));
            }
        }
        JsonArray viewArray = new JsonArray();
        views.values().forEach(viewArray::add);
        json.add("views", viewArray);
        return json;
    }

    private static JsonObject relation(IArchimateRelationship r, String direction, IArchimateConcept other) {
        JsonObject json = new JsonObject();
        json.addProperty("id", r.getId());
        json.addProperty("type", r.eClass().getName());
        if (r.getName() != null && !r.getName().isEmpty()) {
            json.addProperty("name", r.getName());
        }
        json.addProperty("direction", direction);
        json.add("other", ref(other));
        return json;
    }

    static JsonObject properties(IProperties owner) {
        JsonObject json = new JsonObject();
        for (IProperty p : owner.getProperties()) {
            json.addProperty(p.getKey(), p.getValue());
        }
        return json;
    }

    static String folderPath(EObject o) {
        Deque<String> names = new ArrayDeque<>();
        for (EObject c = o.eContainer(); c instanceof IFolder f; c = c.eContainer()) {
            names.push(f.getName());
        }
        return String.join("/", names);
    }

    static JsonObject view(IDiagramModel dm) {
        JsonObject json = summary(dm);
        json.addProperty("model_id", dm.getArchimateModel().getId());
        json.addProperty("folder", folderPath(dm));
        json.addProperty("documentation", dm.getDocumentation());
        json.add("nodes", nodes(dm));
        JsonArray connections = new JsonArray();
        for (Iterator<EObject> it = dm.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IDiagramModelConnection c) {
                connections.add(connection(c));
            }
        }
        json.add("connections", connections);
        return json;
    }

    private static JsonArray nodes(IDiagramModelContainer container) {
        JsonArray array = new JsonArray();
        for (IDiagramModelObject child : container.getChildren()) {
            array.add(node(child));
        }
        return array;
    }

    static JsonObject node(IDiagramModelObject o) {
        JsonObject json = new JsonObject();
        json.addProperty("node_id", o.getId());
        if (o instanceof IDiagramModelArchimateObject ao) {
            json.addProperty("kind", "element");
            json.add("element", ref(ao.getArchimateElement()));
        }
        else if (o instanceof IDiagramModelReference ref) {
            json.addProperty("kind", "view-reference");
            json.add("view", ref(ref.getReferencedModel()));
        }
        else {
            json.addProperty("kind", o.eClass().getName().replace("DiagramModel", "").toLowerCase());
            if (o.getName() != null && !o.getName().isEmpty()) {
                json.addProperty("name", o.getName());
            }
            if (o instanceof ITextContent t && t.getContent() != null && !t.getContent().isEmpty()) {
                json.addProperty("text", t.getContent());
            }
        }
        IBounds b = o.getBounds();
        if (b != null) {
            JsonObject bounds = new JsonObject();
            bounds.addProperty("x", b.getX());
            bounds.addProperty("y", b.getY());
            bounds.addProperty("width", b.getWidth());
            bounds.addProperty("height", b.getHeight());
            json.add("bounds", bounds);
        }
        if (o instanceof IDiagramModelContainer c && !c.getChildren().isEmpty()) {
            json.add("children", nodes(c));
        }
        return json;
    }

    static JsonObject connection(IDiagramModelConnection c) {
        JsonObject json = new JsonObject();
        json.addProperty("connection_id", c.getId());
        json.addProperty("source_node", c.getSource() != null ? c.getSource().getId() : null);
        json.addProperty("target_node", c.getTarget() != null ? c.getTarget().getId() : null);
        if (c instanceof IDiagramModelArchimateConnection ac) {
            json.add("relationship", ref(ac.getArchimateRelationship()));
        }
        else {
            json.addProperty("kind", "line");
            if (c.getName() != null && !c.getName().isEmpty()) {
                json.addProperty("name", c.getName());
            }
        }
        return json;
    }
}
