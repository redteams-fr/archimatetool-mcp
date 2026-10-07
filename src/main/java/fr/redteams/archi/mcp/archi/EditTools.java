package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolException;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.google.gson.JsonObject;

/**
 * Tools creating or modifying model content. Each call is one undoable command.
 */
final class EditTools {

    private EditTools() {}

    static void register(ToolRegistry registry) {
        registry.register(new Tool("create_model", "Create model",
                "Creates a new empty model in Archi (with a default view). It is not saved to disk: the user saves it from Archi.",
                Schema.object()
                        .string("name", "Model name.", true)
                        .string("purpose", "Optional purpose/description of the model.", false)
                        .build(),
                false, EditTools::createModel));

        registry.register(new Tool("create_element", "Create element",
                "Creates an ArchiMate element. Search first (search_elements) to avoid duplicates.",
                Schema.object()
                        .string("type", "Element type, e.g. BusinessActor, ApplicationComponent, Node (see list_element_types).", true)
                        .string("name", "Element name.", true)
                        .string("model_id", "Model id or name. Optional when a single model is open.", false)
                        .string("documentation", "Optional documentation.", false)
                        .stringMap("properties", "Optional properties as {key: value}.")
                        .string("folder_id", "Optional sub-folder id; defaults to the folder of the element's layer.", false)
                        .build(),
                false, EditTools::createElement));

        registry.register(new Tool("create_relationship", "Create relationship",
                "Creates a relationship between two elements (or relationships), validated against the ArchiMate rules.",
                Schema.object()
                        .string("type", "Relationship type, e.g. Serving, Assignment, Realization, Flow, Composition.", true)
                        .string("source_id", "Source concept id.", true)
                        .string("target_id", "Target concept id.", true)
                        .string("name", "Optional name.", false)
                        .string("documentation", "Optional documentation.", false)
                        .stringMap("properties", "Optional properties as {key: value}.")
                        .build(),
                false, EditTools::createRelationship));

        registry.register(new Tool("update_element", "Update element",
                "Updates the name, documentation and/or properties of an element, relationship, view or folder. "
                        + "Properties are merged: a null value removes the key.",
                Schema.object()
                        .string("id", "Object id.", true)
                        .string("name", "New name.", false)
                        .string("documentation", "New documentation (replaces the existing text).", false)
                        .stringMap("properties", "Properties to set, as {key: value}; null removes the key.")
                        .build(),
                false, EditTools::updateElement));

        registry.register(new Tool("save_model", "Save model",
                "Saves a model to its file. Fails for a model that has never been saved (the user must choose a file in Archi).",
                Schema.object()
                        .string("model_id", "Model id or name. Optional when a single model is open.", false)
                        .build(),
                false, EditTools::saveModel));
    }

