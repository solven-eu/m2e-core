/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.ui.internal.actions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.core.MavenLogger;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.index.IIndex;
import org.eclipse.m2e.core.index.IndexedArtifact;
import org.eclipse.m2e.core.index.IndexedArtifactFile;
import org.eclipse.m2e.core.ui.internal.Messages;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectManager;
import org.eclipse.m2e.core.ui.internal.dialogs.MavenRepositorySearchDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionDelegate;
import org.eclipse.ui.part.FileEditorInput;


/**
 * Open POM Action
 * 
 * @author Eugene Kuleshov
 */
public class OpenPomAction extends ActionDelegate implements IWorkbenchWindowActionDelegate {

  private static final String ID = "org.eclipse.m2e.openPomAction"; //$NON-NLS-1$

  String type = IIndex.SEARCH_ARTIFACT;

  private IStructuredSelection selection;

  /* (non-Javadoc)
   * @see org.eclipse.ui.IWorkbenchWindowActionDelegate#init(org.eclipse.ui.IWorkbenchWindow)
   */
  public void init(IWorkbenchWindow window) {
  }

  public void selectionChanged(IAction action, ISelection selection) {
    if(selection instanceof IStructuredSelection) {
      this.selection = (IStructuredSelection) selection;
    } else {
      this.selection = null;
    }
  }

