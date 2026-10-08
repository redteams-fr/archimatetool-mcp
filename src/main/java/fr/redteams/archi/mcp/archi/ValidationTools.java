package fr.redteams.archi.mcp.archi;

import java.util.Locale;

import fr.redteams.archi.mcp.server.Arguments;
import fr.redteams.archi.mcp.server.Schema;
import fr.redteams.archi.mcp.server.Tool;
import fr.redteams.archi.mcp.server.ToolException;
import fr.redteams.archi.mcp.server.ToolRegistry;
import fr.redteams.archi.mcp.server.ToolResult;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.hammer.validation.Validator;
import com.archimatetool.hammer.validation.issues.AdviceType;
import com.archimatetool.hammer.validation.issues.ErrorType;
import com.archimatetool.hammer.validation.issues.IIssue;
import com.archimatetool.hammer.validation.issues.IIssueCategory;
import com.archimatetool.hammer.validation.issues.WarningType;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelComponent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Model validation with Archi's own validator (the "Validate Model" view). The checks enabled
 * in Archi's Validator preferences apply.
 */
final class ValidationTools {

    private ValidationTools() {}

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    static void register(ToolRegistry registry) {
        registry.register(new Tool("validate_model", "Validate model",
                "Runs Archi's model validator: invalid relationships, unused elements and relationships, empty views, "
                        + "viewpoint violations, nesting, duplicates and junctions (as enabled in Archi's Validator preferences). "
                        + "Returns counts per severity and the issues with the id of the object concerned.",
                Schema.object()
                        .string("model_id", "Model id or name. Optional when a single model is open.", false)
                        .string("severity", "Minimum severity to return: error, warning or advice (default: advice, i.e. everything).", false)
                        .integer("limit", "Maximum number of issues returned (default 100, max 1000). Counts always cover all issues.", false)
                        .build(),
                true, ValidationTools::validate));
    }

    private enum Severity {
        ERROR, WARNING, ADVICE;

        static Severity of(IIssue issue) {
            if (issue instanceof ErrorType) {
                return ERROR;
            }
            if (issue instanceof WarningType) {
                return WARNING;
            }
            return issue instanceof AdviceType ? ADVICE : null; // null: "OK" pseudo-issue
        }

        static Severity parse(String value) throws ToolException {
            if (value == null || value.isBlank()) {
                return ADVICE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException e) {
                throw new ToolException("severity must be error, warning or advice");
            }
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static ToolResult validate(Arguments args) throws Exception {
        String modelId = args.optString("model_id", null);
        Severity minimum = Severity.parse(args.optString("severity", null));
        int limit = Math.max(1, Math.min(MAX_LIMIT, args.optInt("limit", DEFAULT_LIMIT)));

        return ArchiAccess.onUiThread(() -> {
            IArchimateModel model = ArchiAccess.model(modelId);

            JsonObject counts = new JsonObject();
            for (Severity s : Severity.values()) {
                counts.addProperty(s.key(), 0);
            }
            JsonArray issues = new JsonArray();
            JsonObject explanations = new JsonObject(); // once per issue kind, not per issue

            for (Object entry : new Validator(model).validate()) {
                if (!(entry instanceof IIssueCategory category)) {
                    continue;
                }
                for (IIssue issue : category.getIssues()) {
                    Severity severity = Severity.of(issue);
                    if (severity == null) {
                        continue;
                    }
                    counts.addProperty(severity.key(), counts.get(severity.key()).getAsInt() + 1);
                    if (severity.ordinal() > minimum.ordinal() || issues.size() >= limit) {
                        continue;
                    }
                    issues.add(issue(issue, severity));
                    if (issue.getName() != null && issue.getExplanation() != null && !explanations.has(issue.getName())) {
                        explanations.addProperty(issue.getName(), issue.getExplanation());
                    }
                }
            }

            int matching = 0;
            for (Severity s : Severity.values()) {
                if (s.ordinal() <= minimum.ordinal()) {
                    matching += counts.get(s.key()).getAsInt();
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("model_id", model.getId());
            result.add("counts", counts);
            result.addProperty("returned", issues.size());
            result.addProperty("truncated", issues.size() < matching);
            result.add("issues", issues);
            result.add("explanations", explanations);
            return ToolResult.json(result);
        });
    }

    private static JsonObject issue(IIssue issue, Severity severity) {
        JsonObject json = new JsonObject();
        json.addProperty("severity", severity.key());
        json.addProperty("kind", issue.getName());
        json.addProperty("description", issue.getDescription());
        Object object = issue.getObject();
        if (object instanceof IDiagramModelArchimateComponent component) {
            // Issue on a node or connection of a view: report the concept and where it is drawn
            JsonObject target = ArchiJson.summary(component.getArchimateConcept());
            target.addProperty("node_id", component.getId());
            target.addProperty("view_id", component.getDiagramModel().getId());
            json.add("object", target);
        }
        else if (object instanceof EObject eObject) {
            JsonObject target = ArchiJson.summary(eObject);
            if (eObject instanceof IDiagramModelComponent component && !(eObject instanceof IDiagramModel)) {
                target.addProperty("view_id", component.getDiagramModel().getId());
            }
            json.add("object", target);
        }
        return json;
    }
}
