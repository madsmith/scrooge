package com.twitter;

import com.google.common.collect.ImmutableSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.google.common.base.Join.join;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Collections.list;
import static org.codehaus.plexus.util.FileUtils.*;


/**
 * Abstract Mojo implementation.
 * This class is extended by {@link MavenScroogeCompileMojo} and
 * {@link MavenScroogeTestCompileMojo} in order to override the specific configuration for
 * compiling the main or test classes respectively.
 *
 * @requiresDependencyResolution
 */
abstract class AbstractMavenScroogeMojo extends AbstractMojo {

  private static final String THRIFT_FILE_SUFFIX = ".thrift";

  private static final String DEFAULT_INCLUDES = "**/*" + THRIFT_FILE_SUFFIX;

  /**
   * The current Maven project.
   *
   * @parameter default-value="${project}"
   * @readonly
   * @required
   */
  protected MavenProject project;

  /**
   * A helper used to add resources to the project.
   *
   * @component
   * @required
   */
  protected MavenProjectHelper projectHelper;

  /**
   * A set of include directories to pass to the thrift compiler.
   * @parameter
   */
  private Set<File> thriftIncludes = new HashSet<File>();

  /**
  * Which language the generated files should be ("java" or "scala")
  * @parameter default-value="scala"
   * {@code
   * <configuration>
   *     <language>java</language>
   * </configuration>
   * }
  */
  private String language;

  /**
   * Command line options to pass to scrooge, e.g.
   * {@code
   * <thriftOpts>
   *   <thriftOpt>--finagle</thriftOpt>
   *   <thriftOpt>--ostrich</thriftOpt>
   * </thriftOpts>
   *}
   * @parameter
   */
  private Set<String> thriftOpts = new HashSet<String>();

  /**
   * List of dependencies to extract thrift files from, even if they
   * do not have idl classifier. Make sure to include the
   * correct artifact name (eg. finagle-thrift, not just finagle)
   * {@code
   * <dependencyIncludes>
   *     <include>finagle-thrift</include>
   * </dependencyIncludes>
   * }
   *  @parameter
   */
  private Set<String> dependencyIncludes = new HashSet<String>();

  /**
   * A set of namespace mappings to pass to the thrift compiler, e.g.
   * {@code
   * <thriftNamespaceMappings>
   *   <thriftNamespaceMapping>
   *      <from>com.twitter</from>
   *      <to>com.twitter.thriftscala</to>
   *   </thriftNamespaceMapping>
   * </thriftNamespaceMappings>
   * }
   *
   * Only used by the scrooge generator, usually to avoid clashes with Java namespaces.
   * @parameter
   */
  private Set<ThriftNamespaceMapping> thriftNamespaceMappings = new HashSet<ThriftNamespaceMapping>();


  /**
   * A set of IDL mappings to pass to the thrift compiler, e.g.
   * {@code
   * <includeMappings>
   *   <includeMapping>
   *     <include>external.thrift</include>
   *     <artifactId>some-external-project</artifactId>
   *   </includeMapping>
   * </includeMappings>
   *}
   *
   * @parameter
   */
  private Set<IncludeMapping> includeMappings = new HashSet<IncludeMapping>();

  /**
   * A set of include patterns used to filter thrift files.
   * @parameter
   */
  private Set<String> includes = ImmutableSet.of(DEFAULT_INCLUDES);

  /**
   * A set of exclude patterns used to filter thrift files.
   * @parameter
   */
  private Set<String> excludes = ImmutableSet.of();

  /**
   * Whether or not to fix hashcode being default 0
   * @parameter
   */
  private boolean fixHashcode = false;

  /**
   * Whether or not to skip thrift generation if generated files are newer than source files.
   * @parameter
   */
  private boolean checkStaleness = true;

  /**
   * Delta to use for triggering thrift regeneration
   * @parameter
   */
  private long staleMillis = 0;

  private static Object lock = new Object();

  /**
   * Picks out a File from `thriftFiles` corresponding to a given artifact ID
   * and file name. Returns null if `artifactId` and `fileName` do not map to a
   * thrift file path.
   *
   * @parameter artifactId The artifact ID of which to look up the path
   * @parameter fileName the name of the thrift file for which to extract a path
   * @parameter thriftFiles The set of Thrift files in which to lookup the
   *            artifact ID.
   * @return The path of the directory containing Thrift files for the given
   *         artifact ID. null if artifact ID not found.
   */
  private File extractThriftFile(String artifactId, String fileName, Set<File> thriftFiles) {
    for (File thriftFile : thriftFiles) {
      boolean fileFound = false;
      if (fileName.equals(thriftFile.getName())) {
        for (String pathComponent : thriftFile.getPath().split(File.separator)) {
          if (pathComponent.equals(artifactId)) {
            fileFound = true;
          }
        }
      }

      if (fileFound) {
        return thriftFile;
      }
    }
    return null;
  }

