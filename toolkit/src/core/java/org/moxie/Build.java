/*
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moxie;

import static java.text.MessageFormat.format;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.moxie.Toolkit.Key;
import org.moxie.console.Console;
import org.moxie.maxml.MaxmlException;
import org.moxie.maxml.MaxmlMap;
import org.moxie.utils.Base64;
import org.moxie.utils.DeepCopier;
import org.moxie.utils.FileUtils;
import org.moxie.utils.StringUtils;


/**
 * Represents the current build (build.moxie, settings.moxie, and build state)
 */
public class Build {

	public static final Repository MAVENCENTRAL = new Repository("MavenCentral", "http://repo1.maven.org/maven2");
	public static final Repository CENTRAL = new Repository("Central", "http://repo1.maven.org/maven2");
	public static final Repository CODEHAUS = new Repository("Codehaus", "http://repository.codehaus.org");
	public static final Repository JAVANET = new Repository("Java.net", "http://download.java.net/maven/2");
	public static final Repository RESTLET = new Repository("Restlet", "http://maven.restlet.org");
	public static final Repository GOOGLECODE = new GoogleCode();
	
	public static final Repository [] REPOSITORIES = { MAVENCENTRAL, CENTRAL, CODEHAUS, JAVANET, RESTLET, GOOGLECODE };
	
	public final Set<Proxy> proxies;
	public final Set<Repository> repositories;
	public final Config moxie;
	public final Config project;
	public final MoxieCache moxieCache;
	public final Console console;
	
	private final Map<String, Dependency> aliases;
	private final Map<Scope, Set<Dependency>> solutions;	
	private final Map<Scope, List<File>> classpaths;
	
	private final File configFile;
	private final File projectFolder;
	
	private List<Build> linkedProjects;
	
	private boolean silent;
	private boolean verbose;
	private boolean solutionBuilt;
	
	public Build(File configFile, File basedir) throws MaxmlException, IOException {
		this.configFile = configFile;
		if (basedir == null) {
			this.projectFolder = configFile.getAbsoluteFile().getParentFile();
		} else {
			this.projectFolder = basedir;
		}
		
		// allow specifying Moxie root folder
		File moxieRoot = new File(System.getProperty("user.home") + "/.moxie");
		if (System.getProperty(Toolkit.MX_ROOT) != null) {
			String value = System.getProperty(Toolkit.MX_ROOT);
			if (!StringUtils.isEmpty(value)) {
				moxieRoot = new File(value);
			}
		}
		moxieRoot.mkdirs();
		
		this.moxie = new Config(new File(moxieRoot, Toolkit.MOXIE_SETTINGS), projectFolder, Toolkit.MOXIE_SETTINGS);
		this.project = new Config(configFile, projectFolder, Toolkit.MOXIE_DEFAULTS);
		
		this.proxies = new LinkedHashSet<Proxy>();
		this.repositories = new LinkedHashSet<Repository>();
		this.moxieCache = new MoxieCache(moxieRoot);
		this.solutions = new HashMap<Scope, Set<Dependency>>();
		this.classpaths = new HashMap<Scope, List<File>>();
		this.linkedProjects = new ArrayList<Build>();
		this.console = new Console(isColor());
		this.console.setDebug(isDebug());

		console.debug("determining proxies and repositories");
		determineProxies();
		determineRepositories();
		
		console.debug("building alias map");
		aliases = new HashMap<String, Dependency>();
		aliases.putAll(moxie.dependencyAliases);
		aliases.putAll(project.dependencyAliases);
	}
	
	@Override
	public int hashCode() {
		return 11 + configFile.hashCode();
	}
	
	@Override
	public boolean equals(Object o) {
		if (o instanceof Build) {
			return configFile.equals(((Build) o).configFile);
		}
		return false;
	}
	
	public boolean isColor() {
		String mxColor = System.getProperty(Toolkit.MX_COLOR, null);
		if (StringUtils.isEmpty(mxColor)) {
			// use Moxie apply setting
			return moxie.apply(Toolkit.APPLY_COLOR) || project.apply(Toolkit.APPLY_COLOR);
		} else {
			// use system property to determine color
			return Boolean.parseBoolean(mxColor);
		}
	}
	
	public boolean isDebug() {
		String mxDebug = System.getProperty(Toolkit.MX_DEBUG, null);
		if (StringUtils.isEmpty(mxDebug)) {
			// use Moxie apply setting
			return moxie.apply(Toolkit.APPLY_DEBUG) || project.apply(Toolkit.APPLY_DEBUG);
		} else {
			// use system property to determine debug
			return Boolean.parseBoolean(mxDebug);
		}
	}

	public boolean isVerbose() {
		return verbose;
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}
	
	public boolean isOnline() {
		String mxOnline = System.getProperty(Toolkit.MX_ONLINE, null);
		if (!StringUtils.isEmpty(mxOnline)) {
			// use system property to determine online
			return Boolean.parseBoolean(mxOnline);
		}
		return true;
	}
	
	public boolean isUpdateMetadata() {
		String mxUpdateMetadata = System.getProperty(Toolkit.MX_UPDATEMETADATA, null);
		if (!StringUtils.isEmpty(mxUpdateMetadata)) {
			// use system property to force updating maven-metadata.xml
			return Boolean.parseBoolean(mxUpdateMetadata);
		}
		return false;
	}

