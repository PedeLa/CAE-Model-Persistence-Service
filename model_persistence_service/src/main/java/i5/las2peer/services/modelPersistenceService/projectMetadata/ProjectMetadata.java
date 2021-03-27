package i5.las2peer.services.modelPersistenceService.projectMetadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class ProjectMetadata {

	/**
	 * Roles that are available in this project.
	 */
	private ArrayList<Role> roles;
	
	/**
	 * Maps user agent id to the name of the role assigned to the user.
	 */
	private HashMap<String, String> mapUserRole; 
	
	/**
	 * List of components that belong to this project.
	 */
	private ArrayList<Component> components;
	
	/**
	 * List of dependencies that belong to this project.
	 */
	private ArrayList<Component> dependencies;
	
	/**
	 * List of external dependencies that belong to this project.
	 */
	private ArrayList<ExternalDependency> externalDependencies;
	
	public ProjectMetadata(Connection connection, String projectName, String projectCreatorAgentId) throws SQLException {
	    this.roles = PredefinedRoles.get();	
	    
	    this.mapUserRole = new HashMap<>();
	    // add the project creator to the user role map
	    this.mapUserRole.put(projectCreatorAgentId, PredefinedRoles.getDefaultRoleName());
	    
	    this.components = new ArrayList<>();
	    // store empty application model
	    createApplicationComponent(connection, projectName);
	    
	    this.dependencies = new ArrayList<>();
	    this.externalDependencies = new ArrayList<>();
	}
	
	private void createApplicationComponent(Connection connection, String projectName) throws SQLException {
		String applicationComponentName = projectName + "-application";
		Component applicationComponent = new Component(applicationComponentName, Component.TYPE_APPLICATION);
		
		// create versioned model for the component
		applicationComponent.createEmptyVersionedModel(connection);
		
		// also create category in Requirements Bazaar
		// TODO
		
		this.components.add(applicationComponent);
	}
	
	@SuppressWarnings("unchecked")
	public JSONObject toJSONObject() {
		JSONObject jsonMetadata = new JSONObject();
		
		JSONArray jsonRoles = new JSONArray();
		for(Role role : roles) {
			jsonRoles.add(role.toJSONObject());
		}
		jsonMetadata.put("roles", jsonRoles);
		
		jsonMetadata.put("mapUserRole", this.mapUserRole);
		
		JSONArray jsonComponents = new JSONArray();
		for(Component component : components) {
			jsonComponents.add(component.toJSONObject());
		}
		jsonMetadata.put("components", jsonComponents);
		
		JSONArray jsonDependencies = new JSONArray();
		for(Component dependency : dependencies) {
			jsonDependencies.add(dependency.toJSONObject());
		}
		jsonMetadata.put("dependencies", jsonDependencies);
		
		JSONArray jsonExternalDependencies = new JSONArray();
		for(ExternalDependency externalDependency : externalDependencies) {
			jsonExternalDependencies.add(externalDependency.toJSONObject());
		}
		jsonMetadata.put("externalDependencies", jsonExternalDependencies);
		
		return jsonMetadata;
	}
	
}
