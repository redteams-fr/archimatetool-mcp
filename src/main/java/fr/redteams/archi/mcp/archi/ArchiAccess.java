package fr.redteams.archi.mcp.archi;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import fr.redteams.archi.mcp.server.ToolException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.UUIDFactory;
import com.archimatetool.model.IIdentifier;

/**
 * Access to Archi's models. EMF models are not thread safe and Archi only touches them
 * from the UI thread, so every tool runs its body through {@link #onUiThread(Callable)}.
 */
final class ArchiAccess {

    private ArchiAccess() {}

    static <T> T onUiThread(Callable<T> task) throws Exception {
        if (!PlatformUI.isWorkbenchRunning()) {
            throw new ToolException("Archi is not running");
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (Display.getCurrent() == display) {
            return task.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                result.set(task.call());
            }
            catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    static List<IArchimateModel> models() {
        return IEditorModelManager.INSTANCE.getModels();
    }

    /**
     * @param idOrName model id or exact name; when null, the only open model
     */
    static IArchimateModel model(String idOrName) throws ToolException {
        List<IArchimateModel> models = models();
        if (idOrName == null || idOrName.isBlank()) {
            if (models.size() == 1) {
                return models.get(0);
            }
            throw new ToolException(models.isEmpty()
                    ? "No model is open in Archi. Open one, or call create_model."
                    : "Several models are open: pass model_id (see list_models).");
        }
        for (IArchimateModel m : models) {
            if (idOrName.equals(m.getId())) {
                return m;
            }
        }
        for (IArchimateModel m : models) {
            if (idOrName.equals(m.getName())) {
                return m;
            }
        }
        throw new ToolException("No open model with id or name '" + idOrName + "' (see list_models)");
    }

    /** Finds an object by id in the given model, or in all open models when modelId is null. */
    static <T> T object(String id, String modelId, Class<T> type, String what) throws ToolException {
        List<IArchimateModel> scope = modelId != null && !modelId.isBlank() ? List.of(model(modelId)) : models();
        for (IArchimateModel m : scope) {
            EObject o = ArchimateModelUtils.getObjectByID(m, id);
            if (o != null) {
                if (!type.isInstance(o)) {
                    throw new ToolException("Object '" + id + "' is a " + o.eClass().getName() + ", not " + what);
                }
                return type.cast(o);
            }
        }
        throw new ToolException("No " + what + " with id '" + id + "'");
    }

    static void ensureId(IIdentifier o) {
        if (o.getId() == null || o.getId().isEmpty()) {
            o.setId(UUIDFactory.createID(o));
        }
    }

    /** Runs the command on the model's undo stack (Edit > Undo in Archi). */
    static void execute(IArchimateModel model, Command command) {
        Object stack = model.getAdapter(CommandStack.class);
        if (stack instanceof CommandStack commandStack) {
            commandStack.execute(command);
        }
        else {
            command.execute();
        }
    }
}