	private boolean cache() {
		return moxie.apply(Toolkit.APPLY_CACHE) || project.apply(Toolkit.APPLY_CACHE);
	}
	
	public void setup() {
		solve();
		
		if (project.apply.size() > 0) {
			console.separator();
			console.log("apply");
			boolean applied = false;
			
			// create/update Eclipse configuration files
			if (solutionBuilt && project.apply(Toolkit.APPLY_ECLIPSE)) {
				writeEclipseClasspath();
				writeEclipseProject();
				console.notice(1, "rebuilt Eclipse configuration");
				applied = true;
			}
		
			// create/update Maven POM
			if (solutionBuilt && project.apply(Toolkit.APPLY_POM)) {
				writePOM();
				console.notice(1, "rebuilt pom.xml");
				applied = true;
			}
			
			if (!applied) {
				console.log(1, "nothing applied");
			}
		}
	}
	
	private void determineProxies() {
		proxies.addAll(project.getActiveProxies());
		proxies.addAll(moxie.getActiveProxies());
	}
	
	private void determineRepositories() {
		List<String> urls = new ArrayList<String>();
		urls.addAll(project.repositoryUrls);
		urls.addAll(moxie.repositoryUrls);
		
		for (String url : urls) {
			boolean identified = false;
			for (Repository repository : REPOSITORIES) {
				if (repository.repositoryUrl.equalsIgnoreCase(url) || repository.name.equalsIgnoreCase(url)) {
					repositories.add(repository);
					identified = true;
					break;
				}	
			}
			if (!identified) {
				// unidentified repository
				repositories.add(new Repository(null, url));
			}
		}

		// default to central
		if (repositories.size() == 0) {
			repositories.add(MAVENCENTRAL);
		}
	}
	
	private void resolveAliasedDependencies() {
		resolveAliasedDependencies(project.pom.getDependencies(false).toArray(new Dependency[0]));
	}
	
	private void resolveAliasedDependencies(Dependency... dependencies) {
		for (Dependency dep : dependencies) {
			// check for alias
			String name = null;
			if (StringUtils.isEmpty(dep.artifactId) && aliases.containsKey(dep.groupId)) {
				// alias by simple name
				name = dep.groupId;
			} else if (aliases.containsKey(dep.getManagementId())) {
				// alias by groupId:artifactId
				name = dep.getManagementId();
			}

			if (name != null) {
				// we have an alias
				Dependency alias = aliases.get(name);
				dep.groupId = alias.groupId;
				dep.artifactId = alias.artifactId;
				dep.version = alias.version;
				
				if (StringUtils.isEmpty(dep.version)) {
					dep.version = project.pom.getManagedVersion(dep);
					if (StringUtils.isEmpty(dep.version)) {
						dep.version = moxie.pom.getManagedVersion(dep);
					}
				}
				if (StringUtils.isEmpty(dep.version)) {
					console.error("unable to resolve version for alias {0} = ", name, dep.getCoordinates());
				} else {
					console.debug("resolved dependency alias {0} = {1}", name, dep.getCoordinates());
				}
			}
		}
	}
	
	public Pom getPom() {
		return project.pom;
	}
	
	public MaxmlMap getMxJavacAttributes() {
		return project.mxjavac;
	}

	public MaxmlMap getMxJarAttributes() {
		return project.mxjar;
	}

	public MaxmlMap getMxReportAttributes() {
		return project.mxreport;
	}
	
	public Map<String, String> getExternalProperties() {
		return project.externalProperties;
	}
	
	public List<SourceFolder> getSourceFolders() {
		return project.sourceFolders;
	}

	public List<File> getSourceFolders(Scope scope) {
		List<File> folders = new ArrayList<File>();
		for (SourceFolder sourceFolder : project.sourceFolders) {
			if (scope == null || sourceFolder.scope.equals(scope)) {				
				folders.add(sourceFolder.getSources());
			}
		}
		return folders;
	}
	
	public List<Build> getLinkedProjects() {
		return linkedProjects;
	}

	public MoxieCache getMoxieCache() {
		return moxieCache;
	}
	
	public Collection<Repository> getRepositories() {
		return repositories;
	}
	
	public java.net.Proxy getProxy(String url) {
		if (proxies.size() == 0) {
			return java.net.Proxy.NO_PROXY;
		}
		for (Proxy proxy : proxies) {
			if (proxy.active && proxy.matches(url)) {
				return new java.net.Proxy(java.net.Proxy.Type.HTTP, proxy.getSocketAddress());
			}
		}
		return java.net.Proxy.NO_PROXY;
	}
	
	public String getProxyAuthorization(String url) {
		for (Proxy proxy : proxies) {
			if (proxy.active && proxy.matches(url)) {
				return "Basic " + Base64.encodeBytes((proxy.username + ":" + proxy.password).getBytes());
			}
		}
		return "";
	}
	
	public Pom getPom(Dependency dependency) {
		return PomReader.readPom(moxieCache, dependency);
	}

	public File getArtifact(Dependency dependency) {
		return moxieCache.getArtifact(dependency, dependency.type);
	}