  /**
   * Executes the mojo.
   */
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      Set<File> thriftFiles = findThriftFiles();

      final File outputDirectory = getOutputDirectory();
      ImmutableSet<File> outputFiles = findGeneratedFilesInDirectory(getOutputDirectory());

      Set<String> compileRoots = new HashSet<String>();
      compileRoots.add("scrooge");

      if (thriftFiles.isEmpty()) {
        getLog().info("No thrift files to compile.");
      } else if (checkStaleness && ((lastModified(thriftFiles) + staleMillis) < lastModified(outputFiles))) {
        getLog().info("Generated thrift files up to date, skipping compile.");
        attachFiles(compileRoots);
      } else {
        outputDirectory.mkdirs();

        // Quick fix to fix issues with two mvn installs in a row (ie no clean)
        cleanDirectory(outputDirectory);

        getLog().info(format("compiling thrift files %s with Scrooge", thriftFiles));
        synchronized(lock) {
          ScroogeRunner runner = new ScroogeRunner();
          Map<String, String> thriftNamespaceMap = new HashMap<String, String>();
          for (ThriftNamespaceMapping mapping : thriftNamespaceMappings) {
            thriftNamespaceMap.put(mapping.getFrom(), mapping.getTo());
          }

          Map<String, String> includeMap = new HashMap<String, String>();
          for (IncludeMapping mapping : includeMappings) {
            String fileName = mapping.getInclude();
            File mappedFile = extractThriftFile(mapping.getArtifactId(), fileName, thriftFiles);

            if (mappedFile != null) {
              includeMap.put(fileName, mappedFile.getPath());
            }
          }

          // Include thrifts from resource as well.
          Set<File> includes = thriftIncludes;
          includes.add(getResourcesOutputDirectory());

          runner.compile(
                  getLog(),
                  new File(outputDirectory, "scrooge"),
                  thriftFiles,
                  includes,
                  thriftNamespaceMap,
                  includeMap,
                  language,
                  thriftOpts);
        }
        attachFiles(compileRoots);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("An IO error occured", e);
    }
  }

  /**
   * Where our local thrift files live.
   */
  protected abstract File getThriftSourceRoot();

  /**
   * Where our generated files go.
   */
  protected abstract File getOutputDirectory();

  /**
   * Where all our thrift files (from references, dependencies, local) get copied.
   */
  protected abstract File getResourcesOutputDirectory();

  /**
   * Add newly created files to the project.
   * @return A set of directories that contain generated source files.
   */
  protected abstract void attachFiles(Set<String> compileRoots);

  /**
   * What scope should we look at for dependent thrift files in {@link #getDependencyArtifacts()}.
   * @return A string used to filter scope, e.g. "compile".
   */
  protected abstract String getDependencyScopeFilter();

  /**
   * Thrift files from referenced projects.
   */
  protected abstract List<File> getReferencedThriftFiles() throws IOException;

  /**
   * Get the last modified time for a set of files.
   */
  private long lastModified(Set<File> files) {
    long result = 0;
    for (File file : files) {
      if (file.lastModified() > result)
        result = file.lastModified();
    }
    return result;
  }

  /**
   * build a complete set of local files, files from referenced projects, and dependencies.
   */
  private Set<File> findThriftFiles() throws IOException {
    final File thriftSourceRoot = getThriftSourceRoot();
    Set<File> thriftFiles = new HashSet<File>();
    if (thriftSourceRoot != null && thriftSourceRoot.exists()) {
      thriftFiles.addAll(findThriftFilesInDirectory(thriftSourceRoot));
    }
    getLog().info("finding thrift files in dependencies");
    extractFilesFromDependencies(findThriftDependencies(dependencyIncludes), getResourcesOutputDirectory());
    if (getResourcesOutputDirectory().exists()) {
      thriftFiles.addAll(findThriftFilesInDirectory(getResourcesOutputDirectory()));
    }
    getLog().info("finding thrift files in referenced (reactor) projects");
    thriftFiles.addAll(getReferencedThriftFiles());
    return thriftFiles;
  }

  /**
   * Iterate through dependencies, find those specified in the whitelist
   */
  private Set<Artifact> findThriftDependencies(Set<String> whitelist) throws IOException {
    Set<Artifact> thriftDependencies = new HashSet<Artifact>();
    Set<Artifact> allDependencies = new HashSet<Artifact>();
    allDependencies.addAll(project.getArtifacts());
    allDependencies.addAll(project.getDependencyArtifacts());

    for(Artifact artifact : allDependencies) {
      String artifactId = artifact.getArtifactId();
      if (whitelist.contains(artifactId)) {
        thriftDependencies.add(artifact);
      }
    }
    return thriftDependencies;
  }

  /**
   * Copy thrift files from dependency jars to {@link #getResourcesOutputDirectory()}.
   * @param dependencies A set of jar artifacts ths project depends on.
   * @param output The directory to copy any found files into.
   */
  private void extractFilesFromDependencies(Collection<Artifact> dependencies, File output) throws IOException {
    for (Artifact artifact : dependencies) {
      File dep = artifact.getFile();
      getLog().info("extracting thrift files from " + dep.getCanonicalPath());
      File destFolder = new File(output, artifact.getArtifactId());
      destFolder.mkdirs();
      if (dep.isFile() && dep.canRead() && dep.getName().endsWith(".jar")) {
        JarFile jar = new JarFile(dep);
        for (JarEntry entry : list(jar.entries())) {
          if (entry.getName().endsWith(THRIFT_FILE_SUFFIX)) {
            File destination = new File(destFolder, entry.getName());
            getLog().info(format("extracting %s to %s", entry.getName(), destination.getCanonicalPath()));
            copyStreamToFile(new RawInputStreamFacade(jar.getInputStream(entry)), destination);
            if (!destination.setLastModified(dep.lastModified()))
              getLog().warn(format("fail to set last modified time for %s", destination.getCanonicalPath()));
          }
        }
      } else {
        getLog().warn(format("dep %s isn't a file or can't be read", dep.getCanonicalPath()));
      }
    }
  }

  /**
   * Find all {@code .java} and {@code .scala} files in the given directory.
   */
  private ImmutableSet<File> findGeneratedFilesInDirectory(File directory) throws IOException {
    if (directory == null || !directory.isDirectory())
      return ImmutableSet.of();
    List<File> sourceFilesInDirectory = getFiles(directory, "**/*.java", null);
    sourceFilesInDirectory.addAll(getFiles(directory, "**/*.scala", null));
    return ImmutableSet.copyOf(sourceFilesInDirectory);
  }

  /**
   * Find all {@code .thrift} files in the given directory.
   */
  private ImmutableSet<File> findThriftFilesInDirectory(File directory) throws IOException {
    checkNotNull(directory);
    checkArgument(directory.isDirectory(), "%s is not a directory", directory);
    List<File> thriftFilesInDirectory = getFiles(directory, join(",", includes), join(",", excludes));
    return ImmutableSet.copyOf(thriftFilesInDirectory);
  }

  /**
   * Walk project references recursively, building up a list of thrift files they provide, starting
   * with an empty file list.
   */
  protected List<File> getRecursiveThriftFiles(MavenProject project, String outputDirectory) throws IOException {
    return getRecursiveThriftFiles(project, outputDirectory, new ArrayList<File>());
  }

  /**
   * Walk project references recursively, adding thrift files to the provided list.
   */
  List<File> getRecursiveThriftFiles(MavenProject project, String outputDirectory, List<File> files) throws IOException {
    if (dependencyIncludes.contains(project.getArtifactId())) {
      File dir = new File(new File(project.getFile().getParent(), "target"), outputDirectory);
      if (dir.exists()) {
        URI baseDir = getFileURI(dir);
        for (File f : findThriftFilesInDirectory(dir)) {
          URI fileURI = getFileURI(f);
          String relPath = baseDir.relativize(fileURI).getPath();
          File destFolder = new File(getResourcesOutputDirectory(), project.getArtifactId());
          destFolder.mkdirs();
          File destFile = new File(destFolder, relPath);
          getLog().info(format("copying %s to %s", f.getCanonicalPath(), destFile.getCanonicalPath()));
          copyFile(f, destFile);
          files.add(destFile);
        }
      }
    }
    Map<String, MavenProject> refs = project.getProjectReferences();
    for (String name : refs.keySet()) {
      getRecursiveThriftFiles(refs.get(name), outputDirectory, files);
    }
    return files;
  }

  URI getFileURI(File file) throws IOException {
    try {
      return new URI("file://"+(file.getCanonicalPath().replace("\\","/")));
    } catch (URISyntaxException urie) {
      throw new IOException("error forming URI for file transfer: " + urie);
    }
  }
}