    private static ToolResult createModel(Arguments args) throws Exception {
        String name = args.string("name");
        String purpose = args.optString("purpose", null);
        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = IEditorModelManager.INSTANCE.createNewModel();
            model.setName(name);
            if (purpose != null) {
                model.setPurpose(purpose);
            }
            return ToolResult.json(ArchiJson.model(model));
        });
    }

    private static ToolResult createElement(Arguments args) throws Exception {
        String type = args.string("type");
        String name = args.string("name");
        String modelId = args.optString("model_id", null);
        String documentation = args.optString("documentation", null);
        Map<String, String> properties = args.optStringMap("properties");
        String folderId = args.optString("folder_id", null);

        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = ArchiAccess.model(modelId);
            EClass eClass = ConceptTypes.element(type);
            IArchimateElement element = (IArchimateElement) IArchimateFactory.eINSTANCE.create(eClass);
            ArchiAccess.ensureId(element);
            element.setName(name);
            if (documentation != null) {
                element.setDocumentation(documentation);
            }
            if (properties != null) {
                element.getProperties().addAll(mergeProperties(List.of(), properties));
            }
            IFolder folder = targetFolder(model, element, folderId);

            ArchiAccess.execute(model, new ModelCommand("Create " + name,
                    () -> folder.getElements().add(element),
                    () -> folder.getElements().remove(element)));

            JsonObject result = ArchiJson.summary(element);
            result.addProperty("folder", ArchiJson.folderPath(element));
            return ToolResult.json(result);
        });
    }

    private static ToolResult createRelationship(Arguments args) throws Exception {
        String type = args.string("type");
        String sourceId = args.string("source_id");
        String targetId = args.string("target_id");
        String name = args.optString("name", null);
        String documentation = args.optString("documentation", null);
        Map<String, String> properties = args.optStringMap("properties");

        return ArchiAccess.onUiThread(() -> {
            IArchimateConcept source = ArchiAccess.object(sourceId, null, IArchimateConcept.class, "element or relationship");
            IArchimateConcept target = ArchiAccess.object(targetId, null, IArchimateConcept.class, "element or relationship");
            IArchimateModel model = source.getArchimateModel();
            if (target.getArchimateModel() != model) {
                throw new ToolException("Source and target belong to different models");
            }
            EClass eClass = ConceptTypes.relationship(type);
            if (!ArchimateModelUtils.isValidRelationship(source, target, eClass)) {
                List<String> valid = ArchimateModelUtils.getValidRelationships(source, target).stream().map(EClass::getName).toList();
                throw new ToolException(eClass.getName() + " is not allowed from " + source.eClass().getName()
                        + " to " + target.eClass().getName() + ". Valid relationship types: "
                        + (valid.isEmpty() ? "none" : String.join(", ", valid)));
            }
            IArchimateRelationship relationship = (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(eClass);
            ArchiAccess.ensureId(relationship);
            if (name != null) {
                relationship.setName(name);
            }
            if (documentation != null) {
                relationship.setDocumentation(documentation);
            }
            if (properties != null) {
                relationship.getProperties().addAll(mergeProperties(List.of(), properties));
            }
            IFolder folder = model.getDefaultFolderForObject(relationship);

            ArchiAccess.execute(model, new ModelCommand("Create " + eClass.getName(),
                    () -> {
                        relationship.connect(source, target);
                        folder.getElements().add(relationship);
                    },
                    () -> {
                        folder.getElements().remove(relationship);
                        relationship.disconnect();
                    }));

            return ToolResult.json(ArchiJson.summary(relationship));
        });
    }

    private static ToolResult updateElement(Arguments args) throws Exception {
        String id = args.string("id");
        String name = args.optString("name", null);
        String documentation = args.optString("documentation", null);
        Map<String, String> properties = args.optStringMap("properties");
        if (name == null && documentation == null && properties == null) {
            throw new ToolException("Nothing to update: pass name, documentation and/or properties");
        }

        return ArchiAccess.onUiThread(() -> {
            IArchimateModelObject object = ArchiAccess.object(id, null, IArchimateModelObject.class, "model object");
            if (documentation != null && !(object instanceof IDocumentable)) {
                throw new ToolException(object.eClass().getName() + " has no documentation");
            }
            if (properties != null && !(object instanceof IProperties)) {
                throw new ToolException(object.eClass().getName() + " has no properties");
            }

            String oldName = object.getName();
            String oldDoc = object instanceof IDocumentable d ? d.getDocumentation() : null;
            List<IProperty> oldProps = object instanceof IProperties p ? new ArrayList<>(p.getProperties()) : List.of();
            List<IProperty> newProps = properties != null ? mergeProperties(oldProps, properties) : oldProps;

            ArchiAccess.execute(object.getArchimateModel(), new ModelCommand("Update " + oldName,
                    () -> apply(object, name != null ? name : oldName, documentation != null ? documentation : oldDoc, newProps),
                    () -> apply(object, oldName, oldDoc, oldProps)));

            EObject o = object;
            return ToolResult.json(o instanceof IArchimateConcept c ? ArchiJson.details(c) : ArchiJson.summary(o));
        });
    }

    private static void apply(IArchimateModelObject object, String name, String documentation, List<IProperty> properties) {
        object.setName(name);
        if (object instanceof IDocumentable d) {
            d.setDocumentation(documentation);
        }
        if (object instanceof IProperties p && !p.getProperties().equals(properties)) {
            p.getProperties().clear();
            p.getProperties().addAll(properties);
        }
    }

    /**
     * Returns a new property list: existing properties are kept (same objects) unless changed;
     * changed or added ones are new objects, so the old list stays valid for undo.
     */
    static List<IProperty> mergeProperties(List<IProperty> existing, Map<String, String> changes) {
        List<IProperty> result = new ArrayList<>();
        for (IProperty p : existing) {
            if (!changes.containsKey(p.getKey())) {
                result.add(p);
            }
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (change.getValue() != null) {
                result.add(IArchimateFactory.eINSTANCE.createProperty(change.getKey(), change.getValue()));
            }
        }
        return result;
    }

    /** The requested folder (checked to be in the right top-level folder) or the default one. */
    static IFolder targetFolder(IArchimateModel model, EObject object, String folderId) throws ToolException {
        IFolder defaultFolder = model.getDefaultFolderForObject(object);
        if (folderId == null || folderId.isBlank()) {
            return defaultFolder;
        }
        IFolder folder = ArchiAccess.object(folderId, model.getId(), IFolder.class, "folder");
        IFolder top = folder;
        while (top.eContainer() instanceof IFolder parent) {
            top = parent;
        }
        if (top != defaultFolder) {
            throw new ToolException("Folder '" + folder.getName() + "' is not inside the '" + defaultFolder.getName()
                    + "' folder, where a " + object.eClass().getName() + " must go");
        }
        return folder;
    }

    private static ToolResult saveModel(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = ArchiAccess.model(modelId);
            if (model.getFile() == null) {
                throw new ToolException("Model '" + model.getName() + "' has never been saved: ask the user to save it once from Archi (File > Save As).");
            }
            if (!IEditorModelManager.INSTANCE.saveModel(model)) {
                throw new ToolException("Archi did not save the model");
            }
            return ToolResult.json(ArchiJson.model(model));
        });
    }
}