	public Set<Dependency> getDependencies(Scope scope) {
		return solve(scope);
	}
	
	public void solve() {
		readProjectSolution();
		if (solutions.size() == 0) {
			// solve linked projects
			solveLinkedProjects();
			
			// substitute aliases with definitions
			resolveAliasedDependencies();

			// build solution
			retrievePOMs();
			importDependencyManagement();
			assimilateDependencies();
			retrieveDependencies();
			
			// cache built solution
			cacheProjectSolution();
			
			// flag new solution
			solutionBuilt = true;
		} else {
			// we may have a cached solution, but we need to confirm we have
			// the pom and artifacts
			Set<Dependency> all = new LinkedHashSet<Dependency>();
			for (Map.Entry<Scope, Set<Dependency>> entry : solutions.entrySet()) {
				all.addAll(entry.getValue());
			}
			for (Dependency dep : all) {
				retrievePOM(dep);
				retrieveArtifact(dep, true);
			}
		}
	}
	
	private void solveLinkedProjects() {
		if (project.linkedProjects.size() > 0) {
			console.separator();
			console.log("solving {0} linked projects", project.getPom().getManagementId());
			console.separator();
		}
		Set<Build> builds = new LinkedHashSet<Build>();
		for (LinkedProject linkedProject : project.linkedProjects) {
			console.debug(Console.SEP);
			String resolvedName = getPom().resolveProperties(linkedProject.name);
			if (resolvedName.equals(linkedProject.name)) {
				console.debug("locating linked project {0}", linkedProject.name);
			} else {
				console.debug("locating linked project {0} ({1})", linkedProject.name, resolvedName);
			}
			File projectDir = new File(resolvedName);
			console.debug(1, "trying {0}", projectDir.getAbsolutePath());
			if (!projectDir.exists()) {
				projectDir = new File(projectFolder.getParentFile(), resolvedName);
				console.debug(1, "trying {0}", projectDir.getAbsolutePath());
				if (!projectDir.exists()) {
					console.error("failed to find linked project \"{0}\".", linkedProject.name);
					continue;
				}
			}
			try {
				File file = new File(projectDir, linkedProject.descriptor);
				if (file.exists()) {
					// use Moxie config
					console.debug("located linked project {0} ({1})", linkedProject.name, file.getAbsolutePath());
					Build subProject = new Build(file.getAbsoluteFile(), null);
					subProject.silent = true;
					console.log(1, "=> project {0}", subProject.getPom().getCoordinates());
					subProject.solve();
					// add this subproject and it's dependent projects
					builds.add(subProject);
					builds.addAll(subProject.linkedProjects);
					
					// linked project dependencies are considered ring-0
					for (Scope scope : new Scope[] { Scope.compile }) {
						for (Dependency dep : subProject.getPom().getDependencies(scope, 0)) {
							project.getPom().addDependency(dep, scope);
						}
					}
				} else {
					console.error("linked project {0} does not have a {1} descriptor!", linkedProject.name, linkedProject.descriptor);
				}
			} catch (Exception e) {
				console.error(e, "failed to parse linked project {0}", linkedProject.name);
				throw new RuntimeException(e);
			}
		}
		
		// add the list of unique builds
		linkedProjects.addAll(builds);
	}
	
	private void retrievePOMs() {
		console.debug("locating POMs");
		// retrieve POMs for all dependencies in all scopes
		for (Scope scope : project.pom.getScopes()) {
			for (Dependency dependency : project.pom.getDependencies(scope, 0)) {
				retrievePOM(dependency);
			}
		}
	}

	private void importDependencyManagement() {
		if (project.pom.getScopes().contains(Scope.imprt)) {
			console.debug("importing dependency management");

			// This Moxie project imports a pom's dependencyManagement list.
			for (Dependency dependency : project.pom.getDependencies(Scope.imprt, 0)) {
				Pom pom = PomReader.readPom(moxieCache, dependency);
				project.pom.importManagedDependencies(pom);
			}
		}
	}
	
	private void assimilateDependencies() {
		Map<Scope, List<Dependency>> assimilate = new LinkedHashMap<Scope, List<Dependency>>();
		if (project.pom.getScopes().contains(Scope.assimilate)) {
			console.debug("assimilating dependencies");
			
			// This Moxie project integrates a pom's dependency list.
			for (Dependency dependency : project.pom.getDependencies(Scope.assimilate, 0)) {
				Pom pom = PomReader.readPom(moxieCache, dependency);
				for (Scope scope : pom.getScopes()) {
					if (!assimilate.containsKey(scope)) {
						assimilate.put(scope,  new ArrayList<Dependency>());
					}
					assimilate.get(scope).addAll(pom.getDependencies(scope));
				}
			}
			
			// merge unique, assimilated dependencies into the Moxie project pom
			for (Map.Entry<Scope, List<Dependency>> entry : assimilate.entrySet()) {
				for (Dependency dependency : entry.getValue()) {
					project.pom.addDependency(dependency, entry.getKey());
				}
			}
		}
		
		// remove assimilate scope from the project pom, like it never existed
		project.pom.removeScope(Scope.assimilate);
	}
	
