package com.googlecode.goclipse.builder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;

import com.googlecode.goclipse.Activator;
import com.googlecode.goclipse.Environment;
import com.googlecode.goclipse.SysUtils;
import com.googlecode.goclipse.dependency.IDependencyVisitor;
import com.googlecode.goclipse.dependency.DependencyGraph;
/**
 * limitations: 
 * - dependency is computed at package level - full package is built everytime a file is changed
 * 
 * @author ami
 *
 */
public class GoDependencyManager implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private transient Map<String, String> env;
	private transient boolean caseInsensitive = false;

	private transient ExternalCommand depToolCmd = null;

	DependencyGraph manager = new DependencyGraph();
	
	//turns true after a full build
	private transient boolean fullBuild = false;
	//turns false after first incremental
	private transient boolean firstTime = true;
	

	public GoDependencyManager() {
		depToolCmd = new ExternalCommand(Environment.INSTANCE.getDependencyTool());
	}

	public void setEnvironment(Map<String, String> env) {
		this.env = env;
		if (env.get(GoConstants.GOOS).equals("windows")) {
			//paths in Windows are case insensitive. 
			//it will convert all paths to lowercase in maps for searching
			caseInsensitive = true;
		} else {
			caseInsensitive = false;
		}
	}

	/**
	 * removes dependencies for resources that will be removed.
	 * 
	 * @param toRemove
	 * @param pmonitor
	 */
	public void removeDep(List<IResource> toRemove, IProgressMonitor pmonitor) {
		for (IResource res : toRemove) {
			IPath relPath = res.getProjectRelativePath();
			
			//remove entries from files that have this resource as dependent
			//don't care about files that depend on the resource. they will be updated some other way
			manager.removeItem(relPath.toOSString());
		}
	}
	
	

	/**
	 * computes and updates dependencies for given files
	 * 
	 * @param toChange
	 * @param pmonitor
	 */
	public void buildDep(List<IResource> toChange,
			IProgressMonitor pmonitor) {
		depToolCmd.setEnvironment(env);
		StreamAsLines output = new StreamAsLines();
		depToolCmd.setResultsFilter(output);
		
		for (IResource res : toChange) {
			IPath resourceLocation = res.getLocation();
			String resourceFullPath = resourceLocation.toOSString();
			if (!isTestFile(resourceLocation.lastSegment())){
				IPath resourceRelativePath = res.getProjectRelativePath();
				String pathInPrj = resourceRelativePath.toOSString();
				IProject project = res.getProject();
				
				//remove entries from files that have this resource as dependent
				//don't care about files that depend on the resource. they will be updated some other way
				manager.removeItem(pathInPrj);
				
				List<String> depToolParams = new ArrayList<String>();
				depToolParams.add(resourceFullPath);
				String result = depToolCmd.execute(depToolParams, true);
				if (result != null){
					Activator.getDefault().getLog().log(new Status(Status.WARNING,Activator.PLUGIN_ID, "Error parsing dependencies: "+result));
					continue;
				}
	
				boolean first = true;
				String dPackName = "";
				for (String pkgImport: output.getLines()) {
					//pkgImport (actually a file reference) may be global or relative to the path of the resource
					//if it is relative, it starts with .
					//global packages are ignored in source dependencies
					//first output line contains the name of the declared package with "p:" as prefix
					IPath srcFolderPath = resourceRelativePath.removeLastSegments(1);
					if (first) {
						//handle package declaration
						first = false;
	
						if (Environment.INSTANCE.isCmdFile(resourceRelativePath)){
							String cmdName;
							cmdName = getCmdName(resourceRelativePath);
							IPath objFile = getObjectFilePath(cmdName, resourceRelativePath.removeLastSegments(1));
							IPath executablePath = getExecutablePath(cmdName, project);

							// compilation is two-step, compile into object file, and link into executable
							manager.addDependency(objFile.toOSString(), pathInPrj); // e.g. src/cmd/app/_obj/app.8 depends on src/cmd/app/main.go
							manager.addDependency(executablePath.toOSString(), objFile.toOSString()); // e.g bin/linux_386/app.exe depends on src/cmd/app/_obj/a.8
						} else if (Environment.INSTANCE.isPkgFile(resourceRelativePath)) {
							dPackName = pkgImport.substring(2, pkgImport.length());
							IPath packageFullPath = getLocalLibraryFromPath(srcFolderPath, dPackName, project);
							IPath objFilePath = getObjectFilePath(dPackName, srcFolderPath);
							
							// compilation is two-step, compile into object file, and archive into library
							manager.addDependency(objFilePath.toOSString(), pathInPrj);  // e.g. src/pkg/foo/_obj/foo.8 depends on src/pkg/foo/foo.go
							manager.addDependency(packageFullPath.toOSString(), objFilePath.toOSString()); // e.g. pkg/linux_386/foo.a depends on src/pkg/foo/_obj/foo.8
							continue;
						}
					} else {
						//compute relative path to project for the imported file.
						String noQuotes = pkgImport.substring(1, pkgImport.length() - 1);
						if (!Environment.INSTANCE.isStandardLibrary(project, noQuotes)) {
							IPath packageFullPath = getLocalLibraryPath(noQuotes, project);
							if (Environment.INSTANCE.isCmdFile(resourceRelativePath)){
								String cmdName = getCmdName(resourceRelativePath);
								IPath objFile = getObjectFilePath(cmdName, resourceRelativePath.removeLastSegments(1));
								manager.addDependency(objFile.toOSString(), packageFullPath.toOSString()); // e.g. src/cmd/app/_obj/app.8 depends on pkg/linux_386/bar.a
								
							} else if (Environment.INSTANCE.isPkgFile(resourceRelativePath)) {
								IPath objFilePath = getObjectFilePath(dPackName, srcFolderPath);
								manager.addDependency(objFilePath.toOSString(), packageFullPath.toOSString()); // e.g. src/pkg/foo/_obj/util.8 depends on pkg/linux_386/bar.a
							}			
						}
					}
				}
			}
		}
		System.out.println(manager.toString());
	}

	private boolean isTestFile(String fileName) {
		return fileName.endsWith(GoConstants.TEST_FILE_DIRECTORY+GoConstants.GO_SOURCE_FILE_EXTENSION) || GoConstants.GO_TEST_MAIN.equals(fileName);
	}

	public static String getCmdName(IPath resourceRelativePath) {
		IPath srcFolderPath = resourceRelativePath.removeLastSegments(1);
		String cmdName;
		// if it's directly in the cmd folder, name it by filename, else name it by folder name
		if (Environment.INSTANCE.getDefaultCmdSourceFolder().equals(srcFolderPath)){
			cmdName = resourceRelativePath.removeFileExtension().lastSegment();
		} else {
			cmdName = srcFolderPath.lastSegment();
		}
		return cmdName;
	}

	private IPath getLocalLibraryPath(String packagePath, IProject project) {
		return Environment.INSTANCE.getPkgOutputFolder(project).append(packagePath+GoConstants.GO_LIBRARY_FILE_EXTENSION);
	}
	
	/**
	 * 
	 * @param srcFolderPath the folder that the source file lives in
	 * @param packageName
	 * @param project
	 * @return
	 */
	private IPath getLocalLibraryFromPath(IPath srcFolderPath, String packageName, IProject project) {
		IPath pkgFolder = Environment.INSTANCE.getDefaultPkgSourceFolder();
		srcFolderPath = srcFolderPath.removeLastSegments(1).makeRelativeTo(pkgFolder);
		return Environment.INSTANCE.getPkgOutputFolder(project).append(srcFolderPath).append(packageName+GoConstants.GO_LIBRARY_FILE_EXTENSION);
	}

	public static IPath getExecutablePath(String cmdName, IProject project) {
		Environment instance = Environment.INSTANCE;
		Arch arch = instance.getArch();
		IPath binOutputFolder = instance.getBinOutputFolder(project);
		IPath executablePath = binOutputFolder.append(cmdName+instance.getExecutableExtension());
		return executablePath;
	}

	public static IPath getObjectFilePath(String dPackName, IPath srcFolderPath) {
		Arch arch = Environment.INSTANCE.getArch();
		IPath objPath = srcFolderPath.append(GoConstants.OBJ_FILE_DIRECTORY);
		IPath objFilePath = objPath.append(dPackName + arch.getExtension());
		return objFilePath;
	}

	/**
	 * clears dependencies for the project
	 * 
	 * @param pmonitor
	 */
	public void clearState(IProgressMonitor pmonitor) {
		manager.reset();
	}
	
	public List<IResource> getResources(List<String> relPaths, IProject project) {
		List<IResource> res = new ArrayList<IResource>();
		for (String path : relPaths) {
			IResource r = project.getFile(path);
			if (r != null) {
				res.add(r);
			} else {
				SysUtils.warning("can't find resource: " + path);
			}
		}
		return res;
	}

	/**
	 * 
	 * @param paths
	 * @param pmonitor
	 * @return <package name>, List<project relative paths>
	 * there may be files in the same package but in different folders!
	 */
	public void accept(Set<String> paths, IDependencyVisitor visitor) {
		if (paths == null){
			manager.accept(visitor);
		} else {
			manager.accept(paths, visitor);
		}
		
			
	}

	public void prepare(IProject project, boolean isFull){
		if (!isFull && !firstTime) {
			//if full build was not executed at least once this session
			//then load saved configuration
			loadSaved(project);
		}
		firstTime = false;
	}
	
	public void loadSaved(IProject project) {
		SysUtils.debug("loading dependencies");
		IWorkspace root = ResourcesPlugin.getWorkspace();
		IPath base = root.getRoot().getLocation();
		IPath prj = base.append(".metadata").append(".go").append(".prj").append(project.getName());
		String inFile = prj.append("dep.data").toOSString();
		// Read from disk using FileInputStream
		FileInputStream fIn;
		try {
			fIn = new FileInputStream(inFile);
			// Read object using ObjectInputStream
			ObjectInputStream objIn = new ObjectInputStream(fIn);
			// Read an object
			Object obj = objIn.readObject();
			GoDependencyManager gdm = (GoDependencyManager)obj;
			objIn.close();
			SysUtils.debug(gdm.toString());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void save(IProject project) {
		SysUtils.debug("saving dependencies for project " + project);
		
		IWorkspace root = ResourcesPlugin.getWorkspace();
		IPath base = root.getRoot().getLocation();
		IPath prj = base.append(".metadata").append(".go").append(".prj").append(project.getName());
		String prjPath = prj.toOSString();
		File prjFile = new File(prjPath);
		if (!prjFile.exists()) {
			prjFile.mkdirs();
		}
		String outFile = prj.append("dep.data").toOSString();
		try {
			// Write to disk with FileOutputStream
			FileOutputStream fOut = new FileOutputStream(outFile);
	
			// Write object with ObjectOutputStream
			ObjectOutputStream objOut = new	ObjectOutputStream (fOut);
	
			// Write object out to disk
			objOut.writeObject(this);
			objOut.close();
		}catch(Exception e) {
			e.printStackTrace();
		}
		
	}

	
}