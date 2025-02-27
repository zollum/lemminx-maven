package org.eclipse.lemminx.extensions.maven.utils;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.ARTIFACT_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCY_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GROUP_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.MODULE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PLUGIN_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.RELATIVE_PATH_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.VERSION_ELT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.commons.lang3.Validate;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteCentralRepositorySearcher;
import org.eclipse.lemminx.services.extensions.IPositionRequest;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Range;

public class ParticipantUtils {
	private static Properties environmentProperties = null;
	
	public static Properties getEnvironmentProperties() {
		if (environmentProperties == null) {
			Properties properties = new Properties();
			EnvironmentUtils.addEnvVars(properties);
			environmentProperties = properties;
		}
		return environmentProperties;
	}
	
	public static DOMElement findInterestingElement(DOMNode node) {
		if (node == null) {
			return null;
		}
		if (!node.isElement()) {
			return findInterestingElement(node.getParentElement());
		}
		DOMElement element = (DOMElement) node;
		switch (node.getLocalName()) {
		case MODULE_ELT:
			return element;
		case ARTIFACT_ID_ELT:
		case GROUP_ID_ELT:
		case VERSION_ELT:
		case RELATIVE_PATH_ELT:
			return node.getParentElement();
		default:
			// continue
		}
		if (DOMUtils.findChildElementText(element, ARTIFACT_ID_ELT).isPresent()) {
			return element;
		}
		return null;
	}
	
	public static Artifact findWorkspaceArtifact(MavenLemminxExtension plugin, IPositionRequest request, Dependency artifactToSearch) {
		// Here we can search only if all artifact is well defined
		if (!isWellDefinedDependency(artifactToSearch)) {
			return null;
		}
		
		DOMElement element = findInterestingElement(request.getNode());
		MavenProject p = plugin.getProjectCache().getLastSuccessfulMavenProject(request.getXMLDocument());
        List<RemoteRepository> remoteRepositories = getRemoteRepositories(p, element);
		try {
			ArtifactResult result = plugin.getPlexusContainer().lookup(ArtifactResolver.class)
					.resolveArtifact(plugin.getMavenSession().getRepositorySession(), 
							new ArtifactRequest(
									new DefaultArtifact(
										artifactToSearch.getGroupId(), artifactToSearch.getArtifactId(), 
										isPlugin(element) ? "pom" : null, artifactToSearch.getVersion()), 
									remoteRepositories, null));
			if (result !=null) {
				return result.getArtifact();
			}
		} catch (ArtifactResolutionException | ComponentLookupException e) {
			// can happen
		}
		return null;
	}

	public static List<RemoteRepository> getRemoteRepositories(MavenProject pproject, DOMElement element) {
        List<RemoteRepository> remoteRepositories = new ArrayList<RemoteRepository>();
        remoteRepositories.add(RemoteCentralRepositorySearcher.CENTRAL_REPO);
		if (isPlugin(element)) {
			pproject.getRemotePluginRepositories().stream().forEach(r -> {
				if (!remoteRepositories.contains(r)) {
					remoteRepositories.add(r);
				}
			});
			
		} else if (isDependency(element)) {
			pproject.getRemoteArtifactRepositories().stream().forEach(ar -> {
				RemoteRepository r = new RemoteRepository.Builder(ar.getId(), "default", ar.getUrl()).build();
				if (!remoteRepositories.contains(r)) {
					remoteRepositories.add(r);
				}
			});
		}
		return remoteRepositories;
	}
	
	public static boolean isDependency(DOMElement element) {
		return DEPENDENCY_ELT.equals(element.getLocalName()) || 
				(element.getParentElement() != null && DEPENDENCY_ELT.equals(element.getParentElement().getLocalName()));
	}

	
	public static boolean isPlugin(DOMElement element) {
		return  PLUGIN_ELT.equals(element.getLocalName()) || 
					(element.getParentElement() != null && PLUGIN_ELT.equals(element.getParentElement().getLocalName()));
	}
	
	public static boolean isParentDeclaration(DOMElement element) {
		return  PARENT_ELT.equals(element.getLocalName()) || 
					(element.getParentElement() != null && PARENT_ELT.equals(element.getParentElement().getLocalName()));
	}

