package fr.redteams.archi.mcp.archi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.redteams.archi.mcp.server.ToolException;
import org.eclipse.emf.ecore.EClass;

import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * ArchiMate element and relationship types, resolved leniently from user input
 * ("BusinessActor", "business-actor", "Business Actor", "Serving" all work).
 */
final class ConceptTypes {

    private ConceptTypes() {}

    static Map<String, EClass[]> elementLayers() {
        Map<String, EClass[]> layers = new LinkedHashMap<>();
        layers.put("strategy", ArchimateModelUtils.getStrategyClasses());
        layers.put("business", ArchimateModelUtils.getBusinessClasses());
        layers.put("application", ArchimateModelUtils.getApplicationClasses());
        layers.put("technology", ArchimateModelUtils.getTechnologyClasses());
        layers.put("physical", ArchimateModelUtils.getPhysicalClasses());
        layers.put("motivation", ArchimateModelUtils.getMotivationClasses());
        layers.put("implementation_migration", ArchimateModelUtils.getImplementationMigrationClasses());
        layers.put("other", ArchimateModelUtils.getOtherClasses());
        layers.put("connector", ArchimateModelUtils.getConnectorClasses());
        return layers;
    }

    static List<EClass> elementTypes() {
        List<EClass> all = new ArrayList<>();
        for (EClass[] classes : elementLayers().values()) {
            all.addAll(List.of(classes));
        }
        return all;
    }

    static List<EClass> relationshipTypes() {
        return List.of(ArchimateModelUtils.getRelationsClasses());
    }

    static EClass element(String type) throws ToolException {
        return resolve(type, elementTypes(), false, "element");
    }

    static EClass relationship(String type) throws ToolException {
        return resolve(type, relationshipTypes(), true, "relationship");
    }

    private static EClass resolve(String type, List<EClass> candidates, boolean relationship, String what) throws ToolException {
        String key = normalize(type);
        for (EClass c : candidates) {
            String name = normalize(c.getName());
            if (name.equals(key) || (relationship && name.equals(key + "relationship"))) {
                return c;
            }
        }
        List<String> names = candidates.stream().map(EClass::getName).toList();
        throw new ToolException("Unknown " + what + " type '" + type + "'. Valid types: " + String.join(", ", names));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
    }
}
