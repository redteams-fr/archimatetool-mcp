package fr.redteams.archi.mcp.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import fr.redteams.archi.mcp.AboutInfo;

/**
 * "About" dialog: author, contact, GitHub and LinkedIn links, versions.
 */
public class AboutDialog extends Dialog {

    private final String bundleVersion;

    public AboutDialog(Shell parent, String bundleVersion) {
        super(parent);
        this.bundleVersion = bundleVersion;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("About " + AboutInfo.PRODUCT);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.swtDefaults().margins(24, 16).spacing(8, 10).applyTo(area);

        Composite credits = createCredits(area);
        GridDataFactory.swtDefaults().align(SWT.CENTER, SWT.CENTER).indent(0, 4).applyTo(credits);

        linkButton(area, "✉  " + AboutInfo.EMAIL, "mailto:" + AboutInfo.EMAIL);
        linkButton(area, "GitHub  ·  " + AboutInfo.GITHUB_REPO, AboutInfo.GITHUB_URL);
        linkButton(area, "LinkedIn  ·  richard-eric", AboutInfo.LINKEDIN_URL);

        Label separator = new Label(area, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, false).indent(0, 6).applyTo(separator);

        Label version = new Label(area, SWT.CENTER);
        version.setFont(JFaceResources.getTextFont());
        version.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND));
        version.setText("archi-mcp v" + AboutInfo.releaseVersion(bundleVersion) + "  ·  bundle " + bundleVersion);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(version);
        return area;
    }

    /**
     * Footer line: product version and credits on the left, GitHub and About links on the right.
     */
    public static Composite createFooter(Composite parent, String bundleVersion) {
        Composite footer = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(4, 0).applyTo(footer);

        new Label(footer, SWT.NONE).setText(AboutInfo.PRODUCT + " " + AboutInfo.releaseVersion(bundleVersion) + "  · ");
        createCredits(footer);

        Link links = new Link(footer, SWT.NONE);
        links.setText("<a href=\"" + AboutInfo.GITHUB_URL + "\">GitHub</a>     <a href=\"about\">About…</a>");
        GridDataFactory.swtDefaults().align(SWT.END, SWT.CENTER).grab(true, false).applyTo(links);
        links.addListener(SWT.Selection, e -> {
            if ("about".equals(e.text)) {
                new AboutDialog(footer.getShell(), bundleVersion).open();
            }
            else {
                Program.launch(e.text);
            }
        });
        return footer;
    }

    /** "Crafted with ♥ by ..." with a red heart. */
    static Composite createCredits(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(4, 0).applyTo(row);
        new Label(row, SWT.NONE).setText("Crafted with");
        Label heart = new Label(row, SWT.NONE);
        heart.setText("♥");
        heart.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_RED));
        new Label(row, SWT.NONE).setText("by " + AboutInfo.AUTHOR);
        return row;
    }

    private static void linkButton(Composite parent, String text, String url) {
        Button button = new Button(parent, SWT.PUSH | SWT.LEFT);
        button.setText(text);
        button.setToolTipText(url);
        GridDataFactory.fillDefaults().grab(true, false).hint(320, SWT.DEFAULT).applyTo(button);
        button.addListener(SWT.Selection, e -> Program.launch(url));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected boolean isResizable() {
        return false;
    }
}