	private void retrieveDependencies() {
		console.debug("retrieving artifacts");
		// solve dependencies for compile, runtime, test, and build scopes
		for (Scope scope : new Scope [] { Scope.compile, Scope.runtime, Scope.test, Scope.build }) {
			if (!silent && verbose) {
				console.separator();
				console.scope(scope, 0);
				console.separator();
			}
			Set<Dependency> solution = solve(scope);
			if (solution.size() == 0) {
				if (!silent && verbose) {
					console.log(1, "none");
				}
			} else {
				for (Dependency dependency : solution) {
					if (!silent && verbose) {
						console.dependency(dependency);
					}
					retrieveArtifact(dependency, true);
				}
			}
		}
	}
	
	private Set<Dependency> solve(Scope solutionScope) {
		if (solutions.containsKey(solutionScope)) {
			return solutions.get(solutionScope);
		}
		
		console.debug("solving {0} dependency solution", solutionScope);
		
		// assemble the flat, ordered list of dependencies
		// this list may have duplicates/conflicts
		List<Dependency> all = new ArrayList<Dependency>();
		for (Dependency dependency : project.pom.getDependencies(solutionScope, 0)) {
			console.debug(dependency.getDetailedCoordinates());
			all.add(dependency);
			all.addAll(solve(solutionScope, dependency));
		}
		
		// dependency mediation based on artifact type and nearness (ring)
		Map<String, Dependency> uniques = new LinkedHashMap<String, Dependency>();		
		for (Dependency dependency : all) {
			if (uniques.containsKey(dependency.getMediationId())) {
				// we have another registration for this dependency
				Dependency registered = uniques.get(dependency.getMediationId());
				if (registered.ring > dependency.ring) {
					// this dependency is closer, use it instead
					uniques.put(dependency.getMediationId(), dependency);
				}
			} else {
				// register unique dependency
				uniques.put(dependency.getMediationId(), dependency);
			}
		}
		
		Set<Dependency> solution = new LinkedHashSet<Dependency>(uniques.values());		
		solutions.put(solutionScope, solution);
		return solution;
	}
	
	private List<Dependency> solve(Scope scope, Dependency dependency) {
		List<Dependency> resolved = new ArrayList<Dependency>();
		if (!dependency.resolveDependencies) {
			return resolved;
		}
		File pomFile = retrievePOM(dependency);
		if (pomFile == null || !pomFile.exists()) {
			return resolved;
		}
		
		List<Dependency> dependencies = null;
		
		// check to see if we have overridden the POM dependencies
		Pom override = project.getDependencyOverrides(scope, dependency.getCoordinates());
		if (override == null) {
			override = moxie.getDependencyOverrides(scope, dependency.getCoordinates());
		}
		if (override != null) {
			if (Scope.build.equals(scope)) {
				// build scope overrides are normal
				console.debug("OVERRIDE: {0} {1} dependency {2}", project.getPom().getCoordinates(), scope.name().toUpperCase(), dependency.getCoordinates());
			} else {
				// notify on any other scope
				console.notice("OVERRIDE: {0} {1} dependency {2}", project.getPom().getCoordinates(), scope.name().toUpperCase(), dependency.getCoordinates());
			}
			dependencies = override.getDependencies(scope, dependency.ring + 1);
		}
		
		if (dependencies == null) {
			// try pre-resolved solution for this scope
			dependencies = readSolution(scope, dependency);
		}
		
		if (dependencies == null) {
			// solve the transitive dependencies for this scope
			Pom pom = PomReader.readPom(moxieCache, dependency);
			dependencies = pom.getDependencies(scope, dependency.ring + 1);

			// cache the scope's transitive dependency solution
			cacheSolution(scope, dependency, dependencies);
		}

		if (dependencies.size() > 0) {			
			for (Dependency dep : dependencies) {
				if (!dependency.excludes(dep)) {
					resolved.add(dep);
					resolved.addAll(solve(scope, dep));
				}
			}			
		}

		return resolved;
	}
	
	private List<Dependency> readSolution(Scope scope, Dependency dependency) {
		if (!cache() || !dependency.isMavenObject()) {
			// caching forbidden 
			return null;
		}
		if (dependency.isSnapshot()) {
			// do not use cached solution for snapshots
			return null;
		}
		MoxieData moxiedata = moxieCache.readMoxieData(dependency);
		if (moxiedata.getLastSolved().getTime() == FileUtils.getLastModified(moxieCache.getArtifact(dependency, Constants.POM))) {
			// solution lastModified date must equal pom lastModified date
			try {
				console.debug(1, "=> reusing solution {0}", dependency.getDetailedCoordinates());				
				if (moxiedata.hasScope(scope)) {
					List<Dependency> list = new ArrayList<Dependency>(moxiedata.getDependencies(scope));
					for (Dependency dep : list) {
						// reset ring to be relative to the dependency
						dep.ring += dependency.ring + 1;
					}
					return list;
				}
			} catch (Exception e) {
				console.error(e, "Failed to read dependency solution {0}", dependency.getDetailedCoordinates());
			}
		}
		return null;
	}
	