  /* (non-Javadoc)
   * @see org.eclipse.ui.actions.ActionDelegate#run(org.eclipse.jface.action.IAction)
   */
  public void run(IAction action) {
    //TODO mkleint: this asks for rewrite.. having one action that does 2 quite different things based
    // on something as vague as selection passed in is unreadable..
    if(selection != null) {
      Object element = this.selection.getFirstElement();
      if(IIndex.SEARCH_ARTIFACT.equals(type) && element != null) {
        try {
          final ArtifactKey ak = SelectionUtil.getArtifactKey(element);
          if(ak != null) {
            new Job(Messages.OpenPomAction_job_opening) {
              protected IStatus run(IProgressMonitor monitor) {
                openEditor(ak.getGroupId(), ak.getArtifactId(), ak.getVersion(), monitor);
                return Status.OK_STATUS;
              }
            }.schedule();
            return;
          }
        } catch(CoreException ex) {
          MavenLogger.log(ex);
          PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
            public void run() {
              MessageDialog.openInformation(Display.getDefault().getActiveShell(), //
                  Messages.OpenPomAction_open_error_title, Messages.OpenPomAction_open_error_message);
            }
          });
        }
      }
    }

    String title = Messages.OpenPomAction_title_pom;

    Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
    MavenRepositorySearchDialog dialog = MavenRepositorySearchDialog.createOpenPomDialog(shell, title);
    if(dialog.open() == Window.OK) {
      final IndexedArtifactFile iaf = (IndexedArtifactFile) dialog.getFirstResult();
      new Job(Messages.OpenPomAction_job_opening) {
        protected IStatus run(IProgressMonitor monitor) {
          if(iaf != null) {
            openEditor(iaf.group, iaf.artifact, iaf.version, monitor);
          }
          return Status.OK_STATUS;
        }
      }.schedule();
    }
  }

  public static void openEditor(IndexedArtifact ia, IndexedArtifactFile f, IProgressMonitor monitor) {
    if(f == null || ia.getClassname() == null || ia.getPackageName() == null) {
      return;
    }

    String groupId = f.getDependency().getGroupId();
    String artifactId = f.getDependency().getArtifactId();
    String version = f.getDependency().getVersion();

    String name = ia.getClassname();
    String fileName = ia.getPackageName().replace('.', '/') + "/" + ia.getClassname() + ".java"; //$NON-NLS-1$ //$NON-NLS-2$
    String tooltip = groupId + ":" + artifactId + ":" + version + "/" + fileName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    try {
      IMaven maven = MavenPlugin.getDefault().getMaven();

      List<ArtifactRepository> artifactRepositories = maven.getArtifactRepositories();

      Artifact artifact = maven.resolve(groupId, artifactId, version, "java-source", "sources", artifactRepositories, //$NON-NLS-1$ //$NON-NLS-2$
          monitor);

      final File file = artifact.getFile();
      if(file == null) {
        openDialog(NLS.bind(Messages.OpenPomAction_error_download_source, tooltip));
        return;
      }

      // that won't work if source archive have subfolders before actual source tree
      String url = "jar:" + file.toURL().toString() + "!/" + fileName; //$NON-NLS-1$ //$NON-NLS-2$
      InputStream is = new URL(url).openStream();
      byte[] buff = readStream(is);

      openEditor(new MavenPathStorageEditorInput(name + ".java", tooltip, url, buff), name + ".java"); //$NON-NLS-1$ //$NON-NLS-2$

    } catch(IOException ex) {
      String msg = NLS.bind(Messages.OpenPomAction_error_open_editor, name);
      MavenLogger.log(msg, ex);
      openDialog(msg + "\n" + ex.toString()); //$NON-NLS-1$
    } catch(CoreException ex) {
      MavenLogger.log(ex);
      openDialog(ex.getMessage() + "\n" + ex.toString()); //$NON-NLS-1$
    }
  }

  public static IEditorPart openEditor(String groupId, String artifactId, String version, IProgressMonitor monitor) {
    if(groupId.length() > 0 && artifactId.length() > 0) {
      final String name = groupId + ":" + artifactId + ":" + version + ".pom"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

      try {
        MavenPlugin plugin = MavenPlugin.getDefault();

        MavenProjectManager projectManager = plugin.getMavenProjectManager();
        IMavenProjectFacade projectFacade = projectManager.getMavenProject(groupId, artifactId, version);
        if(projectFacade != null) {
          final IFile pomFile = projectFacade.getPom();
          return openEditor(new FileEditorInput(pomFile), name);
        }

        IMaven maven = MavenPlugin.getDefault().getMaven();

        List<ArtifactRepository> artifactRepositories = maven.getArtifactRepositories();

        Artifact artifact = maven.resolve(groupId, artifactId, version, "pom", null, artifactRepositories, monitor); //$NON-NLS-1$

        File file = artifact.getFile();
        if(file != null) {
          return openEditor(new MavenPathStorageEditorInput(name, name, file.getAbsolutePath(),
              readStream(new FileInputStream(file))), name);
        }

        openDialog(NLS.bind(Messages.OpenPomAction_error_download, name));

      } catch(IOException ex) {
        String msg = NLS.bind(Messages.OpenPomAction_error_open_pom, name);
        MavenLogger.log(msg, ex);
        openDialog(msg + "\n" + ex.toString()); //$NON-NLS-1$
      } catch(CoreException ex) {
        MavenLogger.log(ex);
        openDialog(ex.getMessage() + "\n" + ex.toString()); //$NON-NLS-1$
      }
    }

    return null;
  }

  public static IEditorPart openEditor(final IEditorInput editorInput, final String name) {
    final IEditorPart[] part = new IEditorPart[1];
    PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {
      public void run() {
        IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
        IContentType contentType = contentTypeManager.findContentTypeFor(name);
        IEditorRegistry editorRegistry = PlatformUI.getWorkbench().getEditorRegistry();
        IEditorDescriptor editor = editorRegistry.getDefaultEditor(name, contentType);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(window != null) {
          IWorkbenchPage page = window.getActivePage();
          if(page != null) {
            try {
              part[0] = page.openEditor(editorInput, editor.getId());
            } catch(PartInitException ex) {
              MessageDialog.openInformation(Display.getDefault().getActiveShell(), //
                  Messages.OpenPomAction_open_title, NLS.bind(Messages.OpenPomAction_33, editorInput.getName(), ex.toString())); //$NON-NLS-1$
            }
          }
        }
      }
    });
    return part[0];
  }

  private static void openDialog(final String msg) {
    PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
      public void run() {
        MessageDialog.openInformation(Display.getDefault().getActiveShell(), //
            Messages.OpenPomAction_open_title, msg);
      }
    });
  }

  private static byte[] readStream(InputStream is) throws IOException {
    byte[] b = new byte[is.available()];
    int len = 0;
    while(true) {
      int n = is.read(b, len, b.length - len);
      if(n == -1) {
        if(len < b.length) {
          byte[] c = new byte[len];
          System.arraycopy(b, 0, c, 0, len);
          b = c;
        }
        return b;
      }
      len += n;
      if(len == b.length) {
        byte[] c = new byte[b.length + 1000];
        System.arraycopy(b, 0, c, 0, len);
        b = c;
      }
    }
  }

  /**
   * Storage editor input implementation for Maven poms
   */
  public static class MavenStorageEditorInput implements IStorageEditorInput {

    private final String name;

    private final String path;

    private final String tooltip;

    private final byte[] content;

    public MavenStorageEditorInput(String name, String tooltip, String path, byte[] content) {
      this.name = name;
      this.path = path;
      this.tooltip = tooltip;
      this.content = content;
    }

    // IStorageEditorInput

    public boolean exists() {
      return true;
    }

    public String getName() {
      return this.name;
    }

    public String getToolTipText() {
      return this.tooltip;
    }

    public IStorage getStorage() {
      return new MavenStorage(name, path, content);
    }

    public ImageDescriptor getImageDescriptor() {
      return null;
    }

    public IPersistableElement getPersistable() {
      return null;
    }

    @SuppressWarnings("unchecked")
    public Object getAdapter(Class adapter) {
      return null;
    }

    // IPathEditorInput

    public IPath getPath() {
      return path == null ? null : new Path(path);
    }

  }

  public static class MavenPathStorageEditorInput extends MavenStorageEditorInput implements IPathEditorInput {
    public MavenPathStorageEditorInput(String name, String tooltip, String path, byte[] content) {
      super(name, tooltip, path, content);
    }
    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    //implemented as hinted by IPathEditorInput javadoc.
    public boolean equals(Object obj) {
      IPath path = getPath();
      if (path != null && obj instanceof MavenPathStorageEditorInput) {
        return path.equals(((MavenPathStorageEditorInput)obj).getPath());
      }
      return super.equals(obj);
    }
  }
  
  private static class MavenStorage implements IStorage {
    private String name;

    private final String path;

    private final byte[] content;

    public MavenStorage(String name, String path, byte[] content) {
      this.name = name;
      this.path = path;
      this.content = content;
    }

    public String getName() {
      return name;
    }

    public IPath getFullPath() {
      return path == null ? null : new Path(path);
    }

    public InputStream getContents() {
      return new ByteArrayInputStream(content);
    }

    public boolean isReadOnly() {
      return true;
    }

    @SuppressWarnings("unchecked")
    public Object getAdapter(Class adapter) {
      return null;
    }

    
  }

}