	public static Dependency getArtifactToSearch(MavenProject pproject, IPositionRequest request) {
		Dependency artifactToSearch = MavenParseUtils.parseArtifact(request.getNode());
		if (artifactToSearch != null) {
			if (artifactToSearch.getGroupId() != null && artifactToSearch.getGroupId().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setGroupId(resolveValueWithProperties(pproject, artifactToSearch.getGroupId()));
			}
			if (artifactToSearch.getArtifactId() != null && artifactToSearch.getArtifactId().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setArtifactId(resolveValueWithProperties(pproject, artifactToSearch.getArtifactId()));
			}
			if (artifactToSearch.getVersion() != null && artifactToSearch.getVersion().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setVersion(resolveValueWithProperties(pproject, artifactToSearch.getVersion()));
			}
		}
		return artifactToSearch;
	}
	
	public static String resolveValueWithProperties(MavenProject project, String value) {
		Map<String, String> properties = getMavenProjectProperties(project);
		
		StringBuilder sb = new StringBuilder();
		int index = -1;
		int closeIndex = -1;
		int start = 0;
		while((index = value.indexOf("${", start)) != -1 && 
				(closeIndex = value.indexOf("}", start)) != -1) {
			String propertyName = value.substring(index + 2, closeIndex);
			String propertyValue = properties.get(propertyName);
			if (propertyValue != null) {
				sb.append(propertyValue);
			}
			start = closeIndex + 1;
		}
		sb.append(value.substring(start));
		
		return sb.length() == 0 ? null : sb.toString();
	}
	
	public static Map<String, String> getMavenProjectProperties(MavenProject project) {
		Map<String, String> allProps = new HashMap<>();
		Properties projectProperties = project.getProperties();
		projectProperties.putAll(getEnvironmentProperties());
		if (project.getProperties() != null) {
			for (Entry<Object, Object> prop : projectProperties.entrySet()) {
				allProps.put((String) prop.getKey(), (String) prop.getValue());
			}
		}
		allProps.put("basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.version", project == null ? "unknown" : project.getVersion());
		allProps.put("project.groupId", project == null ? "unknown" : project.getGroupId());
		allProps.put("project.artifactId", project == null ? "unknown" : project.getArtifactId());
		allProps.put("project.name", project == null ? "unknown" : project.getName());
		allProps.put("project.build.directory",
				project.getBuild() == null ? "unknown" : project.getBuild().getDirectory());
		allProps.put("project.build.outputDirectory",
				project.getBuild() == null ? "unknown" : project.getBuild().getOutputDirectory());

		return allProps;
	}
	
	public static Map.Entry<Range, String> getMavenPropertyInRequest(IPositionRequest request) {
		DOMNode tag = request.getNode();
		String tagText = tag.getNodeValue();
		if (tagText == null) {
			return null;
		}

		int hoverLocation = request.getOffset();
		int propertyOffset = request.getNode().getStart();
		int beforeHover = hoverLocation - propertyOffset;

		String beforeHoverText = tagText.substring(0, beforeHover);
		String afterHoverText = tagText.substring(beforeHover);

		int indexOpen = beforeHoverText.lastIndexOf("${");
		int indexCloseBefore = beforeHoverText.lastIndexOf('}');
		int indexCloseAfter = afterHoverText.indexOf('}');
		if (indexOpen > indexCloseBefore) {

			String propertyText = tagText.substring(indexOpen + 2, indexCloseAfter + beforeHover);
			int textStart = request.getNode().getStart();
			Range propertyRange = XMLPositionUtility.createRange(textStart + indexOpen + 2,
					textStart + indexCloseAfter - 1, request.getXMLDocument());
			return Map.entry(propertyRange, propertyText);
		}
		return null;
	}
	
	public static boolean isWellDefinedDependency(Dependency dependency) {
		try {
			notNullNorBlank(dependency.getGroupId());
			notNullNorBlank(dependency.getArtifactId());
			notNullNorBlank(dependency.getVersion());
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	private static void notNullNorBlank(String str) {
        int c = str != null && str.length() > 0 ? str.charAt( 0 ) : 0;
        if ( ( c < '0' || c > '9' ) && ( c < 'a' || c > 'z' ) )
        {
            Validate.notBlank( str, "Argument can neither be null, empty nor blank" );
        }
    }
}