	private void cacheSolution(Scope scope, Dependency dependency, List<Dependency> transitiveDependencies) {
		if (transitiveDependencies.size() == 0) {
			return;
		}
		MoxieData moxiedata = moxieCache.readMoxieData(dependency);
		// copy transitives and reset the ring level relative to the dependency		
		List<Dependency> dependencies = new ArrayList<Dependency>();
		for (Dependency dep : transitiveDependencies) {
			dep = DeepCopier.copy(dep);
			dep.ring -= (dependency.ring + 1);
			dependencies.add(dep);
		}
		try {
			console.debug(1, "=> caching solution {0}", scope);			
			moxiedata.setDependencies(scope, dependencies);
			// solution date is lastModified of POM
			moxiedata.setLastSolved(new Date(FileUtils.getLastModified(moxieCache.getArtifact(dependency, Constants.POM))));
			moxieCache.writeMoxieData(dependency, moxiedata);
		} catch (Exception e) {
			console.error(e, "Failed to cache {0} solution {1}", scope, dependency.getDetailedCoordinates());
		}
	}
	
	private void readProjectSolution() {
		if (!cache()) {
			// caching forbidden 
			return;
		}
		String coordinates = project.pom.getCoordinates();
		Dependency projectAsDep = new Dependency(coordinates);
		if (projectAsDep.isSnapshot()) {
			// do not use cached solution for snapshots
			return;
		}
		MoxieData moxiedata = moxieCache.readMoxieData(projectAsDep);
		if (moxiedata.getLastSolved().getTime() == project.lastModified) {
			try {
				console.debug("reusing project solution {0}", getPom());				
				for (Scope scope : moxiedata.getScopes()) {
					Set<Dependency> dependencies = new LinkedHashSet<Dependency>(moxiedata.getDependencies(scope));
					console.debug(1, "{0} {1} dependencies", dependencies.size(), scope);
					solutions.put(scope, dependencies);
				}
			} catch (Exception e) {
				console.error(e, "Failed to read project solution {0}", projectAsDep.getDetailedCoordinates());
			}
		}
	}
	
	private void cacheProjectSolution() {
		Dependency projectAsDep = new Dependency(getPom().toString());
		MoxieData moxiedata = moxieCache.readMoxieData(projectAsDep);
		try {
			console.debug("caching project solution {0}", getPom());			
			for (Map.Entry<Scope, Set<Dependency>> entry : solutions.entrySet()) {
				moxiedata.setDependencies(entry.getKey(), entry.getValue());
			}
			moxiedata.setLastSolved(new Date(project.lastModified));
			moxieCache.writeMoxieData(projectAsDep, moxiedata);
		} catch (Exception e) {
			console.error(e, "Failed to cache project solution {0}", projectAsDep.getDetailedCoordinates());
		}
	}
	
	private File retrievePOM(Dependency dependency) {
		if (!dependency.isMavenObject()) {
			return null;
		}
		if (StringUtils.isEmpty(dependency.version)) {
			return null;
		}
		
		if (dependency.isSnapshot()
				|| dependency.version.equalsIgnoreCase(Constants.RELEASE)
				|| dependency.version.equalsIgnoreCase(Constants.LATEST)) {
			// Support SNAPSHOT, RELEASE and LATEST versions
			File metadataFile = moxieCache.getMetadata(dependency, Constants.XML);
			boolean updateRequired = !metadataFile.exists() || isUpdateMetadata();
			
			if (!updateRequired) {
				MoxieData moxiedata = moxieCache.readMoxieData(dependency);
				// we have metadata, check update policy
				if (UpdatePolicy.daily.equals(project.updatePolicy)) {
					// daily is a special case
					SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd");
					String mdate = df.format(moxiedata.getLastChecked());
					String today = df.format(new Date());
					updateRequired = !mdate.equals(today);
				} else {
					// always, never, interval
					long msecs = project.updatePolicy.mins*60*1000L;
					updateRequired = Math.abs(System.currentTimeMillis() - moxiedata.getLastChecked().getTime()) > msecs;
				}
				
				if (updateRequired) {
					console.debug(1, "{0} maven-metadata.xml is STALE according to {1} update policy", dependency.getManagementId(), project.updatePolicy.toString());
				} else {
					console.debug(1, "{0} maven-metadata.xml is CURRENT according to {1} update policy", dependency.getManagementId(), project.updatePolicy.toString());
				}
			}
			
			if (updateRequired && isOnline()) {
				// download artifact maven-metadata.xml
				for (Repository repository : repositories) {
					if (!repository.isMavenSource()) {
						// skip non-Maven repositories
						continue;
					}
					console.debug(1, "locating maven-metadata.xml for {0}", dependency.getManagementId());
					metadataFile = repository.downloadMetadata(this, dependency);
					if (metadataFile != null && metadataFile.exists()) {
						// downloaded the metadata
						break;
					}
				}
			} else {
				console.debug(1, "reading maven-metadata.xml for {0}", dependency.getManagementId());
			}

			// read SNAPSHOT, LATEST, or RELEASE from metadata
			if (metadataFile != null && metadataFile.exists()) {
				Metadata metadata = MetadataReader.readMetadata(metadataFile);
				String version;
				String revision;
				if (Constants.RELEASE.equalsIgnoreCase(dependency.version)) {
					version = metadata.release;
					revision = version;
				} else if (Constants.LATEST.equalsIgnoreCase(dependency.version)) {
					version = metadata.latest;
					revision = version;
				} else {
					// SNAPSHOT
					version = dependency.version;
					revision = metadata.getSnapshotRevision();
				}
				console.debug(1, "{0} = {1}", dependency.getCoordinates(), revision);
				dependency.version = version;
				dependency.revision = revision;
			}
			
			if (updateRequired && isOnline()) {
				// reset last checked date for next update check
				// after we have resolved RELEASE, LATEST, or SNAPSHOT
				MoxieData moxiedata = moxieCache.readMoxieData(dependency);
				moxiedata.setLastChecked(new Date());
				moxieCache.writeMoxieData(dependency, moxiedata);
			}
		}
		
		MoxieData moxiedata = moxieCache.readMoxieData(dependency);
		File pomFile = moxieCache.getArtifact(dependency, Constants.POM);
		if ((!pomFile.exists() || (dependency.isSnapshot() && moxiedata.isRefreshRequired())) && isOnline()) {
			// download the POM
			for (Repository repository : repositories) {
				if (!repository.isMavenSource()) {
					// skip non-Maven repositories
					continue;
				}
				console.debug(1, "locating POM for {0}", dependency.getDetailedCoordinates());
				File retrievedFile = repository.download(this, dependency, Constants.POM);
				if (retrievedFile != null && retrievedFile.exists()) {
					pomFile = retrievedFile;
					break;
				}
			}
		}

		// Read POM
		if (pomFile.exists()) {
			try {
				Pom pom = PomReader.readPom(moxieCache, dependency);
				// retrieve parent POM
				if (pom.hasParentDependency()) {			
					Dependency parent = pom.getParentDependency();
					parent.ring = dependency.ring;
					retrievePOM(parent);
				}
				
				// retrieve all dependent POMs
				for (Scope scope : pom.getScopes()) {
					for (Dependency dep : pom.getDependencies(scope, dependency.ring + 1)) {
						retrievePOM(dep);
					}
				}
			} catch (Exception e) {
				console.error(e);
			}
			return pomFile;
		}		
		return null;
	}
	
