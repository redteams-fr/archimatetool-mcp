package fr.redteams.archi.mcp.ui;

import fr.redteams.archi.mcp.Activator;
import fr.redteams.archi.mcp.McpPreferences;
import fr.redteams.archi.mcp.McpServerController;
import fr.redteams.archi.mcp.clients.ClientInstructions;
import fr.redteams.archi.mcp.server.BindAddresses;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enabledButton;
    private Text portText;
    private Combo bindCombo;
    private Label remoteWarning;
    private Button requireTokenButton;
    private Text tokenText;
    private Label statusLabel;
    private Text commandText;
    private Combo clientCombo;
    private Label tokenStepLabel;
    private Text exportText;
    private Button exportCopyButton;
    private Label clientHintLabel;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Exposes the open Archi models to AI assistants through the Model Context Protocol (MCP).");
    }

    @Override
    protected IPreferenceStore doGetPreferenceStore() {
        return Activator.getDefault().getPreferenceStore();
    }

    @Override
    protected Control createContents(Composite parent) {
        IPreferenceStore store = getPreferenceStore();
        Composite root = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(root);

        // Server settings
        Group server = new Group(root, SWT.NONE);
        server.setText("Server");
        GridLayoutFactory.swtDefaults().numColumns(3).applyTo(server);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(server);

        enabledButton = new Button(server, SWT.CHECK);
        enabledButton.setText("Enable the MCP server");
        GridDataFactory.fillDefaults().span(3, 1).applyTo(enabledButton);

        new Label(server, SWT.NONE).setText("Listen on:");
        bindCombo = new Combo(server, SWT.DROP_DOWN);
        bindCombo.setItems(BindAddresses.LOOPBACK, BindAddresses.ALL_INTERFACES);
        GridDataFactory.fillDefaults().hint(160, SWT.DEFAULT).applyTo(bindCombo);
        bindCombo.addModifyListener(e -> validate());
        Label bindHint = new Label(server, SWT.NONE);
        bindHint.setText("127.0.0.1 = this computer only, 0.0.0.0 = all network interfaces");

        new Label(server, SWT.NONE).setText("Port:");
        portText = new Text(server, SWT.BORDER);
        GridDataFactory.fillDefaults().span(2, 1).hint(80, SWT.DEFAULT).applyTo(portText);
        portText.addModifyListener(e -> validate());

        remoteWarning = new Label(server, SWT.WRAP);
        remoteWarning.setText("Warning: remote access. Anyone on the network who has the token can read and modify "
                + "the open models, and the traffic is not encrypted (HTTP). A token is mandatory. "
                + "Prefer a trusted network, a firewall rule or an SSH tunnel.");
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).hint(400, SWT.DEFAULT).applyTo(remoteWarning);

        requireTokenButton = new Button(server, SWT.CHECK);
        requireTokenButton.setText("Require a bearer token (recommended)");
        GridDataFactory.fillDefaults().span(3, 1).applyTo(requireTokenButton);
        requireTokenButton.addListener(SWT.Selection, e -> updateCommand());

        new Label(server, SWT.NONE).setText("Token:");
        tokenText = new Text(server, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(tokenText);
        tokenText.addModifyListener(e -> updateCommand());
        Button generate = new Button(server, SWT.PUSH);
        generate.setText("Generate");
        generate.addListener(SWT.Selection, e -> tokenText.setText(McpServerController.generateToken()));

        // Status
        Group statusGroup = new Group(root, SWT.NONE);
        statusGroup.setText("Status");
        GridLayoutFactory.swtDefaults().applyTo(statusGroup);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusGroup);
        statusLabel = new Label(statusGroup, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusLabel);

        // Client configuration
        Group client = new Group(root, SWT.NONE);
        client.setText("Connect a client");
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(client);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(client);

        Composite clientRow = new Composite(client, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(clientRow);
        GridDataFactory.fillDefaults().span(2, 1).applyTo(clientRow);
        new Label(clientRow, SWT.NONE).setText("Client:");
        clientCombo = new Combo(clientRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (ClientInstructions c : ClientInstructions.values()) {
            clientCombo.add(c.label());
        }
        clientCombo.select(0);
        clientCombo.addListener(SWT.Selection, e -> updateCommand());

        tokenStepLabel = new Label(client, SWT.WRAP);
        tokenStepLabel.setText("1. Define the token variable, e.g. in ~/.zshrc or ~/.bashrc (Windows: setx "
                + ClientInstructions.TOKEN_ENV + " <token>). Clients read it when they connect:");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(400, SWT.DEFAULT).applyTo(tokenStepLabel);
        exportText = new Text(client, SWT.BORDER | SWT.READ_ONLY);
        exportText.setFont(JFaceResources.getTextFont());
        GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(exportText);
        exportCopyButton = copyButton(client, exportText);

        clientHintLabel = new Label(client, SWT.WRAP);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(400, SWT.DEFAULT).applyTo(clientHintLabel);
        commandText = new Text(client, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        commandText.setFont(JFaceResources.getTextFont());
        GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(commandText);
        copyButton(client, commandText);

        // Credits, GitHub and About
        Label footerSeparator = new Label(root, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, false).indent(0, 8).applyTo(footerSeparator);
        Composite footer = AboutDialog.createFooter(root, Activator.getDefault().getBundle().getVersion().toString());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(footer);

        enabledButton.setSelection(store.getBoolean(McpPreferences.ENABLED));
        portText.setText(String.valueOf(store.getInt(McpPreferences.PORT)));
        requireTokenButton.setSelection(store.getBoolean(McpPreferences.REQUIRE_TOKEN));
        tokenText.setText(store.getString(McpPreferences.TOKEN));
        bindCombo.setText(BindAddresses.normalize(store.getString(McpPreferences.BIND_ADDRESS)));
        updateStatus();
        validate();
        return root;
    }

    private Button copyButton(Composite parent, Text source) {
        Button copy = new Button(parent, SWT.PUSH);
        copy.setText("Copy");
        GridDataFactory.swtDefaults().align(SWT.BEGINNING, SWT.BEGINNING).applyTo(copy);
        copy.addListener(SWT.Selection, e -> {
            Clipboard clipboard = new Clipboard(getShell().getDisplay());
            try {
                clipboard.setContents(new Object[] { source.getText() }, new Transfer[] { TextTransfer.getInstance() });
            }
            finally {
                clipboard.dispose();
            }
        });
        return copy;
    }

    private static void show(Control control, boolean visible) {
        control.setVisible(visible);
        ((GridData) control.getLayoutData()).exclude = !visible;
    }

    private int parsePort() {
        try {
            int port = Integer.parseInt(portText.getText().trim());
            return port >= 1024 && port <= 65535 ? port : -1;
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private String bindAddress() {
        return BindAddresses.normalize(bindCombo.getText());
    }

    private void validate() {
        String error = null;
        if (!BindAddresses.isValid(bindAddress())) {
            error = "Listen on: enter an IP address, e.g. 127.0.0.1 or 0.0.0.0";
        }
        else if (parsePort() < 0) {
            error = "Port must be a number between 1024 and 65535";
        }
        setErrorMessage(error);
        setValid(error == null);
        updateRemoteState();
        updateCommand();
    }

    /** Outside the loopback interface the token is mandatory and a warning is shown. */
    private void updateRemoteState() {
        boolean remote = BindAddresses.isValid(bindAddress()) && !BindAddresses.isLoopback(bindAddress());
        if (remote) {
            requireTokenButton.setSelection(true);
            if (tokenText.getText().isBlank()) {
                tokenText.setText(McpServerController.generateToken());
            }
        }
        requireTokenButton.setEnabled(!remote);
        remoteWarning.setVisible(remote);
        ((GridData) remoteWarning.getLayoutData()).exclude = !remote;
        remoteWarning.getParent().getParent().layout(true, true);
    }

    private void updateStatus() {
        statusLabel.setText(Activator.getDefault().getController().getStatus());
        statusLabel.getParent().layout();
    }

    private void updateCommand() {
        if (commandText == null) {
            return;
        }
        int port = parsePort();
        String url = McpServerController.endpointUrl(bindAddress(), port > 0 ? port : McpPreferences.DEFAULT_PORT);
        boolean withToken = requireTokenButton.getSelection();
        ClientInstructions client = ClientInstructions.values()[Math.max(0, clientCombo.getSelectionIndex())];

        show(tokenStepLabel, withToken);
        show(exportText, withToken);
        show(exportCopyButton, withToken);
        exportText.setText(ClientInstructions.exportLine(tokenText.getText().trim()));
        clientHintLabel.setText((withToken ? "2. " : "") + client.hint());
        commandText.setText(client.snippet(url, withToken));
        commandText.getParent().getParent().layout(true, true);
    }

    @Override
    protected void performDefaults() {
        IPreferenceStore store = getPreferenceStore();
        enabledButton.setSelection(store.getDefaultBoolean(McpPreferences.ENABLED));
        portText.setText(String.valueOf(store.getDefaultInt(McpPreferences.PORT)));
        bindCombo.setText(store.getDefaultString(McpPreferences.BIND_ADDRESS));
        requireTokenButton.setSelection(store.getDefaultBoolean(McpPreferences.REQUIRE_TOKEN));
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        store.setValue(McpPreferences.ENABLED, enabledButton.getSelection());
        store.setValue(McpPreferences.PORT, parsePort());
        store.setValue(McpPreferences.BIND_ADDRESS, bindAddress());
        store.setValue(McpPreferences.REQUIRE_TOKEN, requireTokenButton.getSelection());
        store.setValue(McpPreferences.TOKEN, tokenText.getText().trim());
        if (store instanceof IPersistentPreferenceStore persistent) {
            try {
                persistent.save();
            }
            catch (Exception e) {
                Activator.error("Cannot save preferences", e);
            }
        }
        McpServerController controller = Activator.getDefault().getController();
        controller.applyPreferences();
        // A token may have been generated on start
        tokenText.setText(store.getString(McpPreferences.TOKEN));
        updateStatus();
        return true;
    }
}