	/**
	 * Download an artifact from a local or remote artifact repository.
	 * 
	 * @param dependency
	 *            the dependency to download
	 * @param forProject
	 *            true if this is a project dependency, false if this is a
	 *            Moxie dependency
	 * @return
	 */
	private void retrieveArtifact(Dependency dependency, boolean forProject) {
		for (Repository repository : repositories) {
			if (!repository.isSource(dependency)) {
				// dependency incompatible with repository
				continue;
			}
			
			// Determine to download/update the dependency
			File artifactFile = moxieCache.getArtifact(dependency, dependency.type);
			boolean downloadDependency = !artifactFile.exists();				
			if (!downloadDependency && dependency.isSnapshot()) {
				MoxieData moxiedata = moxieCache.readMoxieData(dependency);
				downloadDependency = moxiedata.isRefreshRequired();
				if (downloadDependency) {
					console.debug(1, "{0} is STALE according to {1}", dependency.getManagementId(), moxiedata.getOrigin());
				} else {
					console.debug(1, "{0} is CURRENT according to {1}", dependency.getManagementId(), moxiedata.getOrigin());
				}
			}
			
			if (downloadDependency && isOnline()) {
				// Download primary artifact (e.g. jar)
				artifactFile = repository.download(this, dependency, dependency.type);
				// Download sources artifact (e.g. -sources.jar)
				Dependency sources = dependency.getSourcesArtifact();
				repository.download(this, sources, sources.type);
			}
			
			// optionally copy primary artifact to project-specified folder
			if (artifactFile != null && artifactFile.exists()) {
				if (forProject && project.dependencyFolder != null) {
					File projectFile = new File(project.dependencyFolder, artifactFile.getName());
					if (dependency.isSnapshot() || !projectFile.exists()) {
						console.debug(1, "copying {0} to {1}", artifactFile.getName(), projectFile.getParent());
						try {
							projectFile.getParentFile().mkdirs();
							FileUtils.copy(projectFile.getParentFile(), artifactFile);
						} catch (IOException e) {
							throw new RuntimeException("Error writing to file " + projectFile, e);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Downloads an internal dependency needed for runtime operation of Moxie.
	 * This dependency is automatically loaded by the classloader.
	 * 
	 * @param dependencies
	 */
	public void loadDependency(Dependency... dependencies) {		
		// solve the classpath solution for the Moxie runtime dependencies
		Pom pom = new Pom();
		for (Dependency dependency : dependencies) {
			resolveAliasedDependencies(dependency);
			retrievePOM(dependency);
			pom.addDependency(dependency, Scope.compile);
		}
		Set<Dependency> solution = new LinkedHashSet<Dependency>();
		for (Dependency dependency : pom.getDependencies(Scope.compile, 0)) {
			solution.add(dependency);
			solution.addAll(solve(Scope.compile, dependency));
		}		
		for (Dependency dependency : solution) {
			retrieveArtifact(dependency, false);
		}

		// load dependency onto executing classpath from Moxie cache
		Class<?>[] PARAMETERS = new Class[] { URL.class };
		URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();
		Class<?> sysclass = URLClassLoader.class;
		for (Dependency dependency : solution) {
			File file = moxieCache.getArtifact(dependency, dependency.type);
			if (file.exists()) {
				try {
					URL u = file.toURI().toURL();
					Method method = sysclass.getDeclaredMethod("addURL", PARAMETERS);
					method.setAccessible(true);
					method.invoke(sysloader, new Object[] { u });
				} catch (Throwable t) {
					console.error(t, "Error, could not add {0} to system classloader", file.getPath());					
				}
			}
		}
	}
	
	public List<File> getClasspath(Scope scope) {
		if (classpaths.containsKey(scope)) {
			return classpaths.get(scope);
		}
		
		File projectFolder = null;
		if (project.dependencyFolder != null && project.dependencyFolder.exists()) {
			projectFolder = project.dependencyFolder;
		}
		console.debug("solving {0} classpath", scope);
		Set<Dependency> dependencies = solve(scope);
		List<File> jars = new ArrayList<File>();
		for (Dependency dependency : dependencies) {
			File jar;
			if (dependency instanceof SystemDependency) {
				SystemDependency sys = (SystemDependency) dependency;				
				jar = new File(sys.path);
			} else {
				jar = moxieCache.getArtifact(dependency, dependency.type); 
				if (projectFolder != null) {
					File pJar = new File(projectFolder, jar.getName());
					if (pJar.exists()) {
						jar = pJar;
					}
				}
			}
			jars.add(jar);
		}
		classpaths.put(scope, jars);
		return jars;
	}
	
	public File getOutputFolder(Scope scope) {
		if (scope == null) {
			return project.outputFolder;
		}
		switch (scope) {
		case test:
			return new File(project.outputFolder, "test-classes");
		default:
			return new File(project.outputFolder, "classes");
		}
	}
	
	private File getEclipseOutputFolder(Scope scope) {
		File baseFolder = new File(projectFolder, "bin");
		if (scope == null) {
			return baseFolder;
		}
		switch (scope) {
		case test:
			return new File(baseFolder, "test-classes");
		default:
			return new File(baseFolder, "classes");
		}
	}
	
	public File getTargetFile() {
		Pom pom = project.pom;
		String name = pom.groupId + "/" + pom.artifactId + "/" + pom.version + (pom.classifier == null ? "" : ("-" + pom.classifier));
		return new File(project.targetFolder, name + ".jar");
	}

	public File getReportsFolder() {
		return new File(project.targetFolder, "reports");
	}

	public File getTargetFolder() {
		return project.targetFolder;
	}
	
	public File getProjectFolder() {
		return projectFolder;
	}
	
	public File getSiteSourceFolder() {
		return new File(projectFolder, "src/site");
	}

	public File getSiteOutputFolder() {
		return new File(getTargetFolder(), "site");
	}
	
	private void writeEclipseClasspath() {
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<classpath>\n");
		for (SourceFolder sourceFolder : project.sourceFolders) {
			if (sourceFolder.scope.isDefault()) {
				sb.append(format("<classpathentry kind=\"src\" path=\"{0}\"/>\n", FileUtils.getRelativePath(projectFolder, sourceFolder.getSources())));
			} else {
				sb.append(format("<classpathentry kind=\"src\" path=\"{0}\" output=\"{1}\"/>\n", FileUtils.getRelativePath(projectFolder, sourceFolder.getSources()), FileUtils.getRelativePath(projectFolder, getEclipseOutputFolder(sourceFolder.scope))));
			}
		}
		
		// always link classpath against Moxie artifact cache
		Set<Dependency> dependencies = solve(Scope.test);
		for (Dependency dependency : dependencies) {
			if (dependency instanceof SystemDependency) {
				SystemDependency sys = (SystemDependency) dependency;
				sb.append(format("<classpathentry kind=\"lib\" path=\"{0}\" />\n", FileUtils.getRelativePath(projectFolder, new File(sys.path))));
			} else {				
				File jar = moxieCache.getArtifact(dependency, dependency.type);
				Dependency sources = dependency.getSourcesArtifact();
				File srcJar = moxieCache.getArtifact(sources, sources.type);
				if (srcJar.exists()) {
					// have sources
					sb.append(format("<classpathentry kind=\"lib\" path=\"{0}\" sourcepath=\"{1}\" />\n", jar.getAbsolutePath(), srcJar.getAbsolutePath()));
				} else {
					// no sources
					sb.append(format("<classpathentry kind=\"lib\" path=\"{0}\" />\n", jar.getAbsolutePath()));
				}
			}
		}
		sb.append(format("<classpathentry kind=\"output\" path=\"{0}\"/>\n", FileUtils.getRelativePath(projectFolder, getEclipseOutputFolder(Scope.compile))));
				
		for (Build linkedProject : linkedProjects) {
			String projectName = null;
			File dotProject = new File(linkedProject.projectFolder, ".project");
			if (dotProject.exists()) {
				// extract Eclipse project name
				console.debug("extracting project name from {0}", dotProject.getAbsolutePath());
				Pattern p = Pattern.compile("(<name>)(.+)(</name>)");
				try {
					Scanner scanner = new Scanner(dotProject);
					while (scanner.hasNextLine()) {
						scanner.nextLine();
						projectName = scanner.findInLine(p);
						if (!StringUtils.isEmpty(projectName)) {
							Matcher m = p.matcher(projectName);
							m.find();
							projectName = m.group(2).trim();
							console.debug(1, projectName);
							break;
						}
					}
				} catch (FileNotFoundException e) {
				}
			} else {
				// use folder name
				projectName = linkedProject.projectFolder.getName();
			}
			sb.append(format("<classpathentry kind=\"src\" path=\"/{0}\"/>\n", projectName));
		}
		sb.append("<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>\n");
		sb.append("</classpath>");
		
		FileUtils.writeContent(new File(projectFolder, ".classpath"), sb.toString());
	}
	
	private void writeEclipseProject() {
		File dotProject = new File(projectFolder, ".project");
		if (dotProject.exists()) {
			// don't recreate the project file
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<projectDescription>\n");
		sb.append(MessageFormat.format("\t<name>{0}</name>\n", project.pom.name));
		sb.append(MessageFormat.format("\t<comment>{0}</comment>\n", project.pom.description == null ? "" : project.pom.description));
		sb.append("\t<projects>\n");
		sb.append("\t</projects>\n");
		sb.append("\t<buildSpec>\n");
		sb.append("\t\t<buildCommand>\n");
		sb.append("\t\t\t<name>org.eclipse.jdt.core.javabuilder</name>\n");
		sb.append("\t\t\t<arguments>\n");
		sb.append("\t\t\t</arguments>\n");
		sb.append("\t\t</buildCommand>\n");
		sb.append("\t</buildSpec>\n");
		sb.append("\t<natures>\n");
		sb.append("\t\t<nature>org.eclipse.jdt.core.javanature</nature>\n");
		sb.append("\t</natures>\n");
		sb.append("</projectDescription>\n");
		
		FileUtils.writeContent(dotProject, sb.toString());
	}
	
	private void writePOM() {
		StringBuilder sb = new StringBuilder();
		sb.append("<!-- This file is automatically generated by Moxie. DO NOT HAND EDIT! -->\n");
		sb.append(project.pom.toXML());
		FileUtils.writeContent(new File(projectFolder, "pom.xml"), sb.toString());
	}
	
	public String getCustomLess() {
		String lessName = configFile.getName().substring(0, configFile.getName().lastIndexOf('.')) + ".less";
		// prefer config-relative LESS
		File less = new File(configFile.getParentFile(), lessName);
		
		// try projectFolder-relative LESS
		if (!less.exists()) {
			less = new File(projectFolder, lessName);
		}
		
		if (less.exists()) {
			return FileUtils.readContent(less, "\n");
		}
		
		// default CSS
		return "";
	}
	
	public void describe() {
		console.title(getPom().name, getPom().version);

		describeConfig();
		describeSettings();
	}
	
	void describeConfig() {
		Pom pom = project.pom;
		console.log("project metadata");
		describe(Key.name, pom.name);
		describe(Key.description, pom.description);
		describe(Key.groupId, pom.groupId);
		describe(Key.artifactId, pom.artifactId);
		describe(Key.version, pom.version);
		describe(Key.organization, pom.organization);
		describe(Key.url, pom.url);
		
		if (!isOnline()) {
			console.separator();
			console.warn("Moxie is running offline. Network functions disabled.");
		}

		if (verbose) {
			console.separator();
			console.log("source folders");
			for (SourceFolder folder : project.sourceFolders) {
				console.sourceFolder(folder);
			}
			console.separator();

			console.log("output folder");
			console.log(1, project.outputFolder.toString());
			console.separator();
		}
	}
	
	void describeSettings() {
		if (verbose) {
			console.log("Moxie parameters");
			describe(Toolkit.MX_ROOT, getMoxieCache().getMoxieRoot().getAbsolutePath());
			describe(Toolkit.MX_ONLINE, "" + isOnline());
			describe(Toolkit.MX_UPDATEMETADATA, "" + isUpdateMetadata());
			describe(Toolkit.MX_DEBUG, "" + isDebug());
			describe(Toolkit.MX_VERBOSE, "" + isVerbose());
			
			console.log("dependency sources");
			if (repositories.size() == 0) {
				console.error("no dependency sources defined!");
			}
			for (Repository repository : repositories) {
				console.log(1, repository.toString());
				console.download(repository.getArtifactUrl());
				console.log();
			}

			List<Proxy> actives = moxie.getActiveProxies();
			if (actives.size() > 0) {
				console.log("proxy settings");
				for (Proxy proxy : actives) {
					if (proxy.active) {
						describe("proxy", proxy.host + ":" + proxy.port);
					}
				}
				console.separator();
			}
		}
	}

	void describe(Enum<?> key, String value) {
		describe(key.name(), value);
	}
	
	void describe(String key, String value) {
		if (StringUtils.isEmpty(value)) {
			return;
		}
		console.key(StringUtils.leftPad(key, 12, ' '), value);
	}
	
	@Override
	public String toString() {
		return "Build (" + project.pom.toString() + ")";
	}
}
